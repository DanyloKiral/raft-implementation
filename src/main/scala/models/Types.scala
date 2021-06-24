package models

import grpc.replication.ReplicationResult

import scala.concurrent.Future

object Types {
  type ReplicationFunc = () => Future[ReplicationResponse]
}

case class ReplicationResponse(followerId: String, replicationResult: Option[ReplicationResult])