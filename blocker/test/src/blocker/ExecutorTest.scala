package blocker

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import java.time.{Duration, Instant}

class ExecutorTest extends CatsEffectSuite:

  private class FakeProvider(val id: String, calls: Ref[IO, List[(String, List[BlockOp])]], fail: Boolean = false)
      extends CalendarProvider:
    def ensureBlocksCalendar(acct: Account) = IO.pure(s"$id-blocks")
    def snapshot(acct: Account, range: Interval, opts: SnapshotOptions) = IO.pure(Snapshot(Nil, Nil, None))
    def applyAll(ops: List[BlockOp]) =
      if fail then IO.raiseError(RuntimeException("boom")) else calls.update(_ :+ (id -> ops))

  private val i       = Interval(Instant.parse("2026-10-31T10:00:00Z"), Instant.parse("2026-10-31T12:00:00Z"))
  private val alice   = Account("google", "a@x")
  private val bob     = Account("outlook", "b@x")
  private val request = Request("joint", "Joint", 5, List(alice, bob), Recurrence.Weekly, None, Duration.ofHours(2), Nil, Map.empty, 0, None, Some("Park"))
  private def block(a: Account, id: String) = Block(BlockRef(a, "cal", id), BlockTag("x", id), a, Set(a.email), i, Set.empty, None)

  private val plan = Plan(
    keep = Nil,
    create = List(Placement(request, BlockTag("joint", "w1"), i)),
    patch = List(BlockUpdate(block(bob, "e2"), i, request.details)),
    delete = List(block(alice, "e1"), block(alice, "e3")),
    unplaced = Nil,
    skippedRequests = Nil
  )

  test("each provider receives all of its operations in a single call") {
    for
      calls    <- Ref.of[IO, List[(String, List[BlockOp])]](Nil)
      providers = Map("google" -> FakeProvider("google", calls), "outlook" -> FakeProvider("outlook", calls))
      _        <- Executor.run(plan, providers, Map(alice -> "google-blocks"))
      got      <- calls.get
    yield assertEquals(
      got.toMap,
      Map(
        "google" -> List(
          BlockOp.Delete(BlockRef(alice, "cal", "e1")),
          BlockOp.Delete(BlockRef(alice, "cal", "e3")),
          BlockOp.Create("google-blocks", NewBlock(BlockTag("joint", "w1"), request.details, i, alice, List(bob)))
        ),
        "outlook" -> List(BlockOp.Update(BlockRef(bob, "cal", "e2"), i, request.details))
      )
    )
  }

  test("a provider failure fails the run") {
    for
      calls  <- Ref.of[IO, List[(String, List[BlockOp])]](Nil)
      result <- Executor.run(plan, Map("google" -> FakeProvider("google", calls, fail = true), "outlook" -> FakeProvider("outlook", calls)), Map(alice -> "c")).attempt
    yield assertEquals(result.left.map(_.getMessage), Left("boom"))
  }
