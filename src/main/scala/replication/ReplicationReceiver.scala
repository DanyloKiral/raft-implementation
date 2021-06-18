package replication

import election.ElectionService
import grpc.replication.{EntryData, Replication, ReplicationResult}
import org.slf4j.Logger
import shared.ServerState

import scala.concurrent.Future

class ReplicationReceiver (electionService: ElectionService, serverState: ServerState, logState: LogState)
                          (implicit logger: Logger) extends Replication {

  logger.info("Starting Replication receiver...")

  override def appendEntries(in: EntryData): Future[ReplicationResult] = {
    logger.info(s"Received AppendEntries from ${in.leaderId}")

    if (in.term < serverState.getCurrentTerm) {
      logger.info("I have higher term")
      return Future.successful(ReplicationResult(serverState.getCurrentTerm, false))
    } else if (in.term > serverState.getCurrentTerm) {
      serverState.increaseTerm(in.term)
    }

    electionService.stepDownIfNeeded

    if (in.entries.nonEmpty) {
      if (in.prevLogIndex != 0 && in.prevLogTerm != 0 &&
        !logState.hasEntryWithIndexAndTerm(in.prevLogIndex, in.prevLogTerm)) {

        logger.info(s"I have missed entries! my lastlog index = ${logState.getLastLogIndex} in = $in")
        return Future.successful(ReplicationResult(serverState.getCurrentTerm, false))
      }

      in.entries.foreach(e => logState.appendLog(e))
    }

    logState.commit(in.leaderCommit)

    serverState.setLeaderId(in.leaderId)
    Future.successful(ReplicationResult(serverState.getCurrentTerm, true))
  }
}
