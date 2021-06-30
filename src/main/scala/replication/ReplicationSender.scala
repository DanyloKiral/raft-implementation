package replication

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import akka.grpc.GrpcClientSettings
import grpc.replication.{EntryData, LogEntry, ReplicationClient, ReplicationResult}
import models.Types.ReplicationFunc
import models.{Log, ReplicationResponse}
import org.slf4j.Logger
import shared.{Configs, ServerState}
import spray.json.RootJsonFormat

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

class ReplicationSender (serverState: ServerState, logState: LogState)
                        (implicit system: ActorSystem, executionContext: ExecutionContextExecutor,
                         logger: Logger, clientJsonFormat: RootJsonFormat[Log]) {
  private val receiverClients = Configs.ServersInfo
      .filter(_.id != serverState.ServerID)
      .map(i => i.id -> ReplicationClient(GrpcClientSettings.connectToServiceAt(i.address, i.port)
                          .withTls(false)))
      .toMap

  private val electorActor = system.actorOf(Props[HeartbeatSender](new HeartbeatSender(this)))

  @volatile
  private var heartbeatIntervalScheduler: Option[Cancellable] = Option.empty

  def getSendFunctions(term: Long): List[(Long, ReplicationFunc)] =
    receiverClients.toList
      .map(followerData => (term, () =>
        sendAppendEntryToClient(followerData._2, getEntryData(followerData._1, term), followerData._1)
          .transform { value => Try(ReplicationResponse(followerData._1, value.get)) }
      ))

  def getEntryData(followerId: String, term: Long) = {
    val followerNextIndex = logState.getNextIndexForFollower(followerId)

    logger.info(s"Sending log to follower $followerId from index = $followerNextIndex")

    val prevEntry = (if (followerNextIndex > 1)
      logState.getEntryByIndex(followerNextIndex - 1) else
      None).getOrElse(LogEntry(0, 0))

    EntryData(
      term,
      serverState.ServerID,
      prevEntry.index,
      prevEntry.term,
      logState.getEntryFromIndex(followerNextIndex),
      logState.getCommitIndex)
  }

  def sendHeartbeats() =
    receiverClients.map(c => {
      val entryData = getEntryData(c._1, serverState.getCurrentTerm)
      val entryIndexes = entryData.entries.map(_.index)
      val maxEntryIndex = if (entryIndexes.nonEmpty) Some(entryIndexes.max) else None

      sendAppendEntryToClient(c._2, entryData, c._1).onComplete {
        case Success(value)
          if maxEntryIndex.nonEmpty && value.nonEmpty && value.get.success && serverState.isLeader && value.get.term == serverState.getCurrentTerm => {
          // increase known replicated log index for follower
          logState.replicatedToFollower(c._1, maxEntryIndex.get)
          
          // commit if replicated enough
          if (logState.getCommitIndex < maxEntryIndex.get &&
              logState.logReplicatedToCount(maxEntryIndex.get) > (Configs.ClusterQuorumNumber - 1)) {
            logState.commit(maxEntryIndex.get)
          }
        }
        case Success(value) if value.nonEmpty && !value.get.success && value.get.term == serverState.getCurrentTerm =>
          logState.decreaseNextIndexForFollower(c._1)
        case _ => {}
      }
    })

  def cancelHeartbeats() = {
    if (heartbeatIntervalScheduler.nonEmpty) {
      logger.info("Cancel heartbeat interval")
      heartbeatIntervalScheduler.get.cancel()
      heartbeatIntervalScheduler = Option.empty
    }
  }

  def resetHeartbeatInterval(newLeader: Boolean = false) = {
    cancelHeartbeats

    logger.info("Starting heartbeat interval")

    val initialTimeout = if (newLeader) Duration.Zero else Configs.getHeartbeatIntervalMs.milliseconds

    val ref = system.scheduler.scheduleAtFixedRate(
      initialTimeout, Configs.getHeartbeatIntervalMs.milliseconds, electorActor, None)
    heartbeatIntervalScheduler = Option(ref)
  }

  private def sendAppendEntryToClient(replicationClient: ReplicationClient, entryData: EntryData, followerId: String): Future[Option[ReplicationResult]] = {
    if (!serverState.isLeader) {
      return Future.successful(None)
    }

    replicationClient.appendEntries(entryData).transform{
      case Success(result) => Try(Some(result))
      case Failure(exception) => {
        logger.error(s"Failed to send AppendEntry to $followerId. Exception = $exception")
        Try(None)
      }
    }
  }

  private class HeartbeatSender (val replicationSender: ReplicationSender) extends Actor {
    override def receive: Receive = {
      case _ => replicationSender.sendHeartbeats
    }
  }
}
