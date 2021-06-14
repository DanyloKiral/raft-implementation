package replication

import election.ElectionService
import grpc.replication.{EntryData, Replication, ReplicationResult}
import org.slf4j.Logger
import shared.ServerStateService

import scala.concurrent.Future

class ReplicationReceiver (electionService: ElectionService) (implicit logger: Logger) extends Replication {
  logger.info("Starting Replication receiver...")

  override def appendEntries(in: EntryData): Future[ReplicationResult] = {
    logger.info(s"Received AppendEntries from ${in.leaderId}")

    if (ServerStateService.ServerID == "raft-server2") {
      logger.info(s"Replication failing")
      return Future.successful(ReplicationResult(ServerStateService.getCurrentTerm, false))
    }
    logger.info(s"Replication successful")
    return Future.successful(ReplicationResult(ServerStateService.getCurrentTerm, true))

    if (in.term < ServerStateService.getCurrentTerm) {
      logger.info(s"Rejecting because I am the leader")
      return Future.successful(ReplicationResult(ServerStateService.getCurrentTerm, false))
    }

    if (in.term > ServerStateService.getCurrentTerm) {
      ServerStateService.increaseTerm(in.term)

      // todo: handle other cases
    }
    electionService.stepDownIfNeeded

    if (in.entries.nonEmpty) {
      // todo: handle adding new entries
    }

    Future.successful(ReplicationResult(ServerStateService.getCurrentTerm, true))
  }
}
