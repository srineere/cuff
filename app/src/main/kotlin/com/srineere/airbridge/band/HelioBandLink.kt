package com.srineere.airbridge.band

import android.content.Context
import com.srineere.cuff.helio.HelioLink
import com.srineere.cuff.helio.helioFailureCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.openstrap.protocol.services.FindDevice

/**
 * The Amazfit Helio Strap, as a [BandLink].
 *
 * The mirror of [AirBandLink]: the two files between them are the whole of
 * what `:app` knows about which band is on the other end. This one is longer
 * only because the strap's transport is written in coroutines and Cuff's
 * callbacks are not, so each operation is launched and its outcome handed
 * back through [BandResult].
 *
 * ## What this band cannot do, and says so honestly
 *
 * The Air answers questions about itself over its own session: charge,
 * signal, firmware. The strap's equivalents live behind protocol modules
 * Cuff did not vendor, because the Health tab reads the strap's
 * measurements from Health Connect and had no reason to ask the strap
 * directly. Rather than return a plausible zero, those reads fail with a
 * code the Alerts list already knows how to phrase. A reading nobody has is
 * better shown as absent than as a number somebody might believe.
 */
internal class HelioBandLink(
    private val context: Context,
    address: String,
    authKey: ByteArray,
) : BandLink {

    private val link = HelioLink(context.applicationContext, address, authKey)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs one strap operation and answers exactly once, whatever happens.
     *
     * The `runCatching` is deliberately broad. Everything below this is
     * either Android's Bluetooth stack or a vendored protocol, and a
     * callback that is never called is a spinner that never stops and an
     * alert the relay believes is still in flight.
     */
    private fun run(result: BandResult, work: suspend () -> Any?) {
        scope.launch {
            runCatching { work() }
                .onSuccess { result.success(it) }
                .onFailure { result.error(helioFailureCode(it), it.message, null) }
        }
    }

    override fun isConnected(result: BandResult) {
        result.success(link.isConnected)
    }

    override fun tap(intensity: Int?, result: BandResult) = run(result) {
        link.tap(millisFor(intensity))
        true
    }

    override fun ring(
        intensity: Int?,
        gapMillis: Long,
        maxMillis: Long,
        pulses: Int?,
        result: BandResult,
    ) = run(result) {
        if (pulses == null) {
            // A call. Unbounded until the phone says stop, capped so a
            // missed one does not buzz somebody's wrist all afternoon.
            link.ring(minOf(maxMillis, FindDevice.FIND_TIMEOUT_MS))
        } else {
            // A fixed shape, which this band can express properly rather
            // than by flipping an intensity setting on and off.
            link.pulses(pulses, millisFor(intensity), gapMillis.toInt())
        }
        true
    }

    override fun stopRing() {
        scope.launch { runCatching { link.stopRing() } }
    }

    override fun readHeartRate(sink: BandSink) {
        // The strap broadcasts the standard BLE Heart Rate Service during a
        // workout, which is a simpler path than the Air's proprietary one
        // and belongs with the Health tab work rather than here.
        sink.error("NOT_ON_THIS_BAND", "Live heart rate is not wired up for the strap yet.", null)
        sink.endOfStream()
    }

    override fun stopHeartRate() = Unit

    override fun readBattery(result: BandResult) = notVendored(result)

    override fun readSignalStrength(result: BandResult) = notVendored(result)

    /**
     * The one fact about the strap Cuff knows without asking it.
     *
     * The Device card wants a name for its heading, and failing that read
     * would leave the card titled by an error while the strap buzzes
     * perfectly well. The name is not a measurement, so answering it from
     * here costs no honesty: what is unknown stays unknown below.
     */
    override fun readDeviceInfo(result: BandResult) =
        result.success(mapOf("name" to BandKind.Helio.label))

    private fun notVendored(result: BandResult) =
        result.error(
            "NOT_ON_THIS_BAND",
            "Cuff does not read that from the strap. Zepp and Health Connect do.",
            null,
        )

    override fun close() {
        link.close()
        scope.cancel()
    }

    /**
     * The Air's two intensities, as two pulse lengths.
     *
     * The Air has a haptic-intensity setting and the strap does not, so
     * "light" and "strong" cannot mean the same thing on both. A shorter
     * and a longer pulse is the closest honest translation: it is the
     * difference somebody actually feels, and it keeps one rule working
     * across two bands rather than needing two.
     */
    private fun millisFor(intensity: Int?): Int =
        if (intensity != null && intensity <= LOW_INTENSITY) LIGHT_MS else STRONG_MS

    private companion object {
        /** `AirLink`'s low setting is 2 and its high is 3. */
        const val LOW_INTENSITY = 2
        const val LIGHT_MS = 150
        const val STRONG_MS = 320
    }
}
