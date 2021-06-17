package replication

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCode, StatusCodes, Uri}
import akka.http.scaladsl.server
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import models.{Log, ReplicateLogFuncData, ReplicationResponse}
import org.slf4j.Logger
import shared.{Configs, ServerState}
import akka.http.scaladsl.server.Directives._
import akka.stream.{FlowShape, Graph}
import akka.stream.scaladsl.{Balance, Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Merge, RetryFlow, Sink, Source}
import grpc.replication.{LogEntry, ReplicationResult}
import GraphDSL.Implicits._
import akka.NotUsed
import election.ElectionService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._

class LogService (replicationSender: ReplicationSender, electionService: ElectionService, serverState: ServerState, logState: LogState)
                 (implicit executionContext: ExecutionContext, logger: Logger, system: ActorSystem) {
  private val mapper: ObjectMapper = new ObjectMapper().registerModule(DefaultScalaModule)
  private val LogProcessingGraph = formatLogProcessingGraph

  def handleLogFromClient(log: Log): Future[HttpResponse] = {
    if (serverState.isLeader()) {
      // todo: send leader url?
      return Future.successful(httpResponse(StatusCodes.MisdirectedRequest))
    }

    // todo: next index?
    val entry = LogEntry(serverState.getCurrentTerm, 1, log.command)

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
      .mapAsyncUnordered(1)(action => action.replicationFunc(action.entryData))

    // todo: change retry interval
    val retryFlow = RetryFlow.withBackoff(5.seconds, 10.seconds, 1, -1, flowToRetry)((i, o) =>
      o.replicationResult match {
          // if follower has outdated log - need to retry
        case Some(result)
          if serverState.isLeader && !result.success && result.term == serverState.getCurrentTerm => {

          val newPrevIndex = i.entryData.prevLogIndex - 1
          val entries = logState.getEntryFromIndex(newPrevIndex)
          val newPrevTerm = entries.last.term
          logger.info(s"Follower ${o.followerId} is outdated. Sending entries from index = ${newPrevIndex + 1}")
          val newEntryData = replicationSender.logToEntryData(
            entries.dropRight(1),
            Some(newPrevIndex),
            Some(newPrevTerm)
          )

          Some(ReplicateLogFuncData(newEntryData, i.replicationFunc))
        }
          // retry if no response
        case None if serverState.isLeader => Some(i)
        case _ => None
      })

    val processReplicationResultSink = Sink.foreach((data: ReplicationResponse) =>
      data.replicationResult match {
        case Some(result) if !result.success && result.term > serverState.getCurrentTerm => {
          serverState.increaseTerm(result.term)
          electionService.stepDownIfNeeded()
        }
        case Some(result) if result.success => {
          logState.updateMatchIndexForFollower(data.followerId, data.entryData.entries.map(x => x.index).max)
        }
        case _ => {
          logger.warn(s"Unexpected result of AppendEntries. state = $data")
        }
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
