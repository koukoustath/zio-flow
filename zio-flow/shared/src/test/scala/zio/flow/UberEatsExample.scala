package zio.flow

import java.net.URI

import zio.ZIO
import zio.clock.Clock
import zio.console.Console
import zio.flow.GoodcoverUseCase.{emailRequest, reminderEmailForManualEvaluation}
import zio.flow.ZFlowMethodSpec.setBoolVarAfterSleep
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.DeriveSchema.gen
import zio.schema.{DeriveSchema, Schema}
import zio.test.Assertion.equalTo
import zio.test.{DefaultRunnableSpec, Spec, TestFailure, TestSuccess, assertM}

object UberEatsExample extends DefaultRunnableSpec {

  case class User(id: String, name: String, address: String)

  case class Restaurant(id: String, name: String)

  case class Order(id: String, itemList: List[(String, Int)])

  sealed trait ConfirmationStatus

  object ConfirmationStatus {

    case object Confirmed extends ConfirmationStatus

    case object Rejected extends ConfirmationStatus

  }

  sealed trait OrderStatus

  object OrderStatus {

    case object InQueue extends OrderStatus

    case object StartedPreparing extends OrderStatus

    case object FoodPrepared extends OrderStatus

    case object Packed extends OrderStatus

  }

  implicit val userSchema: Schema[User] = DeriveSchema.gen[User]
  implicit val restaurantSchema: Schema[Restaurant] = DeriveSchema.gen[Restaurant]
  implicit val orderSchema: Schema[Order] = DeriveSchema.gen[Order]
  implicit val confirmationStatusSchema: Schema[ConfirmationStatus] = DeriveSchema.gen[ConfirmationStatus]
  implicit val orderStatus: Schema[OrderStatus] = DeriveSchema.gen[OrderStatus]
  implicit val acitivityErrorSchema: Schema[ActivityError] = Schema.fail("Activity error schema")

  val user: Remote[User] = Remote(User("1234", "Ash", "Lodha, NCP"))
  val restaurant: Remote[Restaurant] = Remote(Restaurant("2343", "Chinese rest"))
  val order: Remote[Order] = Remote(Order("3321", List(("General tao's chick", 2))))

  //TODO : Model this as a workflow
  val confirmOrderWithRestaurant: Activity[(User, Restaurant, Order), ConfirmationStatus] = Activity[((User, Restaurant, Order)), ConfirmationStatus](
    "get-order-confirmation-status",
    "Returns whether or not an order was confirmed by the restaurant",
    Operation.Http[(User, Restaurant, Order), ConfirmationStatus](
      new URI("getOrderConfirmationStatus.com"),
      "GET",
      Map.empty[String, String],
      implicitly[Schema[(User, Restaurant, Order)]],
      implicitly[Schema[ConfirmationStatus]]
    ),
    ZFlow.succeed(ConfirmationStatus.Confirmed),
    ZFlow.unit
  )

  val cancelOrderFlow: ZFlow[Any, Nothing, Unit] = for {
    _ <- ZFlow.log("Order was cancelled by the restaurant")
  } yield ()

  def mockOrderStatusChangeFromRestaurant(orderStatusVar: Remote[Variable[OrderStatus]], status: OrderStatus): ZFlow[Any, Nothing, Unit] =
    orderStatusVar.set(status)

  def restaurantWorkflow(orderStatus: RemoteVariable[OrderStatus]): ZFlow[Any, Nothing, Unit] = for {
    _ <- ZFlow.log("Initiated Restaurant workflow")
    _ <- ZFlow.sleep(Remote.ofSeconds(1L))
    _ <- mockOrderStatusChangeFromRestaurant(orderStatus, OrderStatus.StartedPreparing)
    _ <- ZFlow.log("Order Status is now set to " + orderStatus)
    _ <- ZFlow.sleep(Remote.ofSeconds(1L))
    _ <- mockOrderStatusChangeFromRestaurant(orderStatus, OrderStatus.FoodPrepared)
    _ <- ZFlow.log("Order Status is now set to " + orderStatus)
    _ <- ZFlow.sleep(Remote.ofSeconds(1L))
    _ <- mockOrderStatusChangeFromRestaurant(orderStatus, OrderStatus.Packed)
    _ <- ZFlow.log("Order Status is now set to " + orderStatus)
  } yield ()

  val riderWorkflow: ZFlow[Any,Nothing,Unit] = for {
    _ <- ZFlow.log("Rider workflow is triggered")
  } yield ()

  def riderWorkflowInitialiser(orderStatusVar: RemoteVariable[OrderStatus]): ZFlow[Any, Nothing, (OrderStatus, Unit)] = (ZFlow.Iterate(
    ZFlow(OrderStatus.InQueue),
    (_: Remote[OrderStatus]) =>
      for {
        _ <- ZFlow.log("Inside rider workflow initialiser.")
        _    <- orderStatusVar.waitUntil(_ === OrderStatus.Packed).timeout(Remote.ofSeconds(1L))
        orderStatus <- orderStatusVar.get
      } yield orderStatus,
    (b: Remote[OrderStatus]) => b !== OrderStatus.Packed) zip ZFlow.log("Rider workflow is now kicked off.")
  )

  val suite1: Spec[Clock with Console, TestFailure[ActivityError], TestSuccess] = suite("Uber eats workflow")(testM("Initialisation workflow") {
    val result: ZIO[Clock with Console, ActivityError, Unit] = (for {
      orderConfirmationStatus <- confirmOrderWithRestaurant(user, restaurant, order)
      orderStatus <- ZFlow.newVar("orderStatus", OrderStatus.InQueue.asInstanceOf[OrderStatus])
      _ <- ZFlow.ifThenElse(orderConfirmationStatus === Remote(ConfirmationStatus.Confirmed))(restaurantWorkflow(orderStatus).fork zip riderWorkflowInitialiser(orderStatus), cancelOrderFlow)
    } yield ()).evaluateInMemForUber

    assertM(result)(equalTo(()))
  })

  override def spec: Spec[Clock with Console, TestFailure[ActivityError], TestSuccess] = suite("End-to-end ubereats workflow example")(suite1)

}
