package shared

import models.ServerStateEnum._
import org.slf4j.Logger

object ServerState {
  val ServerID: String = Configs.ServerID
  // todo: set leader ID
  var CurrentLeaderId: Option[String] = Option.empty
  private var CurrentTerm = 0L
  private var State: ServerStateEnum = Follower
  private var VotedFor: Option[String] = Option.empty

  def becomeCandidate() (implicit logger: Logger): Unit = {
    logger.info("Becoming a Candidate")
    increaseTerm(CurrentTerm + 1)
    State = Candidate
  }

  def becomeLeader() (implicit logger: Logger): Unit = {
    logger.info("Becoming a Leader")
    State = Leader
  }

  def becomeFollower() (implicit logger: Logger): Unit = {
    if (State != Follower) {
      logger.info("Becoming a Follower")
      State = Follower
    }
  }

  def isFollower(): Boolean = State == Follower
  def isLeader(): Boolean = State == Leader

  // terms

  def increaseTerm(newTerm: Long) (implicit logger: Logger) = {
    if (newTerm <= CurrentTerm) {
      throw new Throwable(s"Error in increaseTerm; new term should be higher than current. current = $CurrentTerm; new = $newTerm")
    }

    logger.info(s"Increasing current term to $newTerm")
    CurrentTerm = newTerm
    // todo: save term to permanent storage
  }

  def getCurrentTerm() = CurrentTerm

  // voting

  def grantedVote() = VotedFor

  def voteFor(candidateId: String) = {
    VotedFor = Option(candidateId)
    // todo: save votes to permanent storage
  }
}




