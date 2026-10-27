package blocker.google

import com.google.api.client.auth.oauth2.TokenResponse
import munit.FunSuite

class GoogleAuthProviderTest extends FunSuite:

  test("verification credential accepts a token response that includes a refresh token") {
    val resp = TokenResponse().setAccessToken("access").setRefreshToken("refresh").setExpiresInSeconds(3600L)
    val cred = GoogleAuthProvider.verificationCredential(resp)
    assertEquals(cred.getAccessToken, "access")
    assertEquals(cred.getRefreshToken, null)
  }
