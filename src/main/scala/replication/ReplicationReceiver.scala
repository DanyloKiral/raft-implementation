package replication

import grpc.replication.Replication.ZioReplication.Replication
import grpc.replication.Replication.{EntryData, ReplicationResult}
import io.grpc.Status
import zio.ZIO

class ReplicationReceiver extends Replication {
  override def appendEntries(request: EntryData): ZIO[Any, Status, ReplicationResult] = ???
}
