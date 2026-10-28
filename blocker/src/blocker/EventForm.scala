package blocker

import io.circe.Json
import io.circe.syntax.*

import java.time.DayOfWeek
import java.util.UUID
import scala.util.Try

object EventForm:
  type Fields = Map[String, String]
  val Days = List("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
  val Defaults: Fields = Map("title" -> "", "duration" -> "60m", "recurrence" -> "weekly", "priority" -> "5",
    "prefer-soonest" -> "1", "minimal-gaps" -> "0", "windowCount" -> "1")

  def from(request: Request): Fields =
    Defaults ++ Map("title" -> request.title, "description" -> request.description.getOrElse(""), "location" -> request.location.getOrElse(""),
      "attendees" -> request.attendees.tail.mkString(", "), "duration" -> s"${request.duration.toMinutes}m",
      "recurrence" -> request.recurrence.toString.toLowerCase, "priority" -> request.priority.toString,
      "prefer-soonest" -> request.strategies.getOrElse(Strategy.PreferSoonest, 0.0).toString,
      "minimal-gaps" -> request.strategies.getOrElse(Strategy.MinimalGaps, 0.0).toString,
      "betweenFrom" -> request.between.fold("")(_._1.toString), "betweenTo" -> request.between.fold("")(_._2.toString),
      "windowCount" -> request.windows.size.toString) ++ request.windows.zipWithIndex.flatMap { (w, i) =>
        Map(s"from$i" -> w.from.toString, s"to$i" -> w.to.toString) ++ Days.zipWithIndex.map { (d, j) =>
          s"day$i$d" -> (if w.days(DayOfWeek.of(j + 1)) then "on" else "")
        }
      }

  def count(fields: Fields): Int = fields.get("windowCount").flatMap(_.toIntOption).getOrElse(1).max(1).min(30)

  def canonical(request: Request): Json =
    val fields = List(
      "id" -> request.id.asJson, "title" -> request.title.trim.asJson, "priority" -> request.priority.asJson,
      "attendees" -> request.attendees.map(a => if a.provider == Account.DefaultProvider then a.email else a.toString).asJson,
      "recurrence" -> request.recurrence.toString.toLowerCase.asJson, "duration" -> s"${request.duration.toMinutes}m".asJson,
      "windows" -> request.windows.map(w => Json.obj("days" -> Days.zipWithIndex.collect {
        case (day, index) if w.days(DayOfWeek.of(index + 1)) => day
      }.asJson, "from" -> w.from.toString.asJson, "to" -> w.to.toString.asJson)).asJson,
      "strategies" -> Json.obj("prefer-soonest" -> request.strategies.getOrElse(Strategy.PreferSoonest, 0.0).asJson,
        "minimal-gaps" -> request.strategies.getOrElse(Strategy.MinimalGaps, 0.0).asJson)
    ) ++ request.description.map("description" -> _.asJson) ++ request.location.map("location" -> _.asJson) ++
      request.between.map((from, to) => "between" -> Json.obj("from" -> from.toString.asJson, "to" -> to.toString.asJson))
    Json.obj(fields*)

  def request(fields: Fields, organizer: Account, id: String): Either[String, Json] = Try {
    def value(key: String): String = fields.getOrElse(key, "").trim
    require(value("title").nonEmpty, "Please enter an event title.")
    require(value("title").length <= 300, "Event titles must be at most 300 characters.")
    val guests = value("attendees").split("[,;\\s]+").filter(_.nonEmpty).map(Account.parse).toList
    val attendees = organizer :: guests
    require(attendees.forall(a => a.email.matches("[^/\\\\\\s@]+@[^/\\\\\\s@]+") && a.provider.matches("[a-z0-9-]+")), "Enter valid attendee emails, optionally prefixed with a provider.")
    require(attendees.map(_.email).distinct.size == attendees.size, "An attendee can only be listed once.")
    val priority = value("priority").toIntOption.getOrElse(throw IllegalArgumentException("Priority must be an integer."))
    val weights = List("prefer-soonest", "minimal-gaps").map { key =>
      val weight = value(key).toDoubleOption.getOrElse(throw IllegalArgumentException(s"Invalid weight for $key."))
      require(weight.isFinite && weight >= 0, "Strategy weights must be finite and nonnegative.")
      key -> weight.asJson
    }
    val windows = (0 until count(fields)).map { i => Json.obj(
      "days" -> Days.filter(d => value(s"day$i$d") == "on").asJson,
      "from" -> value(s"from$i").asJson, "to" -> value(s"to$i").asJson
    ) }
    val base = Json.obj("id" -> id.asJson, "title" -> value("title").asJson, "priority" -> priority.asJson,
      "attendees" -> attendees.map(a => if a.provider == Account.DefaultProvider then a.email else a.toString).asJson,
      "recurrence" -> value("recurrence").asJson, "duration" -> value("duration").asJson,
      "windows" -> Json.arr(windows*), "strategies" -> Json.obj(weights*))
    val optional = List("description", "location").filter(k => value(k).nonEmpty).map(k => k -> value(k).asJson) ++
      Option.when(value("recurrence") == "once")("between" -> Json.obj("from" -> value("betweenFrom").asJson, "to" -> value("betweenTo").asJson))
    base.mapObject(o => optional.foldLeft(o) { case (obj, (key, v)) => obj.add(key, v) })
  }.toEither.left.map(e => Option(e.getMessage).getOrElse("Invalid event."))

  def newId(title: String): String =
    val slug = title.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-").take(40)
    s"${if slug.isEmpty then "event" else slug}-${UUID.randomUUID().toString.take(8)}"
