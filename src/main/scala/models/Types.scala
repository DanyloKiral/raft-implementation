package models

import grpc.replication.{EntryData, ReplicationResult}

import scala.concurrent.Future

object Types {
  type ReplicateLogFuncData = (EntryData, (EntryData) => Future[(String, ReplicationResult)])
}
