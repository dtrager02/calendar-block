package blocker

import cats.effect.IO
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

case class UserState(blocksCalendarId: Option[String]) derives Codec.AsObject

/** Per-account state under `<home>/<provider>/users/<email>.json`. An account is onboarded iff its file exists.
 *  Providers keep their own token files under `providerDir`.
 */
class TokenStore(home: Path):

  def providerDir(provider: String): Path = home.resolve(provider)

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

  def read(a: Account): IO[Option[UserState]] = IO.blocking {
    val f = stateFile(a)
    Option.when(Files.exists(f))(f)
  }.flatMap {
    case None    => IO.pure(None)
    case Some(f) => IO.blocking(Files.readString(f)).flatMap(s => IO.fromEither(decode[UserState](s))).map(Some(_))
  }

  def write(a: Account, s: UserState): IO[Unit] = IO.blocking {
    val f = stateFile(a)
    TokenStore.privateDirs(f.getParent)
    Files.writeString(f, s.asJson.spaces2)
    Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"))
  }

  private def stateFile(a: Account): Path =
    require(a.email.matches("[^/\\\\\\s]+@[^/\\\\\\s]+") && a.provider.matches("[a-z0-9-]+"), s"invalid account $a")
    providerDir(a.provider).resolve("users").resolve(s"${a.email}.json")

object TokenStore:
  /** Creates `dir` and any missing parents with owner-only permissions. Existing directories are left alone. */
  def privateDirs(dir: Path): Unit =
    val abs = dir.toAbsolutePath
    if !Files.exists(abs) then
      privateDirs(abs.getParent)
      Files.createDirectory(abs, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

  /** Restricts every regular file directly inside `dir` to owner read/write. */
  def privateFiles(dir: Path): Unit =
    Files.list(dir).iterator.asScala.filter(Files.isRegularFile(_)).foreach(
      Files.setPosixFilePermissions(_, PosixFilePermissions.fromString("rw-------"))
    )
