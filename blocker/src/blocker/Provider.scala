package blocker

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*

/** Everything the app needs from a calendar backend. Implementations live in their own package (e.g. `google`). */
trait CalendarProvider:
  def id: String

  /** Returns the ID of the account's app-owned blocks calendar, creating it if missing. */
  def ensureBlocksCalendar(acct: Account): IO[String]

  /** Unmanaged busy time across all of the account's calendars, plus every tagged event seen.
   *  The account's blocks calendar must be read with no end bound, so blocks of requests removed from the
   *  config are found and deleted however far in the future they are.
   */
  def snapshot(acct: Account, range: Interval, opts: SnapshotOptions): IO[Snapshot]

  /** Creates `b` on the organizer's blocks calendar and invites its guests. */
  def create(calendarId: String, b: NewBlock): IO[BlockRef]

  /** Sets the event's time and details. The provider must also store `details.fingerprint` for the next read. */
  def update(ref: BlockRef, interval: Interval, details: EventDetails): IO[Unit]
  def delete(ref: BlockRef): IO[Unit]

/** Browser-based consent for one account. */
trait AuthProvider:
  def authorizationUrl(state: String, loginHint: String, redirectUri: String): String

  /** Exchanges `code`, verifies it belongs to `expectedEmail`, and stores the token only if it does. */
  def completeAuthorization(code: String, redirectUri: String, expectedEmail: String): IO[Either[String, Account]]

case class Provider(calendar: CalendarProvider, auth: AuthProvider)

object Executor:

  /** Applies `plan`, routing each operation to the provider of the account that owns the event.
   *  Failures are logged and skipped. Returns the number of failed operations.
   */
  def run(plan: Plan, calendars: Map[String, CalendarProvider], blocksCalendarIds: Map[Account, String]): IO[Int] =
    def provider(a: Account) =
      IO.fromOption(calendars.get(a.provider))(IllegalStateException(s"no provider '${a.provider}'"))

    def attempt(desc: String)(op: IO[Unit]): IO[Int] =
      op.as(0).handleErrorWith(e => Console[IO].errorln(s"FAILED $desc: ${e.getMessage}").as(1))

    val deletes = plan.delete.map(b => attempt(s"delete ${b.tag}")(provider(b.ref.account).flatMap(_.delete(b.ref))))
    val patches = plan.patch.map(u =>
      attempt(s"update ${u.block.tag}")(provider(u.block.ref.account).flatMap(_.update(u.block.ref, u.interval, u.details)))
    )
    val creates = plan.create.map { p =>
      val organizer = p.request.attendees.head
      attempt(s"create ${p.tag}") {
        for
          calendarId <- IO.fromOption(blocksCalendarIds.get(organizer))(IllegalStateException(s"no blocks calendar for $organizer"))
          cal        <- provider(organizer)
          _          <- cal.create(calendarId, NewBlock(p.tag, p.request.details, p.interval, organizer, p.request.attendees.tail))
        yield ()
      }
    }
    (deletes ++ patches ++ creates).sequence.map(_.sum)
