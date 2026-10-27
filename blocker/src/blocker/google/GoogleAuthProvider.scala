package blocker.google

import blocker.{Account, AuthProvider, TokenStore}
import cats.effect.IO
import com.google.api.client.auth.oauth2.{BearerToken, Credential, TokenResponse}
import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeFlow, GoogleClientSecrets}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.calendar.Calendar

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Shared Google OAuth flow and API client construction. Tokens live in `tokenDir`, keyed by email. */
final class GoogleClients(credentialsFile: Path, tokenDir: Path):
  private val transport = GoogleNetHttpTransport.newTrustedTransport()
  private val json      = GsonFactory.getDefaultInstance

  val flow: GoogleAuthorizationCodeFlow =
    TokenStore.privateDirs(tokenDir)
    val secrets = Using.resource(Files.newBufferedReader(credentialsFile))(GoogleClientSecrets.load(json, _))
    val flow = GoogleAuthorizationCodeFlow
      .Builder(transport, json, secrets, GoogleClients.Scopes.asJava)
      .setDataStoreFactory(FileDataStoreFactory(tokenDir.toFile))
      .setAccessType("offline")
      .build()
    TokenStore.privateFiles(tokenDir)
    flow

  def build(credential: HttpRequestInitializer): Calendar =
    Calendar.Builder(transport, json, credential).setApplicationName("calendar-blocker").build()

  def calendar(acct: Account): IO[Calendar] =
    IO.blocking(Option(flow.loadCredential(acct.email))).flatMap {
      case Some(c) => IO.pure(build(c))
      case None    => IO.raiseError(IllegalStateException(s"no stored Google token for ${acct.email}; run onboard"))
    }

object GoogleClients:
  val Scopes = List(
    "https://www.googleapis.com/auth/calendar.readonly",
    "https://www.googleapis.com/auth/calendar.app.created"
  )

final class GoogleAuthProvider(clients: GoogleClients) extends AuthProvider:

  def authorizationUrl(state: String, loginHint: String, redirectUri: String): String =
    clients.flow
      .newAuthorizationUrl()
      .setRedirectUri(redirectUri)
      .setState(state)
      .set("prompt", "consent")
      .set("login_hint", loginHint)
      .build()

  def completeAuthorization(code: String, redirectUri: String, expectedEmail: String): IO[Either[String, Account]] =
    IO.blocking {
      val resp    = clients.flow.newTokenRequest(code).setRedirectUri(redirectUri).execute()
      val granted = Option(resp.getScope).toList.flatMap(_.split(" ")).toSet
      if !GoogleClients.Scopes.forall(granted) then
        Left("Not all requested permissions were granted. Please tick every checkbox on Google's consent screen.")
      else if resp.getRefreshToken == null then
        Left("Google did not return a refresh token. Remove calendar-blocker at myaccount.google.com/permissions.")
      else
        val primary = clients.build(GoogleAuthProvider.verificationCredential(resp)).calendars().get("primary").execute().getId
        if !primary.equalsIgnoreCase(expectedEmail) then
          Left(s"You signed in as $primary, but this invite is for $expectedEmail.")
        else
          clients.flow.createAndStoreCredential(resp, expectedEmail)
          Right(Account("google", expectedEmail))
    }

object GoogleAuthProvider:
  /** Access-token-only credential for checking whose account this is before anything is stored.
   *  A bare `Credential` rejects refresh tokens, and none is needed for this single call.
   */
  private[google] def verificationCredential(resp: TokenResponse): Credential =
    Credential(BearerToken.authorizationHeaderAccessMethod()).setAccessToken(resp.getAccessToken)
