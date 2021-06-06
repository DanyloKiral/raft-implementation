FROM openjdk:8-jre-alpine

COPY /target/scala-2.13/Raft-assembly-0.1.jar raft.jar

ENTRYPOINT ["java","-jar","/raft.jar"]