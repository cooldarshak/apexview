package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoginCookieTest {
    @Test
    fun malformedPercentEncodingCannotCrashOrPreventRawJsonLogin() {
        assertEquals("synthetic%token", LoginCookie.parseToken(
            """login-session={"data":{"subscriptionToken":"synthetic%token"}}"""))
        assertNull(LoginCookie.parseToken("login-session=%ZZ"))
        assertNull(LoginCookie.parseToken("other-cookie=abc"))
    }

    @Test
    fun urlEncodedJsonStillParses() {
        val json = """{"data":{"subscriptionToken":"synthetic+token"}}"""
        val encoded = java.net.URLEncoder.encode(json, "UTF-8")
        assertEquals("synthetic+token", LoginCookie.parseToken("other=x; login-session=$encoded"))
    }
}
