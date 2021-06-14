package models

object ServerStateEnum extends Enumeration {
  type ServerStateEnum = Value

  val Leader, Follower, Candidate = Value
}
