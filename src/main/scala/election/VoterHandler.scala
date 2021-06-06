package election

import grpc.election.Voter.ZioVoter.Voter
import grpc.election.Voter.{CandidateData, Vote}
import io.grpc.Status
import zio.clock.Clock
import zio.{Has, ZIO, ZLayer}

//import grpc.election.VoteHandler.{CandidateData, Vote}
//import grpc.election.VoteHandler.ZioVoteHandler.{VoteHandler, VoteHandlerClient}
//import zio.ZIO
import zio.console._
//import io.grpc.{ManagedChannelBuilder, Status}
//import scalapb.zio_grpc.ZManagedChannel

object VoterHandler {
  type VoterHandler = Has[Voter]

  class VoterHandlerImplementation(clock: Clock.Service) extends Voter {
    //  val channel = ZManagedChannel(ManagedChannelBuilder
    //    .forAddress("localhost", 8980))
    //
    //  val voteHandlerClient = VoteHandlerClient.managed(channel)
    //
    override def requestVote(request: CandidateData): ZIO[Any, Status, Vote] = {
      putStrLn("requestVote handler!!")

      ZIO.succeed(Vote(1, true))
    }
  }

  val live: ZLayer[Clock, Nothing, VoterHandler] =
    ZLayer.fromService(new VoterHandlerImplementation(_))
}