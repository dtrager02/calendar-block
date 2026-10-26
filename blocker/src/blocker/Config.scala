package blocker

import cats.effect.IO
import cats.syntax.all.*
import io.circe.derivation.{Configuration, ConfiguredDecoder}
import io.circe.yaml.v12.parser

import java.nio.file.{Files, Path}
import java.time.{DayOfWeek, Duration, LocalDate, LocalTime, ZoneId}
import scala.util.Try

case class AppConfig(
    settings: Settings,
    owner: Account,
    allDayEventsBusy: Boolean,
    publicBaseUrl: Option[String],
    onboardPort: Int,
    calendarName: String,
    excludeCalendars: Map[String, Set[String]],
    requests: List[Request]
):
  def snapshotOptions(a: Account): SnapshotOptions =
    SnapshotOptions(settings.zone, allDayEventsBusy, excludeCalendars.getOrElse(a.email, Set.empty))

object Config:

  private given Configuration = Configuration.default.withDefaults.withStrictDecoding

  private case class RawWindow(days: List[String], from: String, to: String) derives ConfiguredDecoder
  private case class RawBetween(from: String, to: String) derives ConfiguredDecoder
  private case class RawUserOverride(excludeCalendars: List[String] = Nil) derives ConfiguredDecoder
  private case class RawRequest(
      id: String,
      title: Option[String],
      priority: Int,
      attendees: List[String] = Nil,
      recurrence: String,
      between: Option[RawBetween],
      duration: String,
      windows: List[RawWindow],
      strategies: Map[String, Double] = Map.empty
  ) derives ConfiguredDecoder
  private case class RawConfig(
      timezone: String,
      horizonDays: Int = 30,
      weekStart: String = "MONDAY",
      slotStep: String = "15m",
      allDayEventsBusy: Boolean = false,
      owner: String,
      publicBaseUrl: Option[String],
      onboardPort: Int = 8735,
      calendarName: String = "Auto Blocks",
      userOverrides: Map[String, RawUserOverride] = Map.empty,
      requests: List[RawRequest] = Nil
  ) derives ConfiguredDecoder

  def load(path: Path): IO[AppConfig] =
    IO.blocking(Files.readString(path))
      .flatMap(s => IO.fromEither(parse(s).leftMap(e => IllegalArgumentException(s"$path: $e"))))

  def parse(yaml: String): Either[String, AppConfig] =
    for
      json <- parser.parse(yaml).leftMap(e => s"invalid YAML: ${e.getMessage}")
      raw  <- json.as[RawConfig].leftMap(e => s"invalid config: ${e.getMessage}")
      cfg  <- validate(raw)
    yield cfg

  private val DurationRe = """(?:(\d+)h)?(?:(\d+)m)?""".r

  def duration(s: String): Either[String, Duration] = s.trim match
    case DurationRe(h, m) if h != null || m != null =>
      Right(Duration.ofHours(Option(h).fold(0L)(_.toLong)).plusMinutes(Option(m).fold(0L)(_.toLong)))
    case _ => Left(s"invalid duration '$s' (use e.g. 2h, 90m, 1h30m)")

  private def positiveDuration(s: String) =
    duration(s).filterOrElse(d => !d.isZero, s"duration '$s' must be positive")

  private def day(s: String): Either[String, DayOfWeek] =
    val u = s.trim.toUpperCase
    DayOfWeek.values.find(d => u.length >= 3 && d.name.startsWith(u)).toRight(s"invalid day '$s'")

  private def time(s: String) = Try(LocalTime.parse(s.trim)).toEither.leftMap(_ => s"invalid time '$s' (use HH:mm)")
  private def date(s: String) = Try(LocalDate.parse(s.trim)).toEither.leftMap(_ => s"invalid date '$s' (use YYYY-MM-DD)")

  private def window(w: RawWindow): Either[String, TimeWindow] =
    for
      days <- w.days.traverse(day)
      _    <- Either.cond(days.nonEmpty, (), "window needs at least one day")
      from <- time(w.from)
      to   <- time(w.to)
      _    <- Either.cond(from.isBefore(to), (), s"window from ${w.from} must be before to ${w.to}")
    yield TimeWindow(days.toSet, from, to)

  private def strategy(name: String): Either[String, Strategy] = name match
    case "prefer-soonest" => Right(Strategy.PreferSoonest)
    case "minimal-gaps"   => Right(Strategy.MinimalGaps)
    case other            => Left(s"unknown strategy '$other' (use prefer-soonest, minimal-gaps)")

  private def request(r: RawRequest, order: Int, owner: Account): Either[String, Request] =
    val attendees = if r.attendees.isEmpty then List(owner) else r.attendees.map(Account.parse)
    for
      recurrence <- r.recurrence.trim.toLowerCase match
        case "daily"  => Right(Recurrence.Daily)
        case "weekly" => Right(Recurrence.Weekly)
        case "once"   => Right(Recurrence.Once)
        case other    => Left(s"invalid recurrence '$other' (use daily, weekly, once)")
      duration <- positiveDuration(r.duration)
      windows  <- r.windows.traverse(window)
      _        <- Either.cond(windows.nonEmpty, (), "at least one window is required")
      between <- (recurrence, r.between) match
        case (Recurrence.Once, Some(b)) =>
          (date(b.from), date(b.to)).tupled.flatMap((f, t) =>
            Either.cond(!t.isBefore(f), Some(f -> t), "between.to must not be before between.from")
          )
        case (Recurrence.Once, None) => Left("recurrence 'once' requires 'between'")
        case (_, Some(_))            => Left("'between' is only valid with recurrence 'once'")
        case _                       => Right(None)
      strategies <- r.strategies.toList.traverse((name, weight) =>
        strategy(name).flatMap(s => Either.cond(weight >= 0, s -> weight, s"weight for '$name' must be >= 0"))
      )
      _ <- Either.cond(attendees.distinct.size == attendees.size, (), "duplicate attendees")
    yield Request(
      r.id,
      r.title.getOrElse(r.id),
      r.priority,
      attendees,
      recurrence,
      between,
      duration,
      windows,
      if strategies.isEmpty then Map(Strategy.PreferSoonest -> 1.0) else strategies.toMap,
      order
    )

  private def validate(r: RawConfig): Either[String, AppConfig] =
    val owner = Account.parse(r.owner)
    for
      zone      <- Try(ZoneId.of(r.timezone)).toEither.leftMap(_ => s"invalid timezone '${r.timezone}'")
      weekStart <- day(r.weekStart)
      slotStep  <- positiveDuration(r.slotStep)
      _         <- Either.cond(r.horizonDays > 0, (), "horizonDays must be positive")
      requests  <- r.requests.zipWithIndex.traverse((rr, i) => request(rr, i, owner).leftMap(e => s"request '${rr.id}': $e"))
      dupes      = requests.groupBy(_.id).collect { case (id, rs) if rs.size > 1 => id }
      _         <- Either.cond(dupes.isEmpty, (), s"duplicate request ids: ${dupes.mkString(", ")}")
    yield AppConfig(
      Settings(zone, r.horizonDays, weekStart, slotStep),
      owner,
      r.allDayEventsBusy,
      r.publicBaseUrl.map(_.stripSuffix("/")),
      r.onboardPort,
      r.calendarName,
      r.userOverrides.map((e, o) => e.toLowerCase -> o.excludeCalendars.toSet),
      requests
    )
