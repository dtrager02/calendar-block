package blocker

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

/** An app-managed event as read from a calendar. `attendees` includes the organizer. */
case class Block(
    ref: BlockRef,
    tag: BlockTag,
    organizer: Account,
    attendees: Set[String],
    interval: Interval,
    declinedBy: Set[String]
)

/** What one account's calendars look like: unmanaged busy time plus every tagged event seen. */
case class Snapshot(busy: List[Interval], blocks: List[Block])

case class SnapshotOptions(zone: ZoneId, allDayEventsBusy: Boolean, excludeCalendars: Set[String])

case class NewBlock(tag: BlockTag, title: String, interval: Interval, organizer: Account, guests: List[Account])

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
    order: Int
):
  def emails: Set[String] = attendees.map(_.email).toSet

case class Settings(zone: ZoneId, horizonDays: Int, weekStart: DayOfWeek, slotStep: Duration)

case class Placement(request: Request, tag: BlockTag, interval: Interval)

case class Plan(
    keep: List[Block],
    create: List[Placement],
    patch: List[(Block, Interval)],
    delete: List[Block],
    unplaced: List[BlockTag],
    skippedRequests: List[String]
):
  def isNoop: Boolean = create.isEmpty && patch.isEmpty && delete.isEmpty
