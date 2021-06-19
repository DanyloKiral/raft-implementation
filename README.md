# Raft
Raft consensus protocol implementation, using Scala and Akka.

## How to run 
1. Have **java, docker, sbt** installed.
2. Run 'sbt assembly' command.
3. Run 'docker-compose build', 'docker-compose up' commands.

## API
Docker compose set up for 3 instances of Raft server.
Each has exposed API on localhost:605N, where N - instance number.

- GET /health. 
 
Returns 200, if server is up.

- GET /status. 

Returns object, that represents server state.

- POST /command. 

Receives command, replicates and applies it to the state machine.
Returns 200 when command was safely replicated to majority of servers.
Input object sample: { "command": "my-command-to-state-machine" }.

    
   