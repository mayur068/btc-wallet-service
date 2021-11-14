package btc.wallet

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.SendProducer
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ AtLeastOnceProjection, SourceProvider }
import akka.projection.{ ProjectionBehavior, ProjectionId }
import org.apache.kafka.common.serialization.{
  ByteArraySerializer,
  StringSerializer
}
import btc.wallet.repository.ScalikeJdbcSession

object PublishEventsProjection {

  def init(system: ActorSystem[_]): Unit = {
    val sendProducer = createProducer(system)
    val topic =
      system.settings.config.getString("btc-wallet-service.kafka.topic")

    ShardedDaemonProcess(system).init(
      name = "PublishEventsProjection",
      BTCWallet.tags.size,
      index =>
        ProjectionBehavior(
          createProjectionFor(system, topic, sendProducer, index)),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop))
  }

  private def createProducer(
                              system: ActorSystem[_]): SendProducer[String, Array[Byte]] = {
    val producerSettings =
      ProducerSettings(system, new StringSerializer, new ByteArraySerializer)
    val sendProducer =
      SendProducer(producerSettings)(system)
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
      "close-sendProducer") { () =>
      sendProducer.close()
    }
    sendProducer
  }

  private def createProjectionFor(
                                   system: ActorSystem[_],
                                   topic: String,
                                   sendProducer: SendProducer[String, Array[Byte]],
                                   index: Int)
  : AtLeastOnceProjection[Offset, EventEnvelope[BTCWallet.Event]] = {
    val tag = BTCWallet.tags(index)
    val sourceProvider
    : SourceProvider[Offset, EventEnvelope[BTCWallet.Event]] =
      EventSourcedProvider.eventsByTag[BTCWallet.Event](
        system = system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = tag)

    JdbcProjection.atLeastOnceAsync(
      projectionId = ProjectionId("PublishEventsProjection", tag),
      sourceProvider,
      handler =
        () => new PublishEventsProjectionHandler(system, topic, sendProducer),
      sessionFactory = () => new ScalikeJdbcSession())(system)
  }

}