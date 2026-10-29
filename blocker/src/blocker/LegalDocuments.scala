package blocker

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using

object LegalDocuments:
  val Paths = Map("/terms" -> "terms.txt", "/privacy" -> "privacy.txt")

  def response(path: String): Response =
    val resource = s"/legal/${Paths(path)}"
    val body = Using.resource(Option(getClass.getResourceAsStream(resource))
      .getOrElse(throw IllegalStateException(s"Missing bundled document: $resource")))(stream => new String(stream.readAllBytes(), UTF_8))
    Response(200, body, contentType = "text/plain; charset=utf-8")
