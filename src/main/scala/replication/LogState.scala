package replication

import grpc.replication.LogEntry
import org.slf4j.Logger

import scala.collection.mutable

// todo: save to persistent storage
class LogState (stateMachine: StateMachine) (implicit logger: Logger) {
  // todo: consider changing the collection
  private val logList = mutable.Map[Long, LogEntry]()
  private var lastIndex = 0L


  def appendLog(log: LogEntry) = {
    logList.addOne((log.index, log))
    lastIndex = log.index
    logger.info(s"Appended log = $log")
    // todo: appendLog
    log.index
  }

  def commit(index: Long) = {
    // todo: commit log
    logger.info(s"Committing log with index $index")
    // todo: apply commands to state machine
    // todo: increase commit index
  }

  def getEntryFromIndex(startingIndex: Long) =
    (startingIndex to lastIndex)
      .map(logList(_))

  def hasEntryWithIndexAndTerm(index: Long, term: Long) =
    logList.get(index).exists(_.term == term)

  //def getPrevEntry()

  def getLastEntry(): Option[LogEntry] =
    if (lastIndex > 0)
      Some(logList(lastIndex))
    else
      None
}
