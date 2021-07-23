package zio.flow

import zio.schema.DeriveSchema
import zio.schema.DeriveSchema.gen
import zio.test.{DefaultRunnableSpec, ZSpec}

object UberEatsExample extends DefaultRunnableSpec{
  case class User(id : String, name : String, address : String)
  case class Restaurant(id: String, name : String)
  case class Order(id : String, itemList : List[(String, Int)])

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

  implicit val userSchema = DeriveSchema.gen[User]
  implicit val restaurantSchema = DeriveSchema.gen[Restaurant]
  implicit val orderSchema = DeriveSchema.gen[Order]
  implicit val confirmationStatusSchema = DeriveSchema.gen[ConfirmationStatus]
  implicit val orderStatus = DeriveSchema.gen[OrderStatus]

  val user : Remote[User] = Remote(User("1234", "Ash", "Lodha, NCP"))
  val restaurant : Remote[Restaurant] = Remote(Restaurant("2343", "Chinese rest"))
  val order : Remote[Order] = Remote(Order("3321", List(("General tao's chick",2))))

  val confirmOrderWithRestaurant : Activity[(User, Restaurant, Order), ConfirmationStatus] = ???

  val cancelOrderFlow: ZFlow[Any, Nothing, Unit] = for {
    _ <- ZFlow.log("Order was cancelled by the restaurant")
  } yield ()

  def mockOrderStatusChangeFromApp(orderStatusVar : Remote[Variable[OrderStatus]], status : OrderStatus): ZFlow[Any, Nothing, Unit] =
    orderStatusVar.set(status)

  val restaurantWorkflow = for {
    orderStatus <- ZFlow.newVar("orderStatus", OrderStatus.InQueue.asInstanceOf[OrderStatus])
    _ <- ZFlow.log("Initiated Restaurant workflow")
    _ <- ZFlow.sleep(Remote.ofSeconds(1L))
    _ <- mockOrderStatusChangeFromApp(orderStatus, OrderStatus.StartedPreparing)
    _ <- ZFlow.log("Order Status is now set to "+ orderStatus)
    _ <- ZFlow.sleep(Remote.ofSeconds(1L))
    _ <- mockOrderStatusChangeFromApp(orderStatus, OrderStatus.FoodPrepared)
    _ <- ZFlow.log("Order Status is now set to "+ orderStatus)
    _ <- ZFlow.sleep(Remote.ofSeconds(1L))
    _ <- mockOrderStatusChangeFromApp(orderStatus, OrderStatus.Packed)
    _ <- ZFlow.log("Order Status is now set to "+ orderStatus)
  } yield()

  val suite1 = suite("Uber eats workflow")(testM("Initialisation workflow"){
    for {
      orderConfirmationStatus <- confirmOrderWithRestaurant(user, restaurant, order)
      _ <- (orderConfirmationStatus === Remote(ConfirmationStatus.Confirmed)).ifThenElse(restaurantWorkflow, cancelOrderFlow)
    } yield ()
  })
  override def spec = suite("End-to-end ubereats workflow example")(suite1)

}
