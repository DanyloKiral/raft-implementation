package replication

import grpc.replication.LogEntry
import org.slf4j.Logger
import shared.{Configs, ServerState}
import swaydb._
import swaydb.serializers.Default._
import models.LogEntrySerializer

import scala.collection.mutable

class LogState (stateMachine: StateMachine, serverState: ServerState) (implicit logger: Logger) {
  implicit val serializer = LogEntrySerializer.serializer

  private val logList = persistent.Map[Long, LogEntry, Nothing, Glass](dir = Configs.LogStorageFolder)
  private var lastIndex = 0L
  if (logList.nonEmpty) {
    lastIndex = logList.keys.materialize.max
  }

  private var commitIndex = 0L
  private var lastApplied = 0L

  private val nextIndex = mutable.Map[String, Long]()
  private val matchIndex = mutable.Map[String, Long]()

  def getNextIndexForEntry() = lastIndex + 1

  def appendLog(log: LogEntry) = {
    logList.put(log.index, log)
    lastIndex = log.index
    logger.info(s"Appended log = $log")
  }

  def commit(index: Long): Unit = {
    if (index == 0 || index == commitIndex) {
      return
    }

    logger.info(s"Committing log with index $index")

    if (commitIndex >= index) {
      logger.warn(s"Committing index = $index, while last committed was $commitIndex")
    } else {
      commitIndex = index min lastIndex
    }

    if (commitIndex > lastApplied) {
      val indexesToApply = lastApplied to commitIndex
      indexesToApply
        .drop(1)
        .map(logList.get(_).get)
        .foreach(entry => {
          stateMachine.applyCommand(entry.command)
          lastApplied = entry.index
        })
    }
  }

  def commitAsLeader() = commit(lastIndex)

  def replicatedToFollower(followerId: String) = {
    matchIndex.put(followerId, lastIndex)
    nextIndex.put(followerId, lastIndex + 1)
  }

  def initLeaderState(): Unit = {
    nextIndex.clear()
    matchIndex.clear()

    nextIndex.addAll(Configs.ServersInfo
      .filter(_.id != serverState.ServerID)
      .map(s => (s.id, lastIndex + 1)).toIterable)

    matchIndex.addAll(Configs.ServersInfo
      .filter(_.id != serverState.ServerID)
      .map(s => (s.id, 0L)).toIterable)
  }

  def getCommitIndex() = commitIndex
  def getLastApplied() = lastApplied
  def getNextIndex() = nextIndex
  def getMatchIndex() = matchIndex
  def getNextIndexForFollower(followerId: String) = nextIndex(followerId)
  def decreaseNextIndexForFollower(followerId: String) = {
    val newNextIndex = nextIndex(followerId) - 1
    if (newNextIndex <= 0) {
      logger.error(s"Cannot decrease NextIndex (= ${nextIndex(followerId)})")
    }

    nextIndex.put(followerId, newNextIndex)
    newNextIndex
  }

  def getEntryFromIndex(startingIndex: Long) =
    (startingIndex to lastIndex)
      .map(logList.get(_))
      .filter(_.nonEmpty)
      .map(_.get)

  def getEntryByIndex(index: Long) =
    logList.get(index).get

  def hasEntryWithIndexAndTerm(index: Long, term: Long) =
    logList.get(index).exists(_.term == term)

  def getLastLogIndex() = lastIndex
  def getLastLog(): Option[LogEntry] =
    if (lastIndex > 0) logList.get(lastIndex) else None

  def getAllLogs() = logList.values.materialize.toArray


  def close() = logList.close
}
