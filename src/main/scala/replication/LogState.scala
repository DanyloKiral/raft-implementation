package replication

import grpc.replication.LogEntry
import org.slf4j.Logger

import scala.collection.mutable

// todo: save to persistent storage
class LogState (stateMachine: StateMachine) (implicit logger: Logger) {
  // todo: consider changing the collection
  private val logList: mutable.ArrayBuffer[LogEntry] = mutable.ArrayBuffer()

  def appendLog(log: LogEntry): Int = {
    logger.info(s"Appended log = $log")
    // todo: appendLog
    1
  }

  def commit(index: Int) = {
    // todo: commit log
    logger.info(s"Committing log with index $index")
    // todo: apply commands to state machine
  }
}
