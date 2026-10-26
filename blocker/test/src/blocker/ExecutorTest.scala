package blocker

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.time.{Duration, Instant}

class ExecutorTest extends CatsEffectSuite:

  private class FakeProvider(val id: String, log: Ref[IO, List[String]], failDeletes: Boolean = false) extends CalendarProvider:
    def ensureBlocksCalendar(acct: Account) = IO.pure(s"$id-blocks")
    def snapshot(acct: Account, range: Interval, opts: SnapshotOptions) = IO.pure(Snapshot(Nil, Nil))
    def create(calendarId: String, b: NewBlock) =
      log.update(_ :+ s"$id create ${b.tag.requestId} on $calendarId by ${b.organizer} inviting ${b.guests.mkString(",")}")
        .as(BlockRef(b.organizer, calendarId, "new"))
    def patch(ref: BlockRef, interval: Interval) = log.update(_ :+ s"$id patch ${ref.eventId}")
    def delete(ref: BlockRef) =
      if failDeletes then IO.raiseError(RuntimeException("boom")) else log.update(_ :+ s"$id delete ${ref.eventId}")

  private val i       = Interval(Instant.parse("2026-10-31T10:00:00Z"), Instant.parse("2026-10-31T12:00:00Z"))
  private val alice   = Account("google", "a@x")
  private val bob     = Account("outlook", "b@x")
  private val request = Request("joint", "Joint", 5, List(alice, bob), Recurrence.Weekly, None, Duration.ofHours(2), Nil, Map.empty, 0)
  private def block(a: Account, id: String) = Block(BlockRef(a, "cal", id), BlockTag("x", id), a, Set(a.email), i, Set.empty)

  test("routes each operation to the provider of the account that owns the event") {
    val plan = Plan(
      keep = Nil,
      create = List(Placement(request, BlockTag("joint", "w1"), i)),
      patch = List(block(bob, "e2") -> i),
      delete = List(block(alice, "e1")),
      unplaced = Nil,
      skippedRequests = Nil
    )
    for
      log      <- Ref.of[IO, List[String]](Nil)
      providers = Map("google" -> FakeProvider("google", log), "outlook" -> FakeProvider("outlook", log))
      failures <- Executor.run(plan, providers, Map(alice -> "google-blocks"))
      ops      <- log.get
    yield
      assertEquals(failures, 0)
      assertEquals(
        ops,
        List(
          "google delete e1",
          "outlook patch e2",
          "google create joint on google-blocks by google:a@x inviting outlook:b@x"
        )
      )
  }

  test("failed operations are counted and do not stop the rest") {
    val plan = Plan(Nil, Nil, List(block(alice, "e2") -> i), List(block(alice, "e1")), Nil, Nil)
    for
      log      <- Ref.of[IO, List[String]](Nil)
      failures <- Executor.run(plan, Map("google" -> FakeProvider("google", log, failDeletes = true)), Map.empty)
      ops      <- log.get
    yield
      assertEquals(failures, 1)
      assertEquals(ops, List("google patch e2"))
  }
