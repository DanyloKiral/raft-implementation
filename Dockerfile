FROM openjdk:8-jre-alpine

COPY /target/scala-2.13/kafka-throughput-investigation-assembly-0.1.jar kafka-throughput-investigation.jar

ENTRYPOINT ["java","-jar","/kafka-throughput-investigation.jar"]