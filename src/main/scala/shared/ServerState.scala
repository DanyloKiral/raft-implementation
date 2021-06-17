package shared

import models.ServerStateEnum._
import org.slf4j.Logger
import swaydb._
import swaydb.serializers.Default._

class ServerState (implicit logger: Logger) {
  val stateStorage = persistent.Map[String, String, Nothing, Glass](dir = Configs.StateStorageFolder)

  val ServerID: String = Configs.ServerID
  var CurrentLeaderId: Option[String] = Option.empty
  private var CurrentTerm = stateStorage.get("CurrentTerm").getOrElse("0").toLong

  private var State: ServerStateEnum = Follower
  private var VotedFor: Option[String] = stateStorage.get("VotedFor")

  if (stateStorage.isEmpty) {
    stateStorage.put("CurrentTerm", CurrentTerm.toString)
  }

  def becomeCandidate(): Unit = {
    logger.info("Becoming a Candidate")
    increaseTerm(CurrentTerm + 1)
    State = Candidate
  }

  def becomeLeader(): Unit = {
    logger.info("Becoming a Leader")
    State = Leader
    setLeaderId(ServerID)
  }

  def becomeFollower(): Unit = {
    if (State != Follower) {
      logger.info("Becoming a Follower")
      State = Follower
    }
  }

  def isFollower(): Boolean = State == Follower
  def isLeader(): Boolean = State == Leader
  def setLeaderId(id: String) = CurrentLeaderId = Some(id)

  def increaseTerm(newTerm: Long) = {
    if (newTerm <= CurrentTerm) {
      throw new Throwable(s"Error in increaseTerm; new term should be higher than current. current = $CurrentTerm; new = $newTerm")
    }

    logger.info(s"Increasing current term to $newTerm")
    CurrentTerm = newTerm
    stateStorage.update("CurrentTerm", CurrentTerm.toString)
    clearVotedFor
  }

  def getCurrentTerm() = CurrentTerm


  def grantedVote() = VotedFor
  def voteFor(candidateId: String) = {
    VotedFor = Option(candidateId)
    stateStorage.put("VotedFor", candidateId)
  }
  private def clearVotedFor() = {
    VotedFor = None
    stateStorage.remove("VotedFor")
  }

  def close() = stateStorage.close
}




