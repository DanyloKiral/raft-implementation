package replication

import org.slf4j.Logger

class StateMachine (implicit logger: Logger) {
  def applyCommand(command: String) = {
    logger.info(s"State machine applying command '$command''")
  }
}
