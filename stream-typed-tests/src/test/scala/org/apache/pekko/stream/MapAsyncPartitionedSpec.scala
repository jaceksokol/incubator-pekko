/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.stream

import java.util.concurrent.Executors
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import scala.concurrent.{ blocking, ExecutionContext, Future }
import scala.language.postfixOps
import scala.util.Random
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.Behaviors
import pekko.stream.scaladsl.{ Flow, FlowWithContext, Keep, Sink, Source, SourceWithContext }
import org.scalacheck.{ Arbitrary, Gen }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import java.time.Instant
import scala.annotation.nowarn

private object MapAsyncPartitionedSpec {

  object TestData {

    case class BufferSize(value: Int) extends AnyVal
    case class Parallelism(value: Int) extends AnyVal

    case class TestKeyValue(key: Int, delay: FiniteDuration, value: String)

    implicit val bufferSizeArb: Arbitrary[BufferSize] = Arbitrary {
      Gen.choose(1, 100).map(BufferSize.apply)
    }
    implicit val parallelismArb: Arbitrary[Parallelism] = Arbitrary {
      Gen.choose(2, 8).map(Parallelism.apply)
    }
    implicit val elementsArb: Arbitrary[Seq[TestKeyValue]] = Arbitrary {
      for {
        totalElements <- Gen.choose(1, 100)
        totalPartitions <- Gen.choose(1, 8)
      } yield {
        generateElements(totalPartitions, totalElements)
      }
    }

    def generateElements(totalPartitions: Int, totalElements: Int): Seq[TestKeyValue] =
      for (i <- 1 to totalElements) yield {
        TestKeyValue(
          key = Random.nextInt(totalPartitions),
          delay = DurationInt(Random.nextInt(20) + 10).millis,
          value = i.toString)
      }

    def extractPartition(e: TestKeyValue): Int =
      e.key

    type Operation = TestKeyValue => Future[(Int, String)]

    def asyncOperation(e: TestKeyValue, p: Int)(implicit ec: ExecutionContext): Future[(Int, String)] =
      Future {
        p -> e.value
      }

    def blockingOperation(e: TestKeyValue, p: Int)(implicit ec: ExecutionContext): Future[(Int, String)] =
      Future {
        blocking {
          Thread.sleep(e.delay.toMillis)
          p -> e.value
        }
      }

  }
}

class MapAsyncPartitionedSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with ScalaCheckDrivenPropertyChecks {

  import MapAsyncPartitionedSpec.TestData._

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(
    timeout = 5 seconds,
    interval = 100 millis)

  private implicit val system: ActorSystem[_] = ActorSystem(Behaviors.empty, "test-system")
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  override protected def afterAll(): Unit = {
    system.terminate()
    system.whenTerminated.futureValue
    super.afterAll()
  }

  private def infiniteStream(): LazyList[Int] = LazyList.from(1)

  @nowarn("msg=never used")
  private def f(i: Int, p: Int): Future[Int] =
    Future(i % 2)

  behavior.of("MapAsyncPartitionedUnordered")

  it should "process elements in parallel by partition" in {
    val elements = List(
      TestKeyValue(key = 1, delay = 1000 millis, value = "1.a"),
      TestKeyValue(key = 2, delay = 700 millis, value = "2.a"),
      TestKeyValue(key = 1, delay = 500 millis, value = "1.b"),
      TestKeyValue(key = 1, delay = 500 millis, value = "1.c"),
      TestKeyValue(key = 2, delay = 900 millis, value = "2.b"))

    val result =
      Source(elements)
        .mapAsyncPartitionedUnordered(parallelism = 2, bufferSize = 4)(extractPartition)(blockingOperation)
        .runWith(Sink.seq)
        .futureValue
        .map(_._2)

    result shouldBe Vector("2.a", "1.a", "1.b", "2.b", "1.c")
  }

