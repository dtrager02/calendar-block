package blocker

import blocker.google.{GoogleAuthProvider, GoogleCalendarProvider, GoogleClients}
import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import cats.syntax.all.*

import java.nio.file.{Path, Paths}
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*

/** Entry point.
 *
 *  Usage:
 *  {{{
 *    calendar-blocker run [--dry-run] [--config FILE] [--home DIR]
 *    calendar-blocker onboard --email EMAIL [--provider google] [--timeout 1h] [--config FILE] [--home DIR]
 *    calendar-blocker validate [--config FILE]
 *  }}}
 *
 *  `--config` defaults to `~/.config/calendar-blocker-config/config.yaml`, a checkout of the public config repo.
 *  `--home` defaults to `~/.config/calendar-blocker` and holds the private files: `credentials.json` and tokens.
 */
object Main extends IOApp:

  private val Usage =
    """|Usage:
       |  run [--dry-run] [--config FILE] [--home DIR]
       |  onboard --email EMAIL [--provider google] [--timeout 1h] [--config FILE] [--home DIR]
       |  validate [--config FILE]""".stripMargin

  def run(args: List[String]): IO[ExitCode] =
    def find(key: String): Option[String] =
      val idx = args.indexOf(key)
      if idx >= 0 && idx + 1 < args.size then Some(args(idx + 1)) else None

    val userConfig = Paths.get(sys.props("user.home"), ".config")
    val home       = find("--home").map(Paths.get(_)).getOrElse(userConfig.resolve("calendar-blocker"))
    val config     = find("--config").map(Paths.get(_)).getOrElse(userConfig.resolve("calendar-blocker-config").resolve("config.yaml"))
    val program = args.headOption match
      case Some("run")      => runCmd(home, config, args.contains("--dry-run"))
      case Some("onboard")  => onboardCmd(home, config, find("--email"), find("--provider").getOrElse(Account.DefaultProvider), find("--timeout"))
      case Some("validate") => Config.load(config).flatMap(c => IO.println(s"OK: ${c.requests.size} request(s)")).as(ExitCode.Success)
      case _                => Console[IO].errorln(Usage).as(ExitCode.Error)
    program.handleErrorWith {
      case e: java.nio.file.NoSuchFileException => Console[IO].errorln(s"ERROR file not found: ${e.getMessage}").as(ExitCode.Error)
      case e                                    => Console[IO].errorln(s"ERROR ${e.getMessage}").as(ExitCode.Error)
    }

  /** The only place that knows which providers exist. */
  private def providers(home: Path, store: TokenStore, cfg: AppConfig): IO[Map[String, Provider]] =
    IO.blocking {
      val google = GoogleClients(home.resolve("credentials.json"), store.providerDir("google").resolve("tokens"))
      Map("google" -> Provider(GoogleCalendarProvider(google, store, cfg.calendarName, cfg.settings.zone), GoogleAuthProvider(google)))
    }

  private def runCmd(home: Path, config: Path, dryRun: Boolean): IO[ExitCode] =
    val store = TokenStore(home)
    for
      cfg       <- Config.load(config)
      registry  <- providers(home, store, cfg)
      calendars  = registry.view.mapValues(_.calendar).toMap
      now       <- IO.realTimeInstant
      accounts  <- store.accounts
      range      = Scheduler.snapshotRange(cfg.settings, cfg.requests, now)
      loaded <- accounts.traverse { a =>
        calendars.get(a.provider) match
          case None => Console[IO].errorln(s"WARN $a: unknown provider, skipping").as(None)
          case Some(p) =>
            val calendarId = if dryRun then IO.pure(None) else p.ensureBlocksCalendar(a).map(Some(_))
            (calendarId, p.snapshot(a, range, cfg.snapshotOptions(a))).tupled
              .map(r => Some(a -> r))
              .handleErrorWith(e => Console[IO].errorln(s"WARN $a: skipping (${e.getMessage})").as(None))
      }
      ok   = loaded.flatten
      plan = Scheduler.plan(
        cfg.settings,
        cfg.requests,
        now,
        ok.map(_._1.email).toSet,
        ok.map((a, r) => a.email -> r._2.busy).toMap,
        ok.flatMap(_._2._2.blocks)
      )
      _        <- report(cfg, plan, dryRun)
      failures <- if dryRun then IO.pure(0) else Executor.run(plan, calendars, ok.flatMap((a, r) => r._1.map(a -> _)).toMap)
    yield if failures == 0 then ExitCode.Success else ExitCode.Error

  private def report(cfg: AppConfig, plan: Plan, dryRun: Boolean): IO[Unit] =
    val fmt = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm").withZone(cfg.settings.zone)
    def show(i: Interval) = s"${fmt.format(i.start)}–${DateTimeFormatter.ofPattern("HH:mm").withZone(cfg.settings.zone).format(i.end)}"
    def tag(t: BlockTag)  = s"${t.requestId} [${t.periodKey}]"
    val lines =
      plan.create.map(p => s"create  ${tag(p.tag)} ${show(p.interval)}") ++
        plan.patch.map { u =>
          val moved = Option.when(u.interval != u.block.interval)(s"${show(u.block.interval)} -> ${show(u.interval)}")
          val edited = Option.unless(u.block.detailsFingerprint.contains(u.details.fingerprint))("details changed")
          s"update  ${tag(u.block.tag)} ${(moved ++ edited).mkString(", ")}"
        } ++
        plan.delete.map(b => s"delete  ${tag(b.tag)} ${show(b.interval)}") ++
        plan.unplaced.map(t => s"no slot ${tag(t)}") ++
        plan.skippedRequests.map(id => s"skipped $id (attendee not onboarded or unreachable)")
    val header = s"${if dryRun then "[dry run] " else ""}${plan.keep.size} kept, ${plan.create.size} to create, " +
      s"${plan.patch.size} to update, ${plan.delete.size} to delete"
    (header :: lines).traverse_(IO.println)

  private def onboardCmd(home: Path, config: Path, email: Option[String], provider: String, timeoutArg: Option[String]): IO[ExitCode] =
    val store = TokenStore(home)
    for
      email    <- IO.fromOption(email)(IllegalArgumentException(s"--email is required\n$Usage"))
      timeout  <- IO.fromEither(Config.duration(timeoutArg.getOrElse("1h")).leftMap(IllegalArgumentException(_)))
      cfg      <- Config.load(config)
      base     <- IO.fromOption(cfg.publicBaseUrl)(IllegalArgumentException("publicBaseUrl must be set in config.yaml"))
      registry <- providers(home, store, cfg)
      p        <- IO.fromOption(registry.get(provider))(IllegalArgumentException(s"unknown provider '$provider'"))
      acct      = Account(provider, email.trim.toLowerCase)
      invite    = Onboard.randomToken()
      onSuccess = (a: Account) =>
        store.read(a).flatMap(s => store.write(a, UserState(None)).whenA(s.isEmpty)) >>
          p.calendar.ensureBlocksCalendar(a).void.handleErrorWith(e =>
            Console[IO].errorln(s"WARN could not create blocks calendar yet (${e.getMessage}); the next run will retry")
          )
      ob <- Onboard.create(p.auth, acct.email, s"$base${Onboard.CallbackPath}", invite, onSuccess)
      result <- Onboard.serve(ob, cfg.onboardPort).use { _ =>
        IO.println(s"Send this link to ${acct.email}:\n\n  $base${Onboard.StartPath}?invite=$invite\n") >>
          IO.println(s"Waiting up to ${timeout.toMinutes} minutes...") >>
          ob.done.get.timeout(timeout.toMillis.millis).attempt
      }
      code <- result match
        case Right(a) => IO.println(s"Onboarded $a").as(ExitCode.Success)
        case Left(_)  => Console[IO].errorln("Timed out waiting for the invite to be used.").as(ExitCode.Error)
    yield code
