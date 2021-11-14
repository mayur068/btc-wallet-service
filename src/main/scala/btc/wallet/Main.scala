package btc.wallet

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal

object Main {

  val logger = LoggerFactory.getLogger("btc.wallet.Main")

  def main(args: Array[String]): Unit = {
    val system = ActorSystem[Nothing](Behaviors.empty, "BTCWalletService")
    try {
      init(system)
    } catch {
      case NonFatal(e) =>
        logger.error("Terminating due to initialization failure.", e)
        system.terminate()
    }
  }

  def init(system: ActorSystem[_]): Unit = {
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()
    BTCWallet.init(system)
    PublishEventsProjection.init(system)

    val grpcInterface =
      system.settings.config.getString("btc-wallet-service.grpc.interface")
    val grpcPort =
      system.settings.config.getInt("btc-wallet-service.grpc.port")
    val grpcService = new BTCWalletServiceImpl(system)
    BTCWalletServer.start(
      grpcInterface,
      grpcPort,
      system,
      grpcService
    )

  }

}
