package blocker

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class WebFlowTest extends CatsEffectSuite:
  private def field(body: String, key: String): String =
    ("name=\"" + key + "\" value=\"([^\"]*)\"").r.findFirstMatchIn(body).get.group(1)
  private def cookie(response: HttpResponse[String], name: String): String =
    import scala.jdk.CollectionConverters.*
    response.headers.allValues("set-cookie").asScala.find(_.startsWith(name + "=")).get.split(";", 2)(0)

  test("the HTTP server completes sign-in and create/edit/remove workflows using HTML forms only") {
    for
      dir <- IO.blocking(Files.createTempDirectory("web-flow"))
      snapshot <- Ref.of[IO, ConfigSnapshot](ConfigSnapshot("base", "timezone: UTC\nowner: person@example.com\nrequests: []\n"))
      proposals <- Ref.of[IO, List[Proposal]](Nil)
      repo = new ConfigRepository:
        def read = snapshot.get
        def submit(p: Proposal) = proposals.update(_ :+ p).as(PullRequest("https://github.com/test/config/pull/1"))
        def editUrl = "https://github.com/test/config"
      events <- EventWeb.create(repo)
      auth = new AuthProvider:
        def authorizationUrl(state: String, redirectUri: String) = "https://consent.example/?state=" + state
        def completeAuthorization(code: String, redirectUri: String) = IO.pure(Right(Account("google", "person@example.com")))
      ob <- Onboard.create(Map("google" -> ("Google", auth)), "https://calendar.example/callback", _ => IO.unit, WebSessions(dir), events)
      _ <- Onboard.serve(ob, 0).use { port =>
        val client = HttpClient.newHttpClient()
        def request(path: String, cookies: String = "", fields: Option[Map[String, String]] = None): IO[HttpResponse[String]] = IO.blocking {
          val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
          if cookies.nonEmpty then builder.header("Cookie", cookies)
          fields.foreach { values =>
            val body = values.toList.map((k, v) => URLEncoder.encode(k, UTF_8) + "=" + URLEncoder.encode(v, UTF_8)).mkString("&")
            builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
          }
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        for
          home <- request("/")
          terms <- request("/terms")
          privacy <- request("/privacy")
          start <- request("/start")
          state = cookie(start, Onboard.StateCookie)
          callback <- request("/callback?code=test&state=" + state.split("=", 2)(1), state)
          session = cookie(callback, Onboard.SessionCookie)
          page <- request("/events", session)
          signedInPrivacy <- request("/privacy", session)
          fields = EventForm.Defaults ++ Map("submission" -> field(page.body, "submission"), "csrf" -> field(page.body, "csrf"),
            "title" -> "Workout", "day0MON" -> "on", "from0" -> "09:00", "to0" -> "17:00")
          invalid <- request("/events/submit", session, Some(fields - "csrf"))
          added <- request("/events/submit", session, Some(fields + ("action" -> "add-window")))
          created <- request("/events/submit", session, Some(fields))
          receipt <- request(created.headers.firstValue("location").get(), session)
          changes <- proposals.get
          _ <- snapshot.set(ConfigSnapshot("merged", changes.head.yaml))
          approved <- request("/events", session)
          id = Config.parse(changes.head.yaml).toOption.get.requests.head.id
          edit <- request("/events/edit?id=" + id, session)
          editFields = EventForm.from(Config.parse(changes.head.yaml).toOption.get.requests.head) ++ Map(
            "submission" -> field(edit.body, "submission"), "csrf" -> field(edit.body, "csrf"), "title" -> "Exercise")
          edited <- request("/events/submit", session, Some(editFields))
          remove <- request("/events/remove?id=" + id, session)
          removed <- request("/events/submit", session, Some(Map("submission" -> field(remove.body, "submission"), "csrf" -> field(remove.body, "csrf"))))
          all <- proposals.get
          signedOut <- request("/signout", session, Some(Map("csrf" -> field(page.body, "csrf"))))
          denied <- request("/events", session)
        yield
          assert(home.body.contains("Sign in with Google"))
          List(home, page).foreach { response =>
            assert(response.body.contains("<footer>"))
            assert(response.body.contains("href=\"/terms\">Terms of Service</a>"))
            assert(response.body.contains("href=\"/privacy\">Privacy Policy</a>"))
          }
          List(terms, privacy, signedInPrivacy).foreach { response =>
            assertEquals(response.statusCode, 200)
            assertEquals(response.headers.firstValue("content-type").get(), "text/plain; charset=utf-8")
            assert(!response.body.contains("<html"))
          }
          assertEquals(signedInPrivacy.body, privacy.body)
          assertEquals(callback.statusCode, 303)
          assertEquals(invalid.statusCode, 403)
          assertEquals(field(added.body, "windowCount"), "2")
          assertEquals(created.statusCode, 303)
          assert(receipt.body.contains("Waiting for approval"))
          assert(approved.body.contains("<h3>Workout</h3>"))
          assertEquals(edited.statusCode, 303)
          assertEquals(removed.statusCode, 303)
          assertEquals(all.map(_.operation), List("create", "update", "remove"))
          assertEquals(signedOut.statusCode, 303)
          assertEquals(denied.headers.firstValue("location").get(), "/")
          assert(page.headers.firstValue("content-security-policy").get().contains("default-src 'none'"))
          assertEquals(page.headers.firstValue("cache-control").get(), "no-store, no-transform")
          assert(page.body.contains("Signed in as person@example.com"))
          assert(!page.body.contains("<script"))
      }
    yield ()
  }
