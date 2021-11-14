package btc.wallet

import java.util.concurrent.TimeoutException

import scala.concurrent.Future

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.grpc.GrpcServiceException
import akka.util.Timeout
import io.grpc.Status
import org.slf4j.LoggerFactory

class BTCWalletServiceImpl(system: ActorSystem[_])
  extends proto.BTCWalletService {
  import system.executionContext

  private val logger = LoggerFactory.getLogger(getClass)

  implicit private val timeout: Timeout =
    Timeout.create(
      system.settings.config.getDuration("btc-wallet-service.ask-timeout"))

  private val sharding = ClusterSharding(system)

  override def addMoney(in: proto.AddMoneyRequest): Future[proto.Wallet] = {
    logger.info("addMoney {} to Wallet {}", in.transactionId, in.walletId)
    val entityRef = sharding.entityRefFor(BTCWallet.EntityKey, in.walletId)
    val reply: Future[BTCWallet.Summary] =
      entityRef.askWithStatus(BTCWallet.AddMoney(in.walletId, in.transactionId, in.transactionDatetime, in.transactionAmount, _))
    val response = reply.map(wallet => toProtoWallet(wallet))
    convertError(response)
  }

  private def toProtoWallet(wallet: BTCWallet.Summary): proto.Wallet = {
    proto.Wallet(wallet.transactions.iterator.map { case (transactionId, transactionAmount) =>
      proto.Transaction(transactionId)
    }.toSeq)
  }

  private def convertError[T](response: Future[T]): Future[T] = {
    response.recoverWith {
      case _: TimeoutException =>
        Future.failed(
          new GrpcServiceException(
            Status.UNAVAILABLE.withDescription("Operation timed out")))
      case exc =>
        Future.failed(
          new GrpcServiceException(
            Status.INVALID_ARGUMENT.withDescription(exc.getMessage)))
    }
  }

}