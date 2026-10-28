package blocker

import cats.effect.IO
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

class WebSessionsTest extends CatsEffectSuite:
  test("sessions persist across instances with private files, hashed identifiers, and no calendar credentials") {
    for
      dir <- IO.blocking(Files.createTempDirectory("sessions-test"))
      store = WebSessions(dir)
      token <- store.create(Account("google", "person@example.com"))
      loaded <- WebSessions(dir).get(token)
      _ <- IO.blocking {
        val files = Files.list(dir.resolve("sessions"))
        try files.iterator.asScala.foreach { f =>
          assert(!f.getFileName.toString.contains(token))
          assertEquals(Files.getPosixFilePermissions(f), PosixFilePermissions.fromString("rw-------"))
          assert(!Files.readString(f).contains("refresh"))
        }
        finally files.close()
      }
      _ <- store.delete(token)
      gone <- store.get(token)
    yield
      assertEquals(loaded.map(_.email), Some("person@example.com"))
      assertEquals(gone, None)
  }
  test("expired, invalid and corrupted sessions cannot authenticate") {
    for
      dir <- IO.blocking(Files.createTempDirectory("sessions-expire-test"))
      store = WebSessions(dir)
      token <- store.create(Account("google", "person@example.com"))
      _ <- IO.blocking {
        val files = Files.list(dir.resolve("sessions"))
        try
          val file = files.iterator.next()
          val json = parse(Files.readString(file)).toOption.get.mapObject(_.add("expires", 0L.asJson))
          Files.writeString(file, json.noSpaces)
        finally files.close()
      }
      expired <- store.get(token)
      malformed <- store.get("../../file")
      another <- store.create(Account("google", "person@example.com"))
      _ <- IO.blocking {
        val files = Files.list(dir.resolve("sessions"))
        try Files.writeString(files.iterator.next(), "invalid JSON")
        finally files.close()
      }
      corrupted <- store.get(another)
    yield
      assertEquals(expired, None)
      assertEquals(malformed, None)
      assertEquals(corrupted, None)
  }
