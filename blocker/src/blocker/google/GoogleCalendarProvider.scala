package blocker.google

import blocker.*
import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.util.{Data, DateTime}
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.{Event, EventAttendee, EventDateTime, Calendar as GCalendar}

import java.time.{Instant, LocalDate, ZoneId}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** Reads and writes Google Calendar.
 *
 *  Privacy: external events are reduced to bare start/end intervals as soon as they're fetched. Titles,
 *  descriptions, attendees and calendar names are never retained, logged, or written anywhere.
 */
final class GoogleCalendarProvider(clients: GoogleClients, store: TokenStore, calendarName: String, zone: ZoneId)
    extends CalendarProvider:
  import GoogleCalendarProvider.*

  val id = "google"

  def ensureBlocksCalendar(acct: Account): IO[String] =
    for
      cal      <- clients.calendar(acct)
      state    <- store.read(acct)
      existing <- state.flatMap(_.blocksCalendarId).traverse(cid => exists(cal, cid).map(Option.when(_)(cid)))
      calId <- existing.flatten match
        case Some(cid) => IO.pure(cid)
        case None =>
          IO.blocking(cal.calendars().insert(GCalendar().setSummary(calendarName).setTimeZone(zone.getId)).execute().getId)
            .flatTap(cid => store.write(acct, UserState(Some(cid))))
    yield calId

  def snapshot(acct: Account, range: Interval, opts: SnapshotOptions): IO[Snapshot] =
    for
      cal       <- clients.calendar(acct)
      blocksCal <- store.read(acct).map(_.flatMap(_.blocksCalendarId))
      ids       <- IO.blocking(calendarIds(cal)).map(_.filterNot(opts.excludeCalendars).filterNot(blocksCal.contains))
      reads      = blocksCal.map(_ -> None).toList ++ ids.map(_ -> Some(range.end))
      perCal    <- reads.traverse((cid, end) => IO.blocking(events(cal, cid, range.start, end, acct, opts)).attempt)
      unreadable = perCal.count(_.isLeft)
      _         <- Console[IO].errorln(s"WARN $acct: skipped $unreadable unreadable calendar(s)").whenA(unreadable > 0)
      entries    = perCal.collect { case Right(es) => es }.flatten
    yield Snapshot(entries.collect { case Left(i) => i }, entries.collect { case Right(b) => b })

  def create(calendarId: String, b: NewBlock): IO[BlockRef] =
    clients.calendar(b.organizer).flatMap { cal =>
      IO.blocking {
        val created = cal.events().insert(calendarId, toEvent(b)).setSendUpdates(if b.guests.isEmpty then "none" else "all").execute()
        BlockRef(b.organizer, calendarId, created.getId)
      }
    }

  def update(ref: BlockRef, interval: Interval, details: EventDetails): IO[Unit] =
    clients.calendar(ref.account).flatMap { cal =>
      IO.blocking(cal.events().patch(ref.calendarId, ref.eventId, toPatch(interval, details)).setSendUpdates("all").execute()).void
    }

  def delete(ref: BlockRef): IO[Unit] =
    clients.calendar(ref.account).flatMap { cal =>
      IO.blocking(cal.events().delete(ref.calendarId, ref.eventId).setSendUpdates("all").execute()).void.recover {
        case e: GoogleJsonResponseException if e.getStatusCode == 404 || e.getStatusCode == 410 => ()
      }
    }

  private def exists(cal: Calendar, calendarId: String): IO[Boolean] =
    IO.blocking(cal.calendars().get(calendarId).execute()).as(true).recover {
      case e: GoogleJsonResponseException if e.getStatusCode == 404 || e.getStatusCode == 410 => false
    }

  private def calendarIds(cal: Calendar): List[String] =
    val out           = ListBuffer.empty[String]
    var token: String = null
    while
      val page = cal.calendarList().list().setPageToken(token).execute()
      out ++= Option(page.getItems).fold(Nil)(_.asScala.map(_.getId))
      token = page.getNextPageToken
      token != null
    do ()
    out.toList

  private def events(cal: Calendar, calendarId: String, start: Instant, end: Option[Instant], acct: Account, opts: SnapshotOptions) =
    val out           = ListBuffer.empty[Either[Interval, Block]]
    var token: String = null
    while
      val page = cal
        .events()
        .list(calendarId)
        .setTimeMin(DateTime(start.toEpochMilli))
        .setTimeMax(end.map(e => DateTime(e.toEpochMilli)).orNull)
        .setSingleEvents(true)
        .setMaxResults(2500)
        .setPageToken(token)
        .execute()
      out ++= Option(page.getItems).fold(Nil)(_.asScala.flatMap(classify(acct, calendarId, _, opts)))
      token = page.getNextPageToken
      token != null
    do ()
    out.toList

