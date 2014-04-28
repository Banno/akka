/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import akka.testkit.EventFilter
import scala.util.Failure
import scala.util.control.NoStackTrace
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.RecoveryTransformer
import scala.util.Try
import scala.util.Success

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowTransformRecoverSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2))

  "A Flow with transformRecover operations" must {
    "produce one-to-one transformation as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Int] {
          var tot = 0
          override def onNext(element: Try[Int]) = element match {
            case Success(elem) ⇒
              tot += elem
              List(tot)
            case _ ⇒ List(-1)
          }
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(1)
      consumer.expectNext(1)
      consumer.expectNoMsg(200.millis)
      subscription.requestMore(2)
      consumer.expectNext(3)
      consumer.expectNext(6)
      consumer.expectComplete()
    }

    "produce one-to-several transformation as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Int] {
          var tot = 0
          override def onNext(element: Try[Int]) = element match {
            case Success(elem) ⇒
              tot += elem
              Vector.fill(elem)(tot)
            case _ ⇒ List(-1)
          }
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(4)
      consumer.expectNext(1)
      consumer.expectNext(3)
      consumer.expectNext(3)
      consumer.expectNext(6)
      consumer.expectNoMsg(200.millis)
      subscription.requestMore(100)
      consumer.expectNext(6)
      consumer.expectNext(6)
      consumer.expectComplete()
    }

    "produce dropping transformation as expected" in {
      val p = Flow(List(1, 2, 3, 4).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Int] {
          var tot = 0
          override def onNext(element: Try[Int]) = element match {
            case Success(elem) ⇒
              tot += elem
              if (elem % 2 == 0) Nil else List(tot)
            case _ ⇒ List(-1)
          }
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(1)
      consumer.expectNext(1)
      consumer.expectNoMsg(200.millis)
      subscription.requestMore(1)
      consumer.expectNext(6)
      subscription.requestMore(1)
      consumer.expectComplete()
    }

    "produce multi-step transformation as expected" in {
      val p = Flow(List("a", "bc", "def").iterator).toProducer(materializer)
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[String, Int] {
          var concat = ""
          override def onNext(element: Try[String]) = {
            concat += element
            List(concat.length)
          }
        }).
        transformRecover(new RecoveryTransformer[Int, Int] {
          var tot = 0
          override def onNext(element: Try[Int]) = element match {
            case Success(length) ⇒
              tot += length
              List(tot)
            case _ ⇒ List(-1)
          }
        }).
        toProducer(materializer)
      val c1 = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c1)
      val sub1 = c1.expectSubscription()
      val c2 = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c2)
      val sub2 = c2.expectSubscription()
      sub1.requestMore(1)
      sub2.requestMore(2)
      c1.expectNext(10)
      c2.expectNext(10)
      c2.expectNext(31)
      c1.expectNoMsg(200.millis)
      sub1.requestMore(2)
      sub2.requestMore(2)
      c1.expectNext(31)
      c1.expectNext(64)
      c2.expectNext(64)
      c1.expectComplete()
      c2.expectComplete()
    }

    "invoke onComplete when done" in {
      val p = Flow(List("a").iterator).toProducer(materializer)
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[String, String] {
          var s = ""
          override def onNext(element: Try[String]) = {
            s += element
            Nil
          }
          override def onComplete() = List(s + "B")
        }).
        toProducer(materializer)
      val c = StreamTestKit.consumerProbe[String]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(1)
      c.expectNext("Success(a)B")
      c.expectComplete()
    }

    "allow cancellation using isComplete" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Int] {
          var s = ""
          override def onNext(element: Try[Int]) = {
            s += element
            List(element.get)
          }
          override def isComplete = s == "Success(1)"
        }).
        toProducer(materializer)
      val proc = p.expectSubscription
      val c = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectComplete()
      proc.expectCancellation()
    }

    "call onComplete after isComplete signaled completion" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Int] {
          var s = ""
          override def onNext(element: Try[Int]) = {
            s += element
            List(element.get)
          }
          override def isComplete = s == "Success(1)"
          override def onComplete() = List(s.length + 10)
        }).
        toProducer(materializer)
      val proc = p.expectSubscription
      val c = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectNext(20)
      c.expectComplete()
      proc.expectCancellation()
    }

    "report error when exception is thrown" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Int] {
          override def onNext(element: Try[Int]) = element match {
            case Success(elem) ⇒
              if (elem == 2) throw new IllegalArgumentException("two not allowed")
              else List(elem, elem)
            case _ ⇒ List(-1)
          }
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        subscription.requestMore(100)
        consumer.expectNext(1)
        consumer.expectNext(1)
        consumer.expectError().getMessage should be("two not allowed")
        consumer.expectNoMsg(200.millis)
      }
    }

    case class TE(message: String) extends RuntimeException(message) with NoStackTrace

    "transform errors when received" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Throwable] {
          var s = ""
          override def onNext(element: Try[Int]) = element match {
            case Failure(ex) ⇒
              s += ex.getMessage
              List(ex)
            case _ ⇒ List(new IllegalStateException)
          }
          override def onComplete() = List(TE(s.size + "10"))
        }).
        toProducer(materializer)
      val proc = p.expectSubscription()
      val c = StreamTestKit.consumerProbe[Throwable]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      proc.sendError(TE("1"))
      c.expectNext(TE("1"))
      c.expectNext(TE("110"))
      c.expectComplete()
    }

    "forward errors when received and thrown" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Int] {
          override def onNext(in: Try[Int]) = List(in.get)
        }).
        toProducer(materializer)
      val proc = p.expectSubscription()
      val c = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      EventFilter[TE](occurrences = 1) intercept {
        proc.sendError(TE("1"))
        c.expectError(TE("1"))
      }
    }

    "support cancel as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transformRecover(new RecoveryTransformer[Int, Int] {
          override def onNext(element: Try[Int]) = element match {
            case Success(elem) ⇒ List(elem, elem)
            case _             ⇒ List(-1)
          }
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(2)
      consumer.expectNext(1)
      subscription.cancel()
      consumer.expectNext(1)
      consumer.expectNoMsg(500.millis)
      subscription.requestMore(2)
      consumer.expectNoMsg(200.millis)
    }
  }

}