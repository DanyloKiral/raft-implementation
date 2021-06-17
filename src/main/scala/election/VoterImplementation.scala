package election

import grpc.election.{CandidateData, Vote, Voter}
import org.slf4j.Logger
import shared.ServerState

import scala.concurrent.Future

class VoterImplementation (electionService: ElectionService, serverState: ServerState) (implicit logger: Logger) extends Voter {
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

  private def vote(in: CandidateData) = {
    // todo: apply log check for election
    logger.info(s"Voting for ${in.candidateId}")

    serverState.voteFor(in.candidateId)
    Future.successful(Vote(serverState.getCurrentTerm, true))
  }
}
