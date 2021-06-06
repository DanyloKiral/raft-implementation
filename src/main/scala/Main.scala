import election.VoterHandler
import grpc.election.Voter.ZioVoter.Voter
import io.grpc.ServerBuilder
import scalapb.zio_grpc.{Server, ServerLayer, ServerMain, ServiceList, ZBindableService}
import zio._
import zio.clock.Clock
import zio.console.putStrLn

object Main extends zio.App {

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    //??? //Schedule.delayed().

//    val serverLayer =
//      ServerLayer.fromServiceLayer(
//        io.grpc.ServerBuilder.forPort(9000)
//      )(VoterHandler.live)

    serverLive(8080).build.useForever.exitCode

  }

  def serverLive(port: Int): Layer[Throwable, Server] =
    Clock.live >>> VoterHandler.live >>> ServerLayer.access[Voter](
      ServerBuilder.forPort(port)
    )
}

