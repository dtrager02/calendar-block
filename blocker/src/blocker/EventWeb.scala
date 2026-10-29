package blocker

import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.Json
import io.circe.yaml.v12.parser

import java.time.Instant
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

private case class EventDraft(snapshot: ConfigSnapshot, account: Account, request: Option[Request], remove: Boolean,
    id: String, expires: Instant, proposal: Option[Proposal] = None, receipt: Option[PullRequest] = None)

final class EventWeb private(repository: ConfigRepository, drafts: Ref[IO, Map[String, EventDraft]], submitLock: Semaphore[IO]):
  import WebHtml.*

  def handle(method: String, path: String, query: Map[String, String], fields: Map[String, String], session: WebSession): IO[Response] =
    if method == "GET" then path match
      case "/events" => dashboard(session)
      case "/events/edit" => open(session, query.getOrElse("id", ""), false)
      case "/events/remove" => open(session, query.getOrElse("id", ""), true)
      case "/events/receipt" => drafts.get.map { all =>
        all.get(query.getOrElse("submission", "")).filter(_.account == session.account).flatMap(_.receipt) match
          case Some(pr) => page(200, s"<h2>Waiting for approval</h2><p>Your change has been proposed. <a href=\"${escape(pr.url)}\">View pull request</a>.</p><p>Once merged, it takes effect on the next hourly scheduler run.</p><a href=\"/events\">Back to events</a>")
          case None => message(404, "Submission not found. Check GitHub for previously submitted changes.")
      }
      case _ => IO.pure(message(404, "Not found."))
    else if method == "POST" && path == "/events/submit" then
      drafts.get.flatMap { all =>
        all.get(fields.getOrElse("submission", "")).filter(d => d.account == session.account && d.expires.isAfter(Instant.now)) match
          case None => IO.pure(message(409, "This form expired. Reload the dashboard to start again."))
          case Some(draft) =>
            val action = fields.getOrElse("action", "submit")
            val dayAction = action match
              case s"select-all-days-$index" => index.toIntOption.map(_ -> "on")
              case s"clear-days-$index" => index.toIntOption.map(_ -> "")
              case _ => None
            if dayAction.isDefined then
              val (index, selected) = dayAction.get
              if draft.remove || index < 0 || index >= EventForm.count(fields) then IO.pure(message(400, "Invalid form action."))
              else
                val next = fields ++ EventForm.Days.map(day => s"day$index$day" -> selected)
                IO.pure(form(session, fields("submission"), draft, next))
            else if action == "add-window" || action.startsWith("remove-window-") then
              if draft.remove then IO.pure(message(400, "Invalid form action."))
              else
                val count = EventForm.count(fields)
                val next = if action == "add-window" then fields.updated("windowCount", (count + 1).min(30).toString)
                  else
                    val remove = action.stripPrefix("remove-window-").toIntOption.getOrElse(-1)
                    val kept = (0 until count).filterNot(_ == remove)
                    if remove < 0 || remove >= count || kept.isEmpty then fields
                    else
                      val cleared = fields.filterNot { (k, _) => k.matches("(from|to|day)[0-9]+.*") }
                      kept.zipWithIndex.foldLeft(cleared.updated("windowCount", kept.size.toString)) { case (acc, (old, index)) =>
                        val times = List("from", "to").map(k => s"$k$index" -> fields.getOrElse(s"$k$old", ""))
                        acc ++ times ++ EventForm.Days.map(day => s"day$index$day" -> fields.getOrElse(s"day$old$day", ""))
                      }
                IO.pure(form(session, fields("submission"), draft, next))
            else if action != "submit" then IO.pure(message(400, "Invalid form action."))
            else submitLock.permit.use(_ => submit(session, fields("submission"), fields))
      }
    else IO.pure(message(405, "Method not allowed."))

  private def newDraft(snapshot: ConfigSnapshot, account: Account, request: Option[Request], remove: Boolean): IO[(String, EventDraft)] =
    val token = WebSessions.randomToken()
    val d = EventDraft(snapshot, account, request, remove, request.fold(EventForm.newId("event"))(_.id), Instant.now.plusSeconds(3600))
    drafts.modify { all =>
      val fresh = all.filter(_._2.expires.isAfter(Instant.now))
      if fresh.size >= 1000 then (fresh, false) else (fresh.updated(token, d), true)
    }.flatMap(ok => if ok then IO.pure(token -> d) else IO.raiseError(IllegalStateException("Too many open forms. Please try again later.")))

  private def config(snapshot: ConfigSnapshot): IO[AppConfig] = IO.fromEither(Config.parse(snapshot.yaml).leftMap(IllegalStateException(_)))

  private def dashboard(session: WebSession): IO[Response] = for
    snapshot <- repository.read
    cfg <- config(snapshot)
    draft <- newDraft(snapshot, session.account, None, false)
    cards = cfg.requests.filter(_.emails(session.email)).map { r =>
      val id = URLEncoder.encode(r.id, UTF_8)
      s"""<article><h3>${escape(r.title)}</h3>
         |${r.description.fold("")(d => s"<p>${escape(d)}</p>")}${r.location.fold("")(l => s"<p>Location: ${escape(l)}</p>")}
         |<p>${r.duration.toMinutes} minutes · ${escape(r.recurrence.toString.toLowerCase)}</p>
         |${r.between.fold("")((f, t) => s"<p>$f through $t</p>")}
         |<p>Organizer: ${escape(r.attendees.head.email)}</p><p>Attendees: ${escape(r.attendees.map(_.email).mkString(", "))}</p>
         |<p>${escape(r.windows.map(w => s"${w.days.toList.sorted.mkString(", ")} ${w.from}–${w.to}").mkString("; "))}</p>
         |<p>Priority: ${r.priority} · ${escape(r.strategies.toList.sortBy(_._1.toString).map((k, v) => s"$k: $v").mkString(", "))}</p>
         |<a href="/events/edit?id=$id">Edit</a> · <a href="/events/remove?id=$id">Remove</a></article>""".stripMargin
    }.mkString
    intro = s"<p>Signed in as ${escape(session.email)}</p><form method=\"post\" action=\"/signout\">${hidden("csrf", session.csrf)}<button>Sign out</button></form>" +
      s"<p>All time windows use <strong>${escape(cfg.settings.zone.toString)}</strong>.</p><p>These are approved configurations. Proposed changes require a manually merged GitHub PR and take effect on the next hourly run.</p>" +
      "<h2>Your events</h2>" + (if cards.isEmpty then "<p>No events mention you yet. Create your first below.</p>" else s"<div class=\"grid\">$cards</div>")
  yield form(session, draft._1, draft._2, EventForm.Defaults, intro = intro)

  private def open(session: WebSession, id: String, remove: Boolean): IO[Response] = for
    snapshot <- repository.read
    cfg <- config(snapshot)
    result <- cfg.requests.find(r => r.id == id && r.emails(session.email)) match
      case None => IO.pure(message(404, "Event not found."))
      case Some(r) => newDraft(snapshot, session.account, Some(r), remove).map { (token, draft) =>
        form(session, token, draft, EventForm.from(r), intro = s"<p>Time windows use ${escape(cfg.settings.zone.toString)}.</p>")
      }
  yield result

  private def submit(session: WebSession, token: String, fields: Map[String, String]): IO[Response] = drafts.get.flatMap { all =>
    val draft = all(token)
    val id = draft.request.fold(draft.proposal.fold(EventForm.newId(fields.getOrElse("title", "event")))(_.requestId))(_.id)
    def receipt = Response(303, "", Some(s"/events/receipt?submission=$token"))
    if draft.receipt.isDefined then IO.pure(receipt)
    else
      val organizer = draft.request.fold(session.account)(_.attendees.head)
      val replacement = if draft.remove then Right(None) else EventForm.request(fields, organizer, id).map(Some(_))
      val edited = replacement.flatMap { replacement =>
        val value = draft.request match
          case Some(request) if replacement.isDefined =>
            val original = parser.parse(draft.snapshot.yaml).toOption.get.hcursor.get[List[Json]]("requests").toOption.get.find(_.hcursor.get[String]("id").contains(draft.id)).get
            val baseline = EventForm.canonical(request)
            val submitted = replacement.get
            val keys = baseline.asObject.get.keys.toSet ++ submitted.asObject.get.keys.toSet
            Some(keys.foldLeft(original) { (current, key) =>
              if baseline.hcursor.downField(key).focus == submitted.hcursor.downField(key).focus then current
              else current.mapObject(o => submitted.hcursor.downField(key).focus.fold(o.remove(key))(v => o.add(key, v)))
            })
          case _ => replacement
        ConfigEdits.change(draft.snapshot.yaml, id, value, draft.request.isEmpty)
      }
      edited match
        case Left(error) => IO.pure(form(session, token, draft, fields, Some(error), status = 400))
        case Right(yaml) if yaml == draft.snapshot.yaml => IO.pure(form(session, token, draft, fields, Some("No changes to propose."), status = 400))
        case Right(yaml) =>
          val proposal = Proposal(token, draft.snapshot.commit, if draft.remove then "remove" else if draft.request.isDefined then "update" else "create", id, yaml, session.account)
          if draft.proposal.exists(_ != proposal) then IO.pure(form(session, token, draft, fields, Some("This submission already started. Retry the original change, or reload to start a new proposal."), status = 409))
          else
            drafts.update(_.updated(token, draft.copy(id = id, proposal = Some(proposal)))) >>
              repository.submit(proposal).flatMap { pr =>
                drafts.update(_.updated(token, draft.copy(id = id, proposal = Some(proposal), receipt = Some(pr)))).as(receipt)
              }.handleErrorWith {
                case e: ConfigConflict => IO.pure(form(session, token, draft, fields, Some(e.message), status = 409))
                case _ =>
                IO.pure(form(session, token, draft, fields, Some("The PR could not be confirmed. The config may have changed, or GitHub may be unavailable. Retry this same change safely; reload to start a different proposal. " + repository.editUrl), status = 503))
              }
  }

  private def form(session: WebSession, token: String, draft: EventDraft, fields: Map[String, String], error: Option[String] = None,
      intro: String = "", status: Int = 200): Response =
    def value(k: String): String = fields.getOrElse(k, "")
    def input(k: String, label: String, kind: String = "text", required: Boolean = false): String =
      s"<label>${escape(label)}<input type=\"$kind\" name=\"$k\" value=\"${escape(value(k))}\"${if required then " required" else ""}></label>"
    val controls = hidden("submission", token) + hidden("csrf", session.csrf)
    val heading = if draft.remove then "Remove event" else if draft.request.isDefined then "Edit event" else "Create an event"
    val body = if draft.remove then
      s"<p>Propose removing <strong>${escape(draft.request.get.title)}</strong>? After this PR is merged, the next run deletes all its future blocks, including shared invitations. Started events stay unchanged.</p>"
    else
      val recurrence = List("daily", "weekly", "once").map(r => s"<option${if value("recurrence") == r then " selected" else ""}>$r</option>").mkString
      val windows = (0 until EventForm.count(fields)).map { i =>
        val days = EventForm.Days.map(d => s"<label class=\"day\"><input type=\"checkbox\" name=\"day$i$d\"${if value(s"day$i$d") == "on" then " checked" else ""}>$d</label>").mkString
        s"<fieldset><legend>Time window ${i + 1}</legend>$days" +
          s"<div><button name=\"action\" value=\"select-all-days-$i\" formnovalidate>Select all days</button>" +
          s"<button name=\"action\" value=\"clear-days-$i\" formnovalidate>Clear days</button></div>" +
          s"${input(s"from$i", "From", "time", true)}${input(s"to$i", "To", "time", true)}" +
          (if EventForm.count(fields) > 1 then s"<button name=\"action\" value=\"remove-window-$i\" formnovalidate>Remove window</button>" else "") + "</fieldset>"
      }.mkString
      input("title", "Title", required = true) + s"<label>Description<textarea name=\"description\">${escape(value("description"))}</textarea></label>" +
        input("location", "Location") + s"<p>Organizer: ${escape(draft.request.fold(session.account)(_.attendees.head).email)}</p>" +
        input("attendees", "Other attendees (email addresses separated by commas)") +
        input("duration", "Duration (for example 60m or 1h30m)", required = true) +
        s"<label>Recurrence<select name=\"recurrence\">$recurrence</select></label>" +
        input("betweenFrom", "For once only: earliest date", "date") + input("betweenTo", "For once only: latest date", "date") +
        hidden("windowCount", EventForm.count(fields).toString) + windows + "<button name=\"action\" value=\"add-window\" formnovalidate>Add window</button>" +
        s"<details><summary>Advanced options</summary>${input("priority", "Priority (higher wins)", "number", true)}" +
        input("prefer-soonest", "Prefer soonest weight") + input("minimal-gaps", "Minimal gaps weight") + "</details>"
    page(status, intro + s"<h2>$heading</h2>" + error.fold("")(e => s"<p role=\"alert\">${escape(e)} <a href=\"${escape(repository.editUrl)}\">Open config repository</a></p>") +
      s"<form method=\"post\" action=\"/events/submit\">$controls$body<button name=\"action\" value=\"submit\">Propose ${if draft.remove then "removal" else "change"}</button></form><p><a href=\"/events\">Back to events</a></p>")

