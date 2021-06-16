package models

import grpc.replication.{EntryData, ReplicationResult}

import scala.concurrent.Future

object Types {
  type ReplicationResponse = (String, Option[ReplicationResult])
  type ReplicateLogFuncData = (EntryData, EntryData => Future[ReplicationResponse])
}
