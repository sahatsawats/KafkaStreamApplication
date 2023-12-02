import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.kstream.{GlobalKTable, JoinWindows, TimeWindows, Windowed}
import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala._
import org.apache.kafka.streams.scala.kstream.{KGroupedStream, KStream, KTable}
import org.apache.kafka.streams.scala.serialization.Serdes
import org.apache.kafka.streams.scala.serialization.Serdes._
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig, Topology}

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Properties
import kafkaTopics.Topics._

object KafkaStreamApplication {
  // Create Serde
  implicit def serde[ A >: Null : Encoder : Decoder]: Serde[A] = {
    // Convert an Object of type "A" to Json and then convert to Byte
    val serializer = (a:A) => a.asJson.noSpaces.getBytes()
    // Convert Byte to String
    val deserializer = (aAsBytes: Array[Byte]) => {
      // Convert Bytes into String
      val aString = new String(aAsBytes)
      // Pass the String into decode with an Option: Return a or Error
      val aOrError = decode[A](aString)
      aOrError match {
        case Right(a) => Option(a)
        case Left(error) => {
          println(s"There was an error converting the message $aOrError, $error")
          Option.empty
        }
      }
    }
    // Create Serdes instance
    Serdes.fromFn[A](serializer, deserializer)
  }

  object Domain {
    // type: alias from String
    type UserId = String
    type Profile = String
    type OrderId = String
    type Product = String

    case class Order(orderId: OrderId, user: UserId, product: List[Product], amount: Double)
    case class Discount(profile: Profile, amount: Double)
    case class Payment(orderId: OrderId, status: String)
  }

  val builder = new StreamsBuilder()

  val orderUsersStream: KStream[Domain.UserId, Domain.Order] = builder.stream[Domain.UserId, Domain.Order](OrderByUsersTopic)

  def paidOrderStream(): Unit = {
    val usersProfileTable: KTable[Domain.UserId, Domain.Profile] = builder.table[Domain.UserId, Domain.Profile](DiscountByProfileTopic)
    val discountByProfileGTable: GlobalKTable[Domain.Profile, Domain.Discount] = builder.globalTable[Domain.Profile, Domain.Discount](Discount)

    // Map Profile with Order
    // Join Stream and Table: def join[VO, VR](other: KStream[K, VO], joiner: (V, VO) => VR): KStream[K, VR]
    // V0 = RightStream, VR Results
    val orderWithUserProfile: KStream[Domain.UserId, (Domain.Order, Domain.Profile)] = {
      // LeftStream.join[RightStream.Value, Results_value]
      orderUsersStream.join[Domain.Profile, (Domain.Order, Domain.Profile)](usersProfileTable) {
        // Joiner
        (order, profile) => (order, profile)
      }
    }

    val discountedOrderStream: KStream[Domain.UserId, Domain.Order] = {
      // Join Stream and GTable:
      // def join[GK, GV, RV](globalKTable: GlobalKTable[GK, GV])(keyValueMapper: (K, V) => GK, joiner: (V, GV) => RV, ): KStream[K, RV]
      // Received 3 parameter, GK: GlobalKey, GV: Gvalue, RV: ResultsValue
      orderWithUserProfile.join[Domain.Profile, Domain.Discount, Domain.Order](discountByProfileGTable)(
        // KeyValueMapper
        { case (_,(_,profile)) => profile },
        // Joiner
        { case ((order,_), discount) => order.copy(amount = order.amount * discount.amount)} )
      }

    // selectKey
    // def selectKey[K1, V](mapper: KeyValueMapper[_ >: K, _ >: V, _ <: K1]): KStream[K1, V]
    val ordersStream: KStream[Domain.UserId, Domain.Order] = discountedOrderStream.selectKey {
      // order is parameter represents the value is associate with the key
      (_, order) => order.orderId
    }

    val paymentsStream: KStream[Domain.OrderId, Domain.Payment] = builder.stream[Domain.OrderId, Domain.Payment](PaymentsTopic)

    val PaidsStream: KStream[Domain.OrderId, Domain.Order] = {
      // Due to Stream is not static, we have to create time frame to capture
      val windowFrame = JoinWindows.of(Duration.of(5, ChronoUnit.MINUTES))
      // Return only paid
      val joinOrderAndPayment = (order: Domain.Order, payment: Domain.Payment) => {
        if (payment.status == "PAID") Option(order) else Option.empty[Domain.Order]
      }
      ordersStream.join[Domain.Payment, Option[Domain.Order]](paymentsStream)(joinOrderAndPayment, windowFrame)
        .flatMapValues(maybeOrder=> maybeOrder.toIterable)
    }
    PaidsStream.to(PaidTopic)
  }

  def main(args: Array[String]): Unit = {
    val prop = new Properties

    prop.put(StreamsConfig.APPLICATION_ID_CONFIG, "Kafka-Streaming")
    prop.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    prop.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.stringSerde.getClass)

    paidOrderStream()

    val topology:Topology = builder.build()
    println(topology.describe())

    val application: KafkaStreams = new KafkaStreams(topology, prop)
    application.start()
  }
}
