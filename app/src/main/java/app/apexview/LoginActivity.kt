package app.apexview

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Shows F1's own sign-in page. Once the login-session cookie appears, saves the token and closes.
 * Use email and password: Google sign-in refuses to run inside an embedded browser.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!captureToken()) handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Look like plain Chrome; some sign-in pages reject the WebView marker.
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Keep F1's own sign-in page, but make the active WebView control obvious on TV.
                // The WebView retains its native DPAD navigation and form-entry behaviour.
                view?.evaluateJavascript(
                    """
                    (() => {
                      const id = 'apexview-tv-focus-style';
                      let style = document.getElementById(id);
                      if (!style) {
                        style = document.createElement('style');
                        style.id = id;
                        (document.head || document.documentElement).appendChild(style);
                      }
                      style.textContent = 'button:focus, input:focus, select:focus, textarea:focus, a:focus, [tabindex]:focus { outline: 3px solid #168BFF !important; outline-offset: 2px !important; box-shadow: 0 0 0 2px rgba(22,139,255,.28) !important; }';
                      const active = document.activeElement;
                      if (!active || active === document.body || active === document.documentElement) {
                        const first = document.querySelector('input:not([type=hidden]), button, a[href], select, textarea, [tabindex="0"]');
                        if (first) first.focus();
                      }
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        }
        setContentView(web)

        web.loadUrl(LoginCookie.LOGIN_URL)
        web.requestFocus()
        handler.post(poll)
    }

    private fun captureToken(): Boolean {
        val token = LoginCookie.readToken() ?: return false
        TokenStore.save(this, token)
        setResult(RESULT_OK)
        finish()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        web.destroy()
        super.onDestroy()
    }
}
