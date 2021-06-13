package shared

import models.ServerInfo

import scala.util.Properties
import org.json4s.jackson.JsonMethods._

object Configs {
  implicit val formats = org.json4s.DefaultFormats
  private val random = scala.util.Random

  val ServerID = Properties.envOrElse("SERVER_ID", "raft-server")

  val GrpcPort = Properties.envOrElse("GRPC_PORT", "5051").toInt

  // all servers, including this
  private val envServersInfo = parse(Properties.envOrElse("SERVERS_INFO_JSON", "[]")).extract[Array[ServerInfo]]
  val ServersInfo = if (envServersInfo.nonEmpty)
    envServersInfo else
    Array(
      ServerInfo("raft-server1", "raft-server1", GrpcPort),
      ServerInfo("raft-server2", "raft-server2", GrpcPort),
      ServerInfo("raft-server3", "raft-server3", GrpcPort)
    )


  val ClusterQuorumNumber = (ServersInfo.length / 2) + 1

  private val electionTimeoutFromMs = Properties.envOrElse("ELECTION_TIMEOUT_FROM_MS", "4000").toInt
  private val electionTimeoutToMs = Properties.envOrElse("ELECTION_TIMEOUT_TO_MS", "7000").toInt

  def getElectionTimeoutMs(): Int = random.between(electionTimeoutFromMs, electionTimeoutToMs)

  // should be less than election timeout
  // todo: should be randomized?
  def getHeartbeatIntervalMs(): Int = Properties.envOrElse("HEARTBEAT_INTERVAL_MS", "2000").toInt
}
