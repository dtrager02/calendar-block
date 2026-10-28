package blocker

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Console, Dispatcher}
import cats.syntax.all.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetAddress, InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.concurrent.Executors

case class Response(status: Int, body: String, location: Option[String] = None, cookies: List[String] = Nil)
case class WebRequest(method: String, path: String, query: Map[String, String] = Map.empty,
    fields: Map[String, String] = Map.empty, cookies: Map[String, String] = Map.empty)
private case class SignInState(provider: String, issued: Instant)

final class Onboard private (
    providers: Map[String, (String, AuthProvider)],
    redirectUri: String,
    onConnected: Account => IO[Unit],
    sessions: WebSessions,
    events: EventWeb,
    states: Ref[IO, Map[String, SignInState]]
):
  import Onboard.*
  import WebHtml.*

  def handle(request: WebRequest): IO[Response] =
    sessions.get(request.cookies.getOrElse(SessionCookie, "")).flatMap { session =>
      if request.method == "GET" && request.path == "/" then
        IO.pure(if session.isDefined then redirect("/events") else landing)
      else if request.method == "GET" && request.path == StartPath then start(request)
      else if request.method == "GET" && request.path == CallbackPath then callback(request)
      else if !Set("GET", "POST")(request.method) then IO.pure(message(405, "Method not allowed."))
      else session match
        case None => IO.pure(redirect("/"))
        case Some(s) if request.method == "POST" && !request.fields.get("csrf").contains(s.csrf) => IO.pure(message(403, "Invalid or expired form. Reload the page."))
        case Some(s) if request.method == "POST" && request.path == "/signout" =>
          sessions.delete(request.cookies(SessionCookie)).as(redirect("/").copy(cookies = List(cookie(SessionCookie, "", 0))))
        case Some(s) => events.handle(request.method, request.path, request.query, request.fields, s)
    }

  private def landing: Response =
    val active = providers.toList.sortBy(_._1).map { case (id, (name, _)) =>
      s"<a class=\"signin\" href=\"/start?provider=${escape(id)}\">Sign in with ${escape(name)}</a>"
    }.mkString
    val placeholders = List("outlook" -> "Outlook", "apple" -> "Apple").filterNot(p => providers.contains(p._1)).map { (_, name) =>
      s"<button class=\"signin\" disabled>Sign in with $name — Coming soon</button>"
    }.mkString
    page(200, "<p>Find time for your activities and shared events.</p><p>Connect your calendar to view and propose event configurations.</p>" +
      active + placeholders + "<button class=\"signin\" disabled>More providers — Coming soon</button>" +
      "<p>External calendar events are reduced to start/end times in memory and discarded after each scheduler run. The app can manage only calendars it creates.</p>" +
      "<p>You can revoke Google access at <a href=\"https://myaccount.google.com/permissions\">myaccount.google.com/permissions</a>.</p>")

  private def start(request: WebRequest): IO[Response] =
    val id = request.query.getOrElse("provider", Account.DefaultProvider)
    providers.get(id) match
      case None => IO.pure(message(404, "This provider is not available yet."))
      case Some((_, auth)) =>
        for
          now <- IO.realTimeInstant
          state = WebSessions.randomToken()
          added <- states.modify { all =>
            val fresh = all.filter(_._2.issued.plus(StateLifetime).isAfter(now))
            if fresh.size >= 10000 then (fresh, false) else (fresh.updated(state, SignInState(id, now)), true)
          }
        yield if !added then message(429, "Too many sign-in attempts. Try again later.")
          else redirect(auth.authorizationUrl(state, redirectUri)).copy(cookies = List(cookie(StateCookie, state, StateLifetime.toSeconds)))

  private def callback(request: WebRequest): IO[Response] = for
    now <- IO.realTimeInstant
    state = request.query.getOrElse("state", "")
    entry <- if request.cookies.get(StateCookie).contains(state) then states.modify(all => (all - state, all.get(state))) else IO.pure(None)
    response <- entry.filter(_.issued.plus(StateLifetime).isAfter(now)) match
      case None => IO.pure(message(403, "This sign-in attempt is invalid or expired. Start again from the home page."))
      case Some(_) if request.query.contains("error") => IO.pure(message(400, "Sign-in was cancelled."))
      case Some(_) if !request.query.contains("code") => IO.pure(message(400, "Missing authorization code."))
      case Some(entry) => providers(entry.provider)._2.completeAuthorization(request.query("code"), redirectUri).flatMap {
        case Left(error) => IO.pure(message(400, error))
        case Right(account) if account.provider != entry.provider => IO.pure(message(400, "The provider returned an unexpected account."))
        case Right(account) => for
          _ <- onConnected(account)
          _ <- request.cookies.get(SessionCookie).traverse_(sessions.delete)
          token <- sessions.create(account)
        yield redirect("/events").copy(cookies = List(cookie(SessionCookie, token, 30L * 86400)))
      }
  yield response.copy(cookies = response.cookies :+ cookie(StateCookie, "", 0))

