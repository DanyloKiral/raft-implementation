package replication

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import akka.grpc.GrpcClientSettings
import election.ElectionService
import grpc.election.VoterClient
import grpc.replication.{EntryData, LogEntry, ReplicationClient}
import org.slf4j.Logger
import shared.{Configs, ServerStateService}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor

class ReplicationSender (implicit system: ActorSystem, executionContext: ExecutionContextExecutor, logger: Logger) {
  private val receiverClients = Configs.ServersInfo
      .filter(_.id != ServerStateService.ServerID)
      .map(i => GrpcClientSettings.connectToServiceAt(i.address, i.port).withTls(false))
      .map(ReplicationClient(_))

  private val electorActor = system.actorOf(Props[HeartbeatSender](new HeartbeatSender(this)))
  private var heartbeatIntervalScheduler: Option[Cancellable] = Option.empty

  def sendLogEntries(entries: Seq[LogEntry]) = {
    if (entries.nonEmpty) {
      // when log sent
      resetHeartbeatInterval()
    }

    // todo: fill log fields
    val data = EntryData(ServerStateService.getCurrentTerm, ServerStateService.ServerID, 0, 0, entries, 0)
    receiverClients.map(_.appendEntries(data))
    // todo: handle responses from followers to commit?
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
      case _ => replicationSender.sendLogEntries(Seq())
    }
  }
}
