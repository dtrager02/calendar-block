package blocker

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.nio.file.Files

class WebEnvTest extends CatsEffectSuite:
  test("loads credentials directly from web.env with file values taking precedence") {
    for
      home <- IO.blocking(Files.createTempDirectory("web-env-test"))
      _ <- IO.blocking(Files.writeString(home.resolve("web.env"), """# Private server configuration
CALENDAR_BLOCKER_GITHUB_TOKEN=file-token
CALENDAR_BLOCKER_CONFIG_REPO=example/config

"""))
      settings <- WebEnv.load(home.resolve("web.env"), Map("CALENDAR_BLOCKER_GITHUB_TOKEN" -> "environment-token", "CALENDAR_BLOCKER_CONFIG_PATH" -> "custom.yaml"))
    yield
      assertEquals(settings("CALENDAR_BLOCKER_GITHUB_TOKEN"), "file-token")
      assertEquals(settings("CALENDAR_BLOCKER_CONFIG_REPO"), "example/config")
      assertEquals(settings("CALENDAR_BLOCKER_CONFIG_PATH"), "custom.yaml")
  }

  test("supports a custom home, quotes, comments, and literal values without shell expansion") {
    for
      home <- IO.blocking(Files.createTempDirectory("custom-web-home"))
      _ <- IO.blocking(Files.writeString(home.resolve("web.env"), """export CALENDAR_BLOCKER_GITHUB_TOKEN = "token=with#characters" # comment
CALENDAR_BLOCKER_CONFIG_BRANCH='feature/branch'
CALENDAR_BLOCKER_CONFIG_PATH = custom.yaml # comment
LITERAL=$(do-not-execute)-$HOME\n
""".replace("\n", "\r\n")))
      settings <- WebEnv.load(home.resolve("web.env"), Map.empty)
    yield
      assertEquals(settings("CALENDAR_BLOCKER_GITHUB_TOKEN"), "token=with#characters")
      assertEquals(settings("CALENDAR_BLOCKER_CONFIG_BRANCH"), "feature/branch")
      assertEquals(settings("CALENDAR_BLOCKER_CONFIG_PATH"), "custom.yaml")
      assertEquals(settings("LITERAL"), "$(do-not-execute)-$HOME\\n")
  }

  test("missing files preserve environment-only startup and explicit empty values override the environment") {
    for
      home <- IO.blocking(Files.createTempDirectory("missing-web-env"))
      fallback = Map("CALENDAR_BLOCKER_GITHUB_TOKEN" -> "environment-token")
      missing <- WebEnv.load(home.resolve("web.env"), fallback)
      _ <- IO.blocking(Files.writeString(home.resolve("web.env"), "CALENDAR_BLOCKER_GITHUB_TOKEN=\n"))
      empty <- WebEnv.load(home.resolve("web.env"), fallback)
    yield
      assertEquals(missing, fallback)
      assertEquals(empty("CALENDAR_BLOCKER_GITHUB_TOKEN"), "")
  }

  test("malformed files fail with a location without disclosing credentials") {
    List("secret-token-without-equals", "TOKEN='secret-token", "TOKEN=\"secret-token\" unexpected", "INVALID KEY=secret-token").traverse_ { line =>
      testMalformed(line)
    }
  }

  private def testMalformed(line: String): IO[Unit] = for
    home <- IO.blocking(Files.createTempDirectory("malformed-web-env"))
    _ <- IO.blocking(Files.writeString(home.resolve("web.env"), "# config\n" + line))
    result <- WebEnv.load(home.resolve("web.env"), Map.empty).attempt
  yield
    val error = result.swap.toOption.get.getMessage
    assert(error.contains("web.env:2:"))
    assert(!error.contains("secret-token"))
