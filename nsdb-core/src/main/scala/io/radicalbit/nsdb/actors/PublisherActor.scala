package io.radicalbit.nsdb.actors

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import io.radicalbit.nsdb.actors.PublisherActor.Command._
import io.radicalbit.nsdb.actors.PublisherActor.Events._
import io.radicalbit.nsdb.common.protocol.Bit
import io.radicalbit.nsdb.common.statement.SelectSQLStatement
import io.radicalbit.nsdb.index.{NsdbQuery, TemporaryIndex}
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._
import io.radicalbit.nsdb.statement.StatementParser
import io.radicalbit.nsdb.statement.StatementParser.{ParsedAggregatedQuery, ParsedSimpleQuery}
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.search.IndexSearcher

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

class PublisherActor(val basePath: String, readCoordinator: ActorRef, namespaceSchemaActor: ActorRef)
    extends Actor
    with ActorLogging {

  lazy val subscribedActors: mutable.Map[String, Set[ActorRef]] = mutable.Map.empty

  lazy val queries: mutable.Map[String, NsdbQuery] = mutable.Map.empty

  implicit val disp: ExecutionContextExecutor = context.system.dispatcher

  implicit val timeout: Timeout =
    Timeout(context.system.settings.config.getDuration("nsdb.publisher.timeout", TimeUnit.SECONDS), TimeUnit.SECONDS)

  val interval = FiniteDuration(
    context.system.settings.config.getDuration("nsdb.publisher.scheduler.interval", TimeUnit.SECONDS),
    TimeUnit.SECONDS)

  context.system.scheduler.schedule(interval, interval) {

    log.debug("{} actors subscribed {} ",
              subscribedActors.values.flatten.toSet.size,
              subscribedActors.values.flatten.toSet.map((a: ActorRef) => a.path).mkString(","))

    queries
      .filter {
        case (id, q) =>
          q.aggregated && subscribedActors.get(id).isDefined && subscribedActors(id).nonEmpty
      }
      .foreach {
        case (id, nsdbQuery) =>
          val f = (readCoordinator ? ExecuteStatement(nsdbQuery.query))
            .map {
              case e: SelectStatementExecuted => RecordsPublished(id, e.metric, e.values)
              case e: SelectStatementFailed   => RecordsPublished(id, nsdbQuery.query.metric, Seq.empty)
            }
          subscribedActors.get(id).foreach(e => e.foreach(f.pipeTo(_)))
      }
  }

  override def receive: Receive = {
    case SubscribeBySqlStatement(actor, queryString, query) =>
      subscribedActors
        .find { case (_, v) => v == actor }
        .fold {
          val f = (namespaceSchemaActor ? GetSchema(query.db, query.namespace, query.metric))
            .flatMap {
              case SchemaGot(_, _, _, Some(schema)) =>
                new StatementParser().parseStatement(query, schema) match {
                  case Success(parsedQuery) =>
                    val id = queries.find { case (k, v) => v.query == query }.map(_._1) getOrElse
                      UUID.randomUUID().toString

                    (readCoordinator ? ExecuteStatement(query))
                      .map {
                        case e: SelectStatementExecuted =>
                          val previousRegisteredActors = subscribedActors.getOrElse(id, Set.empty)
                          subscribedActors += (id -> (previousRegisteredActors + actor))
                          queries += (id          -> NsdbQuery(id, parsedQuery.isInstanceOf[ParsedAggregatedQuery], query))
                          SubscribedByQueryString(queryString, id, e.values)
                        case SelectStatementFailed(reason) => SubscriptionFailed(reason)
                      }

                  case Failure(ex) => Future(SubscriptionFailed(ex.getMessage))
                }

              case _ => Future(SubscriptionFailed(s"Metric ${query.metric} does not exist "))
            }
          f.pipeTo(sender())
        } {
          case (id, _) =>
            (readCoordinator ? ExecuteStatement(query))
              .mapTo[SelectStatementExecuted]
              .map(e => SubscribedByQueryString(queryString, id, e.values))
              .pipeTo(sender())
        }
    case SubscribeByQueryId(actor, quid) =>
      queries.get(quid) match {
        case Some(q) =>
          log.debug(s"found query $q for id $quid")
          (readCoordinator ? ExecuteStatement(q.query))
            .map {
              case e: SelectStatementExecuted =>
                val previousRegisteredActors = subscribedActors.getOrElse(quid, Set.empty)
                subscribedActors += (quid -> (previousRegisteredActors + actor))
                SubscribedByQuid(quid, e.values)
              case SelectStatementFailed(reason) => SubscriptionFailed(reason)
            }
            .pipeTo(sender())
        case None => sender ! SubscriptionFailed(s"quid $quid not found")
      }
    case PublishRecord(db, namespace, metric, record, schema) =>
      queries.foreach {
        case (id, nsdbQuery) if !nsdbQuery.aggregated =>
          val luceneQuery = new StatementParser().parseStatement(nsdbQuery.query, schema)
          luceneQuery match {
            case Success(parsedQuery: ParsedSimpleQuery) =>
              val temporaryIndex: TemporaryIndex = new TemporaryIndex()
              implicit val writer: IndexWriter   = temporaryIndex.getWriter
              temporaryIndex.write(record)
              writer.close()
              implicit val searcher: IndexSearcher = temporaryIndex.getSearcher
              if (db == nsdbQuery.query.db && namespace == nsdbQuery.query.namespace && metric == nsdbQuery.query.metric && temporaryIndex
                    .query(parsedQuery.q, parsedQuery.fields, 1, None)
                    .lengthCompare(1) == 0)
                subscribedActors.get(id).foreach(e => e.foreach(_ ! RecordsPublished(id, metric, Seq(record))))
            case Success(_) => log.error("unreachable branch reached...")
            case Failure(ex) =>
              log.error(ex, s"query ${nsdbQuery.query} not valid because of")
          }
        case _ =>
      }
    case Unsubscribe(actor) =>
      log.debug("unsubscribe actor {} ", actor)
      val x = subscribedActors.filter { case (_, v) => v.contains(actor) }

      x.foreach {
        case (k, v) =>
          if (v.size == 1) subscribedActors -= k
          else
            subscribedActors += (k -> (v - actor))
          sender() ! Unsubscribed(actor)
      }
    case RemoveQuery(quid) =>
      subscribedActors.get(quid).foreach { actors =>
        actors.foreach(_ ! PoisonPill)
      }
      subscribedActors -= quid
      queries -= quid
      sender() ! QueryRemoved(quid)
  }
}

object PublisherActor {

  def props(basePath: String, readCoordinator: ActorRef, namespaceSchemaActor: ActorRef): Props =
    Props(new PublisherActor(basePath, readCoordinator, namespaceSchemaActor))

  object Command {
    case class SubscribeBySqlStatement(actor: ActorRef, queryString: String, query: SelectSQLStatement)
    case class SubscribeByQueryId(actor: ActorRef, qid: String)
    case class Unsubscribe(actor: ActorRef)
    case class RemoveQuery(quid: String)
  }

  object Events {
    case class SubscribedByQuid(quid: String, records: Seq[Bit])
    case class SubscribedByQueryString(queryString: String, quid: String, records: Seq[Bit])
    case class SubscriptionFailed(reason: String)

    case class RecordsPublished(quid: String, metric: String, records: Seq[Bit])
    case class Unsubscribed(actor: ActorRef)
    case class QueryRemoved(quid: String)
  }
}
