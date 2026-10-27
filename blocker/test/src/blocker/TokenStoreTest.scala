package blocker

import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

class TokenStoreTest extends CatsEffectSuite:

  private val home  = FunFixture[Path](_ => Files.createTempDirectory("tokens"), d => Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete))
  private val alice = Account("google", "alice@x.com")
  private val bob   = Account("google", "bob@x.com")

  private def perms(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))
  private def files(p: Path) = Files.walk(p).iterator.asScala.filter(Files.isRegularFile(_)).toList

  home.test("each account's token is its own owner-only file, replaced without leaving temp files") { dir =>
    val store = TokenStore(dir)
    for
      _ <- store.writeToken(alice, "a1")
      _ <- store.writeToken(bob, "b1")
      _ <- store.writeToken(alice, "a2")
      a <- store.token(alice)
      b <- store.token(bob)
    yield
      assertEquals((a, b), (Some("a2"), Some("b1")))
      assertEquals(files(dir).map(_.getFileName.toString).sorted, List("alice@x.com", "bob@x.com"))
      assert(files(dir).forall(perms(_) == "rw-------"))
      assertEquals(perms(dir.resolve("google/tokens")), "rwx------")
  }

  home.test("connecting marks an account onboarded without clobbering its existing state") { dir =>
    val store = TokenStore(dir)
    for
      _       <- store.writeToken(bob, "only a token")
      _       <- store.onboard(alice)
      fresh   <- store.read(alice)
      _       <- store.write(alice, UserState(Some("cal-1")))
      _       <- store.onboard(alice)
      kept    <- store.read(alice)
      listed  <- store.accounts
    yield
      assertEquals(fresh, Some(UserState(None)))
      assertEquals(kept, Some(UserState(Some("cal-1"))))
      assertEquals(listed, List(alice), "an account is listed only once onboarding has created its state")
  }

  home.test("account emails can't escape the store directory") { dir =>
    interceptIO[IllegalArgumentException](TokenStore(dir).writeToken(Account("google", "../../evil@x.com"), "t"))
  }
