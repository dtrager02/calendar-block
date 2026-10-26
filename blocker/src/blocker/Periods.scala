package blocker

import java.time.{Duration, Instant, LocalDate, ZoneId}
import java.time.temporal.TemporalAdjusters

/** One period of a request that needs a block. `period` is the whole period; `range` is the part still schedulable. */
case class Occurrence(request: Request, periodKey: String, period: Interval, range: Interval):
  def tag: BlockTag = BlockTag(request.id, periodKey)

object Periods:

  def horizonEnd(s: Settings, now: Instant): Instant = now.plus(Duration.ofDays(s.horizonDays.toLong))

  def currentWeek(s: Settings, now: Instant): Interval =
    val ws = weekOf(s, LocalDate.ofInstant(now, s.zone))
    Interval(startOf(ws, s.zone), startOf(ws.plusWeeks(1), s.zone))

  /** Recurring occurrences are clipped to `[now, now + horizon]`; one-time occurrences only to `now`. */
  def expand(s: Settings, r: Request, now: Instant): List[Occurrence] =
    val today   = LocalDate.ofInstant(now, s.zone)
    val horizon = Interval(now, horizonEnd(s, now))

    def occurrence(key: String, from: LocalDate, until: LocalDate, clip: Interval) =
      val period = Interval(startOf(from, s.zone), startOf(until, s.zone))
      period.intersect(clip).map(Occurrence(r, key, period, _))

    def upToHorizon(first: LocalDate, next: LocalDate => LocalDate) =
      Iterator.iterate(first)(next).takeWhile(d => startOf(d, s.zone).isBefore(horizon.end)).toList

    r.recurrence match
      case Recurrence.Once =>
        r.between.flatMap((from, to) =>
          occurrence("once", from, to.plusDays(1), Interval(now, Instant.MAX))
        ).toList
      case Recurrence.Daily =>
        upToHorizon(today, _.plusDays(1)).flatMap(d => occurrence(d.toString, d, d.plusDays(1), horizon))
      case Recurrence.Weekly =>
        upToHorizon(weekOf(s, today), _.plusWeeks(1))
          .flatMap(w => occurrence(s"week-of-$w", w, w.plusWeeks(1), horizon))

  /** Each window instance of `r` overlapping `range`, as (nominal window, part inside `range`). */
  def windowInstances(s: Settings, r: Request, range: Interval): List[(Interval, Interval)] =
    val first = LocalDate.ofInstant(range.start, s.zone)
    val last  = LocalDate.ofInstant(range.end, s.zone)
    for
      d       <- Iterator.iterate(first)(_.plusDays(1)).takeWhile(!_.isAfter(last)).toList
      w       <- r.windows if w.days.contains(d.getDayOfWeek)
      nominal  = Interval(d.atTime(w.from).atZone(s.zone).toInstant, d.atTime(w.to).atZone(s.zone).toInstant)
      clipped <- nominal.intersect(range)
    yield (nominal, clipped)

  private def weekOf(s: Settings, date: LocalDate): LocalDate =
    date.`with`(TemporalAdjusters.previousOrSame(s.weekStart))

  private def startOf(d: LocalDate, zone: ZoneId): Instant = d.atStartOfDay(zone).toInstant
