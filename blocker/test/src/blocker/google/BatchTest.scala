package blocker.google

import blocker.*
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.{MockHttpTransport, MockLowLevelHttpRequest, MockLowLevelHttpResponse}
import com.google.api.services.calendar.Calendar
import munit.FunSuite

import java.io.IOException
import java.time.Instant
import scala.collection.mutable.ListBuffer

class BatchTest extends FunSuite:
  import GoogleCalendarProvider.*

  /** Records every HTTP call and answers each batch part with `status(n)`, n counting parts across all calls. */
  private class Transport(status: Int => Int = _ => 200) extends MockHttpTransport:
    val urls  = ListBuffer.empty[String]
    var parts = 0
    override def buildRequest(method: String, url: String): LowLevelHttpRequest =
      urls += url
      new MockLowLevelHttpRequest(url):
        override def execute() =
          val count = "application/http".r.findAllIn(getContentAsString).size
          val body = (0 until count).map { k =>
            val code = status(parts + k)
            s"""--batch_x\r
               |Content-Type: application/http\r
               |Content-ID: <response-${k + 1}>\r
               |\r
               |HTTP/1.1 $code ${if code == 200 then "OK" else "Error"}\r
               |Content-Type: application/json; charset=UTF-8\r
               |\r
               |${if code == 200 then "{}" else s"""{"error":{"code":$code,"message":"nope"}}"""}\r
               |""".stripMargin
          }
          parts += count
          MockLowLevelHttpResponse().setContentType("multipart/mixed; boundary=batch_x").setContent(body.mkString + "--batch_x--\r\n")

  private def calendar(t: Transport) = Calendar.Builder(t, GsonFactory.getDefaultInstance, null).setApplicationName("test").build()

  private val acct = Account("google", "a@x.com")
  private val i    = Interval(Instant.parse("2026-10-31T10:00:00Z"), Instant.parse("2026-10-31T12:00:00Z"))
  private val ops = List(
    BlockOp.Create("blocks", NewBlock(BlockTag("r", "once"), EventDetails("R", None, None), i, acct, Nil)),
    BlockOp.Update(BlockRef(acct, "blocks", "e1"), i, EventDetails("R", None, None)),
    BlockOp.Delete(BlockRef(acct, "blocks", "e2"))
  )

  test("a create, an update and a delete go out as one HTTP call to the batch endpoint") {
    val t   = Transport()
    val cal = calendar(t)
    batched(cal, ops.map(request(cal, _)))
    assertEquals(t.urls.toList, List("https://www.googleapis.com/batch/calendar/v3"))
  }

  test("more than 50 requests are split into batches of 50") {
    val t   = Transport()
    val cal = calendar(t)
    assertEquals(batched(cal, List.fill(51)(request(cal, ops.last))).size, 51)
    assertEquals(t.urls.size, 2)
    assertEquals(t.parts, 51)
  }

  test("any failed request fails the whole call") {
    val t   = Transport(n => if n == 1 then 404 else 200)
    val cal = calendar(t)
    val e   = intercept[IOException](batched(cal, ops.map(request(cal, _))))
    assert(e.getMessage.contains("1 of 3 Google Calendar requests failed, e.g. 404 nope"), e.getMessage)
  }
