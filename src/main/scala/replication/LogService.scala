package replication

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCode, StatusCodes, Uri}
import akka.http.scaladsl.server
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import models.Log
import org.slf4j.Logger
import shared.ServerStateService
import akka.http.scaladsl.server.Directives._
import grpc.replication.LogEntry

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class LogService (replicationSender: ReplicationSender, logState: LogState)
                 (implicit executionContext: ExecutionContext, logger: Logger) {
  private val mapper: ObjectMapper = new ObjectMapper().registerModule(DefaultScalaModule)

  def receiveLogFromClient(log: Log): server.Route = {
    if (ServerStateService.isLeader()) {
      // todo: send leader url?
      return redirect(Uri(""), StatusCodes.TemporaryRedirect)
    }
    // todo: save next index
    val data = LogEntry(ServerStateService.getCurrentTerm, 1, log.command)

    logState.appendLog(data)

    // todo: should be retried forever
    replicationSender.replicateLogEntriesToAll(Seq(data)).foreach(data => {
      val followerId = data._1
      data._2.andThen{
        case Success(value) => { }
        case Failure(exception) => logger.error("Unexpected error on log replication", exception)
      }
    })

    complete(StatusCodes.OK)
  }


}
