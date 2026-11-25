package com.srineere.airbridge.band

import android.content.Context
import dev.airpulse.fitbit_air.AirLink
import dev.airpulse.fitbit_air.AirResult
import dev.airpulse.fitbit_air.AirSink

/**
 * The Fitbit Air, as a [BandLink].
 *
 * **This is the only file in `:app` that imports `:air`.** Before it there
 * were six, and the point of the move is that a second transport now has one
 * file to sit beside rather than six call sites to be threaded through.
 *
 * It is a pass-through and nothing more: no retries, no queueing, no state.
 * All of that lives above, in [com.srineere.airbridge.Band] and in the relay
 * service, because it is about how *the app* shares one session rather than
 * about how the Air speaks. Putting any of it here would mean writing it
 * twice the day a second band lands.
 */
internal class AirBandLink(context: Context) : BandLink {

    private val air = AirLink(context.applicationContext)

    override fun isConnected(result: BandResult) = air.isAirConnected(result.toAir())

    override fun readBattery(result: BandResult) = air.readBattery(result.toAir())

    override fun readSignalStrength(result: BandResult) = air.readSignalStrength(result.toAir())

    override fun readDeviceInfo(result: BandResult) = air.readDeviceInfo(result.toAir())

    override fun tap(intensity: Int?, result: BandResult) = air.tap(intensity, result.toAir())

    override fun ring(
        intensity: Int?,
        gapMillis: Long,
        maxMillis: Long,
        pulses: Int?,
        result: BandResult,
    ) = air.ring(intensity, gapMillis, maxMillis, pulses, result.toAir())

    override fun stopRing() = air.stopRing()

    override fun readHeartRate(sink: BandSink) = air.readHeartRate(sink.toAir())

    override fun stopHeartRate() = air.stopHeartRate()

    override fun close() = air.close()
}

/**
 * The two callback shapes, bridged.
 *
 * `:air`'s own interfaces are structurally identical to the band ones, which
 * is not a coincidence — the band interfaces were lifted from them. They are
 * still two types rather than a typealias, so that a second transport can
 * describe a reply without depending on the Air's module. The cost of that
 * decision is exactly these two adapters.
 */
private fun BandResult.toAir(): AirResult = object : AirResult {
    override fun success(result: Any?) = this@toAir.success(result)
    override fun error(code: String, message: String?, details: Any?) =
        this@toAir.error(code, message, details)
}

private fun BandSink.toAir(): AirSink = object : AirSink {
    override fun success(event: Any?) = this@toAir.success(event)
    override fun error(code: String, message: String?, details: Any?) =
        this@toAir.error(code, message, details)
    override fun endOfStream() = this@toAir.endOfStream()
}
