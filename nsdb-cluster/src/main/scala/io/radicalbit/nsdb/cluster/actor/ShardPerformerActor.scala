package io.radicalbit.nsdb.cluster.actor

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import akka.util.Timeout
import cats.data.Validated.{Invalid, Valid}
import io.radicalbit.nsdb.cluster.actor.ShardAccumulatorActor.Refresh
import io.radicalbit.nsdb.cluster.actor.ShardPerformerActor.PerformShardWrites
import io.radicalbit.nsdb.index.{FacetIndex, TimeSeriesIndex}
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.store.MMapDirectory

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration

class ShardPerformerActor(basePath: String, db: String, namespace: String) extends Actor with ActorLogging {
  import scala.collection.mutable

  val shards: mutable.Map[ShardKey, TimeSeriesIndex] = mutable.Map.empty

  val facetIndexShards: mutable.Map[ShardKey, FacetIndex] = mutable.Map.empty

  implicit val dispatcher: ExecutionContextExecutor = context.system.dispatcher

  implicit val timeout: Timeout =
    Timeout(context.system.settings.config.getDuration("nsdb.publisher.timeout", TimeUnit.SECONDS), TimeUnit.SECONDS)

  lazy val interval = FiniteDuration(
    context.system.settings.config.getDuration("nsdb.write.scheduler.interval", TimeUnit.SECONDS),
    TimeUnit.SECONDS)

  private def getIndex(key: ShardKey) =
    shards.getOrElse(
      key, {
        val directory =
          new MMapDirectory(Paths.get(basePath, db, namespace, "shards", s"${key.metric}_${key.from}_${key.to}"))
        val newIndex = new TimeSeriesIndex(directory)
        shards += (key -> newIndex)
        newIndex
      }
    )

  private def getFacetIndex(key: ShardKey) =
    facetIndexShards.getOrElse(
      key, {
        val directory =
          new MMapDirectory(
            Paths.get(basePath, db, namespace, "shards", s"${key.metric}_${key.from}_${key.to}", "facet"))
        val taxoDirectory = new MMapDirectory(
          Paths.get(basePath, db, namespace, "shards", s"${key.metric}_${key.from}_${key.to}", "facet", "taxo"))
        val newIndex = new FacetIndex(directory, taxoDirectory)
        facetIndexShards += (key -> newIndex)
        newIndex
      }
    )

  def receive: Receive = {
    case PerformShardWrites(opBufferMap) =>
      val groupedByKey = opBufferMap.values.groupBy(_.shardKey)
      groupedByKey.foreach {
        case (key, ops) =>
          val index                               = getIndex(key)
          val facetIndex                          = getFacetIndex(key)
          implicit val writer: IndexWriter        = index.getWriter
          val facetWriter: IndexWriter            = facetIndex.getWriter
          val taxoWriter: DirectoryTaxonomyWriter = facetIndex.getTaxoWriter
          ops.foreach {
            case WriteShardOperation(_, _, bit) =>
              index.write(bit).map(_ => facetIndex.write(bit)(facetWriter, taxoWriter)) match {
                case Valid(_)      =>
                case Invalid(errs) => log.error(errs.toList.mkString(","))
              }
            //TODO handle errors
            case DeleteShardRecordOperation(_, _, bit) =>
              index.delete(bit)
              facetIndex.delete(bit)(facetWriter)
            case DeleteShardQueryOperation(_, _, q) =>
              index.delete(q)
          }
          writer.flush()
          writer.close()
          taxoWriter.close()
          facetWriter.close()
      }
      context.parent ! Refresh(opBufferMap.keys.toSeq, groupedByKey.keys.toSeq)
  }
}

object ShardPerformerActor {

  case class PerformShardWrites(opBufferMap: Map[String, ShardOperation])

  def props(basePath: String, db: String, namespace: String): Props =
    Props(new ShardPerformerActor(basePath, db, namespace))
}
