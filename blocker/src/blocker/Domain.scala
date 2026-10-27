package blocker

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{DayOfWeek, Duration, Instant, LocalDate, LocalTime, ZoneId}

/** A person on a specific calendar provider. The scheduler identifies people by email. */
case class Account(provider: String, email: String):
  override def toString = s"$provider:$email"

object Account:
  val DefaultProvider = "google"

  /** Parses `email` or `provider:email`. */
  def parse(s: String): Account =
    s.trim.split(":", 2) match
      case Array(p, e) if !p.contains("@") => Account(p.toLowerCase, e.trim.toLowerCase)
      case _                               => Account(DefaultProvider, s.trim.toLowerCase)

case class Interval(start: Instant, end: Instant):
  def overlaps(o: Interval): Boolean = start.isBefore(o.end) && o.start.isBefore(end)
  def contains(o: Interval): Boolean = !o.start.isBefore(start) && !o.end.isAfter(end)
  def length: Duration = Duration.between(start, end)

  def intersect(o: Interval): Option[Interval] =
    val s = if start.isAfter(o.start) then start else o.start
    val e = if end.isBefore(o.end) then end else o.end
    Option.when(s.isBefore(e))(Interval(s, e))

/** Identifies which request and period an app-managed event satisfies. */
case class BlockTag(requestId: String, periodKey: String)

/** Opaque, provider-issued handle to one copy of an event on one account's calendar. */
case class BlockRef(account: Account, calendarId: String, eventId: String)

/** User-facing event content, declared per request. */
case class EventDetails(title: String, description: Option[String], location: Option[String]):
  /** Stable digest stored on each event, so changed details are detected without reading the content back. */
  def fingerprint: String =
    val bytes = MessageDigest.getInstance("SHA-256").digest(List(title, description.orNull, location.orNull).mkString("\u0000").getBytes(UTF_8))
    bytes.take(12).map(b => f"$b%02x").mkString

/** An app-managed event as read from a calendar. `attendees` includes the organizer. */
case class Block(
    ref: BlockRef,
    tag: BlockTag,
    organizer: Account,
    attendees: Set[String],
    interval: Interval,
    declinedBy: Set[String],
    detailsFingerprint: Option[String]
)

/** Brings an existing block to the planned time and the request's current details in one provider call. */
case class BlockUpdate(block: Block, interval: Interval, details: EventDetails)

/** What one account's calendars look like: unmanaged busy time, every tagged event seen, and the account's
 *  blocks calendar if it still exists.
 */
case class Snapshot(busy: List[Interval], blocks: List[Block], blocksCalendarId: Option[String])

/** `blocksOnly` reads just the account's blocks calendar: for people not in any request, whose other calendars
 *  are never needed but whose leftover blocks may still have to be deleted.
 */
case class SnapshotOptions(zone: ZoneId, allDayEventsBusy: Boolean, excludeCalendars: Set[String], blocksOnly: Boolean)

case class NewBlock(
    tag: BlockTag,
    details: EventDetails,
    interval: Interval,
    organizer: Account,
    guests: List[Account]
)

/** One write to a calendar. Providers receive all of a run's operations at once so they can batch them. */
enum BlockOp:
  case Create(calendarId: String, block: NewBlock)
  case Update(ref: BlockRef, interval: Interval, details: EventDetails)
  case Delete(ref: BlockRef)

  /** The account whose credentials perform this operation. */
  def account: Account = this match
    case Create(_, b)    => b.organizer
    case Update(r, _, _) => r.account
    case Delete(r)       => r.account

enum Recurrence:
  case Daily, Weekly, Once

enum Strategy:
  case PreferSoonest, MinimalGaps

case class TimeWindow(days: Set[DayOfWeek], from: LocalTime, to: LocalTime)

/** A declared block request. `attendees.head` is the organizer. `order` is its position in the config. */
case class Request(
    id: String,
    title: String,
    priority: Int,
    attendees: List[Account],
    recurrence: Recurrence,
    between: Option[(LocalDate, LocalDate)],
    duration: Duration,
    windows: List[TimeWindow],
    strategies: Map[Strategy, Double],
    order: Int,
    description: Option[String] = None,
    location: Option[String] = None
):
  def emails: Set[String]     = attendees.map(_.email).toSet
  def details: EventDetails = EventDetails(title, description, location)

case class Settings(zone: ZoneId, horizonDays: Int, weekStart: DayOfWeek, slotStep: Duration)

case class Placement(request: Request, tag: BlockTag, interval: Interval)

case class Plan(
    keep: List[Block],
    create: List[Placement],
    patch: List[BlockUpdate],
    delete: List[Block],
    unplaced: List[BlockTag],
    skippedRequests: List[String]
):
  def isNoop: Boolean = create.isEmpty && patch.isEmpty && delete.isEmpty
