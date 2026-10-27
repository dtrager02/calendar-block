package blocker

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

class OnboardTest extends CatsEffectSuite:

  private val alice = Account("google", "alice@x.com")

  private class FakeAuth(result: Either[String, Account]) extends AuthProvider:
    def authorizationUrl(state: String, redirectUri: String) = s"https://consent.example/?state=$state"
    def completeAuthorization(code: String, redirectUri: String) = IO.pure(result)

  private def setup(result: Either[String, Account] = Right(alice)) =
    for
      connected <- Ref.of[IO, List[Account]](Nil)
      ob        <- Onboard.create(FakeAuth(result), "https://calendar.example.com/callback", a => connected.update(a :: _))
    yield (ob, connected)

  private def start(ob: Onboard) =
    ob.handle(Onboard.StartPath, Map.empty).map { r =>
      assertEquals(r.status, 302)
      r.location.get.split("state=")(1)
    }

  test("the landing page offers to connect") {
    setup().flatMap((ob, _) => ob.handle("/", Map.empty)).map { r =>
      assertEquals(r.status, 200)
      assert(r.body.contains("""href="/start""""))
    }
  }

  test("anyone can start, and a completed callback connects the account the provider reports") {
    for
      (ob, connected) <- setup()
      state           <- start(ob)
      r               <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "code" -> "c"))
      accounts        <- connected.get
    yield
      assertEquals(r.status, 200)
      assert(r.body.contains("Connected as alice@x.com"))
      assertEquals(accounts, List(alice))
  }

  test("forged, missing and replayed states are rejected") {
    for
      (ob, connected) <- setup()
      forged          <- ob.handle(Onboard.CallbackPath, Map("state" -> "forged", "code" -> "c"))
      missing         <- ob.handle(Onboard.CallbackPath, Map("code" -> "c"))
      state           <- start(ob)
      _               <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "code" -> "c"))
      replay          <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "code" -> "c"))
      accounts        <- connected.get
    yield
      assertEquals(List(forged.status, missing.status, replay.status), List(403, 403, 403))
      assertEquals(accounts.size, 1)
  }

  test("provider rejection connects nothing and offers a retry, with the message escaped") {
    for
      (ob, connected) <- setup(Left("Missing <permission>."))
      state           <- start(ob)
      r               <- ob.handle(Onboard.CallbackPath, Map("state" -> state, "code" -> "c"))
      accounts        <- connected.get
    yield
      assertEquals(r.status, 400)
      assert(r.body.contains("Missing &lt;permission&gt;.") && r.body.contains("""href="/start""""))
      assert(accounts.isEmpty)
  }

  test("cancelling on the consent screen is reported") {
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