  it should "process elements in parallel preserving order in partition" in {
    forAll(minSuccessful(1000)) { (bufferSize: BufferSize, parallelism: Parallelism, elements: Seq[TestKeyValue]) =>
      val result =
        Source(elements.toIndexedSeq)
          .mapAsyncPartitionedUnordered(parallelism.value, bufferSize.value)(extractPartition)(asyncOperation)
          .runWith(Sink.seq)
          .futureValue

      val actual = result.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      val expected = elements.toSeq.groupBy(_.key).view.mapValues(_.map(_.value)).toMap

      actual shouldBe expected
    }
  }

  it should "process elements in sequence preserving order in partition" in {
    forAll(minSuccessful(1000)) { (bufferSize: BufferSize, elements: Seq[TestKeyValue]) =>
      val result =
        Source
          .fromIterator(() => elements.iterator)
          .mapAsyncPartitionedUnordered(parallelism = 1, bufferSize.value)(extractPartition)(asyncOperation)
          .runWith(Sink.seq)
          .futureValue

      val actual = result.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      val expected = elements.toSeq.groupBy(_.key).view.mapValues(_.map(_.value)).toMap

      actual shouldBe expected
    }
  }

  it should "process elements in parallel preserving order in partition with blocking operation" in {
    forAll(minSuccessful(10)) { (bufferSize: BufferSize, parallelism: Parallelism, elements: Seq[TestKeyValue]) =>
      val result =
        Source
          .fromIterator(() => elements.iterator)
          .mapAsyncPartitionedUnordered(parallelism.value, bufferSize.value)(extractPartition)(blockingOperation)
          .runWith(Sink.seq)
          .futureValue

      val actual = result.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      val expected = elements.toSeq.groupBy(_.key).view.mapValues(_.map(_.value)).toMap

      actual shouldBe expected
    }
  }

  it should "stop the stream via a KillSwitch" in {
    val (killSwitch, future) =
      Source(infiniteStream())
        .mapAsyncPartitionedUnordered(parallelism = 6)(i => i % 6) { (i, _) =>
          Future {
            blocking {
              Thread.sleep(40)
              (i % 6).toString -> i.toString
            }
          }
        }
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

    Thread.sleep(500)

    killSwitch.shutdown()

    val result = future.futureValue.groupBy(_._1)
    result should have size 6
    result.values.foreach {
      _.size should be >= 10
    }
  }

  it should "stop the stream if any operation fails" in {
    val future =
      Source(infiniteStream())
        .mapAsyncPartitionedUnordered(parallelism = 4)(i => i % 8) { (i, _) =>
          Future {
            if (i == 23) throw new RuntimeException("Ignore it")
            else i.toString
          }
        }
        .toMat(Sink.ignore)(Keep.right)
        .run()

    future.failed.futureValue shouldBe a[RuntimeException]
  }

  behavior.of("MapAsyncPartitionedOrdered")

  it should "process elements in parallel by partition" in {
    val elements = List(
      TestKeyValue(key = 1, delay = 1000 millis, value = "1.a"),
      TestKeyValue(key = 2, delay = 700 millis, value = "2.a"),
      TestKeyValue(key = 1, delay = 500 millis, value = "1.b"),
      TestKeyValue(key = 1, delay = 500 millis, value = "1.c"),
      TestKeyValue(key = 2, delay = 900 millis, value = "2.b"))

    def processElement(e: TestKeyValue, p: Int)(implicit ec: ExecutionContext): Future[(Int, (String, Instant))] =
      Future {
        blocking {
          val startedAt = Instant.now()
          Thread.sleep(e.delay.toMillis)
          p -> (e.value -> startedAt)
        }
      }

    val result =
      Source(elements)
        .mapAsyncPartitioned(parallelism = 2, bufferSize = 4)(extractPartition)(processElement)
        .runWith(Sink.seq)
        .futureValue
        .map(_._2)

    result.map(_._1) shouldBe Vector("1.a", "2.a", "1.b", "1.c", "2.b")
    val elementStartTime = result.toMap

    elementStartTime("1.a") should be < elementStartTime("1.b")
    elementStartTime("1.b") should be < elementStartTime("1.c")
    elementStartTime("2.a") should be < elementStartTime("2.b")
  }

