import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import election.{ElectionService, VoterImplementation}
import grpc.replication.{Replication, ReplicationHandler}
import replication.{ReplicationReceiver, ReplicationSender}
import akka.grpc.scaladsl.ServiceHandler
import com.typesafe.config.ConfigFactory
import grpc.election.VoterHandler
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import shared.Configs

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._

object Main extends App {
  val conf = ConfigFactory
    .parseString("akka.http.server.preview.enable-http2 = on")
    .withFallback(ConfigFactory.defaultApplication())
  implicit val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
  implicit val system = ActorSystem("Raft", conf)
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  val replicationSender = new ReplicationSender()

  val electionService = new ElectionService(replicationSender)
  startGrpcServer(electionService)

  electionService.resetElectionTimeout()

  system.registerOnTermination(() => electionService.close)

  private def startGrpcServer(electionService: ElectionService) = {
    val voterServer: PartialFunction[HttpRequest, Future[HttpResponse]] =
      VoterHandler.partial(new VoterImplementation(electionService))
    val replicationServer: PartialFunction[HttpRequest, Future[HttpResponse]] =
      ReplicationHandler.partial(new ReplicationReceiver(electionService))

    val serviceHandlers: HttpRequest => Future[HttpResponse] =
      ServiceHandler.concatOrNotFound(voterServer, replicationServer)

    val binding = Http()
      .newServerAt("0.0.0.0", Configs.GrpcPort)
      .bind(serviceHandlers)

    binding.foreach { binding => println(s"gRPC server bound to: ${binding.localAddress}") }
  }
}
