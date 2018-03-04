package io.radicalbit.nsdb.protocol

import akka.actor.ActorRef
import io.radicalbit.nsdb.actors.ShardKey
import io.radicalbit.nsdb.common.protocol.Bit
import io.radicalbit.nsdb.common.statement.{DeleteSQLStatement, SelectSQLStatement}
import io.radicalbit.nsdb.index.Schema

object MessageProtocol {

  object Commands {
    case class GetNamespaces(db: String)
    case class GetMetrics(db: String, namespace: String)
    case class GetSchema(db: String, namespace: String, metric: String)
    case class ExecuteStatement(selectStatement: SelectSQLStatement)
    case class ExecuteSelectStatement(selectStatement: SelectSQLStatement, schema: Schema)

    case class FlatInput(ts: Long, db: String, namespace: String, metric: String, data: Array[Byte])
    case class MapInput(ts: Long, db: String, namespace: String, metric: String, record: Bit)
    case class PublishRecord(db: String, namespace: String, metric: String, record: Bit, schema: Schema)
    case class ExecuteDeleteStatement(statement: DeleteSQLStatement)
    case class ExecuteDeleteStatementInShards(statement: DeleteSQLStatement, schema: Schema, keys: Seq[ShardKey])
    case class DropMetric(db: String, namespace: String, metric: String)
    case class DeleteNamespace(db: String, namespace: String)

    case class UpdateSchemaFromRecord(db: String, namespace: String, metric: String, record: Bit)
    case class DeleteSchema(db: String, namespace: String, metric: String)
    case class DeleteAllSchemas(db: String, namespace: String)

    case class GetCount(db: String, namespace: String, metric: String)
    case class AddRecordToShard(db: String, namespace: String, shardKey: ShardKey, bit: Bit)
    case class DeleteRecordFromShard(db: String, namespace: String, shardKey: ShardKey, bit: Bit)
    case class DeleteAllMetrics(db: String, namespace: String)

    case object GetReadCoordinator
    case object GetWriteCoordinator
    case object GetPublisher

    case class SubscribeNamespaceDataActor(actor: ActorRef, nodeName: String)
  }

  object Events {

    sealed trait ErrorCode
    case class MetricNotFound(metric: String) extends ErrorCode
    case object Generic                       extends ErrorCode

    case class NamespacesGot(db: String, namespaces: Set[String])
    case class SchemaGot(db: String, namespace: String, metric: String, schema: Option[Schema])
    case class MetricsGot(db: String, namespace: String, metrics: Set[String])
    case class SelectStatementExecuted(db: String, namespace: String, metric: String, values: Seq[Bit])
    case class SelectStatementFailed(reason: String, errorCode: ErrorCode = Generic)

    case class InputMapped(db: String, namespace: String, metric: String, record: Bit)
    case class DeleteStatementExecuted(db: String, namespace: String, metric: String)
    case class DeleteStatementFailed(db: String, namespace: String, metric: String, reason: String)
    case class MetricDropped(db: String, namespace: String, metric: String)
    case class NamespaceDeleted(db: String, namespace: String)

    case class SchemaUpdated(db: String, namespace: String, metric: String, schema: Schema)
    case class UpdateSchemaFailed(db: String, namespace: String, metric: String, errors: List[String])
    case class SchemaDeleted(db: String, namespace: String, metric: String)
    case class AllSchemasDeleted(db: String, namespace: String)

    case class CountGot(db: String, namespace: String, metric: String, count: Int)
    case class RecordAdded(db: String, namespace: String, metric: String, record: Bit)
    case class RecordRejected(db: String, namespace: String, metric: String, record: Bit, reasons: List[String])
    case class RecordDeleted(db: String, namespace: String, metric: String, record: Bit)
    case class AllMetricsDeleted(db: String, namespace: String)

    case class NamespaceDataActorSubscribed(actor: ActorRef, nodeName: String)
    case class NamespaceDataActorSubscriptionFailed(actor: ActorRef, host: Option[String] = None, reason: String)
  }

}
