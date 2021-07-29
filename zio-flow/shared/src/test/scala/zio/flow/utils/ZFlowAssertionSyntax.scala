package zio.flow.utils

import java.net.URI

import zio.clock.Clock
import zio.console.Console
import zio.flow.UberEatsExample.ConfirmationStatus
import zio.flow.ZFlowExecutor.InMemory
import zio.flow.ZFlowExecutor.InMemory.State
import zio.flow.utils.MocksForGCExample.mockInMemoryForGCExample
import zio.flow.{Activity, ActivityError, Operation, OperationExecutor, ZFlow}
import zio.schema.DeriveSchema.gen
import zio.schema.Schema
import zio.{Has, Ref, ZIO, console}

object ZFlowAssertionSyntax {

  object Mocks {
    val mockActivity: Activity[Any, Int] =
      Activity(
        "Test Activity",
        "Mock activity created for test",
        Operation.Http[Any, Int](
          new URI("testUrlForActivity.com"),
          "GET",
          Map.empty[String, String],
          Schema.fail("No schema"),
          implicitly[Schema[Int]]
        ),
        ZFlow.succeed(12),
        ZFlow.succeed(15)
      )

    val mockOpExec: OperationExecutor[Console with Clock] = new OperationExecutor[Console with Clock] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Console with Clock, ActivityError, A] =
        console.putStrLn("Activity processing") *> ZIO.succeed(input.asInstanceOf[A])
    }

    def mockOpExec1(map: Map[URI, Any]): OperationExecutor[Console with Clock] = new OperationExecutor[Console with Clock] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Console with Clock, ActivityError, A] =
        operation match {
          case Operation.Http(url, _, _, _, _) =>
            console.putStrLn(s"Request to : ${url.toString}") *> ZIO.succeed(map.get(url).get.asInstanceOf[A])
          case Operation.SendEmail(server, port) =>
            console.putStrLn("Sending email") *> ZIO.succeed(().asInstanceOf[A])
        }
    }

    val mapForUberExample = Map[URI, Any](
      new URI("getOrderConfirmationStatus.com") -> ConfirmationStatus.Confirmed
    )

    val mockInMemoryTestClock: ZIO[Clock with Console, Nothing, InMemory[String, Clock with Console]] = ZIO
      .environment[Clock with Console]
      .flatMap(testClock =>
        Ref
          .make[Map[String, Ref[InMemory.State]]](Map.empty)
          .map(ref => InMemory[String, Clock with Console](testClock, mockOpExec, ref))
      )

    val mockInMemoryLiveClock: ZIO[Any, Nothing, InMemory[String, Has[Clock.Service] with Has[Console.Service]]] =
      Ref
        .make[Map[String, Ref[InMemory.State]]](Map.empty)
        .map(ref =>
          InMemory(Has(zio.clock.Clock.Service.live) ++ Has(zio.console.Console.Service.live), mockOpExec, ref)
        )

    val mockInMemoryForUber = Ref.make[Map[String, Ref[State]]](Map.empty).map(state =>
      InMemory((Has(zio.clock.Clock.Service.live) ++ Has(zio.console.Console.Service.live)), mockOpExec1(mapForUberExample), state))
  }

  import Mocks._

  implicit final class InMemoryZFlowAssertion[R, E, A](private val zflow: ZFlow[Any, E, A]) {

    def evaluateTestInMem(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryTestClock
        result <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateLiveInMem(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryLiveClock
        result <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateInMemForUber(implicit schemaA: Schema[A], schemaE: Schema[E]) = {
      for {
        inMemory <- mockInMemoryForUber
        result <- inMemory.submit("1234", zflow)
      } yield result
    }

    def evaluateInMemForGCExample(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Any, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryForGCExample
        result <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }
  }
}
