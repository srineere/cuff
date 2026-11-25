package com.srineere.airbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.srineere.airbridge.band.BandLink
import com.srineere.airbridge.band.BandResult
import com.srineere.airbridge.band.BandSink
import com.srineere.airbridge.band.Bands

/**
 * The app's way to the band.
 *
 * The Air holds one secure session at a time, which is why everything the
 * relay sends goes through a single queue inside the service. That is a rule
 * about the band, though, not about the service: when the relay is down there
 * is nothing to collide with, and making someone turn on notification relaying
 * before they can read their own battery is a toll, not a safeguard.
 *
 * So when the relay is up this borrows its link and its queue, and when it is
 * not it holds a link of its own and lets it go again shortly after.
 */
internal object Band {

    /** Long enough to cover a sync and then a buzz; short enough to let go. */
    private const val IDLE_MILLIS = 20_000L
    private const val TAG = "AirBridge"

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Work>()
    private var link: BandLink? = null
    private var busy = false
    private var measuring = false
    /** The screen waiting on a live reading, so closing can answer it. */
    private var heartSink: BandSink? = null
    private val idle = Runnable { close() }

    /**
     * One queued operation, with the reply that is owed either way.
     *
     * The reply is held beside the work because dropping the queue -- which
     * closing does -- has to answer what is in it. Clearing it silently left
     * the screen that asked waiting on a result that was never coming.
     */
    private class Work(val reply: BandResult, val run: (BandLink) -> Unit)

    /**
     * Whether the band is linked. This asks the Bluetooth adapter rather than
     * the band, so it costs no session and never waits behind one.
     */
    fun isConnected(context: Context, reply: BandResult) {
        // takeIf, as in measureHeartRate below. The relay sets active before
        // it opens a link and can spend up to ninety seconds waiting on the
        // notification listener first; asking it in that window answered
        // NOT_RUNNING and drew the band as disconnected, when the adapter
        // sitting right here knew the truth and costs nothing to ask.
        CalendarRelayService.active?.takeIf { it.holdsBand }
            ?.let { return it.readConnection(reply) }
        own(context).isConnected(reply)
    }

    fun readBattery(context: Context, reply: BandResult) =
        operate(context, reply) { air, r -> air.readBattery(r) }

    fun readSignal(context: Context, reply: BandResult) =
        operate(context, reply) { air, r -> air.readSignalStrength(r) }

    fun readDeviceInfo(context: Context, reply: BandResult) =
        operate(context, reply) { air, r -> air.readDeviceInfo(r) }

    /**
     * One buzz in the shape a notification would get, so that trying it tells
     * you something true about what the band will do tonight.
     *
     * It deliberately ignores a pause and quiet hours. Those silence the
     * relay's own decisions; this is the person in front of the screen asking
     * for a buzz now, and refusing them with "could not reach the band" was
     * both a refusal and a lie about the reason.
     */
    fun buzz(context: Context, reply: BandResult) {
        val preset = RelayRuleStore.read(context).defaultPreset
        if (preset.silent) {
            reply.error("SILENT", "Your default vibration is silent.", null)
            return
        }
        if (preset.callsOnly) {
            reply.error("CALLS_ONLY", "A repeat needs a call to stop it.", null)
            return
        }
        val pulses = preset.pattern.pulses ?: 1
        val at = System.currentTimeMillis()
        val recorded = object : BandResult {
            override fun success(result: Any?) {
                record(context, at, "verified", null, preset.id)
                reply.success(result)
            }
            override fun error(code: String, message: String?, details: Any?) {
                record(context, at, "failed", code, preset.id)
                reply.error(code, message, details)
            }
        }
        // More than one pulse is a short bounded ring, exactly as the relay
        // sends it: one session, so the pulses read as one shape.
        if (pulses > 1) {
            operate(context, recorded) { air, r ->
                air.ring(preset.intensity, PATTERN_GAP_MILLIS, 20_000L, pulses, r)
            }
        } else {
            operate(context, recorded) { air, r -> air.tap(preset.intensity, r) }
        }
    }

    /**
     * A live heart rate, for as long as the screen wants one.
     *
     * This is the only thing the app asks of the band that is not over in a
     * second, and it costs the band's single session for the whole time. The
     * relay therefore lends its own link rather than letting a second one be
     * opened, and refuses outright while it is mid-delivery.
     */
    fun measureHeartRate(context: Context, sink: BandSink): Boolean {
        // Only defer to the relay when it is actually holding the link. A
        // service that exists but has not taken the band yet must not stop
        // the screen opening one, or Measure refuses for no reason.
        CalendarRelayService.active?.takeIf { it.holdsBand }
            ?.let { return it.startHeartRate(sink) }
        handler.removeCallbacks(idle)
        if (busy) return false
        busy = true
        measuring = true
        heartSink = sink
        own(context).readHeartRate(object : BandSink {
            override fun success(event: Any?) = sink.success(event)
            override fun error(code: String, message: String?, details: Any?) {
                finishMeasuring()
                sink.error(code, message, details)
            }
            override fun endOfStream() {
                finishMeasuring()
                sink.endOfStream()
            }
        })
        return true
    }

