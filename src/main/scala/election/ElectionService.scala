package election

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import akka.grpc.GrpcClientSettings
import grpc.election.{CandidateData, Vote, Voter, VoterClient}
import org.slf4j.Logger
import replication.ReplicationSender
import shared.{Configs, ServerState}

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import com.softwaremill.macwire.akkasupport._
import scala.util.{Failure, Success}

class ElectionService (replicationSender: ReplicationSender)
                      (implicit system: ActorSystem,
                       executionContext: ExecutionContextExecutor,
                       logger: Logger) {
  private lazy val voterClients = Configs.ServersInfo
      .filter(_.id != ServerState.ServerID)
      .map(i => GrpcClientSettings.connectToServiceAt(i.address, i.port).withTls(false))
      .map(VoterClient(_))

  private val electorActor = system.actorOf(Props[Elector](new Elector(this)))
  private var electionTimeoutScheduler: Option[Cancellable] = Option.empty
  private var electionFutures: Option[Array[Future[Vote]]] = Option.empty


  def resetElectionTimeout() = {
    clearElectionTimeout

    logger.info("Started new election timeout")
    val ref = system.scheduler.scheduleOnce(Configs.getElectionTimeoutMs.milliseconds, electorActor, None)
    electionTimeoutScheduler = Option(ref)
  }

  // should be idempotent
  def stepDownIfNeeded() = {
    if (!ServerState.isFollower) {
      logger.info("Step down")
      ServerState.becomeFollower
    }

    resetElectionTimeout
    // todo: cancel election
  // todo: implement
  }

  def close() = {
    logger.info("Closing ElectionService resources")
    voterClients.map(_.close)
      .map(Await.result(_, Duration.Inf))
  }


  private def convertToCandidate() = {
    ServerState.becomeCandidate
    resetElectionTimeout
    ServerState.voteFor(ServerState.ServerID)
    requestVotes
  }

  private def requestVotes() = {
    var collectedVotes = 1
    var wonElection = false

    val candidateData = collectCandidateData()
    val futures = voterClients.map(c => c.requestVote(candidateData))
    electionFutures = Option(futures)

    futures.foreach(_.onComplete {
      case Success(value) => {
        if (value.term > ServerState.getCurrentTerm) {
          ServerState.increaseTerm(value.term)
          stepDownIfNeeded
          collectedVotes = 0
        } else if (value.voteGranted && !wonElection) {
          collectedVotes += 1

          if (collectedVotes >= Configs.ClusterQuorumNumber) {
            wonElection = true
            winElection
          }
        }

        // todo: handle other cases
      }
      case Failure(exception) => logger.error("Unexpected Vote failure", exception)
    })
  }

  private def winElection() = {
    // todo: cancel election futures

    ServerState.becomeLeader
    clearElectionTimeout
    replicationSender.resetHeartbeatInterval(true)
  }

  private def collectCandidateData(): CandidateData = {
    // todo: Add log data
    CandidateData(ServerState.getCurrentTerm, ServerState.ServerID, 1, 1)
  }

  private def clearElectionTimeout() = {
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
