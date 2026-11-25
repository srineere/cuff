package com.srineere.cuff.helio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.openstrap.app.ble.GattClient
import org.openstrap.app.ble.GattStrapLink
import org.openstrap.app.ble.GattTimeoutException
import org.openstrap.app.ble.GattUnusableException
import org.openstrap.protocol.ChannelSession
import org.openstrap.protocol.ProtocolException
import org.openstrap.protocol.ProtocolTimeoutException
import org.openstrap.protocol.auth.AuthException
import org.openstrap.protocol.services.Endpoints
import org.openstrap.protocol.services.FindDevice
import org.openstrap.protocol.services.MotorVibrate

/**
 * One Amazfit Helio Strap, for as long as somebody is using it.
 *
 * The counterpart to the Air's `AirLink`, and deliberately the same shape:
 * open a link, do one thing at a time with it, let it go when nobody needs
 * it. The strap is a one-session device like the Air — Zepp takes the
 * channel whenever it syncs — so the discipline above this, in `Band` and in
 * the relay service, applies unchanged.
 *
 * What is different is that the strap authenticates. Nothing can talk to it
 * without the 16-byte key Zepp issues for the account the strap is bound to,
 * which is why [authKey] is a constructor argument and why there is no
 * "connect and see" path: without a key there is nothing to try.
 *
 * ## Buzzing a thing with no notification support
 *
 * The strap has no notification module, so a buzz is borrowed from two
 * places that do vibrate:
 *
 * - **A pattern** through `MotorVibrate` (0x0018), played under a scene the
 *   strap never raises on its own. This is the good one: real vibrate and
 *   pause milliseconds, which is more than the Air's intensity-toggle trick
 *   can express.
 * - **An unbounded ring** through `FindDevice` (0x001A): `BUZZ` starts it
 *   and only `STOP` ends it. Upstream found that tapping the strap does not
 *   stop a `BUZZ` on firmware 3.25, so silence always has to come from the
 *   phone — which is exactly how the Air's ring already works here.
 */
