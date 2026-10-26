package blocker

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.{Console, Dispatcher}
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetAddress, InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

case class Response(status: Int, body: String, location: Option[String] = None)

/** Handles one invite: `/calblock/start` redirects to the provider's consent page, `/calblock/callback` finishes it. */
final class Onboard private (
    auth: AuthProvider,
    email: String,
    redirectUri: String,
    invite: String,
    onSuccess: Account => IO[Unit],
    states: Ref[IO, Set[String]],
    val done: Deferred[IO, Account]
):
  import Onboard.*

  def handle(path: String, query: Map[String, String]): IO[Response] = path match
    case StartPath =>
      done.tryGet.flatMap {
        case Some(_) => IO.pure(page(410, "This invite has already been used."))
        case None if !query.get("invite").exists(constantTimeEquals(_, invite)) =>
          IO.pure(page(403, "This invite link is invalid."))
        case None =>
          val state = randomToken()
          states.update(_ + state).as(Response(302, "", Some(auth.authorizationUrl(state, email, redirectUri))))
      }
    case CallbackPath =>
      states.get.flatMap { valid =>
        (query.get("state").filter(valid), query.get("code")) match
          case (None, _) => IO.pure(page(403, "This sign-in attempt is invalid or expired. Open your invite link again."))
          case (Some(_), _) if query.contains("error") =>
            IO.pure(page(400, "Sign-in was cancelled. Open your invite link again to retry."))
          case (Some(_), None) => IO.pure(page(400, "Missing authorization code."))
          case (Some(state), Some(code)) =>
            states.update(_ - state) >> auth.completeAuthorization(code, redirectUri, email).flatMap {
              case Left(msg) => IO.pure(page(400, s"$msg Open your invite link again to retry."))
              case Right(acct) =>
                onSuccess(acct) >> done.complete(acct).as(
                  page(200, "You're all set. calendar-blocker can now schedule time on your calendar. You can close this tab.")
                )
            }
      }
    case _ => IO.pure(page(404, "Not found."))

object Onboard:
  val StartPath    = "/calblock/start"
  val CallbackPath = "/calblock/callback"

  def create(auth: AuthProvider, email: String, redirectUri: String, invite: String, onSuccess: Account => IO[Unit]): IO[Onboard] =
    (Ref.of[IO, Set[String]](Set.empty), Deferred[IO, Account]).mapN(Onboard(auth, email, redirectUri, invite, onSuccess, _, _))

  def randomToken(): String =
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))

  def page(status: Int, message: String): Response =
    Response(
      status,
      s"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
         |<title>calendar-blocker</title></head>
         |<body style="font-family:system-ui;max-width:32rem;margin:4rem auto;padding:0 1rem">
         |<h1>calendar-blocker</h1><p>${escape(message)}</p></body></html>""".stripMargin
    )

  private def escape(s: String): String =
    s.flatMap {
      case '&'  => "&amp;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case '"'  => "&quot;"
      case '\'' => "&#39;"
      case c    => c.toString
    }

  def parseQuery(raw: String): Map[String, String] =
    Option(raw).filter(_.nonEmpty).fold(Map.empty)(_.split("&").toList.map { kv =>
      kv.split("=", 2) match
        case Array(k, v) => URLDecoder.decode(k, UTF_8) -> URLDecoder.decode(v, UTF_8)
        case Array(k)    => URLDecoder.decode(k, UTF_8) -> ""
    }.toMap)

  /** Serves `onboard` on loopback only; the reverse proxy provides TLS and the public hostname. */
  def serve(onboard: Onboard, port: Int): Resource[IO, Unit] =
    Dispatcher.parallel[IO].flatMap { dispatcher =>
      def respond(ex: HttpExchange): Unit =
        val resp =
          if ex.getRequestMethod != "GET" then page(405, "Method not allowed.")
          else
            dispatcher.unsafeRunSync(
              onboard
                .handle(ex.getRequestURI.getPath, parseQuery(ex.getRequestURI.getRawQuery))
                .handleErrorWith(e =>
                  Console[IO].errorln(s"onboarding error: ${e.getMessage}")
                    .as(page(500, "Something went wrong. Open your invite link again to retry."))
                )
            )
        val bytes   = resp.body.getBytes(UTF_8)
        val headers = ex.getResponseHeaders
        headers.set("Content-Type", "text/html; charset=utf-8")
        headers.set("Cache-Control", "no-store")
        headers.set("Referrer-Policy", "no-referrer")
        headers.set("X-Content-Type-Options", "nosniff")
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        resp.location.foreach(headers.set("Location", _))
        ex.sendResponseHeaders(resp.status, if bytes.isEmpty then -1 else bytes.length.toLong)
        if bytes.nonEmpty then ex.getResponseBody.write(bytes)
        ex.close()

      Resource.make(IO.blocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, port), 0)
        server.createContext("/", respond(_))
        server.start()
        server
      })(s => IO.blocking(s.stop(0))).void
    }
