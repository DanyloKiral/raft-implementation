package replication

import java.util.concurrent.atomic.AtomicLong

import grpc.replication.LogEntry
import org.slf4j.Logger
import shared.{Configs, ServerState}
import swaydb._
import swaydb.serializers.Default._
import models.LogEntrySerializer
import scala.collection.concurrent.TrieMap

class LogState (stateMachine: StateMachine, serverState: ServerState) (implicit logger: Logger) {
  implicit val serializer = LogEntrySerializer.serializer

  private val logList = persistent.Map[Long, LogEntry, Nothing, Glass](dir = Configs.LogStorageFolder)
  private val lastIndex = new AtomicLong(0L)
  if (logList.nonEmpty) {
    lastIndex.set(logList.keys.materialize.max)
  }

  private val commitIndex = new AtomicLong(0L)
  private val lastApplied = new AtomicLong(0L)

  private val nextIndex = TrieMap[String, Long]()
  private val matchIndex = TrieMap[String, Long]()

  def getNextIndexForEntry() = lastIndex.incrementAndGet

  def appendLog(log: LogEntry): Unit = {
    val existingEntry = getEntryByIndex(log.index)
    if (existingEntry.nonEmpty && existingEntry.get.term == log.term) {
      logger.info(s"Ignoring entry with index = ${log.index} and term = ${log.term} - already exists")
      return
    }

    if (lastIndex.get > log.index) {
      logger.info(s"Clearing logs, inconsistent with current leader; from index ${log.index} to $lastIndex")
      (log.index to lastIndex.get)
        .foreach(logList.remove(_))
    }

    logList.put(log.index, log)
    lastIndex.set(log.index)
    logger.info(s"Appended log = $log")
  }

  def commit(index: Long): Unit = {
    if (index == 0 || index == commitIndex.get) {
      return
    }

    logger.info(s"Committing log with index $index")

    if (commitIndex.get > index) {
      logger.warn(s"Committing index = $index, while last committed was ${commitIndex.get}")
    } else {
      commitIndex.set(index min lastIndex.get)
    }

    if (commitIndex.get > lastApplied.get) {
      val indexesToApply = lastApplied.get to commitIndex.get
      indexesToApply
        .drop(1)
        .map(logList.get(_))
        .filter(_.nonEmpty)
        .map(_.get)
        .foreach(entry => {
          stateMachine.applyCommand(entry.command)
          lastApplied.set(entry.index)
        })
    }
  }

  def commitAsLeader() = commit(lastIndex.get)

  def replicatedToFollower(followerId: String) = {
    matchIndex.put(followerId, lastIndex.get)
    nextIndex.put(followerId, lastIndex.get + 1)
  }

  def initLeaderState(): Unit = {
    nextIndex.clear()
    matchIndex.clear()

    Configs.ServersInfo
      .filter(_.id != serverState.ServerID)
      .map(s => (s.id, lastIndex.get + 1))
      .foreach(d => nextIndex.put(d._1, d._2))

    Configs.ServersInfo
      .filter(_.id != serverState.ServerID)
      .map(s => (s.id, 0L))
      .foreach(d => matchIndex.put(d._1, d._2))
  }

  def getCommitIndex() = commitIndex.get
  def getLastApplied() = lastApplied.get
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
    (startingIndex to lastIndex.get)
      .map(logList.get(_))
      .filter(_.nonEmpty)
      .map(_.get)

  def getEntryByIndex(index: Long) =
    logList.get(index)

  def hasEntryWithIndexAndTerm(index: Long, term: Long) = {
    logList.get(index).exists(_.term == term)
  }

  def getLastLogIndex() = lastIndex.get
  def getLastLog(): Option[LogEntry] =
    if (lastIndex.get > 0) logList.get(lastIndex.get) else None

  def getAllLogs() = logList.values.materialize.toArray


  def close() = logList.close
}
