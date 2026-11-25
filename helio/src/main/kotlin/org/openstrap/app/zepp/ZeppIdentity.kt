// SPDX-License-Identifier: MIT
// Copyright (c) 2026 OpenStrap contributors
//
// The three small pieces ZeppCloud.kt needs that live, upstream, inside
// files Cuff has no other use for: ZeppApp and StrapIdentity sit in
// sayed0am/unstrap's firmware-update code, and ZeppAccount in a settings
// store of some 1,200 lines. The values are reproduced here rather than
// those files vendored whole, at commit
// 0d323c9bfb74f5374e49f90013888fb0e57fdc57 (2026-11-23).
//
// Upstream is dual MIT / Apache-2.0; taken here under MIT. Full texts in
// third_party/unstrap/. See THIRD_PARTY_NOTICES.md.

package org.openstrap.app.zepp

/**
 * The identity the Zepp API is addressed as.
 *
 * These are the official app's own values. The device-list endpoint will not
 * answer a client it does not recognise, so there is no honest alternative to
 * sending them; the call is made with the account holder's own token, on
 * their behalf, and asks for nothing but the list of straps they own.
 */
object ZeppApp {
    const val ID = "428135909242707968"
    const val NAME = "com.huami.midong"
    const val VERSION_NAME = "10.10.5-play"
    const val VERSION_CODE = "151997"
}

/** How a Helio Strap is told apart from the other things on a Zepp account. */
object StrapIdentity {
    const val DEVICE_SOURCE_GLOBAL = 10289411
    const val DEVICE_SOURCE_CHINA = 10289410
    const val PRODUCT_ID = 157
}

/**
 * A signed-in Zepp account, as far as Cuff needs one.
 *
 * Held only to fetch the strap's auth key and to renew the token that fetch
 * needs. Nothing about the person, nothing about their health, and no second
 * call: once the key is stored, this can be forgotten and the strap still
 * buzzes. [com.srineere.airbridge.band.HelioKeyStore] is where it lands, and
 * the Device screen can clear it.
 */
data class ZeppAccount(
    val appToken: String,
    val userId: String,
    val apiBase: String,
    val china: Boolean,
    val loginToken: String? = null,
    val appTokenAtMs: Long? = null,
    val appTtlSeconds: Long? = null,
) {
    /** Never the tokens. These end up in logs. */
    override fun toString() =
        "ZeppAccount(userId=$userId, apiBase=$apiBase, china=$china, appToken=…, " +
            "loginToken=${if (loginToken == null) "none" else "…"}, " +
            "appTokenAtMs=$appTokenAtMs, appTtlSeconds=$appTtlSeconds)"

    companion object {
        const val DEFAULT_API_BASE = "https://api-mifit.zepp.com/"
    }
}
