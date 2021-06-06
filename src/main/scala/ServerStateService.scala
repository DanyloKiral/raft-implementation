import models.ServerState.{Candidate, Follower, Leader, ServerState}

object ServerStateService {
  private var CurrentTerm = 0L
  private var State: ServerState = Follower

  def becomeCandidate(): Unit =
    State = Candidate

  def becomeLeader(): Unit =
    State = Leader

  def becomeFollower(): Unit =
    State = Follower
}
