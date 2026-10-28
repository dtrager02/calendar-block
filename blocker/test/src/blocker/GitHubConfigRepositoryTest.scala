package blocker

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.collection.mutable.ListBuffer

class GitHubConfigRepositoryTest extends CatsEffectSuite:
  private class FakeGitHub:
    val calls = ListBuffer.empty[(String, String, Json)]
    var status = 200
    var base = "base"
    var pr = false
    var ref = false
    var failPullOnce = false
    var failPullAfterCreation = false
    var failedRefAfterCreation = false
    val yaml = "timezone: UTC\nowner: user@x.com\nrequests: []\n"
    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", ex => {
      val method = ex.getRequestMethod
      val path = ex.getRequestURI.getPath
      val body = parse(new String(ex.getRequestBody.readAllBytes(), UTF_8)).getOrElse(Json.Null)
      calls.synchronized(calls += ((method, path, body)))
      var code = status
      val response = if status != 200 then Json.obj("message" -> "secret response must not leak".asJson)
      else if path.endsWith("/pulls") && method == "GET" then
        if pr then Json.arr(Json.obj("html_url" -> "https://github.com/test/config/pull/1".asJson)) else Json.arr()
      else if path.endsWith("/pulls") && method == "POST" then
        if failPullOnce then
          failPullOnce = false
          code = 500
          Json.obj()
        else
          pr = true
          if failPullAfterCreation then code = 500
          Json.obj("html_url" -> "https://github.com/test/config/pull/1".asJson)
      else if path.endsWith("/git/ref/heads/main") then Json.obj("object" -> Json.obj("sha" -> base.asJson))
      else if path.contains("/git/ref/heads/calendar-blocker/") then
        if !ref then code = 404
        Json.obj("object" -> Json.obj("sha" -> "commit".asJson))
      else if path.contains("/contents/") then
        val value = if ex.getRequestURI.getQuery.contains("ref=commit") then "updated config" else yaml
        Json.obj("content" -> Base64.getEncoder.encodeToString(value.getBytes(UTF_8)).asJson)
      else if path.endsWith("/git/commits/base") then Json.obj("tree" -> Json.obj("sha" -> "tree".asJson))
      else if path.endsWith("/git/commits/commit") then Json.obj("tree" -> Json.obj("sha" -> "newtree".asJson), "parents" -> Json.arr(Json.obj("sha" -> "base".asJson)), "message" -> calls.find(c => c._1 == "POST" && c._2.endsWith("/git/commits")).get._3.hcursor.get[String]("message").toOption.get.asJson)
      else if path.endsWith("/git/trees") then Json.obj("sha" -> "newtree".asJson)
      else if path.endsWith("/git/commits") then Json.obj("sha" -> "commit".asJson)
      else if path.endsWith("/git/refs") then
        if ref then code = 422
        ref = true
        if failedRefAfterCreation then code = 500
        Json.obj("ref" -> "refs/heads/feature".asJson)
      else Json.obj("default_branch" -> "main".asJson)
      val bytes = response.noSpaces.getBytes(UTF_8)
      ex.sendResponseHeaders(code, bytes.length)
      ex.getResponseBody.write(bytes)
      ex.close()
    })
    def start(): Unit = server.start()
    def stop(): Unit = server.stop(0)
    def repository = GitHubConfigRepository("test-token", "test/config", apiBase = s"http://127.0.0.1:${server.getAddress.getPort}")

  private def server = Resource.make(IO.blocking { val s = new FakeGitHub; s.start(); s })(s => IO.blocking(s.stop()))
  private def proposal = Proposal("x" * 43, "base", "create", "event-id", "updated config", Account("google", "user@x.com"))

  test("reads the config at a pinned commit and proposes only the configured file with verified attribution") {
    server.use { fake =>
      for
        snapshot <- fake.repository.read
        pr <- fake.repository.submit(proposal)
        retried <- fake.repository.submit(proposal)
      yield {
      assertEquals(snapshot, ConfigSnapshot("base", fake.yaml))
      assertEquals(pr, retried)
      assertEquals(fake.calls.count(c => c._1 == "POST" && c._2.endsWith("/pulls")), 1)
      val commit = fake.calls.find(c => c._1 == "POST" && c._2.endsWith("/git/commits")).get._3
      assert(commit.hcursor.get[String]("message").toOption.get.contains("google:user@x.com"))
      assert(!commit.hcursor.downField("author").succeeded)
      val tree = fake.calls.find(c => c._1 == "POST" && c._2.endsWith("/git/trees")).get._3
      assertEquals(tree.hcursor.downField("tree").as[List[Json]].toOption.get.size, 1)
      assertEquals(tree.hcursor.downField("tree").downArray.get[String]("path"), Right("config.yaml"))
      }
    }
  }
  test("stale forms cannot create branches") {
    server.use { fake =>
      fake.base = "new-base"
      fake.repository.submit(proposal).attempt.map { result =>
        assert(result.isLeft)
        assert(!fake.calls.exists(_._1 == "POST"))
      }
    }
  }
  test("a failed pull creation resumes from the deterministic branch without duplicating a PR") {
    server.use { fake =>
      for
        _ <- IO { fake.failPullOnce = true }
        failed <- fake.repository.submit(proposal).attempt
        retried <- fake.repository.submit(proposal)
      yield {
      assert(failed.isLeft)
      assertEquals(retried.url, "https://github.com/test/config/pull/1")
      assert(fake.ref)
      assert(fake.pr)
      }
    }
  }
  test("lost responses after ref and PR creation recover safely") {
    server.use { fake =>
      fake.failedRefAfterCreation = true
      fake.failPullAfterCreation = true
      fake.repository.submit(proposal).map(pr => assertEquals(pr.url, "https://github.com/test/config/pull/1"))
    }
  }
  test("permission and rate-limit failures do not disclose response bodies or tokens") {
    server.use { fake =>
      List(403, 429).traverse_ { code =>
        (IO { fake.status = code } >> fake.repository.read.attempt).map { result =>
          val message = result.swap.toOption.get.getMessage
          assert(message.contains(code.toString))
          assert(!message.contains("test-token"))
          assert(!message.contains("secret response"))
        }
      }
    }
  }
