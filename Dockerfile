FROM openjdk:8-jre-alpine

COPY /src/resources/logback.xml logback.xml
COPY /target/scala-2.13/Raft-assembly-0.1.jar raft.jar

ENTRYPOINT ["java","-Dlogback.configurationFile=/logback.xml", "-jar","/raft.jar"]