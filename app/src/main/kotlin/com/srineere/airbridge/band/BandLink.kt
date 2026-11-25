package com.srineere.airbridge.band

import android.content.Context

/**
 * One band, as the rest of the app needs it.
 *
 * Everything above this line — the relay, the rules, the Device screen, the
 * live heart rate — talks to a band through this and learns nothing about
 * which band it is. Everything below it is one transport: today the Air's
 * Golden Gate stack in `:air`, and the point of the interface is that a
 * second one can arrive without any of the callers moving.
 *
 * The surface is exactly the ten operations `:app` actually calls today,
 * taken from the call sites rather than from what a band might plausibly do.
 * `AirLink` offers ten more — `previewHaptic`, `keepLinkWarm`, `isAirPaired`,
 * `watchConnection`, the haptic-intensity pair and the rest — and not one of
 * them is reached from `:app`, so not one of them is here. They are still
 * there to be promoted the day something calls them; an interface method with
 * no caller is a guess about a second implementation nobody has written.
 *
 * ## The one rule every implementation shares
 *
 * **One session at a time.** The Air holds a single secure session, and the
 * queueing in [com.srineere.airbridge.Band] and in the relay service exists
 * for that reason alone. A band that could hold two would not need to be told
 * so here, but a band that cannot must behave like the Air does: refuse or
 * queue, never open a second.
 */
internal interface BandLink {

    /**
     * Whether the band is linked.
     *
     * Answered from the Bluetooth adapter rather than by asking the band, so
     * it costs no session and never waits behind one.
     */
    fun isConnected(result: BandResult)

    fun readBattery(result: BandResult)

    fun readSignalStrength(result: BandResult)

    fun readDeviceInfo(result: BandResult)

    /** One pulse. [intensity] is the band's own scale, or null for its default. */
    fun tap(intensity: Int?, result: BandResult)

    /**
     * Pulses until [stopRing], [maxMillis] elapses, or [pulses] have played.
     *
     * A null [pulses] is unbounded — a call ringing — and a number is a fixed
     * pattern sent as one session so the pulses read as one shape.
     */
    fun ring(
        intensity: Int?,
        gapMillis: Long,
        maxMillis: Long,
        pulses: Int?,
        result: BandResult,
    )

    fun stopRing()

    /** A live reading for as long as the caller wants one. Costs the session. */
    fun readHeartRate(sink: BandSink)

    fun stopHeartRate()

    /** Drops the link. Two sessions to one band is the thing none of this may do. */
    fun close()
}

/**
 * How one band operation finishes.
 *
 * This is the shape the Flutter MethodChannel used, which `:air` kept
 * deliberately as the only seam its transport ever had. It is declared here
 * rather than aliased to `dev.airpulse.fitbit_air.AirResult` so that a second
 * transport does not have to depend on the first one just to describe a
 * reply — which is the coupling this interface exists to remove.
 */
internal interface BandResult {
    fun success(result: Any?)
    fun error(code: String, message: String?, details: Any?)
}

/** A stream of values from the band, such as live heart rate. */
internal interface BandSink {
    fun success(event: Any?)
    fun error(code: String, message: String?, details: Any?)
    fun endOfStream()
}

/** Builds a [BandResult] from lambdas, for callers that do not want an object. */
internal fun bandResult(
    onError: (code: String, message: String?) -> Unit = { _, _ -> },
    onSuccess: (Any?) -> Unit = {},
): BandResult = object : BandResult {
    override fun success(result: Any?) = onSuccess(result)
    override fun error(code: String, message: String?, details: Any?) = onError(code, message)
}

/**
 * Where a link comes from.
 *
 * The one place in the app that names a particular band. When a second
 * transport arrives this is what reads the stored choice and returns the
 * other implementation; until then it has one answer, and saying so in one
 * function is the whole reason the rest of the app no longer has to.
 */
internal object Bands {
    fun open(context: Context): BandLink = AirBandLink(context.applicationContext)
}