object GoogleCalendarProvider:
  private[google] val TagKey       = "calblock"
  private[google] val RequestKey   = "calblockRequest"
  private[google] val PeriodKey    = "calblockPeriod"
  private[google] val OrganizerKey = "calblockOrganizer"
  private[google] val AttendeesKey = "calblockAttendees"
  private[google] val DetailsKey   = "calblockDetails"
  private val Footer               = "Managed by calendar-blocker."

  /** Tagged events become blocks; other events that occupy time become bare intervals; the rest is dropped. */
  private[google] def classify(acct: Account, calendarId: String, e: Event, opts: SnapshotOptions): Option[Either[Interval, Block]] =
    val shared    = Option(e.getExtendedProperties).flatMap(p => Option(p.getShared)).fold(Map.empty[String, String])(_.asScala.toMap)
    val attendees = Option(e.getAttendees).fold(Nil)(_.asScala.toList)
    val interval  = (instant(e.getStart, opts.zone), instant(e.getEnd, opts.zone)).mapN(Interval(_, _))
    val allDay    = Option(e.getStart).exists(_.getDate != null)

    if e.getStatus == "cancelled" then None
    else if shared.get(TagKey).contains("1") then
      for
        requestId <- shared.get(RequestKey)
        periodKey <- shared.get(PeriodKey)
        organizer <- shared.get(OrganizerKey).map(Account.parse)
        i         <- interval
      yield Right(
        Block(
          BlockRef(acct, calendarId, e.getId),
          BlockTag(requestId, periodKey),
          organizer,
          shared.get(AttendeesKey).fold(Set(organizer.email))(_.split(",").map(_.trim.toLowerCase).toSet),
          i,
          attendees.filter(_.getResponseStatus == "declined").flatMap(a => Option(a.getEmail)).map(_.toLowerCase).toSet,
          shared.get(DetailsKey)
        )
      )
    else if e.getTransparency == "transparent" then None
    else if attendees.exists(a => a.getSelf == true && a.getResponseStatus == "declined") then None
    else if allDay && !opts.allDayEventsBusy then None
    else interval.map(Left(_))

  private[google] def toEvent(b: NewBlock): Event =
    val ev = withDetails(Event(), b.details)
      .setStart(dateTime(b.interval.start))
      .setEnd(dateTime(b.interval.end))
      .setExtendedProperties(
        Event.ExtendedProperties().setShared(
          Map(
            TagKey       -> "1",
            RequestKey   -> b.tag.requestId,
            PeriodKey    -> b.tag.periodKey,
            OrganizerKey -> b.organizer.toString,
            AttendeesKey -> (b.organizer.email :: b.guests.map(_.email)).mkString(","),
            DetailsKey   -> b.details.fingerprint
          ).asJava
        )
      )
    if b.guests.nonEmpty then ev.setAttendees(b.guests.map(g => EventAttendee().setEmail(g.email)).asJava)
    ev

  /** Patch body for an existing block. Shared properties are merged by key, so only the fingerprint is sent. */
  private[google] def toPatch(interval: Interval, details: EventDetails): Event =
    withDetails(Event(), details, patching = true)
      .setStart(dateTime(interval.start))
      .setEnd(dateTime(interval.end))
      .setExtendedProperties(Event.ExtendedProperties().setShared(Map(DetailsKey -> details.fingerprint).asJava))

  /** In a patch, an absent field means "leave unchanged", so a removed location must be sent as an explicit null. */
  private def withDetails(ev: Event, d: EventDetails, patching: Boolean = false): Event =
    ev.setSummary(d.title)
      .setDescription(d.description.fold(Footer)(text => s"$text\n\n$Footer"))
      .setLocation(d.location.getOrElse(if patching then Data.NULL_STRING else null))

  private def instant(t: EventDateTime, zone: ZoneId): Option[Instant] =
    Option(t).flatMap { t =>
      Option(t.getDateTime).map(dt => Instant.ofEpochMilli(dt.getValue))
        .orElse(Option(t.getDate).map(d => LocalDate.parse(d.toStringRfc3339).atStartOfDay(zone).toInstant))
    }

  private def dateTime(i: Instant): EventDateTime = EventDateTime().setDateTime(DateTime(i.toEpochMilli))
