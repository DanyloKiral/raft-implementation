package replication

import grpc.election.Voter.{CandidateData, Vote}
import grpc.election.Voter.ZioVoter.Voter
import grpc.replication.Replication.ZioReplication.Replication
import grpc.replication.Replication.{EntryData, ReplicationResult}
import io.grpc.Status
import zio.{Has, ZIO, ZLayer}
import zio.clock.Clock
import zio.console.putStrLn

object ReplicationReceiver {
  type ReplicationReceiverEnv = Has[Replication]

  class ReplicationReceiverImplementation(clock: Clock.Service) extends Replication {
    println("Starting Replication...")

    override def appendEntries(request: EntryData): ZIO[Any, Status, ReplicationResult] = {
      ZIO.succeed(ReplicationResult(1, true))
    }
  }

  val live: ZLayer[Clock, Nothing, ReplicationReceiverEnv] =
      ZLayer.fromService(new ReplicationReceiverImplementation(_))
}

