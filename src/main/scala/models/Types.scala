package models

import grpc.replication.{EntryData, ReplicationResult}

import scala.concurrent.Future


case class ReplicateLogFuncData (entryData: EntryData, replicationFunc: EntryData => Future[ReplicationResponse])

case class ReplicationResponse(followerId: String, entryData: EntryData, replicationResult: Option[ReplicationResult])