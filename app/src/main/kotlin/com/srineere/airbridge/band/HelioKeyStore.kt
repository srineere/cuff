package com.srineere.airbridge.band

import android.content.Context
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.ZeppAccount
import org.openstrap.app.zepp.ZeppCloud

/**
 * The Helio Strap's pairing key, and the Zepp sign-in that produced it.
 *
 * The strap answers nothing it cannot authenticate, and the only issuer of
 * that key is Zepp's cloud, against the account the strap was first linked
 * to. So one network call buys one 16-byte key, and this is where it lands.
 *
 * **Everything here is a secret.** The key lets anything holding it open an
 * authenticated channel to the strap; the app token lets anything holding it
 * read that Zepp account's device list. Neither is ever logged, neither
 * appears in the alert history, and [toString] on [ZeppAccount] is written to
 * keep them out of a stack trace. They live in the app's own private
 * storage, which no other app can read, and `allowBackup` is off, so they do
 * not leave with a cloud backup either.
 *
 * The account is kept only so the token can be renewed when Zepp expires it.
 * Once [key] is set the strap buzzes whether or not the account is still
 * good, which is why [forgetAccount] exists separately from [forget]: you can
 * sign out of Zepp and keep using the strap.
 */
internal object HelioKeyStore {
    private const val PREFS = "helio"
    private const val KEY = "auth_key"
    private const val MAC = "mac"
    private const val APP_TOKEN = "app_token"
    private const val USER_ID = "user_id"
    private const val API_BASE = "api_base"
    private const val CHINA = "china"
    private const val LOGIN_TOKEN = "login_token"
    private const val TOKEN_AT = "app_token_at"
    private const val TOKEN_TTL = "app_ttl"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The strap's auth key as 32 lowercase hex characters, or null if none is stored. */
    fun key(context: Context): String? =
        prefs(context).getString(KEY, null)?.let(ZeppCloud::normalizeKey)

    /** The strap's Bluetooth address, which is how the scan finds it again. */
    fun mac(context: Context): String? =
        prefs(context).getString(MAC, null)?.takeIf { it.isNotBlank() }

    /** Whether there is enough stored to open a channel without the network. */
    fun isPaired(context: Context): Boolean = key(context) != null && mac(context) != null

    /**
     * Stores the strap and the account it came from.
     *
     * Refuses a device with no usable key rather than storing a half-pairing:
     * a stored strap that cannot authenticate looks, from every screen above
     * this, exactly like a strap that is out of range.
     */
    fun remember(context: Context, device: BoundDevice, account: ZeppAccount): Boolean {
        val key = ZeppCloud.normalizeKey(device.authKey) ?: return false
        prefs(context).edit()
            .putString(KEY, key)
            .putString(MAC, device.macAddress)
            .apply()
        rememberAccount(context, account)
        return true
    }

    fun rememberAccount(context: Context, account: ZeppAccount) {
        prefs(context).edit()
            .putString(APP_TOKEN, account.appToken)
            .putString(USER_ID, account.userId)
            .putString(API_BASE, account.apiBase)
            .putBoolean(CHINA, account.china)
            .putString(LOGIN_TOKEN, account.loginToken)
            .putLong(TOKEN_AT, account.appTokenAtMs ?: 0L)
            .putLong(TOKEN_TTL, account.appTtlSeconds ?: 0L)
            .apply()
    }

    fun account(context: Context): ZeppAccount? {
        val p = prefs(context)
        val token = p.getString(APP_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = p.getString(USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return ZeppAccount(
            appToken = token,
            userId = user,
            apiBase = p.getString(API_BASE, null) ?: ZeppAccount.DEFAULT_API_BASE,
            china = p.getBoolean(CHINA, false),
            loginToken = p.getString(LOGIN_TOKEN, null)?.takeIf { it.isNotBlank() },
            appTokenAtMs = p.getLong(TOKEN_AT, 0L).takeIf { it > 0L },
            appTtlSeconds = p.getLong(TOKEN_TTL, 0L).takeIf { it > 0L },
        )
    }

    /** Signs out of Zepp and keeps the strap. The key is what buzzes; the token only fetched it. */
    fun forgetAccount(context: Context) {
        prefs(context).edit()
            .remove(APP_TOKEN).remove(USER_ID).remove(API_BASE).remove(CHINA)
            .remove(LOGIN_TOKEN).remove(TOKEN_AT).remove(TOKEN_TTL)
            .apply()
    }

    /** Forgets the strap and the account both. */
    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
