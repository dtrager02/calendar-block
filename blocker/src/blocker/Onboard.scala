package blocker

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Console, Dispatcher}
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetAddress, InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.Executors

case class Response(status: Int, body: String, location: Option[String] = None)

/** Self-serve onboarding: `/` offers a connect button, `/start` redirects to the provider's consent page, and
 *  `/callback` stores the credential. Anyone can connect; only people named in the config ever get time booked.
 */
final class Onboard private (
    auth: AuthProvider,
    redirectUri: String,
    onConnected: Account => IO[Unit],
    states: Ref[IO, Map[String, Instant]]
):
  import Onboard.*

  def handle(path: String, query: Map[String, String]): IO[Response] = path match
    case "/" => IO.pure(Landing)
    case StartPath =>
      for
        now  <- IO.realTimeInstant
        state = randomToken()
        _    <- states.update(_.filter((_, issued) => issued.plus(StateLifetime).isAfter(now)) + (state -> now))
      yield Response(302, "", Some(auth.authorizationUrl(state, redirectUri)))
    case CallbackPath =>
      IO.realTimeInstant.flatMap { now =>
        val state = query.getOrElse("state", "")
        states.modify(s => (s - state, s.get(state).exists(_.plus(StateLifetime).isAfter(now)))).flatMap { valid =>
          (valid, query.get("code")) match
            case (false, _) => IO.pure(page(403, "This sign-in attempt is invalid or expired.", retry = true))
            case _ if query.contains("error") => IO.pure(page(400, "Sign-in was cancelled.", retry = true))
            case (true, None) => IO.pure(page(400, "Missing authorization code.", retry = true))
            case (true, Some(code)) =>
              auth.completeAuthorization(code, redirectUri).flatMap {
                case Left(msg) => IO.pure(page(400, msg, retry = true))
                case Right(acct) =>
                  onConnected(acct).as(
                    page(
                      200,
                      s"Connected as ${acct.email}. Once you're added to an event in the shared config, " +
                        "time gets booked on your calendar within the hour. You can close this tab."
                    )
                  )
              }
        }
      }
    case _ => IO.pure(page(404, "Not found."))

object Onboard:
  val StartPath    = "/start"
  val CallbackPath = "/callback"

  private val StateLifetime = Duration.ofMinutes(15)

  def create(auth: AuthProvider, redirectUri: String, onConnected: Account => IO[Unit]): IO[Onboard] =
    Ref.of[IO, Map[String, Instant]](Map.empty).map(Onboard(auth, redirectUri, onConnected, _))

  private def randomToken(): String =
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private val Landing = Response(
    200,
    html(
      """<p>calendar-blocker finds time on everyone's Google Calendar for the events in a shared config, and books it.</p>
        |<ul>
        |<li>It only ever sees the start and end times of your events, keeps nothing after each run, and can only edit a
        |calendar it creates for you ("Auto Blocks").</li>
        |<li>You can disconnect at any time at <a href="https://myaccount.google.com/permissions">myaccount.google.com/permissions</a>.</li>
        |</ul>
        |<p><a href="/start" style="display:inline-block;padding:.6rem 1rem;background:#1a73e8;color:#fff;border-radius:6px;text-decoration:none">Connect Google Calendar</a></p>""".stripMargin
    )
  )

  def page(status: Int, message: String, retry: Boolean = false): Response =
    Response(status, html(s"<p>${escape(message)}</p>" + (if retry then """<p><a href="/start">Try again</a></p>""" else "")))

  private def html(body: String): String =
    s"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
       |<title>calendar-blocker</title></head>
       |<body style="font-family:system-ui;max-width:36rem;margin:4rem auto;padding:0 1rem;line-height:1.5">
       |<h1>calendar-blocker</h1>$body</body></html>""".stripMargin

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

  /** Serves `onboard` on loopback only; a tunnel or reverse proxy provides TLS and the public hostname. */
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
                  Console[IO].errorln(s"onboarding error: ${e.getMessage}").as(page(500, "Something went wrong.", retry = true))
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

      for
        pool <- Resource.make(IO(Executors.newFixedThreadPool(4)))(p => IO.blocking(p.shutdown()))
        _ <- Resource.make(IO.blocking {
          val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, port), 0)
          server.setExecutor(pool)
          server.createContext("/", respond(_))
          server.start()
          server
        })(s => IO.blocking(s.stop(0)))
      yield ()
    }
