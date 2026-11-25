package com.srineere.airbridge.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.srineere.airbridge.band.HelioKeyStore
import com.srineere.airbridge.ui.theme.extras
import kotlinx.coroutines.launch
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.ZeppCloud
import org.openstrap.app.zepp.ZeppCloudException

/**
 * Signing in to Zepp, which is the only way to get a Helio Strap's key.
 *
 * The one screen in this app that opens a network connection, and it is
 * worth being exact about what it does. The person signs in on Zepp's own
 * page, inside a web view: **Cuff never sees the password**, only the
 * session cookie the page leaves behind afterwards. That cookie yields a
 * token, the token fetches the account's device list, and the strap's entry
 * in that list carries the pairing key. The key is stored; the cookies are
 * thrown away; nothing else is asked for and nothing of yours is sent.
 *
 * Three steps rather than one screen's worth of state, because each can fail
 * differently and the person needs to be told which: [ZeppStep.SignIn] is Zepp's
 * page, [ZeppStep.Fetching] is the one request, and [ZeppStep.Done] or [ZeppStep.Failed]
 * is the answer.
 */
private sealed interface ZeppStep {
    data object SignIn : ZeppStep
    data object Fetching : ZeppStep
    data class Done(val device: BoundDevice) : ZeppStep
    data class Failed(val message: String, val signInAgain: Boolean) : ZeppStep

    /** Google and the rest refuse to run in a web view, so say so rather than show their error. */
    data class ThirdParty(val provider: String) : ZeppStep
}

/**
 * The providers that will not complete inside an embedded web view.
 *
 * Google blocks it outright, and the others fail in less obvious ways. A
 * Zepp account can have an email password added to it, which is the way
 * through; the alternative is reading the key on a computer.
 */
private val THIRD_PARTY = mapOf(
    "accounts.google.com" to "Google",
    "appleid.apple.com" to "Apple",
    "www.facebook.com" to "Facebook",
    "m.facebook.com" to "Facebook",
    "account.xiaomi.com" to "Xiaomi",
    "access.line.me" to "LINE",
)

@Composable
internal fun ZeppSignInScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<ZeppStep>(ZeppStep.SignIn) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Sign in with Zepp", subtitle = zeppStepSubtitle(step), onBack = onBack)

        when (val now = step) {
            is ZeppStep.SignIn -> {
                ZeppNote(
                    "Sign in with the email and password on your Zepp account. Cuff does not " +
                        "see your password — only the session Zepp leaves behind, which it uses " +
                        "once to ask which straps are yours.",
                )
                Box(Modifier.weight(1f)) {
                    ZeppWebView(
                        Modifier.fillMaxSize(),
                        onThirdParty = { step = ZeppStep.ThirdParty(it) },
                    )
                }
                Button(
                    onClick = {
                        step = ZeppStep.Fetching
                        scope.launch { step = fetchStrap(context) }
                    },
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                ) { Text("I have signed in — find my strap") }
            }

            is ZeppStep.Fetching -> Centred {
                CircularProgressIndicator()
                Text(
                    "Asking Zepp which straps are on your account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }

            is ZeppStep.Done -> {
                ZeppNote(
                    "Paired. Cuff has the key it needs and will not ask Zepp again. You can " +
                        "sign out of Zepp on the Device screen and the strap will still buzz.",
                )
                ZeppNote("Strap ${now.device.macAddress}", lm = true)
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                ) { Text("Done") }
            }

            is ZeppStep.Failed -> Centred {
                Text(
                    now.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.extras.hrv,
                )
                if (now.signInAgain) {
                    TextButton(onClick = { clearZeppSession(); step = ZeppStep.SignIn }) {
                        Text("Sign in again")
                    }
                } else {
                    TextButton(onClick = { step = ZeppStep.SignIn }) { Text("Back to the sign-in") }
                }
            }

            is ZeppStep.ThirdParty -> Centred {
                Text(
                    "${now.provider} sign-in will not finish inside an app.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Add an email and password to your Zepp account in the Zepp app, then sign " +
                        "in with those here. Zepp allows both on one account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = { clearZeppSession(); step = ZeppStep.SignIn }) {
                    Text("Back to the sign-in")
                }
            }
        }
    }
}

