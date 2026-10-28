package blocker

import cats.effect.IO
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.Base64

case class WebSession(provider: String, email: String, csrf: String, expires: Long) derives Codec.AsObject:
  def account: Account = Account(provider, email)

class WebSessions(home: Path):
  private val directory = home.resolve("sessions")
  private def file(token: String): Path =
    val hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)).map(b => f"$b%02x").mkString
    directory.resolve(hash + ".json")

  def create(account: Account): IO[String] = IO.blocking {
    val token = WebSessions.randomToken()
    val session = WebSession(account.provider, account.email, WebSessions.randomToken(), Instant.now.plusSeconds(30L * 86400).getEpochSecond)
    TokenStore.writeAtomically(file(token), session.asJson.noSpaces)
    token
  }

  def get(token: String): IO[Option[WebSession]] = IO.blocking {
    if !token.matches("[A-Za-z0-9_-]{43}") || !Files.exists(file(token)) then None
    else
      val session = decode[WebSession](Files.readString(file(token))).toOption
      if session.exists(_.expires > Instant.now.getEpochSecond) then session
      else
        Files.deleteIfExists(file(token))
        None
  }

  def delete(token: String): IO[Unit] = IO.blocking { Files.deleteIfExists(file(token)); () }

object WebSessions:
  def randomToken(): String =
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
