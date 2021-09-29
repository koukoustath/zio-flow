package zio.flow

import zio.console.{ Console, putStrLn }
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.Schema
import zio.test.Assertion.{ anything, dies, equalTo, isLeft, isSubtype }
import zio.test.TestAspect.ignore
import zio.test.{ Annotations, DefaultRunnableSpec, Spec, TestFailure, TestSuccess, ZSpec, assertM }
import zio.{ Has, ZIO }

object PersistentExecutorSpec extends DefaultRunnableSpec {

  implicit val nothingSchema: Schema[Nothing] = Schema.fail("Nothing schema")

  def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    if ((a mod Remote(2)) == Remote(1)) (Remote(true), a) else (Remote(false), a)

  val suite1: Spec[Annotations with Console, TestFailure[Any], TestSuccess] =
    suite("Test the easy operators")(
      testM("Test Return") {

        val flow: ZFlow[Any, Nothing, Int] = ZFlow.Return(12)
        assertM(flow.evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema))(equalTo(12))
      },
      testM("Test NewVar") {
        val compileResult = (for {
          variable <- ZFlow.newVar[Int]("variable1", 10)
          //modifiedVariable <- variable.modify(isOdd)
          v        <- variable.get
        } yield v).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
        assertM(compileResult)(equalTo(10))
      } @@ ignore,
      testM("Test Fold - success side") {
        val compileResult = ZFlow
          .succeed(15)
          .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
          .evaluateLivePersistent(implicitly[Schema[Unit]], nothingSchema)
        assertM(compileResult)(equalTo(()))
      } @@ ignore,
      testM("Test Fold - error side") {
        val compileResult = ZFlow
          .fail(15)
          .foldM(_ => ZFlow.unit, _ => ZFlow.unit)
          .evaluateLivePersistent(implicitly[Schema[Unit]], nothingSchema)
        assertM(compileResult)(equalTo(()))
      } @@ ignore,
      testM("Test input") {
        val compileResult = ZFlow.input[Int].provide(12).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
        assertM(compileResult)(equalTo(12))
      } @@ ignore,
      testM("Test flatmap") {
        val compileResult = (for {
          a <- ZFlow.succeed(12)
          //b <- ZFlow.succeed(10)
        } yield a).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
        assertM(compileResult)(equalTo(12))
      } @@ ignore,
      testM("Test Provide") {
        val compileResult = ZFlow.succeed(12).provide(15).evaluateLivePersistent(implicitly[Schema[Int]], nothingSchema)
        assertM(compileResult)(equalTo(12))
      } @@ ignore,
      testM("Test Ensuring of Zio") {
        val compileResult1 = ZIO
          .succeed(throw new Exception("Woah! exception!!!"))
          .ensuring(putStrLn("Inside Ensuring1"))
        val compileResult2 = ZIO.fail(12).ensuring(putStrLn("Inside Ensuring2")).either
        val compileResult3 = ZIO.succeed(12).ensuring(putStrLn("Inside Ensuring3"))
        assertM(compileResult1.run)(dies(isSubtype[Exception](anything))) *> assertM(compileResult2)(isLeft(equalTo((12)))) *>
          assertM(compileResult3)(equalTo(12))
      },
      testM("Test Ensuring with ZFlow") {
        val compileResult1: ZFlow[Any, Int, Nothing] = ZFlow.fail(10).ensuring(ZFlow.log("Inside Ensuring1, in ZFlow"))
        assertM(compileResult1.evaluateLivePersistent(implicitly[Schema[Int]], implicitly[Schema[Int]]).either)(
          isLeft(equalTo(10))
        )
      } @@ ignore,
      testM("Test Push Environment") {
        val compileResult = ZFlow.PushEnv(Remote(12)).evaluateLivePersistent(Schema[Unit], nothingSchema)
        assertM(compileResult)(equalTo(()))
      },
      testM("Test Pop Environment") {
        val compileResult = ZFlow.PopEnv.evaluateLivePersistent(Schema[Unit], nothingSchema)
        assertM(compileResult)(equalTo(()))
      }
    )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("All tests")(suite1)
}
