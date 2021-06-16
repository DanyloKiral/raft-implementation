package replication

import election.ElectionService
import grpc.replication.{EntryData, Replication, ReplicationResult}
import org.slf4j.Logger
import shared.ServerState

import scala.concurrent.Future

class ReplicationReceiver (electionService: ElectionService, logState: LogState) (implicit logger: Logger) extends Replication {
  logger.info("Starting Replication receiver...")

  override def appendEntries(in: EntryData): Future[ReplicationResult] = {
    logger.info(s"Received AppendEntries from ${in.leaderId}")

    if (in.term < ServerState.getCurrentTerm) {
      logger.info("I have higher term")
      return Future.successful(ReplicationResult(ServerState.getCurrentTerm, false))
    }

    if (in.term > ServerState.getCurrentTerm) {
      ServerState.increaseTerm(in.term)
    }
    electionService.stepDownIfNeeded

    // todo: what if default values (its a first entry)?
    if (in.prevLogIndex != 0 && in.prevLogTerm != 0 &&
      logState.hasEntryWithIndexAndTerm(in.prevLogIndex, in.prevLogTerm)) {

      logger.info("I have missed entries!")
      return Future.successful(ReplicationResult(ServerState.getCurrentTerm, false))
    }

    if (in.entries.nonEmpty) {
      // todo: handle adding new entries
    }

    Future.successful(ReplicationResult(ServerState.getCurrentTerm, true))
  }
}
