package btc.wallet

import akka.actor.typed.ActorRef
import akka.pattern.StatusReply
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.ReplyEffect

import scala.concurrent.duration._
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.RetentionCriteria


object BTCWallet {

  /**
   * This interface defines all the commands (messages) that the BTCWallet actor supports.
   */
  sealed trait Command extends CborSerializable

  /**
   * A command to process transaction in wallet.
   *
   * It replies with `StatusReply[Summary]`, which is sent back to the caller when
   * all the events emitted by this command are successfully persisted.
   */
  final case class AddMoney(
                             walletId: String,
                             transactionId: String,
                             transactionDatetime: String,
                             transactionAmount: Double,
                             replyTo: ActorRef[StatusReply[Summary]])
    extends Command

  /**
   * Summary of the btc wallet state, used in reply messages.
   */
  final case class Summary(transactions: Map[String, Double]) extends CborSerializable

  /**
   * This interface defines all the events that the ShoppingCart supports.
   */
  sealed trait Event extends CborSerializable {
    def walletId: String
  }

  final case class TransactionProcessed(walletId: String, transactionId: String, transactionDatetime: String, transactionAmount: Double)
    extends Event

  final case class State(transactions: Map[String, Double]) extends CborSerializable {

    def hasTransaction(transactionId: String): Boolean =
      transactions.contains(transactionId)

    def isEmpty: Boolean =
      transactions.isEmpty

    def updateTransaction(transactionId: String, transactionAmount: Double): State = {
      transactionAmount match {
        case 0 => copy(transactions = transactions - transactionId)
        case _ => copy(transactions = transactions + (transactionId -> transactionAmount))
      }
    }
  }
  object State {
    val empty = State(transactions = Map.empty)
  }

  private def handleCommand(
                             walletId: String,
                             state: State,
                             command: Command): ReplyEffect[Event, State] = {
    command match {
      case AddMoney(walletId, transactionId, transactionDatetime, transactionAmount, replyTo) =>
        if (state.hasTransaction(transactionId))
          Effect.reply(replyTo)(
            StatusReply.Error(
              s"Item '$transactionId' was already processed"))
        else if (transactionAmount <= 0)
          Effect.reply(replyTo)(
            StatusReply.Error("Amount must be greater than zero"))
        else
          Effect
            .persist(TransactionProcessed(walletId, transactionId, transactionDatetime, transactionAmount))
            .thenReply(replyTo) { updatedWallet =>
              StatusReply.Success(Summary(updatedWallet.transactions))
            }
    }
  }

  private def handleEvent(state: State, event: Event) = {
    event match {
      case TransactionProcessed(_, transactionId, transactionDatetime, transactionAmount) =>
        state.updateTransaction(transactionId, transactionAmount)
    }
  }

  val EntityKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("BTCWallet")

  def init(system: ActorSystem[_]): Unit = {
    ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
      BTCWallet(entityContext.entityId)
    })
  }

  def apply(walletId: String): Behavior[Command] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State](
        persistenceId = PersistenceId(EntityKey.name, walletId),
        emptyState = State.empty,
        commandHandler =
          (state, command) => handleCommand(walletId, state, command),
        eventHandler = (state, event) => handleEvent(state, event))
      .withRetention(RetentionCriteria
        .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(
        SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1)
      )
  }

  val tags = Vector.tabulate(5)(i => s"wallets-$i")
}