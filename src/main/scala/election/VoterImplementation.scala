package election

import grpc.election.{CandidateData, Vote, Voter}
import org.slf4j.Logger
import shared.ServerStateService

import scala.concurrent.Future

class VoterImplementation (electionService: ElectionService) (implicit logger: Logger) extends Voter {
  logger.info("Starting Voter...")

  override def requestVote(in: CandidateData): Future[Vote] = in.term match {
        case term if term == ServerStateService.getCurrentTerm => {
          logger.info(s"Received RequestVote from ${in.candidateId}")

          val currentVote = ServerStateService.grantedVote
          if (currentVote.nonEmpty && currentVote.get != in.candidateId) {
            logger.info(s"Already voted for ${currentVote.get}")
            Future.successful(Vote(ServerStateService.getCurrentTerm, false))
          } else {
            vote(in)
          }
        }
        case term if term > ServerStateService.getCurrentTerm => {
          logger.info(s"Received RequestVote from ${in.candidateId}")
          ServerStateService.increaseTerm(term)
          electionService.stepDownIfNeeded
          vote(in)
        }
        case term if term < ServerStateService.getCurrentTerm => {
          logger.info(s"Received RequestVote from ${in.candidateId}")
          logger.info(s"Election term is lower than current. current = ${ServerStateService.getCurrentTerm}; candidate term = $term")
          Future.successful(Vote(ServerStateService.getCurrentTerm, false))
        }
      }

  private def vote(in: CandidateData) = {
    // todo: apply log check for election
    logger.info(s"Voting for ${in.candidateId}")

    ServerStateService.voteFor(in.candidateId)
    Future.successful(Vote(ServerStateService.getCurrentTerm, true))
  }
}
