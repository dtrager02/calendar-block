package blocker

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*

import java.nio.file.{Files, Path}
import java.nio.file.StandardCopyOption.{ATOMIC_MOVE, REPLACE_EXISTING}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

case class UserState(blocksCalendarId: Option[String]) derives Codec.AsObject

/** Private per-account files under `<home>/<provider>/`, one file per account so writers never overlap:
 *  - `users/<email>.json`: [[UserState]]; an account is onboarded iff this exists. Written by runs (under the run lock)
 *    and created by onboarding only when absent.
 *  - `tokens/<email>`: the provider's long-lived credential. Written only by onboarding.
 *  Every write replaces the whole file atomically, so readers never see a partial file.
 */
class TokenStore(home: Path):

  def accounts: IO[List[Account]] = IO.blocking {
    if !Files.isDirectory(home) then Nil
    else
      Files.list(home).iterator.asScala.toList.filter(Files.isDirectory(_)).sorted.flatMap { dir =>
        val users = dir.resolve("users")
        if !Files.isDirectory(users) then Nil
        else
          Files.list(users).iterator.asScala.toList.sorted.map(_.getFileName.toString).collect {
            case f if f.endsWith(".json") => Account(dir.getFileName.toString, f.stripSuffix(".json"))
          }
      }
  }

  def read(a: Account): IO[Option[UserState]] =
    readFile(file(a, "users", ".json")).flatMap(_.traverse(s => IO.fromEither(decode[UserState](s))))

  def write(a: Account, s: UserState): IO[Unit] = IO.blocking(TokenStore.writeAtomically(file(a, "users", ".json"), s.asJson.spaces2))

  /** Marks `a` as onboarded, leaving any existing state (such as its blocks calendar) untouched. */
  def onboard(a: Account): IO[Unit] = IO.blocking {
    val f = file(a, "users", ".json")
    if Files.notExists(f) then TokenStore.writeAtomically(f, UserState(None).asJson.spaces2)
  }

  def token(a: Account): IO[Option[String]] = readFile(file(a, "tokens", ""))

  def writeToken(a: Account, token: String): IO[Unit] = IO.blocking(TokenStore.writeAtomically(file(a, "tokens", ""), token))

  private def readFile(f: Path): IO[Option[String]] = IO.blocking(Option.when(Files.exists(f))(Files.readString(f)))

  private def file(a: Account, kind: String, suffix: String): Path =
    require(a.email.matches("[^/\\\\\\s]+@[^/\\\\\\s]+") && a.provider.matches("[a-z0-9-]+"), s"invalid account $a")
    home.resolve(a.provider).resolve(kind).resolve(a.email + suffix)

object TokenStore:
  private val OwnerOnly = PosixFilePermissions.fromString("rw-------")

  /** Creates `dir` and any missing parents with owner-only permissions. Existing directories are left alone. */
  def privateDirs(dir: Path): Unit =
    val abs = dir.toAbsolutePath
    if !Files.exists(abs) then
      privateDirs(abs.getParent)
      Files.createDirectory(abs, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

  /** Writes an owner-only temp file next to `f`, then renames it over `f` in one step. */
  private[blocker] def writeAtomically(f: Path, content: String): Unit =
    privateDirs(f.getParent)
    val tmp = Files.createTempFile(f.getParent, ".tmp-", "", PosixFilePermissions.asFileAttribute(OwnerOnly))
    Files.writeString(tmp, content)
    Files.move(tmp, f, ATOMIC_MOVE, REPLACE_EXISTING)
