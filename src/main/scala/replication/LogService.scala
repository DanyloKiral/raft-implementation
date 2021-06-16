package replication

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCode, StatusCodes, Uri}
import akka.http.scaladsl.server
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import models.Log
import org.slf4j.Logger
import shared.{Configs, ServerState}
import akka.http.scaladsl.server.Directives._
import akka.stream.{FlowShape, Graph}
import akka.stream.scaladsl.{Balance, Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Merge, RetryFlow, Sink, Source}
import grpc.replication.{LogEntry, ReplicationResult}
import models.Types.{ReplicateLogFuncData, ReplicationResponse}
import GraphDSL.Implicits._
import akka.NotUsed
import election.ElectionService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._

class LogService (replicationSender: ReplicationSender, electionService: ElectionService, logState: LogState)
                 (implicit executionContext: ExecutionContext, logger: Logger, system: ActorSystem) {
  private val mapper: ObjectMapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private val LogProcessingGraph = formatLogProcessingGraph

  def handleLogFromClient(log: Log): Future[HttpResponse] = {
    if (ServerState.isLeader()) {
      // todo: send leader url?
      return Future.successful(httpResponse(StatusCodes.MisdirectedRequest))
    }

    // todo: next index?
    val entry = LogEntry(ServerState.getCurrentTerm, 1, log.command)

    val currentLogIndex = logState.appendLog(entry)

    val replicationHandlerSource =
      Source(replicationSender.getSendFunctions(entry))
        .via(LogProcessingGraph)
        .toMat(BroadcastHub.sink)(Keep.right)
        .run

    replicationSender.resetHeartbeatInterval()

    replicationHandlerSource
      .grouped(Configs.ClusterQuorumNumber - 1)
      .map(x => {

        // todo: commit here
        logState.commit(currentLogIndex)

        httpResponse(StatusCodes.OK, "Success")
      })
      .runWith(Sink.head)
  }

  private def formatLogProcessingGraph(): Graph[FlowShape[ReplicateLogFuncData, ReplicationResponse], NotUsed] = {
    val flowToRetry = Flow[ReplicateLogFuncData]
      .mapAsyncUnordered(1)(action => action._2(action._1))

    // todo: change retry interval
    val retryFlow = RetryFlow.withBackoff(5.seconds, 10.seconds, 1, -1, flowToRetry)((i, o) =>
      o._2 match {
          // if follower has outdated log - need to retry
        case Some(result)
          if ServerState.isLeader && !result.success && result.term == ServerState.getCurrentTerm => {

          val newPrevIndex = i._1.prevLogIndex - 1
          val entries = logState.getEntryFromIndex(newPrevIndex)
          val newPrevTerm = entries.last.term

          val newEntryData = replicationSender.logToEntryData(
            entries.dropRight(1),
            Some(newPrevIndex),
            Some(newPrevTerm)
          )

          Some((newEntryData, i._2))
        }
          // retry if no response
        case None if ServerState.isLeader => Some(i)
        case _ => None
      })

    val processReplicationResultSink = Sink.foreach((data: ReplicationResponse) =>
      data._2 match {
        case Some(result) if !result.success && result.term > ServerState.getCurrentTerm => {
          ServerState.increaseTerm(result.term)
          electionService.stepDownIfNeeded()
        }
        case _ => {}
      })

    GraphDSL.create[FlowShape[ReplicateLogFuncData, ReplicationResponse]]() { implicit graphBuilder =>
      val IN = graphBuilder.add(Broadcast[ReplicateLogFuncData](1))
      val PROCESS_REPLICATION = graphBuilder.add(Broadcast[ReplicationResponse](2))
      val parallelNo = Configs.ServersInfo.size - 1
      val BALANCE = graphBuilder.add(Balance[ReplicateLogFuncData](parallelNo))
      val MERGE = graphBuilder.add(Merge[ReplicationResponse](parallelNo))
      val OUT = graphBuilder.add(Merge[ReplicationResponse](1))

      IN ~> BALANCE.in

      for (i <- 0 until parallelNo)
        BALANCE.out(i) ~> retryFlow ~> MERGE.in(i)

      MERGE.out ~> PROCESS_REPLICATION.in

      PROCESS_REPLICATION.out(0) ~> processReplicationResultSink
      PROCESS_REPLICATION.out(1) ~> OUT

      FlowShape(IN.in, OUT.out)
    }
  }

  private def httpResponse(code: StatusCode, data: Any = null) =
    HttpResponse(code, entity = HttpEntity(ContentTypes.`application/json`, mapper.writeValueAsString(data)))
}
