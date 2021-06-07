import election.VoterHandler
import grpc.election.Voter.ZioVoter.Voter
import io.grpc.ServerBuilder
import replication.ReplicationReceiver
import scalapb.zio_grpc.{ManagedServer, Server, ServerLayer, ServerMain, ServiceList, ZBindableService}
import zio._
import zio.clock.Clock
import zio.console.putStrLn

object Main extends zio.App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    formatAppLayers(Configs.VoterGrpcPort, Configs.ReplicationGrpcPort).build.useForever
      .catchAll(t => ZIO.succeed(t.printStackTrace()).map(_ => ExitCode.failure))
      .onExit {
        exit =>
          println("exitexit")
          ZIO.succeed()
      }
      .exitCode
  }

  def formatAppLayers(voterPort: Int, replicationPort: Int): Layer[Throwable, Server] =
    Clock.live >>> (
        (VoterHandler.live >>> ZLayer.fromManaged(ManagedServer.fromBuilder(ServerBuilder.forPort(voterPort)))) ++
        (ReplicationReceiver.live >>> ZLayer.fromManaged(ManagedServer.fromBuilder(ServerBuilder.forPort(replicationPort)))))
}

