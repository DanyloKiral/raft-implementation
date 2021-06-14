package replication

import election.ElectionService
import grpc.replication.{EntryData, Replication, ReplicationResult}
import org.slf4j.Logger
import shared.ServerState

import scala.concurrent.Future

class ReplicationReceiver (electionService: ElectionService) (implicit logger: Logger) extends Replication {
  logger.info("Starting Replication receiver...")

  override def appendEntries(in: EntryData): Future[ReplicationResult] = {
    logger.info(s"Received AppendEntries from ${in.leaderId}")

    if (in.term < ServerState.getCurrentTerm) {
      logger.info(s"Rejecting because I am the leader")
      return Future.successful(ReplicationResult(ServerState.getCurrentTerm, false))
    }

    if (in.term > ServerState.getCurrentTerm) {
      ServerState.increaseTerm(in.term)

      // todo: handle other cases
    }
    electionService.stepDownIfNeeded

    if (in.entries.nonEmpty) {
      // todo: handle adding new entries
    }

    Future.successful(ReplicationResult(ServerState.getCurrentTerm, true))
  }
}
