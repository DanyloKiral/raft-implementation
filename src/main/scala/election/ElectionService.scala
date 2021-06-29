package election

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import akka.grpc.GrpcClientSettings
import grpc.election.{CandidateData, VoterClient}
import org.slf4j.Logger
import replication.{LogState, ReplicationSender}
import shared.{Configs, ServerState}

import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.concurrent.duration._
import grpc.replication.LogEntry

import scala.util.{Failure, Success}

class ElectionService (replicationSender: ReplicationSender,
                       serverState: ServerState,
                       logState: LogState)
                      (implicit system: ActorSystem,
                       executionContext: ExecutionContextExecutor,
                       logger: Logger) {
  private lazy val voterClients = Configs.ServersInfo
      .filter(_.id != serverState.ServerID)
      .map(i => GrpcClientSettings.connectToServiceAt(i.address, i.port).withTls(false))
      .map(VoterClient(_))

  private val electorActor = system.actorOf(Props[Elector](new Elector(this)))

  @volatile
  private var electionTimeoutScheduler: Option[Cancellable] = Option.empty


   def resetElectionTimeout() = {
    clearElectionTimeout

    logger.info("Started new election timeout")
    val ref = system.scheduler.scheduleOnce(Configs.getElectionTimeoutMs.milliseconds, electorActor, None)
    electionTimeoutScheduler = Option(ref)
  }

  // should be idempotent
  def stepDownIfNeeded() = {
    if (!serverState.isFollower) {
      logger.info("Step down")
      serverState.becomeFollower
    }

    replicationSender.cancelHeartbeats
    resetElectionTimeout
  }

  def close() = {
    logger.info("Closing ElectionService resources")
    voterClients.map(_.close)
      .map(Await.result(_, Duration.Inf))
  }


  private def convertToCandidate() = {
    serverState.becomeCandidate
    resetElectionTimeout
    serverState.voteFor(serverState.ServerID)
    requestVotes
  }

  private def requestVotes() = {
    val collectedVotes = new AtomicInteger(1)
    val wonElection = new AtomicBoolean(false)

    val candidateData = collectCandidateData()

    voterClients.map(c => c.requestVote(candidateData))
      .foreach(_.onComplete {
        case Success(value) => {
          if (value.term > serverState.getCurrentTerm) {
            serverState.increaseTerm(value.term)
            stepDownIfNeeded
            collectedVotes.set(0)
          } else if (value.voteGranted && !wonElection.get) {
            collectedVotes.incrementAndGet()

            if (collectedVotes.get >= Configs.ClusterQuorumNumber) {
              wonElection.set(true)
              winElection
            }
          }
        }
        case Failure(exception) => logger.error("Vote failure")
      })
  }

  private def winElection() = {
    serverState.becomeLeader
    logState.initLeaderState
    clearElectionTimeout
    replicationSender.resetHeartbeatInterval(true)
  }

  private def collectCandidateData(): CandidateData = {
    val lastLog = logState.getLastLog.getOrElse(LogEntry(0, 0))

    CandidateData(
      serverState.getCurrentTerm,
      serverState.ServerID,
      lastLog.index,
      lastLog.term)
  }

  private def clearElectionTimeout() = this.synchronized {
    if (electionTimeoutScheduler.nonEmpty) {
      logger.info("Cleared election timeout")
      electionTimeoutScheduler.get.cancel()
      electionTimeoutScheduler = Option.empty
    }
  }


  private class Elector (val electionService: ElectionService) extends Actor {
    override def receive: Receive = {
      case _ => electionService.convertToCandidate()
    }
  }
}
