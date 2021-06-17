package replication

import grpc.replication.LogEntry
import org.slf4j.Logger
import shared.{Configs, ServerState}
import swaydb._
import swaydb.serializers.Default._
import models.LogEntrySerializer

import scala.collection.mutable

class LogState (stateMachine: StateMachine) (implicit logger: Logger) {
  implicit val serializer = LogEntrySerializer.serializer

  private val logList = persistent.Map[Long, LogEntry, Nothing, Glass](dir = Configs.LogStorageFolder)
  private var lastIndex = 0L

  // todo
  private val commitIndex = 0L
  private val lastApplied = 0L

  // todo
  private val nextIndex = mutable.Map[String, Long]()
  private val matchIndex = mutable.Map[String, Long]()

  def appendLog(log: LogEntry) = {
    logList.put(log.index, log)
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

  def updateMatchIndexForFollower(followerId: String, index: Long) = {
  // todo
  }

  def initLeaderState(): Unit = {
    nextIndex.clear()
    matchIndex.clear()

    nextIndex.addAll(Configs.ServersInfo.map(s => (s.id, lastIndex + 1)).toIterable)
    matchIndex.addAll(Configs.ServersInfo.map(s => (s.id, 0L)).toIterable)
  }

  def getCommitIndex() = commitIndex

  def getEntryFromIndex(startingIndex: Long) =
    (startingIndex to lastIndex)
      .map(logList.get(_))
      .filter(_.nonEmpty)
      .map(_.get)

  def hasEntryWithIndexAndTerm(index: Long, term: Long) =
    logList.get(index).exists(_.term == term)

  def getLastEntry(): Option[LogEntry] =
    if (lastIndex > 0)
      logList.get(lastIndex)
    else
      None
}
