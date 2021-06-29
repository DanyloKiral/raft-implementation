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
      return Future.successful(httpResponse(StatusCodes.MisdirectedRequest))
    }

    val currentTerm = serverState.getCurrentTerm

    val entry = LogEntry(serverState.getCurrentTerm, logState.getNextIndexForEntry, log.command)

    logState.appendLog(entry)

    val replicationHandlerSource =
      Source(replicationSender.getSendFunctions(currentTerm))
        .via(LogProcessingGraph)
        .toMat(BroadcastHub.sink)(Keep.right)
        .run

    replicationSender.resetHeartbeatInterval()

    replicationHandlerSource
      .grouped(Configs.ClusterQuorumNumber - 1)
      .map(response => {
        if (!serverState.isLeader || response.exists(r => r.replicationResult.isEmpty || !r.replicationResult.get.success)) {
          httpResponse(StatusCodes.ServiceUnavailable)
        } else {
          logState.commitAsLeader()
          httpResponse(StatusCodes.OK)
        }
      })
      .runWith(Sink.head)
  }

  private def formatLogProcessingGraph(): Graph[FlowShape[(Long, ReplicationFunc), ReplicationResponse], NotUsed] = {
    val flowToRetry = Flow[(Long, ReplicationFunc)]
      .mapAsyncUnordered(1)(action => action._2())

    val retryFlow = RetryFlow.withBackoff(
      Configs.getHeartbeatIntervalMs.millis, (Configs.getHeartbeatIntervalMs * 2).millis, 1, -1, flowToRetry)((i, o) =>

      o.replicationResult match {
          // if follower has outdated log - need to retry
        case Some(result)
          if serverState.isLeader && !result.success && result.term == i._1 => {

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

    GraphDSL.create[FlowShape[(Long, ReplicationFunc), ReplicationResponse]]() { implicit graphBuilder =>
      val IN = graphBuilder.add(Broadcast[(Long, ReplicationFunc)](1))
      val PROCESS_REPLICATION = graphBuilder.add(Broadcast[ReplicationResponse](2))
      val parallelNo = Configs.ServersInfo.size - 1
      val BALANCE = graphBuilder.add(Balance[(Long, ReplicationFunc)](parallelNo))
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
