package btc.wallet

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.SendProducer
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.Handler
import com.google.protobuf.any.{ Any => ScalaPBAny }
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory

class PublishEventsProjectionHandler(
                                      system: ActorSystem[_],
                                      topic: String,
                                      sendProducer: SendProducer[String, Array[Byte]])
  extends Handler[EventEnvelope[BTCWallet.Event]] {
  private val log = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext =
    system.executionContext

  override def process(
                        envelope: EventEnvelope[BTCWallet.Event]): Future[Done] = {
    val event = envelope.event

    // using the walletId as the key and `DefaultPartitioner` will select partition based on the key
    // so that events for same wallet always ends up in same partition
    val key = event.walletId
    val producerRecord = new ProducerRecord(topic, key, serialize(event))
    val result = sendProducer.send(producerRecord).map { recordMetadata =>
      log.info(
        "Published event [{}] to topic/partition {}/{}",
        event,
        topic,
        recordMetadata.partition)
      Done
    }
    result
  }

  private def serialize(event: BTCWallet.Event): Array[Byte] = {
    val protoMessage = event match {
      case BTCWallet.TransactionProcessed(walletId, transactionId, transactionDatetime, transactionAmount) =>
        proto.TransactionProcessed(walletId, transactionId, transactionDatetime, transactionAmount)
    }
    // pack in Any so that type information is included for deserialization
    ScalaPBAny.pack(protoMessage, "btc-wallet-service").toByteArray
  }
}