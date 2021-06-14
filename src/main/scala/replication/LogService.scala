package replication

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCode, StatusCodes, Uri}
import akka.http.scaladsl.server
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import models.Log
import org.slf4j.Logger
import shared.{Configs, ServerStateService}
import akka.http.scaladsl.server.Directives._
import akka.stream.{FlowShape, Graph}
import akka.stream.scaladsl.{Balance, Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Merge, RetryFlow, Sink, Source}
import grpc.replication.{LogEntry, ReplicationResult}
import models.Types.ReplicateLogFuncData
import GraphDSL.Implicits._
import akka.NotUsed

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._

class LogService (replicationSender: ReplicationSender, logState: LogState)
                 (implicit executionContext: ExecutionContext, logger: Logger, system: ActorSystem) {
  private val mapper: ObjectMapper = new ObjectMapper().registerModule(DefaultScalaModule)

  private val LogProcessingGraph = formatLogProcessingGraph

  def handleLogFromClient(log: Log): Future[HttpResponse] = {
    if (ServerStateService.isLeader()) {
      // todo: send leader url?
      return Future.successful(httpResponse(StatusCodes.MisdirectedRequest))
    }

    // todo: save next index
    val data = LogEntry(ServerStateService.getCurrentTerm, 1, log.command)

    logState.appendLog(data)
    
    val replicationHandlerSource = Source(replicationSender.getSendFunctions(log))
      .via(LogProcessingGraph)
      .toMat(BroadcastHub.sink)(Keep.right)
      .run

    replicationSender.resetHeartbeatInterval()

    replicationHandlerSource
      .grouped(Configs.ClusterQuorumNumber - 1)
      .map(x => {

        // todo: commit here
        logger.info(s"After Grouping. Contains = $x")

        httpResponse(StatusCodes.OK, "Success")
      })
      .runWith(Sink.head)
  }

  private def formatLogProcessingGraph(): Graph[FlowShape[ReplicateLogFuncData, (String, ReplicationResult)], NotUsed] = {
    val flowToRetry = Flow[ReplicateLogFuncData]
      .mapAsyncUnordered(1)(action => action._2(action._1))

    // todo: change retry interval
    val retryFlow = RetryFlow.withBackoff(5.seconds, 10.seconds, 1, -1, flowToRetry)((i, o) => {
      logger.info(s"Checking retry condition for ${o._1}; current result = ${o._2}")

      // todo: change logs (indexes) for outdated follower
      if (o._2.success) None else Some(i)
    })

    val processReplicationResultSink = Sink.foreach((result: (String, ReplicationResult)) => {
      logger.info(s"processResultsSink. data = $result")
    })

    GraphDSL.create[FlowShape[ReplicateLogFuncData, (String, ReplicationResult)]]() { implicit graphBuilder =>
      val IN = graphBuilder.add(Broadcast[ReplicateLogFuncData](1))
      val PROCESS_REPLICATION = graphBuilder.add(Broadcast[(String, ReplicationResult)](2))
      val parallelNo = Configs.ServersInfo.size - 1
      val BALANCE = graphBuilder.add(Balance[ReplicateLogFuncData](parallelNo))
      val MERGE = graphBuilder.add(Merge[(String, ReplicationResult)](parallelNo))
      val OUT = graphBuilder.add(Merge[(String, ReplicationResult)](1))

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
