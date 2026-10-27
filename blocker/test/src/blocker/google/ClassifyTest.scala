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
        OrganizerKey -> "google:a@x.com", AttendeesKey -> "a@x.com,b@x.com", DetailsKey -> "fp"
      ).asJava))
      .setAttendees(List(EventAttendee().setEmail("B@x.com").setResponseStatus("declined")).asJava)
    assertEquals(
      classify(acct, "primary", tagged, opts),
      Some(Right(Block(
        BlockRef(acct, "primary", "ev"), BlockTag("band", "once"), Account("google", "a@x.com"),
        Set("a@x.com", "b@x.com"), Interval(s, e), Set("b@x.com"), Some("fp")
      )))
    )
  }

  private val details = EventDetails("Band", Some("Bring picks"), Some("Studio 4"))
  private val newBlock = NewBlock(BlockTag("band", "once"), details, Interval(s, e), Account("google", "a@x.com"), List(Account("google", "b@x.com")))

  test("created events carry the details, a fingerprint, and the guests") {
    val ev = toEvent(newBlock)
    assertEquals(ev.getSummary, "Band")
    assertEquals(ev.getDescription, "Bring picks\n\nManaged by calendar-blocker.")
    assertEquals(ev.getLocation, "Studio 4")
    assertEquals(ev.getExtendedProperties.getShared.get(DetailsKey), details.fingerprint)
    assertEquals(ev.getAttendees.asScala.map(_.getEmail).toList, List("b@x.com"))
    val bare = toEvent(newBlock.copy(details = EventDetails("Band", None, None)))
    assertEquals(bare.getDescription, "Managed by calendar-blocker.")
    assertEquals(bare.getLocation, null)
  }

  test("patches set time and details in one body and explicitly clear a removed location") {
    val patch = toPatch(Interval(s, e), EventDetails("Band", None, None))
    assertEquals(patch.getStart.getDateTime.getValue, s.toEpochMilli)
    assertEquals(patch.getSummary, "Band")
    assert(com.google.api.client.util.Data.isNull(patch.getLocation), "location must be an explicit JSON null")
    assertEquals(patch.getExtendedProperties.getShared.asScala.toMap, Map(DetailsKey -> EventDetails("Band", None, None).fingerprint))
  }
