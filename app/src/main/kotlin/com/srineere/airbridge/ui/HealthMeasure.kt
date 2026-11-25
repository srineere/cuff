package com.srineere.airbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.srineere.airbridge.Band
import com.srineere.airbridge.health.HeartSample
import com.srineere.airbridge.band.BandSink
import kotlinx.coroutines.delay

/** Where a live reading is between asking for it and having one. */
internal sealed interface Measure {
    data object Idle : Measure
    data object Starting : Measure
    data class Live(val bpm: Int) : Measure
    data class Failed(val message: String, val sharingHint: Boolean) : Measure
}

/**
 * A live reading in progress, and the two things you can do about it.
 *
 * Held apart from any one screen because two places ask for it — the hero
 * tile, where the number lives, and the Heart screen, which explains what it
 * costs. Both drive the same band through the same single session, and each
 * gives it back when its own screen goes away.
 */
@Stable
internal class MeasureHolder(
    val state: Measure,
    val secondsLeft: Int,
    /** Every beat this run has reported, in order, for the live trace. */
    val samples: List<HeartSample>,
    val start: () -> Unit,
    val stop: () -> Unit,
) {
    val running: Boolean get() = state is Measure.Live || state is Measure.Starting

    /**
     * Anything but idle, failures included.
     *
     * The tile hands itself over for a failure too: a reading that did not
     * arrive is the answer to the tap, and showing it where the number would
     * have been is the only place anyone is looking.
     */
    val active: Boolean get() = state !is Measure.Idle
}

/**
 * The one thing on this tab no Health Connect reader can do.
 *
 * It is a measurement you ask for, not a number that sits there, because
 * holding the subscription holds the band's single session: for as long as
 * this runs the wrist cannot be buzzed and alerts queue behind it. So it
 * starts on a tap, says what it costs while it runs, and stops itself after
 * two minutes whatever the screen does.
 */
@Composable
internal fun rememberMeasure(): MeasureHolder {
    val context = LocalContext.current
    var state by remember { mutableStateOf<Measure>(Measure.Idle) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    // A reading a second for two minutes is 120 points, which is a chart
    // rather than a number that keeps changing under you.
    val samples = remember { mutableStateListOf<HeartSample>() }
    val running = state is Measure.Live || state is Measure.Starting

    // Leaving the screen frees the band now, rather than leaving it held
    // until the relay's watchdog reclaims it four minutes later.
    DisposableEffect(Unit) {
        onDispose { Band.stopMeasuring() }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        secondsLeft = LIMIT_SECONDS
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        Band.stopMeasuring()
        state = Measure.Idle
    }

    // The band accepts the subscription whether or not Google Health is
    // sharing, and the transport waits 45 seconds before admitting nothing
    // came. That is far too long to leave a spinner with no explanation for
    // what is the likeliest failure of all, so the screen gives up first.
    // The first reading is documented at 2.5 to 3.7 seconds, so ten is
    // generous rather than hasty.
    LaunchedEffect(state) {
        if (state !is Measure.Starting) return@LaunchedEffect
        delay(FIRST_READING_DEADLINE_MILLIS)
        if (state is Measure.Starting) {
            Band.stopMeasuring()
            state = Measure.Failed(reasonFor("HEART_RATE_TIMEOUT", null), sharingHint = true)
        }
    }

    return MeasureHolder(
        state = state,
        secondsLeft = secondsLeft,
        samples = samples,
        start = {
            state = Measure.Starting
            samples.clear()
            val started = Band.measureHeartRate(context, object : BandSink {
                override fun success(event: Any?) {
                    val bpm = (event as? Map<*, *>)?.get("beatsPerMinute") as? Int ?: return
                    samples += HeartSample(System.currentTimeMillis(), bpm)
                    state = Measure.Live(bpm)
                }

                override fun error(code: String, message: String?, details: Any?) {
                    state = Measure.Failed(reasonFor(code, message), code == "HEART_RATE_TIMEOUT")
                }

                override fun endOfStream() {
                    if (state is Measure.Starting) state = Measure.Idle
                }
            })
            if (!started) {
                state = Measure.Failed(
                    "The relay is mid-delivery and has the band. Try again in a moment.",
                    sharingHint = false,
                )
            }
        },
        stop = {
            Band.stopMeasuring()
            state = Measure.Idle
        },
    )
}

/** Two minutes is long enough to settle and short enough to give the band back. */
private const val LIMIT_SECONDS = 120

/** How long to wait for the first beat before calling it silence. */
private const val FIRST_READING_DEADLINE_MILLIS = 10_000L
