import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import election.{ElectionService, VoterImplementation}
import grpc.replication.{Replication, ReplicationHandler, ReplicationResult}
import replication.{LogService, LogState, ReplicationReceiver, ReplicationSender, StateMachine}
import akka.grpc.scaladsl.ServiceHandler
import akka.http.scaladsl.server.Directives._
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}
import com.typesafe.config.ConfigFactory
import grpc.election.VoterHandler
import models.Log
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import shared.Configs

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import com.softwaremill.macwire._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import spray.json.RootJsonFormat
import spray.json.DefaultJsonProtocol._

object Main extends App {
  val conf = ConfigFactory
    .parseString("akka.http.server.preview.enable-http2 = on")
    .withFallback(ConfigFactory.defaultApplication())
  implicit val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
  implicit val system = ActorSystem("Raft", conf)
  implicit val materializer: Materializer = Materializer(system)
  implicit val clientJsonFormat: RootJsonFormat[Log] = jsonFormat1(Log)
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  lazy val replicationSender = wire[ReplicationSender]
  lazy val electionService = wire[ElectionService]
  lazy val logState = wire[LogState]
  lazy val logService = wire[LogService]
  lazy val stateMachine = wire[StateMachine]

  startGrpcServer()
  startExposedHttpServer()

  electionService.resetElectionTimeout()

  system.registerOnTermination(() => electionService.close)

  private def startGrpcServer() = {
    lazy val voter = wire[VoterImplementation]
    lazy val replicationReceiver = wire[ReplicationReceiver]

    val voterServer: PartialFunction[HttpRequest, Future[HttpResponse]] =
      VoterHandler.partial(voter)
    val replicationServer: PartialFunction[HttpRequest, Future[HttpResponse]] =
      ReplicationHandler.partial(replicationReceiver)

    val serviceHandlers: HttpRequest => Future[HttpResponse] =
      ServiceHandler.concatOrNotFound(voterServer, replicationServer)

    val binding = Http()
      .newServerAt("0.0.0.0", Configs.GrpcPort)
      .bind(serviceHandlers)

    binding.foreach { binding => println(s"gRPC server bound to: ${binding.localAddress}") }
  }

  private def startExposedHttpServer() = {
    val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
      Http().newServerAt("0.0.0.0", Configs.ExposedHttpPort).connectionSource()

    logger.info(f"Server is starting on port ${Configs.ExposedHttpPort}")
    serverSource.runForeach { connection =>
      connection.handleWith(
        concat (
          get {
            concat(
              path("health") {
                handleSync(_ => HttpResponse(StatusCodes.OK))
              },
              path("status") {
                // todo: implement Status endpoint
                handleSync(_ => HttpResponse(StatusCodes.OK))
              }
            )
          },
          post {
            path("command") {
              entity(as[Log]) { log =>
                complete {
                  logService.handleLogFromClient(log)
                }
              }
            }
          }
        )
      )
    }
  }
}