private fun zeppStepSubtitle(step: ZeppStep): String = when (step) {
    is ZeppStep.SignIn -> "Zepp's own page, in a web view"
    is ZeppStep.Fetching -> "One request"
    is ZeppStep.Done -> "Paired"
    is ZeppStep.Failed -> "Did not work"
    is ZeppStep.ThirdParty -> "Needs an email sign-in"
}

/**
 * The one network call, and everything it can say.
 *
 * Runs after the person says they have signed in rather than watching for a
 * cookie to appear: the page sets several across two hosts and there is no
 * moment that reliably means "finished". A button they press when they can
 * see they are signed in is both simpler and more honest about what happens
 * next.
 */
private suspend fun fetchStrap(context: android.content.Context): ZeppStep {
    val cookies = zeppCookies()
    val session = ZeppCloud.sessionFromCookies(cookies)
        ?: return ZeppStep.Failed(
            "No Zepp sign-in was found. Finish signing in on the page, then try again.",
            signInAgain = false,
        )
    return try {
        val listing = ZeppCloud.listDevices(session, HelioKeyStore.account(context)?.apiBase)
        val strap = listing.helioStrap
            ?: return ZeppStep.Failed(
                "That Zepp account has no Helio Strap on it. Link the strap in the Zepp app " +
                    "once, then sign in here again.",
                signInAgain = false,
            )
        if (strap.authKey == null) {
            return ZeppStep.Failed(
                "Zepp knows your strap but did not give out its pairing key. This usually " +
                    "means the strap is not currently bound to this account — link it again " +
                    "in the Zepp app.",
                signInAgain = false,
            )
        }
        val account = session.toAccount(listing.apiBase, strap.china, System.currentTimeMillis())
        if (!HelioKeyStore.remember(context, strap, account)) {
            return ZeppStep.Failed("The key Zepp returned was not a usable one.", signInAgain = false)
        }
        clearZeppSession()
        ZeppStep.Done(strap)
    } catch (e: ZeppCloudException) {
        ZeppStep.Failed(e.message ?: "Zepp's servers refused the request.", signInAgain = e.rejected)
    } catch (e: java.io.IOException) {
        ZeppStep.Failed(
            "Could not reach Zepp's servers. ${e.message ?: "Check the connection and try again."}",
            signInAgain = false,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ZeppWebView(modifier: Modifier, onThirdParty: (String) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // A WRAP_CONTENT web view gives the page a zero-height
                // viewport, and Zepp's login is sized in dvh: it collapses
                // and pushes its own form off the top of the screen.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        THIRD_PARTY[request.url.host]?.let { onThirdParty(it); return true }
                        // Everything https stays in here so the sign-in
                        // finishes where its cookies can be read; anything
                        // else is somebody else's scheme and is refused.
                        return request.url.scheme != "https"
                    }

                    override fun onPageStarted(
                        view: WebView,
                        url: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        THIRD_PARTY[url?.let(Uri::parse)?.host]?.let {
                            view.stopLoading()
                            onThirdParty(it)
                        }
                    }
                }
                loadUrl(ZeppCloud.SIGN_IN_URL)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

private fun zeppCookies(): String {
    val cookies = CookieManager.getInstance()
    return ZeppCloud.COOKIE_URLS.mapNotNull { cookies.getCookie(it) }.joinToString("; ")
}

/**
 * Throws the sign-in away once the key is stored.
 *
 * The cookie is a live credential for somebody's Zepp account, and keeping
 * it in a web view after it has done its one job is a second place for it to
 * leak from. What is kept is the token, in the app's own storage, because
 * the strap's key may have to be fetched again one day.
 */
private fun clearZeppSession() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}

@Composable
private fun Centred(content: @Composable () -> Unit) = Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
) { content() }

@Composable
private fun ZeppNote(text: String, lm: Boolean = false) = Text(
    text,
    style = if (lm) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
    color = if (lm) MaterialTheme.extras.disabled else MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
)
