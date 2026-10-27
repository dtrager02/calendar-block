package blocker

import munit.FunSuite

import java.time.{DayOfWeek, Duration, Instant, LocalDate, LocalTime, ZoneOffset}

class SchedulerTest extends FunSuite:

  private val settings = Settings(ZoneOffset.UTC, 30, DayOfWeek.MONDAY, Duration.ofMinutes(15))
  private val monday   = Instant.parse("2026-10-26T08:00:00Z")
  private val sat      = LocalDate.parse("2026-10-31")
  private val everyone = Set("a@x", "b@x", "c@x")

  private def at(s: String)              = Instant.parse(s"${s}:00Z")
  private def iv(from: String, to: String) = Interval(at(from), at(to))
  private def acct(e: String)            = Account("google", e)
  private def window(from: String, to: String, days: DayOfWeek*) =
    TimeWindow(if days.isEmpty then Set(DayOfWeek.SATURDAY) else days.toSet, LocalTime.parse(from), LocalTime.parse(to))

  private def req(
      id: String,
      priority: Int,
      attendees: List[String] = List("a@x"),
      windows: List[TimeWindow] = List(window("10:00", "12:00")),
      strategies: Map[Strategy, Double] = Map(Strategy.PreferSoonest -> 1.0),
      recurrence: Recurrence = Recurrence.Once,
      between: Option[(LocalDate, LocalDate)] = Some(sat -> sat),
      order: Int = 0
  ) = Request(id, id, priority, attendees.map(acct), recurrence, between, Duration.ofHours(2), windows, strategies, order)

  private def block(r: Request, period: String, i: Interval, owner: Option[String] = None, declined: Set[String] = Set.empty) =
    val organizer = r.attendees.head
    Block(
      BlockRef(owner.fold(organizer)(acct), "cal", s"${r.id}-$period"),
      BlockTag(r.id, period),
      organizer,
      r.emails,
      i,
      declined
    )

  private def plan(
      requests: List[Request],
      busy: Map[String, List[Interval]] = Map.empty,
      blocks: List[Block] = Nil,
      available: Set[String] = everyone,
      now: Instant = monday
  ) = Scheduler.plan(settings, requests, now, available, busy, blocks)

  private def created(p: Plan) = p.create.map(c => c.tag.requestId -> c.interval).toMap

  test("weekly request gets one block per week, only within the 30-day horizon") {
    val r = req("gaming", 5, recurrence = Recurrence.Weekly, between = None)
    val p = plan(List(r))
    assertEquals(
      p.create.map(_.interval.start),
      List("2026-10-31T10:00", "2026-11-07T10:00", "2026-11-14T10:00", "2026-11-21T10:00").map(at)
    )
    assertEquals(p.unplaced, List(BlockTag("gaming", "week-of-2026-11-23")))
  }

  test("minimal-gaps outweighs prefer-soonest when weighted higher") {
    val busy = Map("a@x" -> List(iv("2026-10-31T14:00", "2026-10-31T15:00")))
    val wide = List(window("10:00", "18:00"))
    val soonest = req("r", 5, windows = wide)
    val snug    = req("r", 5, windows = wide, strategies = Map(Strategy.MinimalGaps -> 2.0, Strategy.PreferSoonest -> 1.0))
    assertEquals(created(plan(List(soonest), busy))("r"), iv("2026-10-31T10:00", "2026-10-31T12:00"))
    assertEquals(created(plan(List(snug), busy))("r"), iv("2026-10-31T15:00", "2026-10-31T17:00"))
  }

  test("higher priority takes a contested slot and the lower one is skipped") {
    val p = plan(List(req("low", 1, order = 0), req("high", 9, order = 1)))
    assertEquals(created(p).keySet, Set("high"))
    assertEquals(p.unplaced, List(BlockTag("low", "once")))
  }

  test("external events are never overridden") {
    val p = plan(List(req("r", 1000)), busy = Map("a@x" -> List(iv("2026-10-31T11:00", "2026-10-31T11:30"))))
    assert(p.create.isEmpty)
    assertEquals(p.unplaced, List(BlockTag("r", "once")))
  }

  private val slot  = iv("2026-10-31T10:00", "2026-10-31T12:00")
  private val joint = req("joint", 8, attendees = List("a@x", "b@x", "c@x"), order = 2)

  test("joint request evicts a lower-priority block on one attendee's calendar") {
    val aGaming = req("a-gaming", 5)
    val p       = plan(List(aGaming, joint), blocks = List(block(aGaming, "once", slot)))
    assertEquals(created(p), Map("joint" -> slot))
    assertEquals(p.delete.map(_.tag.requestId), List("a-gaming"))
    assertEquals(p.unplaced, List(BlockTag("a-gaming", "once")))
  }

  test("joint request cannot evict a higher-priority block on any attendee's calendar") {
    val cFocus = req("c-focus", 10, attendees = List("c@x"))
    val p      = plan(List(cFocus, joint), blocks = List(block(cFocus, "once", slot)))
    assert(p.create.isEmpty)
    assertEquals(p.keep.map(_.tag.requestId), List("c-focus"))
    assertEquals(p.unplaced, List(BlockTag("joint", "once")))
  }

  test("equal priority never evicts") {
    val aGaming = req("a-gaming", 8)
    val p       = plan(List(aGaming, joint), blocks = List(block(aGaming, "once", slot)))
    assertEquals(p.keep.map(_.tag.requestId), List("a-gaming"))
    assertEquals(p.unplaced, List(BlockTag("joint", "once")))
  }

  test("eviction happens only when no free slot exists") {
    val wide    = List(window("10:00", "14:00"))
    val aGaming = req("a-gaming", 5, windows = wide)
    val j       = joint.copy(windows = wide)
    val p       = plan(List(aGaming, j), blocks = List(block(aGaming, "once", slot)))
    assertEquals(created(p), Map("joint" -> iv("2026-10-31T12:00", "2026-10-31T14:00")))
    assertEquals(p.keep.map(_.tag.requestId), List("a-gaming"))
    assert(p.delete.isEmpty)
  }

  test("current-week blocks are sticky, later-week blocks are re-solved and patched") {
    val r       = req("gaming", 5, windows = List(window("10:00", "14:00")), recurrence = Recurrence.Weekly, between = None)
    val thisWk  = block(r, "week-of-2026-10-26", iv("2026-10-31T12:00", "2026-10-31T14:00"))
    val nextWk  = block(r, "week-of-2026-11-02", iv("2026-11-07T12:00", "2026-11-07T14:00"))
    val p       = plan(List(r), blocks = List(thisWk, nextWk))
    assertEquals(p.keep, List(thisWk))
    assertEquals(p.patch, List(nextWk -> iv("2026-11-07T10:00", "2026-11-07T12:00")))
    assert(p.delete.isEmpty)
  }

  test("a sticky block that now conflicts with an external event is moved") {
    val r = req("gaming", 5, windows = List(window("10:00", "14:00")))
    val b = block(r, "once", iv("2026-10-31T12:00", "2026-10-31T14:00"))
    val p = plan(List(r), busy = Map("a@x" -> List(iv("2026-10-31T13:00", "2026-10-31T13:30"))), blocks = List(b))
    assertEquals(p.patch, List(b -> slot))
  }

  test("started blocks are untouched and satisfy their period") {
    val r = req("gaming", 5)
    val b = block(r, "once", slot)
    val p = plan(List(r), blocks = List(b), now = at("2026-10-31T11:00"))
    assertEquals(p.keep, List(b))
    assert(p.isNoop)
    assert(p.unplaced.isEmpty)
  }

  test("a declined joint block is deleted and not re-placed") {
    val b = block(joint, "once", slot, declined = Set("b@x"))
    val p = plan(List(joint), blocks = List(b))
    assertEquals(p.delete, List(b))
    assert(p.create.isEmpty)
  }

  test("removing a request deletes all its future blocks, once per event, however far out") {
    val gone    = joint.copy(id = "gone", recurrence = Recurrence.Weekly, between = None)
    val started = block(gone, "week-of-2026-10-26", iv("2026-10-26T07:00", "2026-10-26T09:00"))
    val next    = block(gone, "week-of-2026-11-02", iv("2026-11-07T10:00", "2026-11-07T12:00"))
    val farOut  = block(gone, "week-of-2027-06-07", iv("2027-06-12T10:00", "2027-06-12T12:00"))
    val guest   = block(gone, "week-of-2026-11-02", next.interval, owner = Some("b@x"))
    val p       = plan(Nil, blocks = List(started, next, farOut, guest))
    assertEquals(p.delete.toSet, Set(next, farOut))
    assertEquals(p.keep, List(started))
  }

  test("guest copies of a joint event are neither busy time nor duplicates") {
    val own   = block(joint, "once", slot)
    val guest = block(joint, "once", slot, owner = Some("b@x"))
    val p     = plan(List(joint), blocks = List(own, guest))
    assertEquals(p.keep, List(own))
    assert(p.isNoop)
  }

  test("one-time requests beyond the horizon are placed") {
    val far = LocalDate.parse("2026-12-26")
    val p   = plan(List(req("taxes", 5, between = Some(far -> far))))
    assertEquals(created(p)("taxes"), iv("2026-12-26T10:00", "2026-12-26T12:00"))
  }

  test("requests with an unavailable attendee are skipped and their blocks left alone") {
    val b = block(joint, "once", slot)
    val p = plan(List(joint), blocks = List(b), available = Set("a@x", "b@x"))
    assertEquals(p.skippedRequests, List("joint"))
    assertEquals(p.keep, List(b))
    assert(p.isNoop)
  }

  test("re-planning against its own output changes nothing") {
    val requests = List(
      req("gaming", 5, windows = List(window("09:00", "21:00")), recurrence = Recurrence.Weekly, between = None,
        strategies = Map(Strategy.MinimalGaps -> 2.0, Strategy.PreferSoonest -> 1.0)),
      joint.copy(windows = List(window("09:00", "21:00")), recurrence = Recurrence.Weekly, between = None),
      req("reading", 3, attendees = List("b@x"), windows = List(window("09:00", "21:00", DayOfWeek.values*)),
        recurrence = Recurrence.Daily, between = None)
    )
    val busy  = Map("a@x" -> List(iv("2026-11-07T13:00", "2026-11-07T14:00")), "b@x" -> List(iv("2026-11-14T09:00", "2026-11-14T12:00")))
    val first = plan(requests, busy)
    assert(first.create.nonEmpty)
    val blocks = first.create.map(c => block(c.request, c.tag.periodKey, c.interval))
    val second = plan(requests, busy, blocks)
    assert(second.isNoop, second)
    assertEquals(second.keep.size, blocks.size)
  }
