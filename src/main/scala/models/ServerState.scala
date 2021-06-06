package models

object ServerState extends Enumeration {
  type ServerState = Value

  val Leader, Follower, Candidate = Value
}
