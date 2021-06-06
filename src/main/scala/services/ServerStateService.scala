package services

import models.ServerState._

object ServerStateService {
  private var State: ServerState = Follower

  def becomeCandidate(): Unit =
    State = Candidate

  def becomeLeader(): Unit =
    State = Leader

  def becomeFollower(): Unit =
    State = Follower
}
