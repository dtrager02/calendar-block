package blocker.google

import blocker.{Account, AccountUnavailable, AuthProvider, TokenStore}
import cats.effect.IO
import com.google.api.client.auth.oauth2.{BearerToken, Credential, TokenResponse}
import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeFlow, GoogleClientSecrets}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar

import java.nio.file.{Files, Path}
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Shared Google OAuth flow and API client construction.
 *  Only refresh tokens are persisted (one file per account, via [[TokenStore]]). Access tokens live in memory,
 *  one per account per process, so runs never write token files.
 */
final class GoogleClients(credentialsFile: Path, store: TokenStore):
  private val transport   = GoogleNetHttpTransport.newTrustedTransport()
  private val json        = GsonFactory.getDefaultInstance
  private val credentials = TrieMap.empty[String, Credential]

  val flow: GoogleAuthorizationCodeFlow =
    val secrets = Using.resource(Files.newBufferedReader(credentialsFile))(GoogleClientSecrets.load(json, _))
    GoogleAuthorizationCodeFlow.Builder(transport, json, secrets, GoogleClients.Scopes.asJava).setAccessType("offline").build()

  def build(credential: HttpRequestInitializer): Calendar =
    Calendar.Builder(transport, json, credential).setApplicationName("calendar-blocker").build()

  def calendar(acct: Account): IO[Calendar] =
    credentials.get(acct.email) match
      case Some(c) => IO.pure(build(c))
      case None =>
        store.token(acct).flatMap {
          case None => IO.raiseError(AccountUnavailable(acct, "not connected"))
          case Some(refreshToken) =>
            IO {
              val c = Credential
                .Builder(flow.getMethod)
                .setTransport(flow.getTransport)
                .setJsonFactory(flow.getJsonFactory)
                .setTokenServerEncodedUrl(flow.getTokenServerEncodedUrl)
                .setClientAuthentication(flow.getClientAuthentication)
                .build()
                .setRefreshToken(refreshToken)
              credentials.update(acct.email, c)
              build(c)
            }
        }

object GoogleClients:
  val Scopes = List(
    "https://www.googleapis.com/auth/calendar.readonly",
    "https://www.googleapis.com/auth/calendar.app.created"
  )

final class GoogleAuthProvider(clients: GoogleClients, store: TokenStore) extends AuthProvider:

  def authorizationUrl(state: String, redirectUri: String): String =
    clients.flow.newAuthorizationUrl().setRedirectUri(redirectUri).setState(state).set("prompt", "consent").build()

  def completeAuthorization(code: String, redirectUri: String): IO[Either[String, Account]] =
    IO.blocking {
      val resp    = clients.flow.newTokenRequest(code).setRedirectUri(redirectUri).execute()
      val granted = Option(resp.getScope).toList.flatMap(_.split(" ")).toSet
      if !GoogleClients.Scopes.forall(granted) then
        Left("Not all requested permissions were granted. Please tick every checkbox on Google's consent screen.")
      else if resp.getRefreshToken == null then
        Left("Google did not return a refresh token. Remove calendar-blocker at myaccount.google.com/permissions.")
      else
        // A Google account's primary calendar ID is its email address.
        val email = clients.build(GoogleAuthProvider.verificationCredential(resp)).calendars().get("primary").execute().getId
        Right(Account("google", email.toLowerCase) -> resp.getRefreshToken)
    }.flatMap {
      case Right((acct, refreshToken)) => store.writeToken(acct, refreshToken).as(Right(acct))
      case Left(msg)                   => IO.pure(Left(msg))
    }

object GoogleAuthProvider:
  /** Access-token-only credential for finding out whose account this is before anything is stored.
   *  A bare `Credential` rejects refresh tokens, and none is needed for this single call.
   */
  private[google] def verificationCredential(resp: TokenResponse): Credential =
    Credential(BearerToken.authorizationHeaderAccessMethod()).setAccessToken(resp.getAccessToken)
