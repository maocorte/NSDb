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

package io.radicalbit.nsdb.cluster.coordinator

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import io.radicalbit.nsdb.common.protocol._
import io.radicalbit.nsdb.index._
import io.radicalbit.nsdb.model.{Schema, SchemaField}
import io.radicalbit.nsdb.protocol.MessageProtocol.Commands._
import io.radicalbit.nsdb.protocol.MessageProtocol.Events._
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

class SchemaCoordinatorSpec
    extends TestKit(ActorSystem("SchemaCoordinatorSpec"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with OneInstancePerTest
    with BeforeAndAfter
    with EitherValues {

  val probe = TestProbe()
  val schemaCoordinator =
    system.actorOf(
      SchemaCoordinator
        .props("target/test_index/NamespaceSchemaCoordinatorSpec", system.actorOf(Props[FakeSchemaCache])))

  val db         = "db"
  val namespace  = "namespace"
  val namespace1 = "namespace1"

  val nameRecord    = Bit(0, 1, Map("name"    -> "name"), Map("city"       -> "milano"))
  val surnameRecord = Bit(0, 1, Map("surname" -> "surname"), Map("country" -> "italy"))

  val baseSchema = Schema(
    "people",
    Set(
      SchemaField("name", DimensionFieldType, VARCHAR()),
      SchemaField("timestamp", TimestampFieldType, BIGINT()),
      SchemaField("value", ValueFieldType, INT()),
      SchemaField("city", TagFieldType, VARCHAR())
    )
  )

  before {
    implicit val timeout = Timeout(10 seconds)

    Await.result(schemaCoordinator ? DeleteNamespace(db, namespace), 10 seconds)
    Await.result(schemaCoordinator ? DeleteNamespace(db, namespace1), 10 seconds)
    Await.result(schemaCoordinator ? UpdateSchemaFromRecord(db, namespace, "people", nameRecord), 10 seconds)
    Await.result(schemaCoordinator ? UpdateSchemaFromRecord(db, namespace1, "people", surnameRecord), 10 seconds)

  }

  "schemaCoordinator" should "get schemas" in {

    probe.send(schemaCoordinator, GetSchema("db", "namespace", "nonexisting"))

    val nonexistingGot = probe.expectMsgType[SchemaGot]
    nonexistingGot.metric shouldBe "nonexisting"
    nonexistingGot.schema shouldBe None

    probe.send(schemaCoordinator, GetSchema("db", "namespace", "people"))

    val existingGot = probe.expectMsgType[SchemaGot]
    existingGot.metric shouldBe "people"
    existingGot.schema shouldBe Some(baseSchema)

  }

  "schemaCoordinator" should "return a failed message when trying to update a schema with an incompatible one" in {
    probe.send(
      schemaCoordinator,
      UpdateSchemaFromRecord(
        "db",
        "namespace",
        "people",
        Bit(0, 23.5, Map("name" -> "john", "surname" -> "doe"), Map("city" -> "milano", "country" -> "italy")))
    )

    probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]] shouldBe 'left

    probe.expectNoMessage(1 second)

  }

  "schemaCoordinator" should "update schemas coming from a record" in {
    probe.send(
      schemaCoordinator,
      UpdateSchemaFromRecord(
        "db",
        "namespace",
        "people",
        Bit(0, 23, Map("name" -> "john", "surname" -> "doe"), Map("city" -> "milano", "country" -> "italy")))
    )

    val schema = probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]].right.value.schema
    schema.fields.exists(_.name == "timestamp") shouldBe true
    schema.fields.exists(_.name == "value") shouldBe true

    probe.send(schemaCoordinator, GetSchema("db", "namespace", "people"))

    val existingGot = probe.expectMsgType[SchemaGot]
    existingGot.metric shouldBe "people"
    existingGot.schema shouldBe Some(
      Schema(
        "people",
        Set(
          SchemaField("timestamp", TimestampFieldType, BIGINT()),
          SchemaField("value", ValueFieldType, INT()),
          SchemaField("name", DimensionFieldType, VARCHAR()),
          SchemaField("surname", DimensionFieldType, VARCHAR()),
          SchemaField("city", TagFieldType, VARCHAR()),
          SchemaField("country", TagFieldType, VARCHAR())
        )
      )
    )

    probe.send(schemaCoordinator,
               UpdateSchemaFromRecord("db", "namespace", "noDimensions", Bit(0, 23.5, Map.empty, Map.empty)))

    probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]] shouldBe 'right
  }

  "schemaCoordinator" should "return the same schema for a new schema included in the old one" in {
    probe.send(
      schemaCoordinator,
      UpdateSchemaFromRecord(
        "db",
        "namespace",
        "people",
        Bit(0, 23, Map("name" -> "john", "surname" -> "doe"), Map("city" -> "milano", "country" -> "italy")))
    )

    probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]] shouldBe 'right

    probe.send(schemaCoordinator, GetSchema("db", "namespace", "people"))

    val existingGot = probe.expectMsgType[SchemaGot]
    existingGot.metric shouldBe "people"
    existingGot.schema shouldBe Some(
      Schema(
        "people",
        Set(
          SchemaField("timestamp", TimestampFieldType, BIGINT()),
          SchemaField("value", ValueFieldType, INT()),
          SchemaField("name", DimensionFieldType, VARCHAR()),
          SchemaField("surname", DimensionFieldType, VARCHAR()),
          SchemaField("city", TagFieldType, VARCHAR()),
          SchemaField("country", TagFieldType, VARCHAR())
        )
      )
    )

    probe.send(
      schemaCoordinator,
      UpdateSchemaFromRecord("db", "namespace", "people", Bit(0, 2, Map("name" -> "john"), Map("country" -> "italy"))))

    probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]] shouldBe 'right

    probe.send(schemaCoordinator, GetSchema("db", "namespace", "people"))

    val newGot = probe.expectMsgType[SchemaGot]
    newGot.metric shouldBe "people"
    newGot.schema shouldBe existingGot.schema
  }

  "schemaCoordinator" should "drop a schema" in {

    implicit val timeout = Timeout(3 seconds)

    probe.send(
      schemaCoordinator,
      UpdateSchemaFromRecord(
        "db",
        "namespace",
        "people",
        Bit(0, 23, Map("name" -> "john", "surname" -> "doe"), Map("city" -> "milano", "country" -> "italy")))
    )

    probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]] shouldBe 'right

    probe.send(schemaCoordinator, GetSchema("db", "namespace", "people"))

    val existingGot = probe.expectMsgType[SchemaGot]
    existingGot.metric shouldBe "people"
    existingGot.schema shouldBe Some(
      Schema(
        "people",
        Set(
          SchemaField("timestamp", TimestampFieldType, BIGINT()),
          SchemaField("value", ValueFieldType, INT()),
          SchemaField("name", DimensionFieldType, VARCHAR()),
          SchemaField("surname", DimensionFieldType, VARCHAR()),
          SchemaField("city", TagFieldType, VARCHAR()),
          SchemaField("country", TagFieldType, VARCHAR())
        )
      )
    )

    probe.send(
      schemaCoordinator,
      UpdateSchemaFromRecord(
        "db",
        "namespace",
        "offices",
        Bit(0, 23, Map("name" -> "john", "surname" -> "doe"), Map("city" -> "milano", "country" -> "italy")))
    )

    probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]] shouldBe 'right

    probe.send(schemaCoordinator, GetSchema("db", "namespace", "offices"))

    val schema = probe.expectMsgType[SchemaGot]
    schema.metric shouldBe "offices"
    schema.schema shouldBe Some(
      Schema(
        "offices",
        Set(
          SchemaField("timestamp", TimestampFieldType, BIGINT()),
          SchemaField("value", ValueFieldType, INT()),
          SchemaField("name", DimensionFieldType, VARCHAR()),
          SchemaField("surname", DimensionFieldType, VARCHAR()),
          SchemaField("city", TagFieldType, VARCHAR()),
          SchemaField("country", TagFieldType, VARCHAR())
        )
      )
    )

    probe.send(
      schemaCoordinator,
      DeleteSchema("db", "namespace", "offices")
    )

    val deletion = probe.expectMsgType[SchemaDeleted]
    deletion.metric shouldBe "offices"

    probe.send(
      schemaCoordinator,
      DeleteSchema("db", "namespace", "offices")
    )

    Await
      .result((schemaCoordinator ? GetSchema("db", "namespace", "offices")).mapTo[SchemaGot], 3 seconds)
      .schema
      .isDefined shouldBe false

    Await
      .result((schemaCoordinator ? GetSchema("db", "namespace", "people")).mapTo[SchemaGot], 3 seconds)
      .schema
      .isDefined shouldBe true
  }

  "schemaCoordinator" should "get schemas from different namespaces" in {

    probe.send(schemaCoordinator, GetSchema(db, namespace, "nonexisting"))

    val nonexistingGot = probe.expectMsgType[SchemaGot]
    nonexistingGot.metric shouldBe "nonexisting"
    nonexistingGot.schema shouldBe None

    probe.send(schemaCoordinator, GetSchema(db, namespace, "people"))

    val existingGot = probe.expectMsgType[SchemaGot]
    existingGot.metric shouldBe "people"
    existingGot.schema shouldBe Some(
      Schema(
        "people",
        Set(
          SchemaField("timestamp", TimestampFieldType, BIGINT()),
          SchemaField("value", ValueFieldType, INT()),
          SchemaField("name", DimensionFieldType, VARCHAR()),
          SchemaField("city", TagFieldType, VARCHAR())
        )
      ))

    probe.send(schemaCoordinator, GetSchema(db, namespace1, "people"))

    val existingGot1 = probe.expectMsgType[SchemaGot]
    existingGot1.metric shouldBe "people"
    existingGot1.schema shouldBe Some(
      Schema(
        "people",
        Set(
          SchemaField("timestamp", TimestampFieldType, BIGINT()),
          SchemaField("value", ValueFieldType, INT()),
          SchemaField("surname", DimensionFieldType, VARCHAR()),
          SchemaField("country", TagFieldType, VARCHAR())
        )
      ))
  }

  "schemaCoordinator" should "update schemas in case of success in different namespaces" in {
    probe.send(schemaCoordinator, UpdateSchemaFromRecord(db, namespace, "people", surnameRecord))

    probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]] shouldBe 'right

    probe.send(schemaCoordinator, GetSchema(db, namespace, "people"))

    val existingGot = probe.expectMsgType[SchemaGot]
    existingGot.metric shouldBe "people"
    existingGot.schema shouldBe Some(
      Schema(
        "people",
        Set(
          SchemaField("timestamp", TimestampFieldType, BIGINT()),
          SchemaField("value", ValueFieldType, INT()),
          SchemaField("name", DimensionFieldType, VARCHAR()),
          SchemaField("surname", DimensionFieldType, VARCHAR()),
          SchemaField("city", TagFieldType, VARCHAR()),
          SchemaField("country", TagFieldType, VARCHAR())
        )
      )
    )

    probe.send(schemaCoordinator, UpdateSchemaFromRecord(db, namespace1, "people", nameRecord))

    probe.expectMsgType[Either[UpdateSchemaFailed, SchemaUpdated]] shouldBe 'right

    probe.send(schemaCoordinator, GetSchema(db, namespace, "people"))

    val existingGot1 = probe.expectMsgType[SchemaGot]
    existingGot1.metric shouldBe "people"
    existingGot1.schema shouldBe Some(
      Schema(
        "people",
        Set(
          SchemaField("timestamp", TimestampFieldType, BIGINT()),
          SchemaField("value", ValueFieldType, INT()),
          SchemaField("name", DimensionFieldType, VARCHAR()),
          SchemaField("surname", DimensionFieldType, VARCHAR()),
          SchemaField("city", TagFieldType, VARCHAR()),
          SchemaField("country", TagFieldType, VARCHAR())
        )
      )
    )
  }
}
