package io.radicalbit.nsdb.actors

import java.nio.file.Paths

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import io.radicalbit.nsdb.actors.PublisherActor.Command.SubscribeBySqlStatement
import io.radicalbit.nsdb.actors.PublisherActor.Events.{RecordPublished, Subscribed}
import io.radicalbit.nsdb.common.protocol.{Bit, BitOut}
import io.radicalbit.nsdb.common.statement._
import io.radicalbit.nsdb.coordinator.ReadCoordinator.{ExecuteStatement, SelectStatementExecuted}
import io.radicalbit.nsdb.coordinator.WriteCoordinator.InputMapped
import io.radicalbit.nsdb.index.QueryIndex
import org.apache.lucene.store.FSDirectory
import org.scalatest._

class FakeReadCoordinatorActor extends Actor {
  def receive: Receive = {
    case ExecuteStatement(_) => sender() ! SelectStatementExecuted(Seq.empty)
  }
}

class PublisherActorSpec
    extends TestKit(ActorSystem("PublisherActorSpec"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with OneInstancePerTest
    with BeforeAndAfter {

  val basePath   = "target/test_index_publisher_actor"
  val probe      = TestProbe()
  val probeActor = probe.testActor
  val publisherActor =
    TestActorRef[PublisherActor](PublisherActor.props(basePath, system.actorOf(Props[FakeReadCoordinatorActor])))

  val testSqlStatement = SelectSQLStatement(
    namespace = "registry",
    metric = "people",
    fields = AllFields,
    condition = Some(
      Condition(ComparisonExpression(dimension = "timestamp", comparison = GreaterOrEqualToOperator, value = 10L))),
    limit = Some(LimitOperator(4))
  )
  val testRecordNotSatisfy = Bit(0, Map("name"   -> "john"), 23)
  val testRecordSatisfy    = Bit(100, Map("name" -> "john"), 25)

  before {
    val queryIndex: QueryIndex = new QueryIndex(FSDirectory.open(Paths.get(basePath, "queries")))
    implicit val writer        = queryIndex.getWriter
    queryIndex.deleteAll()
    writer.close()
  }

  "PublisherActor" should "make other actors subscribe" in {
    probe.send(publisherActor, SubscribeBySqlStatement(probeActor, testSqlStatement))
    probe.expectMsgType[Subscribed]

    publisherActor.underlyingActor.queries.keys.size shouldBe 1
    publisherActor.underlyingActor.queries.values.head.query shouldBe testSqlStatement

    publisherActor.underlyingActor.subscribedActors.keys.size shouldBe 1
    publisherActor.underlyingActor.subscribedActors.values.head shouldBe Set(probeActor)

  }

  "PublisherActor" should "subscribe more than once" in {
    probe.send(publisherActor, SubscribeBySqlStatement(probeActor, testSqlStatement))
    val firstId = probe.expectMsgType[Subscribed].quid

    publisherActor.underlyingActor.queries.keys.size shouldBe 1
    publisherActor.underlyingActor.queries.values.head.query shouldBe testSqlStatement

    publisherActor.underlyingActor.subscribedActors.values.size shouldBe 1
    publisherActor.underlyingActor.subscribedActors.values.head shouldBe Set(probeActor)

    probe.send(publisherActor, SubscribeBySqlStatement(probeActor, testSqlStatement.copy(metric = "anotherOne")))
    val secondId = probe.expectMsgType[Subscribed].quid

    publisherActor.underlyingActor.queries.keys.size shouldBe 2
    publisherActor.underlyingActor.subscribedActors.keys.size shouldBe 2
    publisherActor.underlyingActor.subscribedActors.keys.toSeq.contains(firstId) shouldBe true
    publisherActor.underlyingActor.subscribedActors.keys.toSeq.contains(secondId) shouldBe true
    publisherActor.underlyingActor.subscribedActors.values.head shouldBe Set(probeActor)
    publisherActor.underlyingActor.subscribedActors.values.last shouldBe Set(probeActor)
  }

  "PublisherActor" should "do nothing if an event that does not satisfy a query comes" in {
    probe.send(publisherActor, SubscribeBySqlStatement(probeActor, testSqlStatement))
    probe.expectMsgType[Subscribed]

    probe.send(publisherActor, InputMapped("namespace", "rooms", testRecordNotSatisfy))
    probe.expectNoMsg()

    probe.send(publisherActor, InputMapped("namespace", "people", testRecordNotSatisfy))
    probe.expectNoMsg()
  }

  "PublisherActor" should "send a messge to all its subscribers when a matching event comes" in {
    probe.send(publisherActor, SubscribeBySqlStatement(probeActor, testSqlStatement))
    probe.expectMsgType[Subscribed]

    probe.send(publisherActor, InputMapped("namespace", "people", testRecordSatisfy))
    val recordPublished = probe.expectMsgType[RecordPublished]
    recordPublished.metric shouldBe "people"
    recordPublished.record shouldBe BitOut(testRecordSatisfy)
  }

  "PublisherActor" should "recover its queries when it is restarted" in {
    probe.send(publisherActor, SubscribeBySqlStatement(probeActor, testSqlStatement))
    val subscribed = probe.expectMsgType[Subscribed]

    probe.send(publisherActor, PoisonPill)

    val newPublisherActor = system.actorOf(
      PublisherActor.props("target/test_index_publisher_actor", system.actorOf(Props[FakeReadCoordinatorActor])))

    probe.send(newPublisherActor, SubscribeBySqlStatement(probeActor, testSqlStatement))
    val newSubscribed = probe.expectMsgType[Subscribed]

    newSubscribed.quid shouldBe subscribed.quid
  }
}