object Onboard:
  val StartPath = "/start"
  val CallbackPath = "/callback"
  val SessionCookie = "__Host-calendar-session"
  val StateCookie = "__Host-calendar-oauth"
  private val StateLifetime = Duration.ofMinutes(15)

  def create(providers: Map[String, (String, AuthProvider)], redirectUri: String, onConnected: Account => IO[Unit],
      sessions: WebSessions, events: EventWeb): IO[Onboard] =
    Ref.of[IO, Map[String, SignInState]](Map.empty).map(new Onboard(providers, redirectUri, onConnected, sessions, events, _))

  private def cookie(name: String, value: String, age: Long): String = s"$name=$value; Path=/; Max-Age=$age; Secure; HttpOnly; SameSite=Lax"
  private def redirect(location: String): Response = Response(303, "", Some(location))

  def parseQuery(raw: String): Map[String, String] =
    Option(raw).filter(_.nonEmpty).fold(Map.empty)(_.split("&").toList.map { kv =>
      kv.split("=", 2) match
        case Array(k, v) => URLDecoder.decode(k, UTF_8) -> URLDecoder.decode(v, UTF_8)
        case Array(k) => URLDecoder.decode(k, UTF_8) -> ""
    }.toMap)

  def serve(onboard: Onboard, port: Int): Resource[IO, Int] =
    Dispatcher.parallel[IO].flatMap { dispatcher =>
      def respond(ex: HttpExchange): Unit =
        val resp = dispatcher.unsafeRunSync(
          IO.blocking {
            val method = ex.getRequestMethod
            val bytes = ex.getRequestBody.readNBytes(131073)
            require(bytes.length <= 131072, "Form is too large.")
            require(method != "POST" || Option(ex.getRequestHeaders.getFirst("Content-Type")).exists(_.takeWhile(_ != ';') == "application/x-www-form-urlencoded"), "Unsupported form content type.")
            val cookies = Option(ex.getRequestHeaders.getFirst("Cookie")).toList.flatMap(_.split(";").toList).flatMap { part =>
              part.trim.split("=", 2) match
                case Array(k, v) => Some(k -> v)
                case _ => None
            }.toMap
            WebRequest(method, ex.getRequestURI.getPath, parseQuery(ex.getRequestURI.getRawQuery),
              if method == "POST" then parseQuery(new String(bytes, UTF_8)) else Map.empty, cookies)
          }.flatMap(onboard.handle).handleErrorWith {
            case e: IllegalArgumentException => IO.pure(WebHtml.message(400, Option(e.getMessage).getOrElse("Invalid request.")))
            case _ => Console[IO].errorln("onboarding request failed; check service configuration and GitHub connectivity").as(
              WebHtml.message(503, "Something went wrong. Please try again. Check GitHub before repeating a submission."))
          }
        )
        val bytes = resp.body.getBytes(UTF_8)
        val headers = ex.getResponseHeaders
        headers.set("Content-Type", "text/html; charset=utf-8")
        headers.set("Cache-Control", "no-store")
        headers.set("Referrer-Policy", "no-referrer")
        headers.set("X-Content-Type-Options", "nosniff")
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        resp.location.foreach(headers.set("Location", _))
        resp.cookies.foreach(headers.add("Set-Cookie", _))
        ex.sendResponseHeaders(resp.status, if bytes.isEmpty then -1 else bytes.length.toLong)
        if bytes.nonEmpty then ex.getResponseBody.write(bytes)
        ex.close()
      for
        pool <- Resource.make(IO(Executors.newFixedThreadPool(4)))(p => IO.blocking(p.shutdown()))
        server <- Resource.make(IO.blocking {
          val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, port), 0)
          server.setExecutor(pool)
          server.createContext("/", respond(_))
          server.start()
          server
        })(s => IO.blocking(s.stop(0)))
      yield server.getAddress.getPort
    }
