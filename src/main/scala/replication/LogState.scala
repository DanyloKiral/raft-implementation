package replication

import grpc.replication.LogEntry

import scala.collection.mutable

class LogState {
  // todo: consider changing the collection
  private val logList: mutable.ArrayBuffer[LogEntry] = mutable.ArrayBuffer()

  def appendLog(log: LogEntry): Int = ???
}
