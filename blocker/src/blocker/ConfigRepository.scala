package blocker

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64

case class ConfigSnapshot(commit: String, yaml: String)
case class Proposal(id: String, base: String, operation: String, requestId: String, yaml: String, requester: Account)
case class PullRequest(url: String)
case class ConfigConflict(message: String) extends RuntimeException(message)
private case class GitHubFailure(status: Int) extends RuntimeException(
  s"GitHub returned $status. Check repository permissions, rate limits, and connectivity; retrying the same submission is safe.")

trait ConfigRepository:
  def read: IO[ConfigSnapshot]
  def submit(proposal: Proposal): IO[PullRequest]
  def editUrl: String

class GitHubConfigRepository(
    token: String,
    repository: String = "dtrager02/calendar-blocker-config",
    configPath: String = "config.yaml",
    branch: Option[String] = None,
    apiBase: String = "https://api.github.com",
    client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
) extends ConfigRepository:
  require(repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"), "invalid GitHub repository")
  require(configPath.split("/").forall(p => p.nonEmpty && p != "." && p != ".."), "invalid config path")
  private def encoded(s: String): String = java.net.URLEncoder.encode(s, UTF_8).replace("+", "%20")
  private val root = s"/repos/$repository"
  private val path = configPath.split("/").map(encoded).mkString("/")
  def editUrl: String = s"https://github.com/$repository"

  private def api(method: String, path: String, body: Option[Json] = None): IO[Json] = IO.blocking {
    val builder = HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $token").header("Accept", "application/vnd.github+json")
      .header("X-GitHub-Api-Version", "2022-11-28")
      .header("Content-Type", "application/json")
    val publisher = body.fold(HttpRequest.BodyPublishers.noBody())(j => HttpRequest.BodyPublishers.ofString(j.noSpaces))
    val response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    if response.statusCode < 200 || response.statusCode >= 300 then
      throw GitHubFailure(response.statusCode)
    parse(response.body).fold(_ => throw IllegalStateException("GitHub returned an invalid response."), identity)
  }
  private def string(json: Json, key: String): IO[String] =
    IO.fromEither(json.hcursor.get[String](key).leftMap(_ => IllegalStateException("GitHub returned an incomplete response.")))
  private def baseBranch: IO[String] = branch.fold(api("GET", root).flatMap(string(_, "default_branch")))(IO.pure)

  private def contents(sha: String): IO[String] =
    api("GET", s"$root/contents/$path?ref=${encoded(sha)}").flatMap(string(_, "content"))
      .map(text => new String(Base64.getMimeDecoder.decode(text), UTF_8))

  def read: IO[ConfigSnapshot] = for
    base <- baseBranch
    ref <- api("GET", s"$root/git/ref/heads/${encoded(base)}")
    sha <- IO.fromEither(ref.hcursor.downField("object").get[String]("sha"))
    yaml <- contents(sha)
  yield ConfigSnapshot(sha, yaml)

  def submit(p: Proposal): IO[PullRequest] =
    require(p.id.matches("[A-Za-z0-9_-]{43}"), "invalid submission identifier")
    val head = s"calendar-blocker/${p.id}"
    val attribution = s"Requested by ${p.requester}"
    val title = s"${p.operation.capitalize} event ${p.requestId}"
    val message = s"$title\n\n$attribution"
    def existingBranch: IO[Option[String]] =
      api("GET", s"$root/git/ref/heads/${encoded(head)}").flatMap(j => IO.fromEither(j.hcursor.downField("object").get[String]("sha"))).map(Some(_))
        .recoverWith { case GitHubFailure(404) => IO.pure(None) }
    def verifyBranch(sha: String): IO[Unit] = for
      commit <- api("GET", s"$root/git/commits/$sha")
      yaml <- contents(sha)
      parents = commit.hcursor.get[List[Json]]("parents").getOrElse(Nil).flatMap(_.hcursor.get[String]("sha").toOption)
      _ <- IO.raiseUnless(yaml == p.yaml && parents == List(p.base) && commit.hcursor.get[String]("message").contains(message))(
        IllegalStateException("This submission branch differs from the proposed change. Start a new proposal."))
    yield ()
    def existing: IO[Option[PullRequest]] = api("GET", s"$root/pulls?state=all&head=${encoded(repository.split("/")(0) + ":" + head)}").flatMap { j =>
      j.asArray.flatMap(_.headOption).traverse(r => string(r, "html_url").map(PullRequest(_)))
    }
    def createBranch: IO[Unit] = for
      current <- read
      _ <- IO.raiseWhen(current.commit != p.base)(ConfigConflict("The approved config changed while this form was open. Reload the dashboard and review your change before submitting."))
      original <- api("GET", s"$root/git/commits/${p.base}")
      tree <- IO.fromEither(original.hcursor.downField("tree").get[String]("sha"))
      newTree <- api("POST", s"$root/git/trees", Some(Json.obj("base_tree" -> tree.asJson, "tree" -> Json.arr(Json.obj(
        "path" -> configPath.asJson, "mode" -> "100644".asJson, "type" -> "blob".asJson, "content" -> p.yaml.asJson
      )))))
      treeSha <- string(newTree, "sha")
      commit <- api("POST", s"$root/git/commits", Some(Json.obj("message" -> message.asJson,
        "tree" -> treeSha.asJson, "parents" -> Json.arr(p.base.asJson))))
      commitSha <- string(commit, "sha")
      _ <- api("POST", s"$root/git/refs", Some(Json.obj("ref" -> s"refs/heads/$head".asJson, "sha" -> commitSha.asJson))).void
        .handleErrorWith { error =>
          existingBranch.flatMap(_.fold(IO.raiseError[Unit](error))(verifyBranch))
        }
    yield ()
    def create: IO[PullRequest] = for
      base <- baseBranch
      existing <- existingBranch
      _ <- existing.fold(createBranch)(verifyBranch)
      pr <- api("POST", s"$root/pulls", Some(Json.obj("title" -> title.asJson, "head" -> head.asJson, "base" -> base.asJson,
        "body" -> s"$attribution\n\nOperation: ${p.operation}\nRequest: `${p.requestId}`\nSubmission: `${p.id}`\n\nRequires manual approval. Merging applies this configuration on the next scheduler run.".asJson)))
      url <- string(pr, "html_url")
    yield PullRequest(url)
    existing.flatMap(_.fold(create)(IO.pure)).handleErrorWith(e => existing.flatMap(_.fold(IO.raiseError[PullRequest](e))(IO.pure)))
