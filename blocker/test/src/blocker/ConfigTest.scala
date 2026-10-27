package blocker

import munit.FunSuite

import java.time.{DayOfWeek, Duration, LocalTime}

class ConfigTest extends FunSuite:

  private val example =
    """|timezone: America/Los_Angeles
       |owner: Me@Gmail.com
       |publicBaseUrl: https://example.com/
       |userOverrides:
       |  me@gmail.com: { excludeCalendars: [holidays] }
       |requests:
       |  - id: gaming
       |    title: Gaming
       |    description: "Discord: #gaming"
       |    location: "  "
       |    priority: 5
       |    recurrence: weekly
       |    duration: 2h
       |    windows: [{ days: [FRI, SAT, sun], from: 18:00, to: "23:30" }]
       |    strategies: { minimal-gaps: 2, prefer-soonest: 1 }
       |  - id: band
       |    priority: 8
       |    attendees: [me@gmail.com, b@gmail.com]
       |    recurrence: once
       |    between: { from: 2026-12-01, to: 2026-12-20 }
       |    duration: 1h30m
       |    windows: [{ days: [Saturday], from: "10:00", to: "20:00" }]
       |""".stripMargin

  test("parses a full config with defaults") {
    val cfg = Config.parse(example).fold(fail(_), identity)
    assertEquals(cfg.settings.horizonDays, 30)
    assertEquals(cfg.settings.weekStart, DayOfWeek.MONDAY)
    assertEquals(cfg.settings.slotStep, Duration.ofMinutes(15))
    assertEquals(cfg.publicBaseUrl, Some("https://example.com"))
    assertEquals(cfg.calendarName, "Auto Blocks")
    assertEquals(cfg.snapshotOptions(Account("google", "me@gmail.com")).excludeCalendars, Set("holidays"))

    val List(gaming, band) = cfg.requests: @unchecked
    assertEquals(gaming.attendees, List(Account("google", "me@gmail.com")))
    assertEquals(gaming.windows.head.days, Set(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
    assertEquals(gaming.windows.head.from, LocalTime.of(18, 0))
    assertEquals(gaming.strategies, Map(Strategy.MinimalGaps -> 2.0, Strategy.PreferSoonest -> 1.0))
    assertEquals(gaming.details, EventDetails("Gaming", Some("Discord: #gaming"), None))
    assertEquals(band.title, "band")
    assertEquals(band.duration, Duration.ofMinutes(90))
    assertEquals(band.attendees.map(_.email), List("me@gmail.com", "b@gmail.com"))
    assertEquals(band.strategies, Map(Strategy.PreferSoonest -> 1.0))
    assertEquals(band.order, 1)
  }

  private def error(yaml: String): String = Config.parse(yaml).swap.getOrElse(fail("expected an error"))
  private val base = "timezone: UTC\nowner: me@x.com\nrequests:\n"
  private val req  = "  - { id: r, priority: 1, recurrence: weekly, duration: 2h, windows: [{ days: [SAT], from: '10:00', to: '12:00' }] }\n"

  test("reports clear errors") {
    assert(error(base + req.replace("2h", "2 hours")).contains("request 'r': invalid duration '2 hours'"))
    assert(error(base + req.replace("weekly", "once")).contains("requires 'between'"))
    assert(error(base + req.replace("SAT", "Xyz")).contains("invalid day 'Xyz'"))
    assert(error(base + req.replace("'12:00'", "'09:00'")).contains("must be before"))
    assert(error(base + req + req).contains("duplicate request ids: r"))
    assert(error(base + req.replace("priority: 1", "priority: 1, strategies: { fastest: 1 }")).contains("unknown strategy"))
    assert(error(base + req.replace("priority: 1", "priorty: 1")).contains("invalid config"))
    assert(error("timezone: Mars/Base\nowner: me@x.com\n").contains("invalid timezone"))
  }

  test("Account.parse accepts a provider prefix") {
    assertEquals(Account.parse("B@X.com"), Account("google", "b@x.com"))
    assertEquals(Account.parse("outlook:b@x.com"), Account("outlook", "b@x.com"))
  }
