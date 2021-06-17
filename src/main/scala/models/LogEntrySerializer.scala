package models

import grpc.replication.LogEntry

object LogEntrySerializer {
  import swaydb.data.slice.Slice
  import swaydb.serializers.Serializer

  val serializer =
    new Serializer[LogEntry] {
      override def write(myClass: LogEntry): Slice[Byte] =
        Slice
          .ofBytesScala(500)
          .addLong(myClass.index)
          .addLong(myClass.term)
          .addStringUTF8(myClass.command)
          .close()

      override def read(slice: Slice[Byte]): LogEntry = {
        val reader = slice.createReader()
        LogEntry(
          index = reader.readLong(),
          term = reader.readLong(),
          command = reader.readRemainingAsStringUTF8()
        )
      }
    }
}
