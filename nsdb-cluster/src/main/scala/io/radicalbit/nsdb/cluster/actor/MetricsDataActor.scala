/*
 * Copyright 2018 Radicalbit S.r.l.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.radicalbit.nsdb.cluster.actor

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import io.radicalbit.nsdb.actors.{ShardAccumulatorActor, ShardKey}
import io.radicalbit.nsdb.cluster.actor.MetricsDataActor._
import io.radicalbit.nsdb.cluster.index.Location
import io.radicalbit.nsdb.common.protocol.Bit
import io.radicalbit.nsdb.common.statement.DeleteSQLStatement
import io.radicalbit.nsdb.model.Schema
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._

/**
  * Actor responsible for dispatching read or write commands to the proper actor and index.
  * @param basePath indexes' root path.
  */
class MetricsDataActor(val basePath: String) extends Actor with ActorLogging {

  lazy val sharding: Boolean = context.system.settings.config.getBoolean("nsdb.sharding.enabled")

  /**
    * Gets or creates child actor of class [[ShardAccumulatorActor]] to handle write requests
    *
    * @param db database name
    * @param namespace namespace name
    * @return [[ShardAccumulatorActor]] for selected database and namespace
    */
  private def getOrCreateChild(db: String, namespace: String): ActorRef =
    context
      .child(s"shard_accumulator_${db}_$namespace")
      .getOrElse(
        context.actorOf(ShardAccumulatorActor.props(basePath, db, namespace), s"shard_accumulator_${db}_$namespace"))

  /**
    * If exists, gets child for selected namespace and database.
    * Use in case of read-only operations such as get metrics operations and ddl operations such as drop metrics.
    *
    * @param db database name
    * @param namespace namespace name
    * @return Option containing child actor of class [[ShardAccumulatorActor]]
    */
  private def getChild(db: String, namespace: String): Option[ActorRef] =
    context.child(s"shard_accumulator_${db}_$namespace")

  implicit val timeout: Timeout = Timeout(
    context.system.settings.config.getDuration("nsdb.namespace-data.timeout", TimeUnit.SECONDS),
    TimeUnit.SECONDS)

  override def preStart(): Unit = {
    Option(Paths.get(basePath).toFile.list())
      .map(_.toSet)
      .getOrElse(Set.empty)
      .filter(f => Paths.get(basePath, f).toFile.isDirectory)
      .flatMap(db => {
        Paths.get(basePath, db).toFile.list().map(namespace => (db, namespace))
      })
      .foreach {
        case (db, namespace) =>
          context.actorOf(ShardAccumulatorActor.props(basePath, db, namespace), s"shard_accumulator_${db}_$namespace")
      }
  }

  override def receive: Receive = commons orElse shardBehaviour

  def commons: Receive = {
    case GetDbs =>
      val dbs = context.children.map(_.path.name.split("_")(2))
      sender() ! DbsGot(dbs.toSet)
    case GetNamespaces(db) =>
      val namespaces = context.children.collect {
        case c if c.path.name.split("_")(2) == db => c.path.name.split("_")(3)
      }
      sender() ! NamespacesGot(db, namespaces.toSet)
    case msg @ GetMetrics(db, namespace) =>
      getChild(db, namespace) match {
        case Some(child) => child forward msg
        case None        => sender() ! MetricsGot(db, namespace, Set.empty)
      }
    case DeleteNamespace(db, namespace) =>
      getOrCreateChild(db, namespace) ! DeleteAllMetrics(db, namespace, sender)
    case AllMetricsDeleted(db, namespace, replyTo) =>
      val childToRemove = getOrCreateChild(db, namespace)
      context.stop(childToRemove)
      replyTo ! NamespaceDeleted(db, namespace)
    case msg @ DropMetric(db, namespace, metric) =>
      getChild(db, namespace) match {
        case Some(child) => child forward msg
        case None        => sender() ! MetricDropped(db, namespace, metric)
      }
    case msg @ GetCount(db, namespace, _) =>
      getOrCreateChild(db, namespace).forward(msg)
    case msg @ ExecuteSelectStatement(statement, _) =>
      getOrCreateChild(statement.db, statement.namespace).forward(msg)
  }

  def shardBehaviour: Receive = {
    case AddRecordToLocation(db, namespace, bit, location) =>
      getOrCreateChild(db, namespace).forward(
        AddRecordToShard(db, namespace, ShardKey(location.metric, location.from, location.to), bit))
    case DeleteRecordFromLocation(db, namespace, bit, location) =>
      getOrCreateChild(db, namespace).forward(
        DeleteRecordFromShard(db, namespace, ShardKey(location.metric, location.from, location.to), bit))
    case ExecuteDeleteStatementInternalInLocations(statement, schema, locations) =>
      getOrCreateChild(statement.db, statement.namespace).forward(
        ExecuteDeleteStatementInShards(statement, schema, locations.map(l => ShardKey(l.metric, l.from, l.to))))
  }

}

object MetricsDataActor {
  def props(basePath: String): Props = Props(new MetricsDataActor(basePath))

  case class AddRecordToLocation(db: String, namespace: String, bit: Bit, location: Location)
  case class DeleteRecordFromLocation(db: String, namespace: String, bit: Bit, location: Location)
  case class ExecuteDeleteStatementInternalInLocations(statement: DeleteSQLStatement,
                                                       schema: Schema,
                                                       locations: Seq[Location])
}