object EventWeb:
  def create(repository: ConfigRepository): IO[EventWeb] =
    (Ref.of[IO, Map[String, EventDraft]](Map.empty), Semaphore[IO](1)).mapN(new EventWeb(repository, _, _))

object WebHtml:
  def escape(s: String): String = s.flatMap {
    case '&' => "&amp;"
    case '<' => "&lt;"
    case '>' => "&gt;"
    case '"' => "&quot;"
    case '\'' => "&#39;"
    case c => c.toString
  }
  def hidden(name: String, value: String): String = s"<input type=\"hidden\" name=\"$name\" value=\"${escape(value)}\">"
  def message(status: Int, text: String): Response = page(status, s"<p>${escape(text)}</p><a href=\"/\">Home</a>")
  def page(status: Int, body: String): Response = Response(status,
    s"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
       |<title>calendar-blocker</title><style>
       |body{font:16px/1.5 system-ui,sans-serif;max-width:70rem;margin:2rem auto;padding:0 1rem;color:#202124;background:#fafafa;min-height:calc(100vh - 4rem);box-sizing:border-box;display:flex;flex-direction:column}
       |a{color:#1659ad}h1,h2,h3{line-height:1.2}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,19rem),1fr));gap:1rem}
       |article,fieldset{border:1px solid #ccc;padding:1rem;margin:1rem 0;min-width:0;overflow-wrap:anywhere}form{max-width:42rem}
       |label{display:block;margin:.7rem 0}input:not([type=checkbox]),textarea,select{display:block;box-sizing:border-box;width:100%;padding:.5rem;font:inherit}
       |button,.signin{font:inherit;padding:.6rem 1rem;margin:.4rem .4rem .4rem 0;cursor:pointer}.signin{display:block;max-width:20rem;text-align:center;border:1px solid #bbb;text-decoration:none}
       |button:disabled{cursor:default}.day{display:inline-block;margin:.4rem}details{margin:1rem 0}[role=alert]{border-left:4px solid #a21;padding:1rem;background:#fff0ed}
       |footer{margin-top:auto;padding-top:2rem;text-align:right;font-size:.75rem}footer a{color:#555}
       |</style></head><body><main><h1>calendar-blocker</h1>$body</main><footer><nav aria-label="Legal">
       |<a href="/terms">Terms of Service</a> · <a href="/privacy">Privacy Policy</a>
       |</nav></footer></body></html>""".stripMargin)
