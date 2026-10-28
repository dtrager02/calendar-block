package blocker

import io.circe.Json
import io.circe.yaml.v12.parser
import munit.FunSuite

class ConfigEditsTest extends FunSuite:
  private val base = "timezone: UTC # shared zone\nowner: me@x.com\n"
  private val first = "  - id: first # stable\n    title: 'First 🎲' # display\n    priority: 5\n    recurrence: weekly\n    duration: 1h\n    windows: [{days: [MON], from: '09:00', to: '17:00'}]\n"
  private val second = "  # second event\n  - {id: second, title: Other, priority: 2, recurrence: daily, duration: 30m, windows: [{days: [TUE], from: '10:00', to: '12:00'}]}\n"
  private val source = base + "requests:\n" + first + second + "horizonDays: 20 # after requests\n"
  private def requests(s: String): List[Json] = parser.parse(s).toOption.get.hcursor.get[List[Json]]("requests").toOption.get
  private def success(e: Either[String, String]): String = e.fold(fail(_), identity)
  private val addition = requests(base + "requests:\n" + first).head.mapObject(_.add("id", Json.fromString("new")))

  test("append preserves all existing bytes and supports settings following the list") {
    val result = success(ConfigEdits.change(source, "new", Some(addition), true))
    assert(result.contains(first + second))
    assert(result.startsWith(base))
    assert(result.endsWith("horizonDays: 20 # after requests\n"))
    assertEquals(requests(result).size, 3)
  }
  test("scalar edit preserves quotes, Unicode positions, and inline comments around the changed field") {
    val replacement = requests(source).head.mapObject(_.add("title", Json.fromString("Changed 🎯")))
    val result = success(ConfigEdits.change(source, "first", Some(replacement), false))
    assertEquals(result, source.replace("'First 🎲'", "\"Changed 🎯\""))
  }
  test("collection edits retain comments and unrelated event formatting") {
    val replacement = requests(source).head.mapObject(_.add("attendees", Json.arr(Json.fromString("me@x.com"), Json.fromString("friend@x.com"))))
    val result = success(ConfigEdits.change(source, "first", Some(replacement), false))
    assert(result.contains("# stable"))
    assert(result.contains("# display"))
    assert(result.contains(second))
    assert(result.endsWith("horizonDays: 20 # after requests\n"))
  }
  test("remove handles first, last, and only entries") {
    val removedFirst = success(ConfigEdits.change(source, "first", None, false))
    assert(removedFirst.contains(second))
    assertEquals(requests(removedFirst).map(_.hcursor.get[String]("id").toOption.get), List("second"))
    val removedLast = success(ConfigEdits.change(source, "second", None, false))
    assert(removedLast.contains(first))
    assert(!removedLast.contains("# second event"))
    val removedOnly = success(ConfigEdits.change(base + "requests:\n" + first, "first", None, false))
    assertEquals(requests(removedOnly), Nil)
  }
  test("empty and missing lists support creation, including CRLF") {
    List(base, base + "requests: [] # empty\n", (base + "requests: []\n").replace("\n", "\r\n")).foreach { original =>
      val result = success(ConfigEdits.change(original, "new", Some(addition), true))
      assertEquals(requests(result), List(addition))
      assert(result.startsWith(base.takeWhile(_ != '\n')))
    }
  }
  test("flow lists support creation editing and deletion") {
    val a = requests(source).head
    val b = requests(source)(1)
    val flow = base + s"requests: [${a.noSpaces}, ${b.noSpaces}] # list\n"
    val added = success(ConfigEdits.change(flow, "new", Some(addition), true))
    assertEquals(requests(added), List(a, b, addition))
    assertEquals(requests(success(ConfigEdits.change(flow, "first", None, false))), List(b))
    assertEquals(requests(success(ConfigEdits.change(flow, "second", None, false))), List(a))
    val trailingComma = flow.replace("] # list", ",] # list")
    assertEquals(requests(success(ConfigEdits.change(trailingComma, "new", Some(addition), true))), List(a, b, addition))
  }
  test("deletion removes leading comments belonging to the first event and preserves the next event's comments") {
    val original = source.replace("requests:\n", "requests:\n  # first event\n")
    val result = success(ConfigEdits.change(original, "first", None, false))
    assert(!result.contains("# first event"))
    assert(result.contains(second))
  }
  test("multiline values remain safe when changed") {
    val original = source.replace("title: 'First 🎲' # display", "title: |\n      Multiline\n      title")
    val replacement = requests(original).head.mapObject(_.add("title", Json.fromString("New")))
    val result = success(ConfigEdits.change(original, "first", Some(replacement), false))
    assert(result.contains(second))
    assertEquals(requests(result).head.hcursor.get[String]("title"), Right("New"))
  }
  test("invalid changes and aliases are rejected") {
    assert(ConfigEdits.change(source, "missing", None, false).isLeft)
    assert(ConfigEdits.change(source, "new", Some(addition.mapObject(_.add("duration", Json.fromString("0m")))) , true).isLeft)
    val alias = base + "requests: [&request " + addition.noSpaces + ", *request]\n"
    assert(ConfigEdits.change(alias, "new", None, false).left.toOption.get.contains("aliases"))
  }
