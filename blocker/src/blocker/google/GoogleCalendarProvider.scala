package blocker.google

import blocker.*
import cats.effect.IO
import cats.syntax.all.*
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import com.google.api.client.util.{Data, DateTime}
import com.google.api.services.calendar.{Calendar, CalendarRequest}
import com.google.api.services.calendar.model.{Event, EventAttendee, EventDateTime, Events, Calendar as GCalendar}

import java.io.IOException
import java.time.{Instant, LocalDate, ZoneId}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** Reads and writes Google Calendar, batching requests (up to 50 per HTTP call) wherever the API allows.
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
      cal    <- clients.calendar(acct)
      listed <- IO.blocking(calendarIds(cal))
      stored <- storedBlocksCalendar(acct, listed)
      calId <- stored match
        case Some(cid) => IO.pure(cid)
        case None =>
          IO.blocking(cal.calendars().insert(GCalendar().setSummary(calendarName).setTimeZone(zone.getId)).execute().getId)
            .flatTap(cid => store.write(acct, UserState(Some(cid))))
    yield calId

  def snapshot(acct: Account, range: Interval, opts: SnapshotOptions): IO[Snapshot] =
    val read =
      for
        cal       <- clients.calendar(acct)
        listed    <- IO.blocking(calendarIds(cal))
        blocksCal <- storedBlocksCalendar(acct, listed)
        others     = listed.filterNot(opts.excludeCalendars).filterNot(blocksCal.contains)
        reads      = blocksCal.map(_ -> None).toList ++ others.map(_ -> Some(range.end))
        events    <- IO.blocking(listEvents(cal, reads, range.start))
        entries    = events.flatMap((cid, es) => es.flatMap(classify(acct, cid, _, opts)))
      yield Snapshot(entries.collect { case Left(i) => i }, entries.collect { case Right(b) => b }, blocksCal)
    read.adaptError { case e: TokenResponseException =>
      AccountUnavailable(acct, s"Google rejected the stored token (${Option(e.getDetails).fold(e.getMessage)(_.getError)})")
    }

  def applyAll(ops: List[BlockOp]): IO[Unit] =
    ops.groupBy(_.account).toList.traverse_ { (acct, acctOps) =>
      clients.calendar(acct).flatMap(cal => IO.blocking(batched(cal, acctOps.map(request(cal, _)))).void)
    }

  /** The stored blocks calendar, if it is still in the account's calendar list; otherwise it is recreated. */
  private def storedBlocksCalendar(acct: Account, listed: List[String]): IO[Option[String]] =
    store.read(acct).map(_.flatMap(_.blocksCalendarId).filter(listed.contains))

object GoogleCalendarProvider:
  private[google] val TagKey       = "calblock"
  private[google] val RequestKey   = "calblockRequest"
  private[google] val PeriodKey    = "calblockPeriod"
  private[google] val OrganizerKey = "calblockOrganizer"
  private[google] val AttendeesKey = "calblockAttendees"
  private[google] val DetailsKey   = "calblockDetails"
  private val MaxBatch             = 50
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

  private[google] def request(cal: Calendar, op: BlockOp): CalendarRequest[?] = op match
    case BlockOp.Create(calendarId, b) =>
      cal.events().insert(calendarId, toEvent(b)).setSendUpdates(if b.guests.isEmpty then "none" else "all")
    case BlockOp.Update(ref, interval, details) =>
      cal.events().patch(ref.calendarId, ref.eventId, toPatch(interval, details)).setSendUpdates("all")
    case BlockOp.Delete(ref) =>
      cal.events().delete(ref.calendarId, ref.eventId).setSendUpdates("all")

  /** Sends `requests` in batches of up to 50 per HTTP call and returns their responses in order.
   *  Fails if any request in a batch failed; the requests that did succeed are not rolled back.
   */
  private[google] def batched(cal: Calendar, requests: List[CalendarRequest[?]]): List[Any] =
    requests.grouped(MaxBatch).toList.flatMap { chunk =>
      val results = Array.fill[Option[Either[GoogleJsonError, Any]]](chunk.size)(None)
      val batch   = cal.batch()
      def enqueue[A](r: CalendarRequest[A], i: Int): Unit =
        r.queue(
          batch,
          new JsonBatchCallback[A]:
            def onSuccess(a: A, headers: HttpHeaders): Unit               = results(i) = Some(Right(a))
            def onFailure(e: GoogleJsonError, headers: HttpHeaders): Unit = results(i) = Some(Left(e))
        )
      chunk.zipWithIndex.foreach((r, i) => enqueue(r, i))
      batch.execute()
      val failed = results.collect { case Some(Left(e)) => s"${e.getCode} ${e.getMessage}" } ++ results.collect { case None => "no response" }
      if failed.nonEmpty then
        throw IOException(s"${failed.length} of ${chunk.size} Google Calendar requests failed, e.g. ${failed.head}")
      results.toList.flatMap(_.flatMap(_.toOption))
    }

  /** First pages of every calendar in one batch; any further pages (over 2500 events) are fetched one by one. */
  private def listEvents(cal: Calendar, reads: List[(String, Option[Instant])], start: Instant): List[(String, List[Event])] =
    def page(calendarId: String, end: Option[Instant], token: String) =
      cal
        .events()
        .list(calendarId)
        .setTimeMin(DateTime(start.toEpochMilli))
        .setTimeMax(end.map(e => DateTime(e.toEpochMilli)).orNull)
        .setSingleEvents(true)
        .setMaxResults(2500)
        .setPageToken(token)
    val firsts = batched(cal, reads.map((cid, end) => page(cid, end, null))).map(_.asInstanceOf[Events])
    reads.zip(firsts).map { case ((cid, end), first) =>
      val pages = Iterator.iterate(Option(first))(_.flatMap(p => Option(p.getNextPageToken).map(page(cid, end, _).execute())))
      cid -> pages.takeWhile(_.isDefined).flatten.flatMap(p => Option(p.getItems).fold(Nil)(_.asScala)).toList
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
