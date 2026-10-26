package blocker.google

import blocker.*
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.model.{Event, EventAttendee, EventDateTime}
import munit.FunSuite

import java.time.{Instant, ZoneOffset}
import scala.jdk.CollectionConverters.*

class ClassifyTest extends FunSuite:
  import GoogleCalendarProvider.*

  private val acct = Account("google", "b@x.com")
  private val opts = SnapshotOptions(ZoneOffset.UTC, allDayEventsBusy = false, excludeCalendars = Set.empty)
  private val s    = Instant.parse("2026-10-31T10:00:00Z")
  private val e    = Instant.parse("2026-10-31T12:00:00Z")

  private def event = Event()
    .setId("ev")
    .setSummary("Therapy appointment")
    .setDescription("private notes")
    .setStart(EventDateTime().setDateTime(DateTime(s.toEpochMilli)))
    .setEnd(EventDateTime().setDateTime(DateTime(e.toEpochMilli)))

  test("external events are reduced to a bare interval") {
    assertEquals(classify(acct, "primary", event, opts), Some(Left(Interval(s, e))))
  }

  test("free, self-declined, cancelled and all-day events are not busy") {
    val declined = event.setAttendees(List(EventAttendee().setSelf(true).setResponseStatus("declined")).asJava)
    val allDay   = event.setStart(EventDateTime().setDate(DateTime("2026-10-31"))).setEnd(EventDateTime().setDate(DateTime("2026-11-01")))
    assertEquals(classify(acct, "primary", event.setTransparency("transparent"), opts), None)
    assertEquals(classify(acct, "primary", declined, opts), None)
    assertEquals(classify(acct, "primary", event.setStatus("cancelled"), opts), None)
    assertEquals(classify(acct, "primary", allDay, opts), None)
    assert(classify(acct, "primary", allDay, opts.copy(allDayEventsBusy = true)).isDefined)
  }

  test("a guest's copy of a tagged event becomes a block with declines") {
    val tagged = event
      .setExtendedProperties(Event.ExtendedProperties().setShared(Map(
        TagKey -> "1", RequestKey -> "band", PeriodKey -> "once",
        OrganizerKey -> "google:a@x.com", AttendeesKey -> "a@x.com,b@x.com"
      ).asJava))
      .setAttendees(List(EventAttendee().setEmail("B@x.com").setResponseStatus("declined")).asJava)
    assertEquals(
      classify(acct, "primary", tagged, opts),
      Some(Right(Block(
        BlockRef(acct, "primary", "ev"), BlockTag("band", "once"), Account("google", "a@x.com"),
        Set("a@x.com", "b@x.com"), Interval(s, e), Set("b@x.com")
      )))
    )
  }
