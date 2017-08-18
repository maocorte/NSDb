package io.radicalbit.nsdb.api.scala

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import io.radicalbit.nsdb.JSerializable
import io.radicalbit.nsdb.api.scala.NSDB.Metric.{Dimension, Field}
import io.radicalbit.nsdb.api.scala.NSDB.Namespace
import io.radicalbit.nsdb.client.Client
import io.radicalbit.nsdb.cluster.endpoint.EndpointActor
import io.radicalbit.nsdb.statement.{InsertSQLStatement, ListAssignment}

import scala.concurrent.Future

object NSDB {

  private val host = "127.0.0.1"

  private val port = 2552

  private implicit val system = ActorSystem("nsdb-scala-api", ConfigFactory.load("scala-api"))

  private implicit val client = new Client(host = host, port = port)

  def connect(host: String = host, port: Int = port): NSDB = new NSDB(host = host, port = port)

  case class Namespace(name: String, metric: Option[Metric] = None) {

    def metric(metric: String): Metric = Metric(namespace = name, name = metric)

  }

  object Metric {
    type Dimension = (String, JSerializable)
    type Field     = (String, JSerializable)
  }

  case class Metric(namespace: String,
                    name: String,
                    dimensions: List[Dimension] = List.empty[Dimension],
                    fields: List[Field] = List.empty[Field],
                    ts: Option[Long] = None)(implicit client: Client) {

    def dimension(dim: Dimension): Metric = copy(dimensions = dimensions :+ dim)

    def dimension(k: String, v: JSerializable): Metric = dimension((k, v))

    def field(field: Field): Metric = copy(fields = fields :+ field)

    def field(k: String, v: JSerializable): Metric = field((k, v))

    def timestamp(v: Long) = copy(ts = Some(v))

    def write(): Future[EndpointActor.SQLStatementExecuted] =
      client.executeSqlStatement(
        InsertSQLStatement(namespace = namespace,
                           metric = name,
                           timestamp = ts,
                           dimensions = ListAssignment(dimensions.toMap),
                           fields = ListAssignment(fields.toMap)))
  }

}

case class NSDB(host: String, port: Int) {

  def namespace(name: String): Namespace = Namespace(name)
}