class HelioLink(
    context: Context,
    address: String,
    private val authKey: ByteArray,
    private val log: (String) -> Unit = {},
) {
    private val gatt = GattClient(context.applicationContext, address) { log(it) }
    private val strap = GattStrapLink(gatt)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One operation at a time, because the strap holds one channel at a time. */
    private val lock = Mutex()
    private var session: ChannelSession? = null
    private var ringing: Job? = null

    val isConnected: Boolean get() = gatt.connected.value && session?.isAuthenticated == true

    /**
     * Connects, authenticates, and leaves the channel open.
     *
     * Idempotent: a second call while the channel is up does nothing, which
     * is what lets every operation below simply ask for it.
     */
    suspend fun open() = lock.withLock { openLocked() }

    private suspend fun openLocked() {
        if (session?.isAuthenticated == true && gatt.connected.value) return
        closeQuietly()
        gatt.connect()
        // Bigger chunks mean fewer round trips for a pattern. A refusal is
        // not a failure: the protocol's own default of 20 is correct and
        // the strap will say so through the channel if it differs.
        runCatching { strap.payloadMtu = gatt.requestMtu(MTU_WANTED) - 3 }
        strap.enableChannelNotifications()
        val fresh = ChannelSession(strap) { log(it) }
        fresh.start(scope)
        fresh.handshake(authKey)
        session = fresh
    }

    /** One pulse of [millis], the shape a notification gets. */
    suspend fun tap(millis: Int = SHORT_MS) = pattern(listOf(millis))

    /**
     * A fixed pattern of [pulses] pulses of [millis], [gapMillis] apart.
     *
     * The relay's "two pulses" and the step-goal buzz arrive here. Sent as
     * one message so the strap plays it as one shape rather than as two
     * alerts that happen to be close together.
     */
    suspend fun pulses(pulses: Int, millis: Int = SHORT_MS, gapMillis: Int = GAP_MS) {
        require(pulses in 1..MAX_PULSES) { "a pattern is 1-$MAX_PULSES pulses" }
        pattern(List(pulses) { listOf(millis, gapMillis) }.flatten().dropLast(1))
    }

    private suspend fun pattern(vibratePauseMs: List<Int>) = lock.withLock {
        openLocked()
        val reply = requireSession().request(
            Endpoints.MOTOR_VIBRATE,
            MotorVibrate.play(vibratePauseMs, MotorVibrate.SCENE_APP_NOTIFY),
            timeoutMs = REPLY_TIMEOUT_MS,
        )
        if (!FindDevice.isBuzzOk(reply)) throw ProtocolException("The strap refused the pattern")
    }

    /**
     * Buzzes until [stopRing], or until [maxMillis] has passed.
     *
     * A call ringing. `FindDevice.BUZZ` does not stop by itself and the
     * strap's own tap will not stop it either, so the cap is not a
     * convenience: without it a missed call buzzes somebody's wrist until
     * the battery gives out.
     */
    suspend fun ring(maxMillis: Long = FindDevice.FIND_TIMEOUT_MS) = lock.withLock {
        openLocked()
        val reply = requireSession().request(
            Endpoints.FIND_DEVICE,
            FindDevice.BUZZ,
            timeoutMs = REPLY_TIMEOUT_MS,
        )
        if (!FindDevice.isBuzzOk(reply)) throw ProtocolException("The strap refused to ring")
        ringing?.cancel()
        ringing = scope.launch {
            delay(maxMillis)
            log("ring hit its cap, stopping it")
            runCatching { stopRing() }
        }
    }

    /**
     * Ends a ring.
     *
     * Deliberately forgiving. This runs when a call is answered or declined,
     * and the one thing worse than a failed stop is a stop that throws and
     * leaves the strap buzzing because the caller was not expecting it to.
     */
    suspend fun stopRing() {
        ringing?.cancel()
        ringing = null
        val live = session ?: return
        runCatching {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                live.send(Endpoints.FIND_DEVICE, FindDevice.STOP, wantReply = false)
            }
        }.onFailure { log("could not stop the ring: $it") }
    }

    /** Drops the channel and the link. Two sessions to one strap is the thing none of this may do. */
    fun close() {
        ringing?.cancel()
        ringing = null
        closeQuietly()
        scope.cancel()
    }

    private fun closeQuietly() {
        session?.let { runCatching { it.stop() } }
        session = null
        runCatching { gatt.close() }
    }

    private fun requireSession(): ChannelSession =
        session ?: throw ProtocolException("No channel to the strap")

    private companion object {
        /** Zepp asks for 247; the strap gives what it gives and the channel copes either way. */
        const val MTU_WANTED = 247
        const val REPLY_TIMEOUT_MS = 10_000L

        /** A notification pulse. Short enough to read as one tap on the wrist. */
        const val SHORT_MS = 220

        /** Spacing inside a fixed pattern, matching the Air's PATTERN_GAP_MILLIS. */
        const val GAP_MS = 300

        /** `MotorVibrate` takes u16 pairs; this is well inside that and well past useful. */
        const val MAX_PULSES = 20
    }
}

/**
 * What went wrong, in the words the Alerts tab already uses.
 *
 * The relay records a code against every buzz and the Alerts list turns it
 * into a sentence. A second band that invented its own codes would be a
 * second vocabulary for the same four or five failures, so these map onto
 * the ones the Air already produces wherever the failure is the same thing
 * happening to a different radio. Only the two that have no Air equivalent
 * are new, and both are about the key.
 */
fun helioFailureCode(error: Throwable): String = when (error) {
    is GattUnusableException -> "BLUETOOTH_PERMISSION"
    is GattTimeoutException -> "TIMEOUT"
    is AuthException -> "STRAP_REFUSED_KEY"
    is ProtocolTimeoutException -> "HANDSHAKE_TIMEOUT"
    is ProtocolException -> "WORKER_PROTOCOL"
    else -> if (error.message?.contains("Bluetooth restarted") == true) "GATT_DISCONNECTED"
    else "GATT_CONNECT"
}