  it should "process elements in parallel preserving order in partition" in {
    forAll(minSuccessful(1000)) { (bufferSize: BufferSize, parallelism: Parallelism, elements: Seq[TestKeyValue]) =>
      val result =
        Source(elements.toIndexedSeq)
          .mapAsyncPartitioned(parallelism.value, bufferSize.value)(extractPartition)(asyncOperation)
          .runWith(Sink.seq)
          .futureValue

      val actual = result.map(_._2)
      val expected = elements.map(_.value)

      actual shouldBe expected
    }
  }

  it should "process elements in sequence preserving order in partition" in {
    forAll(minSuccessful(1000)) { (bufferSize: BufferSize, elements: Seq[TestKeyValue]) =>
      val result =
        Source
          .fromIterator(() => elements.iterator)
          .mapAsyncPartitioned(parallelism = 1, bufferSize.value)(extractPartition)(asyncOperation)
          .runWith(Sink.seq)
          .futureValue

      val actual = result.map(_._2)
      val expected = elements.map(_.value)

      actual shouldBe expected
    }
  }

  it should "process elements in parallel preserving order in partition with blocking operation" in {
    forAll(minSuccessful(10)) { (bufferSize: BufferSize, parallelism: Parallelism, elements: Seq[TestKeyValue]) =>
      val result =
        Source
          .fromIterator(() => elements.iterator)
          .mapAsyncPartitioned(parallelism.value, bufferSize.value)(extractPartition)(blockingOperation)
          .runWith(Sink.seq)
          .futureValue

      val actual = result.map(_._2)
      val expected = elements.map(_.value)

      actual shouldBe expected
    }
  }

  it should "stop the stream via a KillSwitch" in {
    val (killSwitch, future) =
      Source(infiniteStream())
        .mapAsyncPartitioned(parallelism = 6)(i => i % 6) { (i, _) =>
          Future {
            blocking {
              Thread.sleep(40)
              (i % 6).toString -> i.toString
            }
          }
        }
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

    Thread.sleep(500)

    killSwitch.shutdown()

    val result = future.futureValue.groupBy(_._1)
    result should have size 6
    result.values.foreach {
      _.size should be >= 10
    }
  }

  it should "stop the stream if any operation fails" in {
    val future =
      Source(infiniteStream())
        .mapAsyncPartitioned(parallelism = 4)(i => i % 8) { (i, _) =>
          Future {
            if (i == 23) throw new RuntimeException("Ignore it")
            else i.toString
          }
        }
        .toMat(Sink.ignore)(Keep.right)
        .run()

    future.failed.futureValue shouldBe a[RuntimeException]
  }

  behavior.of("operator applicability")

  it should "be applicable to a source" in {
    Source
      .single(3)
      .mapAsyncPartitioned(parallelism = 1)(identity)(f)
      .runWith(Sink.seq)
      .futureValue shouldBe Seq(1)
  }

  it should "be applicable to a source with context" in {
    SourceWithContext
      .fromTuples(Source.single(3 -> "A"))
      .mapAsyncPartitioned(parallelism = 1)(identity)(f)
      .runWith(Sink.seq)
      .futureValue shouldBe Seq(1 -> "A")
  }

  it should "be applicable to a flow" in {
    Flow[Int]
      .mapAsyncPartitioned(parallelism = 1)(identity)(f)
      .runWith(Source.single(3), Sink.seq)
      ._2
      .futureValue shouldBe Seq(1)
  }

  it should "be applicable to a flow with context" in {
    val flow =
      FlowWithContext[Int, String]
        .mapAsyncPartitioned(parallelism = 1)(identity)(f)

    SourceWithContext
      .fromTuples(Source.single(3 -> "A"))
      .via(flow)
      .runWith(Sink.seq)
      .futureValue shouldBe Seq(1 -> "A")
  }

}
