package blocker

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

class OnboardTest extends CatsEffectSuite:

  private val invite = "secret-invite"

  private class FakeAuth(result: Either[String, Account]) extends AuthProvider:
    def authorizationUrl(state: String, loginHint: String, redirectUri: String) =
      s"https://consent.example/?state=$state&hint=$loginHint"
    def completeAuthorization(code: String, redirectUri: String, expectedEmail: String) = IO.pure(result)

  private def setup(result: Either[String, Account] = Right(Account("google", "b@x.com"))) =
    for
      stored <- Ref.of[IO, List[Account]](Nil)
      ob     <- Onboard.create(FakeAuth(result), "b@x.com", "https://calendar.example.com/callback", invite, a => stored.update(a :: _))
    yield (ob, stored)

  private def start(ob: Onboard) =
    ob.handle(Onboard.StartPath, Map("invite" -> invite)).map { r =>
      assertEquals(r.status, 302)
      r.location.get.split("state=")(1).split("&")(0)
    }

  test("valid invite redirects to consent, and the callback stores the account") {
    for
      (ob, stored) <- setup()
      state        <- start(ob)
      r            <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "code" -> "c"))
      accounts     <- stored.get
      done         <- ob.done.tryGet
      again        <- ob.handle(Onboard.StartPath, Map("invite" -> invite))
    yield
      assertEquals(r.status, 200)
      assertEquals(accounts, List(Account("google", "b@x.com")))
      assertEquals(done, Some(Account("google", "b@x.com")))
      assertEquals(again.status, 410)
  }

  test("bad invite and forged or replayed state are rejected") {
    for
      (ob, stored) <- setup()
      bad          <- ob.handle(Onboard.StartPath, Map("invite" -> "guess"))
      missing      <- ob.handle(Onboard.StartPath, Map.empty)
      forged       <- ob.handle(Onboard.CallbackPath, Map("state" -> "forged", "code" -> "c"))
      state        <- start(ob)
      _            <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "code" -> "c"))
      replay       <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "code" -> "c"))
    yield
      assertEquals(List(bad.status, missing.status, forged.status, replay.status), List(403, 403, 403, 403))
  }

  test("provider rejection (wrong account, missing scopes) stores nothing and allows retry") {
    for
      (ob, stored) <- setup(Left("You signed in as <evil>@x.com, but this invite is for b@x.com."))
      state        <- start(ob)
      r            <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "code" -> "c"))
      accounts     <- stored.get
      retry        <- ob.handle(Onboard.StartPath, Map("invite" -> invite))
    yield
      assertEquals(r.status, 400)
      assert(r.body.contains("&lt;evil&gt;"), "messages are HTML-escaped")
      assert(accounts.isEmpty)
      assertEquals(retry.status, 302)
  }

  test("user cancelling on the consent screen is reported") {
    for
      (ob, _) <- setup()
      state   <- start(ob)
      r       <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "error" -> "access_denied"))
    yield assertEquals(r.status, 400)
  }

  test("parseQuery decodes parameters") {
    assertEquals(Onboard.parseQuery("a=1&b=x%2By&c"), Map("a" -> "1", "b" -> "x+y", "c" -> ""))
    assertEquals(Onboard.parseQuery(null), Map.empty)
  }