    fun stopMeasuring() {
        CalendarRelayService.active?.takeIf { it.holdsBand }
            ?.let { return it.stopHeartRate() }
        if (!measuring) return
        link?.stopHeartRate()
        finishMeasuring()
    }

    private fun finishMeasuring() {
        if (!measuring) return
        measuring = false
        heartSink = null
        done()
    }

    /**
     * Drops our own link. The relay calls this before taking one of its own,
     * because two sessions to one band is the thing none of this may do.
     */
    fun close() {
        handler.removeCallbacks(idle)
        // Whatever was waiting is not going to happen, and the caller is owed
        // an answer: silence here is a spinner that never stops.
        val dropped = queue.toList()
        queue.clear()
        // A reading in flight is owed an answer exactly as a queued command
        // is, and the band is owed a stop before its link goes. Without this,
        // the relay taking the band mid-measurement left the screen showing
        // the last beat it had with nothing to say the stream had died, and
        // left measuring true over a null link -- so the next stopMeasuring()
        // told the band nothing and it kept notifying.
        val stranded = heartSink.takeIf { measuring }
        if (stranded != null) runCatching { link?.stopHeartRate() }
        heartSink = null
        measuring = false
        busy = false
        link?.let { runCatching { it.close() }.onFailure { e -> Log.w(TAG, "closing band link", e) } }
        link = null
        dropped.forEach { it.reply.error("CLOSED", "The link to the band was closed.", null) }
        stranded?.error("CLOSED", "The link to the band was closed.", null)
    }

    private fun own(context: Context): BandLink =
        link ?: Bands.open(context).also { link = it }

    /** How long to keep waiting out a band that is busy with something else. */
    private const val RETRY_MILLIS = 1_500L
    private const val RETRIES = 3

    private fun operate(
        context: Context,
        reply: BandResult,
        attempt: Int = 0,
        operation: (BandLink, BandResult) -> Unit,
    ) {
        // Only while it is holding the band, again: otherwise a battery read
        // during the relay's start window retried three times over four and a
        // half seconds and then said "the relay is delivering a notification"
        // with nothing being delivered and nobody holding the session.
        CalendarRelayService.active?.takeIf { it.holdsBand }?.let { relay ->
            // The relay refuses while it is mid-delivery, and one screen
            // opening after another is enough to collide with its own earlier
            // read. Waiting a moment is a better answer than handing that
            // race to the person holding the phone.
            return relay.readDeviceStatus(object : BandResult {
                override fun success(result: Any?) = reply.success(result)
                override fun error(code: String, message: String?, details: Any?) {
                    if (code == "BUSY" && attempt < RETRIES) {
                        handler.postDelayed(
                            { operate(context, reply, attempt + 1, operation) },
                            RETRY_MILLIS,
                        )
                    } else reply.error(code, message, details)
                }
            }, operation)
        }

        handler.removeCallbacks(idle)
        val air = own(context)
        val work = Work(reply) { held ->
            operation(held, object : BandResult {
                override fun success(result: Any?) { done(); reply.success(result) }
                override fun error(code: String, message: String?, details: Any?) {
                    done(); reply.error(code, message, details)
                }
            })
        }
        if (busy) { queue.addLast(work); return }
        busy = true
        work.run(air)
    }

    private fun done() {
        val air = link
        if (air == null) {
            // The link went away under us, so everything still waiting is owed
            // the same answer closing would have given it.
            val dropped = queue.toList()
            queue.clear()
            busy = false
            dropped.forEach { it.reply.error("CLOSED", "The link to the band was closed.", null) }
            return
        }
        queue.removeFirstOrNull()?.let { it.run(air); return }
        busy = false
        // Holding the link open costs the band, and would be in the way of a
        // relay started a moment from now.
        handler.postDelayed(idle, IDLE_MILLIS)
    }

    /** Recorded like any other buzz, so Recent reads the same either way. */
    private fun record(context: Context, at: Long, stage: String, code: String?, preset: String) {
        runCatching {
            RelayHistory.add(
                context,
                RelayActivity(
                    commandId = "band-$at",
                    sourcePackage = null,
                    stage = stage,
                    atMillis = System.currentTimeMillis(),
                    latencyMillis = System.currentTimeMillis() - at,
                    errorCode = code,
                    preset = preset,
                ),
            )
        }
    }

    /** Spacing inside a fixed pattern, close enough to feel like one alert. */
    private const val PATTERN_GAP_MILLIS = 300L
}
