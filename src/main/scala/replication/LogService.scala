package replication

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCode, StatusCodes}
import com.fasterxml.jackson.databind.ObjectMapper
import models.{Log, ReplicationResponse}
import org.slf4j.Logger
import shared.{Configs, ServerState}
import akka.stream.{FlowShape, Graph}
import akka.stream.scaladsl.{Balance, Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Merge, RetryFlow, Sink, Source}
import grpc.replication.LogEntry
import GraphDSL.Implicits._
import akka.NotUsed
import election.ElectionService
import models.Types.ReplicationFunc

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class LogService (replicationSender: ReplicationSender, electionService: ElectionService, serverState: ServerState, logState: LogState)
                 (implicit executionContext: ExecutionContext, logger: Logger, system: ActorSystem, mapper: ObjectMapper) {
  private val LogProcessingGraph = formatLogProcessingGraph

  def handleLogFromClient(log: Log): Future[HttpResponse] = {
    if (!serverState.isLeader) {
      // todo: send leader url?
      return Future.successful(httpResponse(StatusCodes.MisdirectedRequest))
    }

    val entry = LogEntry(serverState.getCurrentTerm, logState.getNextIndexForEntry, log.command)

    logState.appendLog(entry)

    val replicationHandlerSource =
      Source(replicationSender.getSendFunctions())
        .via(LogProcessingGraph)
        .toMat(BroadcastHub.sink)(Keep.right)
        .run

    replicationSender.resetHeartbeatInterval()

    replicationHandlerSource
      .grouped(Configs.ClusterQuorumNumber - 1)
      .map(response => {
        logState.commitAsLeader()
        httpResponse(StatusCodes.OK)
      })
      .runWith(Sink.head)
  }

  private def formatLogProcessingGraph(): Graph[FlowShape[ReplicationFunc, ReplicationResponse], NotUsed] = {
    val flowToRetry = Flow[ReplicationFunc]
      .mapAsyncUnordered(1)(action => action())

    val retryFlow = RetryFlow.withBackoff(
      Configs.getHeartbeatIntervalMs.millis, (Configs.getHeartbeatIntervalMs * 2).millis, 1, -1, flowToRetry)((i, o) =>

      o.replicationResult match {
          // if follower has outdated log - need to retry
        case Some(result)
          if serverState.isLeader && !result.success && result.term == serverState.getCurrentTerm => {

            logState.decreaseNextIndexForFollower(o.followerId)
            Some(i)
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
        case Some(result) if result.success =>
          logState.replicatedToFollower(data.followerId)
        case _ =>
          logger.warn(s"Unexpected result of AppendEntries. state = $data")
      })

    GraphDSL.create[FlowShape[ReplicationFunc, ReplicationResponse]]() { implicit graphBuilder =>
      val IN = graphBuilder.add(Broadcast[ReplicationFunc](1))
      val PROCESS_REPLICATION = graphBuilder.add(Broadcast[ReplicationResponse](2))
      val parallelNo = Configs.ServersInfo.size - 1
      val BALANCE = graphBuilder.add(Balance[ReplicationFunc](parallelNo))
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

  private def httpResponse(code: StatusCode, data: Option[Any] = None) =
    HttpResponse(code, entity =
      HttpEntity(ContentTypes.`application/json`, data.map(mapper.writeValueAsString(_)).getOrElse("")))
}
