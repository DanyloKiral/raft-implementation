package replication

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import akka.grpc.GrpcClientSettings
import grpc.replication.{EntryData, LogEntry, ReplicationClient, ReplicationResult}
import models.{Log, ReplicateLogFuncData, ReplicationResponse}
import org.slf4j.Logger
import shared.{Configs, ServerState}
import spray.json.RootJsonFormat

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

import scala.util.{Either, Failure, Success, Try}

class ReplicationSender (serverState: ServerState, logState: LogState)
                        (implicit system: ActorSystem, executionContext: ExecutionContextExecutor,
                         logger: Logger, clientJsonFormat: RootJsonFormat[Log]) {
  private val receiverClients = Configs.ServersInfo
      .filter(_.id != serverState.ServerID)
      .map(i => i.id -> ReplicationClient(GrpcClientSettings.connectToServiceAt(i.address, i.port)
                          .withTls(false)))
      .toMap

  private val electorActor = system.actorOf(Props[HeartbeatSender](new HeartbeatSender(this)))
  private var heartbeatIntervalScheduler: Option[Cancellable] = Option.empty

  def getSendFunctions(entry: LogEntry): List[ReplicateLogFuncData] =
    receiverClients.toList
      .map((_, logToEntryData(Seq(entry))))
      .map(data => ReplicateLogFuncData(data._2, (entry: EntryData) =>
        sendAppendEntryToClient(data._1._2, entry).transform { value =>
          Try(ReplicationResponse(data._1._1, data._2, value.get))
        }))

  def logToEntryData(logs: Seq[LogEntry], prevEntryIndex: Option[Long] = None, prevEntryTerm: Option[Long] = None) = {
    // todo: check if default values are correct
    val prevEntry = logState.getLastEntry.getOrElse(LogEntry(0, 0))

    EntryData(
      serverState.getCurrentTerm,
      serverState.ServerID,
      prevEntryIndex.getOrElse(prevEntry.index),
      prevEntryTerm.getOrElse(prevEntry.term),
      logs,
      logState.getCommitIndex)
  }

  def sendHeartbeats() = {
    val data = EntryData(serverState.getCurrentTerm, serverState.ServerID, 0, 0, Seq(), 0)
    receiverClients.map(c => sendAppendEntryToClient(c._2, data))
  }

  def replicateLogEntriesTo(entries: Seq[LogEntry], followerId: String) = {
    // todo: consider resetting heartbeat interval for separate node
    val data = EntryData(serverState.getCurrentTerm, serverState.ServerID, 0, 0, entries, 0)
    receiverClients(followerId).appendEntries(data)
  }

  def resetHeartbeatInterval(newLeader: Boolean = false) = {
    if (heartbeatIntervalScheduler.nonEmpty) {
      logger.info("Reset heartbeat interval")
      heartbeatIntervalScheduler.get.cancel()
      heartbeatIntervalScheduler = Option.empty
    } else {
      logger.info("Started heartbeat interval")
    }

    val initialTimeout = if (newLeader) Duration.Zero else Configs.getHeartbeatIntervalMs.milliseconds

    val ref = system.scheduler.scheduleAtFixedRate(
      initialTimeout, Configs.getHeartbeatIntervalMs.milliseconds, electorActor, None)
    heartbeatIntervalScheduler = Option(ref)
  }

  private def sendAppendEntryToClient(replicationClient: ReplicationClient, entryData: EntryData): Future[Option[ReplicationResult]] = {
    replicationClient.appendEntries(entryData).transform{
      case Success(result) => Try(Some(result))
      case Failure(exception) => {
        // todo: handle exceptions by type
        logger.error("Error sending AppendEntry to followers", exception)
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
