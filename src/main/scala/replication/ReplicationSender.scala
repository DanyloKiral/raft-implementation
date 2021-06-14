package replication

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import akka.grpc.GrpcClientSettings
import grpc.replication.{EntryData, LogEntry, ReplicationClient, ReplicationResult}
import models.Log
import org.slf4j.Logger
import shared.{Configs, ServerState}
import spray.json.RootJsonFormat

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import models.Types.ReplicateLogFuncData

class ReplicationSender (implicit system: ActorSystem, executionContext: ExecutionContextExecutor, logger: Logger, clientJsonFormat: RootJsonFormat[Log]) {
  private val receiverClients = Configs.ServersInfo
      .filter(_.id != ServerState.ServerID)
      .map(i => i.id -> ReplicationClient(GrpcClientSettings.connectToServiceAt(i.address, i.port)
                          .withTls(false)
                          .withConnectionAttempts(-1)))
      .toMap

  private val electorActor = system.actorOf(Props[HeartbeatSender](new HeartbeatSender(this)))
  private var heartbeatIntervalScheduler: Option[Cancellable] = Option.empty

  def getSendFunctions(log: Log): List[ReplicateLogFuncData] =
    receiverClients.toList
      .map((_, logToEntryData(log)))
      .map(data => (data._2, (entry: EntryData) => {
        Future.successful(data._1._1).zip(data._1._2.appendEntries(entry))
      }))

  def logToEntryData(log: Log) =
    EntryData(ServerState.getCurrentTerm, ServerState.ServerID, 0, 0, Seq(LogEntry(ServerState.getCurrentTerm, 1, log.command)), 0)

  def sendHeartbeats() = {
    val data = EntryData(ServerState.getCurrentTerm, ServerState.ServerID, 0, 0, Seq(), 0)
    receiverClients.map(c => c._2.appendEntries(data).zip(Future.successful(c._1))).toArray
  }

  def replicateLogEntriesTo(entries: Seq[LogEntry], followerId: String) = {
    // todo: consider resetting heartbeat interval for separate node
    val data = EntryData(ServerState.getCurrentTerm, ServerState.ServerID, 0, 0, entries, 0)
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

  private class HeartbeatSender (val replicationSender: ReplicationSender) extends Actor {
    override def receive: Receive = {
      case _ => replicationSender.sendHeartbeats
    }
  }
}
