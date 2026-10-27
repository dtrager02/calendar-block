package blocker

import java.time.Instant
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** Provider-agnostic placement of block requests onto people's timelines. Pure: no IO. */
object Scheduler:

  /** The time range snapshots must cover so every block relevant to `requests` is seen. */
  def snapshotRange(s: Settings, requests: List[Request], now: Instant): Interval =
    val occurrences = requests.flatMap(Periods.expand(s, _, now))
    Interval(
      (Periods.currentWeek(s, now).start :: occurrences.map(_.period.start)).min,
      (Periods.horizonEnd(s, now) :: occurrences.map(_.period.end)).max
    )

  /** @param available emails whose calendars were read successfully
   *  @param busy      unmanaged busy time per email; these are never moved and outrank every request
   *  @param blocks    every tagged event seen on any available calendar
   */
  def plan(
      s: Settings,
      requests: List[Request],
      now: Instant,
      available: Set[String],
      busy: Map[String, List[Interval]],
      blocks: List[Block]
  ): Plan =
    // Guests' copies of joint events only matter as "not hard busy"; the organizer's copy is the block.
    val byTag                = blocks.filter(b => b.ref.account == b.organizer).map(b => b.tag -> b).toMap
    val (active, skipped)    = requests.partition(_.emails.subsetOf(available))
    val activeById           = active.map(r => r.id -> r).toMap
    val skippedIds           = skipped.map(_.id).toSet
    val occurrences          = active.flatMap(Periods.expand(s, _, now))
    val occurrenceByTag      = occurrences.map(o => o.tag -> o).toMap

    val hard      = mutable.Map.from(available.map(e => e -> busy.getOrElse(e, Nil)))
    val satisfied = mutable.Set.empty[BlockTag]
    val keep      = ListBuffer.empty[Block]
    val delete    = ListBuffer.empty[Block]
    val future    = mutable.Map.empty[BlockTag, Block]

    def makeHard(b: Block): Unit = b.attendees.foreach(e => hard.updateWith(e)(_.map(b.interval :: _)))

    byTag.values.foreach { b =>
      if b.interval.start.isBefore(now) then
        makeHard(b); satisfied += b.tag; keep += b
      else if skippedIds(b.tag.requestId) then
        makeHard(b); keep += b
      else if !activeById.contains(b.tag.requestId) then delete += b
      else if b.declinedBy.nonEmpty then
        delete += b; satisfied += b.tag
      else future(b.tag) = b
    }

    val week = Periods.currentWeek(s, now)
    def isSticky(b: Block): Boolean =
      !b.interval.start.isBefore(week.start) && b.interval.start.isBefore(week.end) &&
        occurrenceByTag.get(b.tag).exists { o =>
          b.attendees == o.request.emails && b.interval.length == o.request.duration &&
          Periods.windowInstances(s, o.request, o.range).exists((_, w) => w.contains(b.interval))
        } &&
        b.attendees.forall(e => !hard(e).exists(_.overlaps(b.interval)))

    val pendingSticky = mutable.Map.from(future.view.filter((_, b) => isSticky(b)))
    val reserved      = mutable.Map.empty[String, List[Interval]].withDefaultValue(Nil)
    val results       = mutable.LinkedHashMap.empty[BlockTag, Placement]
    val unplaced      = ListBuffer.empty[BlockTag]

    def reserve(o: Occurrence, i: Interval): Unit =
      o.request.emails.foreach(e => reserved(e) = i :: reserved(e))
      results(o.tag) = Placement(o.request, o.tag, i)

    def stickyOf(e: String) = pendingSticky.values.filter(_.attendees.contains(e))

    val ordered = occurrences.sortBy(o => (-o.request.priority, o.request.order, o.range.start))
    for o <- ordered if !satisfied(o.tag) do
      pendingSticky.remove(o.tag) match
        case Some(b) => reserve(o, b.interval)
        case None =>
          val people = o.request.emails
          // Pending sticky blocks always have priority <= o's, since occurrences run in priority order.
          def free(c: Interval, evictable: Block => Boolean) = people.forall { e =>
            !hard(e).exists(_.overlaps(c)) && !reserved(e).exists(_.overlaps(c)) &&
            stickyOf(e).forall(b => !b.interval.overlaps(c) || evictable(b))
          }
          val all     = candidates(s, o)
          val noEvict = all.filter((c, _) => free(c, _ => false))
          val pool =
            if noEvict.nonEmpty then noEvict
            else all.filter((c, _) => free(c, b => activeById(b.tag.requestId).priority < o.request.priority))

          if pool.isEmpty then unplaced += o.tag
          else
            def occupied(e: String) = hard(e) ++ reserved(e) ++ stickyOf(e).map(_.interval)
            val (best, _) = pool.maxBy((c, w) => (Strategies.score(o, c, w, occupied), -c.start.toEpochMilli))
            pendingSticky.filterInPlace((_, b) => !(b.interval.overlaps(best) && b.attendees.exists(people)))
            reserve(o, best)

    val create = ListBuffer.empty[Placement]
    val patch  = ListBuffer.empty[BlockUpdate]
    results.values.foreach { p =>
      future.remove(p.tag) match
        case Some(b) if b.attendees == p.request.emails && b.organizer == p.request.attendees.head =>
          val details = p.request.details
          if b.interval == p.interval && b.detailsFingerprint.contains(details.fingerprint) then keep += b
          else patch += BlockUpdate(b, p.interval, details)
        case Some(b) =>
          delete += b; create += p
        case None => create += p
    }
    delete ++= future.values

    Plan(keep.toList, create.toList, patch.toList, delete.toList, unplaced.toList, skipped.map(_.id))

  /** Start times every `slotStep` from each window's nominal start, so slots stay on round times. */
  private def candidates(s: Settings, o: Occurrence): List[(Interval, Interval)] =
    Periods.windowInstances(s, o.request, o.range).flatMap { (nominal, clipped) =>
      Iterator
        .iterate(nominal.start)(_.plus(s.slotStep))
        .takeWhile(t => !t.plus(o.request.duration).isAfter(clipped.end))
        .filterNot(_.isBefore(clipped.start))
        .map(t => (Interval(t, t.plus(o.request.duration)), nominal))
    }
