package blocker

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.nio.file.Files

class OnboardTest extends CatsEffectSuite:
  private val alice = Account("google", "alice@x.com")
  private class FakeAuth(result: Either[String, Account]) extends AuthProvider:
    def authorizationUrl(state: String, redirectUri: String) = s"https://consent.example/?state=$state"
    def completeAuthorization(code: String, redirectUri: String) = IO.pure(result)

  private def setup(result: Either[String, Account] = Right(alice)) = for
    connected <- Ref.of[IO, List[Account]](Nil)
    dir <- IO.blocking(Files.createTempDirectory("web-onboard-test"))
    store = WebSessions(dir)
    events <- EventWeb.create(new ConfigRepository:
      def read = IO.pure(ConfigSnapshot("base", "timezone: UTC\nowner: alice@x.com\nrequests: []\n"))
      def submit(p: Proposal) = IO.pure(PullRequest("https://github.com/example/config/pull/1"))
      def editUrl = "https://github.com/example/config"
    )
    ob <- Onboard.create(Map("google" -> ("Google", FakeAuth(result))), "https://calendar.example.com/callback",
      a => connected.update(a :: _), store, events)
  yield (ob, connected, store)

  private def cookie(response: Response, name: String): String = response.cookies.find(_.startsWith(name + "=")).get.split(";", 2)(0).split("=", 2)(1)
  private def start(ob: Onboard) = ob.handle(WebRequest("GET", "/start")).map(r => cookie(r, Onboard.StateCookie))
  private def callback(ob: Onboard, state: String, query: Map[String, String] = Map("code" -> "c")) =
    ob.handle(WebRequest("GET", "/callback", query + ("state" -> state), cookies = Map(Onboard.StateCookie -> state)))

  test("landing page enables registered providers and disables placeholders") {
    setup().flatMap((ob, _, _) => ob.handle(WebRequest("GET", "/"))).map { r =>
      assertEquals(r.status, 200)
      assert(r.body.contains("Sign in with Google"))
      assert(r.body.contains("disabled>Sign in with Outlook"))
      assert(r.body.contains("disabled>Sign in with Apple"))
      assert(!r.body.contains("<script"))
    }
  }

  test("OAuth callback stores the verified identity, sets a secure session, and returning users see events") {
    for
      (ob, connected, store) <- setup()
      state <- start(ob)
      response <- callback(ob, state)
      accounts <- connected.get
      token = cookie(response, Onboard.SessionCookie)
      session <- store.get(token)
      home <- ob.handle(WebRequest("GET", "/", cookies = Map(Onboard.SessionCookie -> token)))
      dashboard <- ob.handle(WebRequest("GET", "/events", cookies = Map(Onboard.SessionCookie -> token)))
    yield
      assertEquals(response.status, 303)
      assertEquals(response.location, Some("/events"))
      assert(response.cookies.head.contains("Secure; HttpOnly; SameSite=Lax"))
      assertEquals(accounts, List(alice))
      assertEquals(session.map(_.account), Some(alice))
      assertEquals(home.location, Some("/events"))
      assert(dashboard.body.contains("Create an event"))
  }

  test("legal documents are available as plaintext without signing in or connecting an account") {
    for
      (ob, connected, _) <- setup()
      terms <- ob.handle(WebRequest("GET", "/terms"))
      privacy <- ob.handle(WebRequest("GET", "/privacy"))
      accounts <- connected.get
    yield
      List(terms, privacy).foreach { response =>
        assertEquals(response.status, 200)
        assertEquals(response.contentType, "text/plain; charset=utf-8")
        assertEquals(response.location, None)
        assertEquals(response.cookies, Nil)
        assert(response.body.contains("Daniel Trager"))
        assert(response.body.contains("dtrager02@gmail.com"))
        assert(response.body.contains("California, United States"))
        assert(!response.body.contains("<html"))
      }
      assert(terms.body.startsWith("CALENDAR-BLOCKER TERMS OF SERVICE"))
      assert(privacy.body.startsWith("CALENDAR-BLOCKER PRIVACY POLICY"))
      assert(privacy.body.contains("The default configuration repository is public"))
      assertEquals(accounts, Nil)
  }

  test("missing browser binding, forged state, and replay are rejected") {
    for
      (ob, connected, _) <- setup()
      state <- start(ob)
      unbound <- ob.handle(WebRequest("GET", "/callback", Map("state" -> state, "code" -> "c")))
      forged <- callback(ob, "forged")
      valid <- callback(ob, state)
      replay <- callback(ob, state)
      accounts <- connected.get
    yield
      assertEquals(List(unbound.status, forged.status, replay.status), List(403, 403, 403))
      assertEquals(valid.status, 303)
      assertEquals(accounts, List(alice))
  }

  test("rejection and cancellation connect nothing and escape provider errors") {
    for
      (ob, connected, _) <- setup(Left("Missing <permission>."))
      state <- start(ob)
      rejected <- callback(ob, state)
      next <- start(ob)
      cancelled <- callback(ob, next, Map("error" -> "access_denied"))
      accounts <- connected.get
    yield
      assertEquals(rejected.status, 400)
      assert(rejected.body.contains("Missing &lt;permission&gt;."))
      assertEquals(cancelled.status, 400)
      assertEquals(accounts, Nil)
  }

  test("sign-out needs CSRF and invalidates the session without disconnecting the account") {
    for
      (ob, connected, store) <- setup()
      state <- start(ob)
      response <- callback(ob, state)
      token = cookie(response, Onboard.SessionCookie)
      session <- store.get(token).map(_.get)
      invalid <- ob.handle(WebRequest("POST", "/signout", cookies = Map(Onboard.SessionCookie -> token)))
      stillThere <- store.get(token)
      signedOut <- ob.handle(WebRequest("POST", "/signout", fields = Map("csrf" -> session.csrf), cookies = Map(Onboard.SessionCookie -> token)))
      gone <- store.get(token)
      accounts <- connected.get
      unauth <- ob.handle(WebRequest("GET", "/events"))
    yield
      assertEquals(invalid.status, 403)
      assert(stillThere.isDefined)
      assertEquals(signedOut.location, Some("/"))
      assertEquals(gone, None)
      assertEquals(accounts, List(alice))
      assertEquals(unauth.location, Some("/"))
  }

  test("provider selection is generic and disabled provider routes cannot authenticate") {
    for
      (ob, _, _) <- setup()
      unsupported <- ob.handle(WebRequest("GET", "/start", Map("provider" -> "apple")))
    yield assertEquals(unsupported.status, 404)
  }

  test("registering another provider exposes its button and dispatches its callback") {
    for
      dir <- IO.blocking(Files.createTempDirectory("provider-registry-test"))
      store = WebSessions(dir)
      events <- EventWeb.create(new ConfigRepository:
        def read = IO.raiseError(new UnsupportedOperationException)
        def submit(p: Proposal) = IO.raiseError(new UnsupportedOperationException)
        def editUrl = "https://github.com/test/config"
      )
      outlook = Account("outlook", "person@example.com")
      ob <- Onboard.create(Map("google" -> ("Google", FakeAuth(Right(alice))), "outlook" -> ("Outlook", FakeAuth(Right(outlook)))),
        "https://calendar.example/callback", _ => IO.unit, store, events)
      home <- ob.handle(WebRequest("GET", "/"))
      started <- ob.handle(WebRequest("GET", "/start", Map("provider" -> "outlook")))
      signedIn <- callback(ob, cookie(started, Onboard.StateCookie))
      session <- store.get(cookie(signedIn, Onboard.SessionCookie))
    yield
      assert(home.body.contains("href=\"/start?provider=outlook\""))
      assert(!home.body.contains("disabled>Sign in with Outlook"))
      assertEquals(session.map(_.account), Some(outlook))
  }

  test("parseQuery decodes form fields") {
    assertEquals(Onboard.parseQuery("a=1&b=x%2By&c"), Map("a" -> "1", "b" -> "x+y", "c" -> ""))
    assertEquals(Onboard.parseQuery(null), Map.empty)
  }
