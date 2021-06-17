package models

import grpc.replication.LogEntry

case class ServerInfo (id: String, address: String, port: Int)

case class GeneralServerStatus(
                              currentTerm: Long,
                              votedFor: Option[String],
                              log: Array[LogEntry],
                              commitIndex: Long,
                              lastApplied: Long,
                              nextIndex: Option[List[(String, Long)]],
                              matchIndex: Option[List[(String, Long)]]
                              )