package blocker

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import java.time.Instant

class EventWebTest extends CatsEffectSuite:
  private val account = Account("google", "me@x.com")
  private val session = WebSession(account.provider, account.email, "csrf", Instant.now.plusSeconds(3600).getEpochSecond)
  private val yaml = """timezone: UTC
owner: me@x.com
requests:
  - id: shared
    title: Shared
    attendees: [organizer@x.com, me@x.com]
    priority: 5
    recurrence: weekly
    duration: 1h
    windows: [{days: [MON], from: '09:00', to: '17:00'}]
  - id: private
    title: Other person's event
    attendees: [other@x.com]
    priority: 2
    recurrence: daily
    duration: 30m
    windows: [{days: [MON], from: '09:00', to: '17:00'}]
  - id: owner-default
    title: Owner default
    priority: 2
    recurrence: daily
    duration: 30m
    windows: [{days: [MON], from: '09:00', to: '17:00'}]
"""
  private def setup = for
    proposals <- Ref.of[IO, List[Proposal]](Nil)
    web <- EventWeb.create(new ConfigRepository:
      def read = IO.pure(ConfigSnapshot("base", yaml))
      def submit(p: Proposal) = proposals.update(_ :+ p).as(PullRequest("https://github.com/test/config/pull/1"))
      def editUrl = "https://github.com/test/config"
    )
  yield (web, proposals)

  private def hidden(body: String, key: String): String =
    ("name=\"" + key + "\" value=\"([^\"]*)\"").r.findFirstMatchIn(body).get.group(1)
  private def fields(page: Response): Map[String, String] = EventForm.Defaults ++ Map("submission" -> hidden(page.body, "submission"),
    "csrf" -> "csrf", "title" -> "New <event>", "day0MON" -> "on", "from0" -> "10:00", "to0" -> "12:00")

  test("cards include only the user's approved events, including owner defaults") {
    setup.flatMap((web, _) => web.handle("GET", "/events", Map.empty, Map.empty, session)).map { page =>
      assert(page.body.contains("Shared"))
      assert(page.body.contains("Owner default"))
      assert(!page.body.contains("Other person's event"))
      assert(!page.body.contains("<script"))
    }
  }
  test("creating an event fixes organizer identity and retries do not submit another proposal") {
    for
      (web, proposals) <- setup
      page <- web.handle("GET", "/events", Map.empty, Map.empty, session)
      values = fields(page) + ("organizer" -> "forged@x.com")
      submitted <- web.handle("POST", "/events/submit", Map.empty, values, session)
      retry <- web.handle("POST", "/events/submit", Map.empty, values, session)
      receipt <- web.handle("GET", "/events/receipt", Map("submission" -> hidden(page.body, "submission")), Map.empty, session)
      changes <- proposals.get
    yield
      assertEquals(submitted.status, 303)
      assertEquals(retry.status, 303)
      assertEquals(changes.size, 1)
      assertEquals(changes.head.requester, account)
      val cfg = Config.parse(changes.head.yaml).toOption.get
      assertEquals(cfg.requests.last.attendees.head, account)
      assertEquals(cfg.requests.last.title, "New <event>")
      assert(receipt.body.contains("Waiting for approval"))
  }
  test("an attendee can edit a shared event while preserving organizer, ID, and unrelated source") {
    for
      (web, proposals) <- setup
      page <- web.handle("GET", "/events/edit", Map("id" -> "shared"), Map.empty, session)
      request = Config.parse(yaml).toOption.get.requests.head
      values = EventForm.from(request) ++ Map("submission" -> hidden(page.body, "submission"), "title" -> "Changed", "organizer" -> "me@x.com")
      result <- web.handle("POST", "/events/submit", Map.empty, values, session)
      changes <- proposals.get
    yield
      assertEquals(result.status, 303)
      assertEquals(changes.head.yaml, yaml.replace("title: Shared", "title: \"Changed\""))
      val edited = Config.parse(changes.head.yaml).toOption.get.requests.head
      assertEquals(edited.id, "shared")
      assertEquals(edited.attendees.head.email, "organizer@x.com")
  }
  test("any attendee can propose removal but others cannot open forms or reuse a submission") {
    for
      (web, proposals) <- setup
      forbidden <- web.handle("GET", "/events/edit", Map("id" -> "private"), Map.empty, session)
      remove <- web.handle("GET", "/events/remove", Map("id" -> "shared"), Map.empty, session)
      values = Map("submission" -> hidden(remove.body, "submission"))
      foreign <- web.handle("POST", "/events/submit", Map.empty, values, session.copy(email = "foreign@x.com"))
      result <- web.handle("POST", "/events/submit", Map.empty, values, session)
      changes <- proposals.get
    yield
      assertEquals(forbidden.status, 404)
      assertEquals(foreign.status, 409)
      assertEquals(result.status, 303)
      assert(!Config.parse(changes.head.yaml).toOption.get.requests.exists(_.id == "shared"))
      assert(remove.body.contains("future blocks"))
  }
  test("add and remove windows preserve fields without creating PRs") {
    for
      (web, proposals) <- setup
      page <- web.handle("GET", "/events", Map.empty, Map.empty, session)
      values = fields(page)
      added <- web.handle("POST", "/events/submit", Map.empty, values + ("action" -> "add-window"), session)
      removed <- web.handle("POST", "/events/submit", Map.empty, values ++ Map("windowCount" -> "2", "action" -> "remove-window-0", "from1" -> "15:00", "to1" -> "17:00", "day1FRI" -> "on"), session)
      changes <- proposals.get
    yield
      assertEquals(hidden(added.body, "windowCount"), "2")
      assert(added.body.contains("New &lt;event&gt;"))
      assert(removed.body.contains("name=\"from0\" value=\"15:00\""))
      assert(removed.body.contains("name=\"day0FRI\" checked"))
      assertEquals(changes, Nil)
  }
  test("validation errors retain input, escape markup, and create no PR") {
    for
      (web, proposals) <- setup
      page <- web.handle("GET", "/events", Map.empty, Map.empty, session)
      result <- web.handle("POST", "/events/submit", Map.empty, fields(page) + ("duration" -> "bad"), session)
      changes <- proposals.get
    yield
      assertEquals(result.status, 400)
      assert(result.body.contains("New &lt;event&gt;"))
      assert(result.body.contains("invalid duration"))
      assertEquals(changes, Nil)
  }
  test("selecting all or clearing days affects only the chosen window and preserves unfinished form fields") {
    for
      (web, proposals) <- setup
      page <- web.handle("GET", "/events", Map.empty, Map.empty, session)
      values = fields(page) ++ Map("windowCount" -> "2", "from1" -> "15:00", "to1" -> "17:00", "duration" -> "unfinished")
      selected <- web.handle("POST", "/events/submit", Map.empty, values + ("action" -> "select-all-days-1"), session)
      allSelected = values ++ EventForm.Days.map(day => s"day1$day" -> "on")
      cleared <- web.handle("POST", "/events/submit", Map.empty, allSelected + ("action" -> "clear-days-1"), session)
      invalid <- web.handle("POST", "/events/submit", Map.empty, values + ("action" -> "select-all-days-2"), session)
      changes <- proposals.get
    yield
      assertEquals(selected.status, 200)
      assertEquals(cleared.status, 200)
      EventForm.Days.foreach { day =>
        assert(selected.body.contains(s"name=\"day1$day\" checked"))
        assert(!cleared.body.contains(s"name=\"day1$day\" checked"))
      }
      List(selected, cleared).foreach { response =>
        assert(response.body.contains("name=\"day0MON\" checked"))
        assert(response.body.contains("name=\"from1\" value=\"15:00\""))
        assert(response.body.contains("name=\"duration\" value=\"unfinished\""))
        assert(response.body.contains("New &lt;event&gt;"))
        assert(response.body.contains("value=\"select-all-days-1\" formnovalidate"))
        assert(response.body.contains("value=\"clear-days-1\" formnovalidate"))
      }
      assertEquals(invalid.status, 400)
      assertEquals(changes, Nil)
  }
  test("once recurrence, finite strategy weights and unique attendees are validated") {
    val values = EventForm.Defaults ++ Map("title" -> "Once", "recurrence" -> "once", "betweenFrom" -> "2026-12-01", "betweenTo" -> "2026-12-05",
      "day0MON" -> "on", "from0" -> "09:00", "to0" -> "17:00")
    val request = EventForm.request(values, account, "once").toOption.get
    assert(request.hcursor.downField("between").get[String]("from").contains("2026-12-01"))
    assert(EventForm.request(values + ("minimal-gaps" -> "NaN"), account, "once").isLeft)
    assert(EventForm.request(values + ("attendees" -> "me@x.com"), account, "once").isLeft)
  }
