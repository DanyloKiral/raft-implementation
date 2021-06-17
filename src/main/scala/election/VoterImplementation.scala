package election

import grpc.election.{CandidateData, Vote, Voter}
import org.slf4j.Logger
import replication.LogState
import shared.ServerState

import scala.concurrent.Future

class VoterImplementation (electionService: ElectionService, serverState: ServerState, logState: LogState)
                          (implicit logger: Logger) extends Voter {
  logger.info("Starting Voter...")

  override def requestVote(in: CandidateData): Future[Vote] = in.term match {
        case term if term == serverState.getCurrentTerm => {
          logger.info(s"Received RequestVote from ${in.candidateId}")

          val currentVote = serverState.grantedVote
          if (currentVote.nonEmpty && currentVote.get != in.candidateId) {
            logger.info(s"Already voted for ${currentVote.get}")
            Future.successful(Vote(serverState.getCurrentTerm, false))
          } else {
            vote(in)
          }
        }
        case term if term > serverState.getCurrentTerm => {
          logger.info(s"Received RequestVote from ${in.candidateId}")
          serverState.increaseTerm(term)
          electionService.stepDownIfNeeded
          vote(in)
        }
        case term if term < serverState.getCurrentTerm => {
          logger.info(s"Received RequestVote from ${in.candidateId}")
          logger.info(s"Election term is lower than current. current = ${serverState.getCurrentTerm}; candidate term = $term")
          Future.successful(Vote(serverState.getCurrentTerm, false))
        }
      }

  private def vote(in: CandidateData): Future[Vote] = {
    val myLastLog = logState.getLastLog
    if (myLastLog.nonEmpty) {

      if (myLastLog.get.term > in.lastLogTerm) {
        logger.info(s"Rejecting candidate ${in.candidateId}; My last log term is higher (${myLastLog.get.term} > ${in.lastLogTerm})")
        return Future.successful(Vote(serverState.getCurrentTerm, false))

      } else if (myLastLog.get.term == in.lastLogTerm && myLastLog.get.index > in.lastLogIndex) {
        logger.info(s"Rejecting candidate ${in.candidateId}; My last log is longer (${myLastLog.get.index} > ${in.lastLogIndex})")
        return Future.successful(Vote(serverState.getCurrentTerm, false))
      }
    }

    logger.info(s"Voting for ${in.candidateId}")

    serverState.voteFor(in.candidateId)
    Future.successful(Vote(serverState.getCurrentTerm, true))
  }
}
