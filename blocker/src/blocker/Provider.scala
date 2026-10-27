package blocker

import cats.effect.IO
import cats.syntax.all.*

/** Everything the app needs from a calendar backend. Implementations live in their own package (e.g. `google`). */
trait CalendarProvider:
  def id: String

  /** Returns the ID of the account's app-owned blocks calendar, creating it if missing. */
  def ensureBlocksCalendar(acct: Account): IO[String]

  /** Unmanaged busy time across all of the account's calendars, plus every tagged event seen.
   *  The account's blocks calendar must be read with no end bound, so blocks of requests removed from the
   *  config are found and deleted however far in the future they are.
   *  Raises [[AccountUnavailable]] when the account must be re-onboarded before it can be read.
   */
  def snapshot(acct: Account, range: Interval, opts: SnapshotOptions): IO[Snapshot]

  /** Performs every operation, batching as the backend allows. Fails if any operation failed; whatever did
   *  not apply is reconciled by the next run.
   */
  def applyAll(ops: List[BlockOp]): IO[Unit]

/** The account can't be used until it is onboarded again, e.g. its token is missing, expired or revoked. */
case class AccountUnavailable(account: Account, reason: String) extends Exception(s"$account: $reason")

/** Browser-based consent. Anyone can complete it; the provider determines who they are. */
trait AuthProvider:
  def authorizationUrl(state: String, redirectUri: String): String

  /** Exchanges `code`, identifies the account from the provider, and stores its credential. */
  def completeAuthorization(code: String, redirectUri: String): IO[Either[String, Account]]

case class Provider(calendar: CalendarProvider, auth: AuthProvider)

object Executor:

  /** Turns `plan` into operations and gives each provider all of its operations in one call. Fails fast. */
  def run(plan: Plan, calendars: Map[String, CalendarProvider], blocksCalendarIds: Map[Account, String]): IO[Unit] =
    for
      creates <- plan.create.traverse { p =>
        val organizer = p.request.attendees.head
        IO.fromOption(blocksCalendarIds.get(organizer))(IllegalStateException(s"no blocks calendar for $organizer"))
          .map(BlockOp.Create(_, NewBlock(p.tag, p.request.details, p.interval, organizer, p.request.attendees.tail)))
      }
      ops = plan.delete.map(b => BlockOp.Delete(b.ref)) ++
        plan.patch.map(u => BlockOp.Update(u.block.ref, u.interval, u.details)) ++
        creates
      _ <- ops.groupBy(_.account.provider).toList.traverse_ { (id, providerOps) =>
        IO.fromOption(calendars.get(id))(IllegalStateException(s"no provider '$id'")).flatMap(_.applyAll(providerOps))
      }
    yield ()
