package com.srineere.airbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.srineere.airbridge.Band
import com.srineere.airbridge.band.BandSink
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.srineere.airbridge.health.ASSUMED_MAX_HEART_RATE
import com.srineere.airbridge.health.Zone
import com.srineere.airbridge.health.zoneOf
import com.srineere.airbridge.health.HeartSample
import com.srineere.airbridge.health.summarise
import com.srineere.airbridge.health.timeInZones
import com.srineere.airbridge.ui.theme.extras
import java.time.Duration
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Today's heart, and the resting trend behind it.
 *
 * Two different questions share this screen on purpose: what your heart did
 * today, and where it has been sitting for a month. The zones belong to the
 * first -- they are about effort, not rest -- so the resting figure stays a
 * single line rather than being drawn into a band it has no business in.
 */
@Composable
internal fun HealthHeartScreen(onBack: () -> Unit) {
    val data by healthData(LocalContext.current)
    val state = LocalHealthState.current
    val extras = MaterialTheme.extras
    val axes = rememberAxes()
    var atHeart by remember { mutableStateOf<Float?>(null) }
    var hours by remember { mutableIntStateOf(8) }
    // Remembered, because atHeart is read in this same scope: scrubbing the
    // chart recomposes the screen on every pointer move, and this re-filtered
    // and re-summarised a whole day of beats -- tens of thousands of them --
    // once per touch event.
    val samples = remember(data.snapshot.heartToday, hours) {
        data.snapshot.heartToday.lastHours(hours)
    }
    val summary = remember(samples) { summarise(samples) }
    val ticks = remember(samples) { hourTicks(samples) }
    val zoneBands = remember(extras) {
        Zone.entries.map { zone ->
            ZoneBand(
                from = zone.lowBpm(ASSUMED_MAX_HEART_RATE).toDouble(),
                until = zone.highBpm(ASSUMED_MAX_HEART_RATE).toDouble(),
                colour = zone.colour(extras),
                label = zone.shortLabel,
            )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Heart", subtitle = "Today", onBack = onBack)

        MeasureCard(axes)

        if (summary == null) {
            Text(
                when {
                    // The one thing the tab's cache does not keep. Beats
                    // are tens of thousands of readings about *today*, so
                    // the kept snapshot carries none and a cold start
                    // reaches here with an empty list and a read in
                    // flight -- which is not the same fact as a band that
                    // has not synced, and must not be said in its words.
                    data.snapshot.heartToday.isEmpty() && state.refreshing ->
                        "Reading today's beats…"
                    data.snapshot.heartToday.isEmpty() ->
                        "No heart rate recorded today yet. Google Health writes it " +
                            "once your band syncs."
                    else ->
                        "Nothing recorded in the last " +
                            "${if (hours >= 24) "day" else "$hours hours"}."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        } else {
            DetailCard {
                // Inside the card, above the chart it governs, exactly where
                // every other window control on this tab sits.
                RangePills(
                    options = listOf(1, 4, 8, 24),
                    selected = hours,
                    onSelect = {
                        hours = it
                        atHeart = null
                    },
                    label = { if (it >= 24) "Today" else "${it}h" },
                )
                Row(Modifier.padding(top = 10.dp)) {
                    Stat("Lowest", "${summary.lowest} bpm", Modifier.weight(1.1f))
                    Stat("Average", "${summary.average} bpm", Modifier.weight(1.1f))
                    Stat("Highest", "${summary.highest} bpm", Modifier.weight(1.1f))
                }
                DayHeartLine(
                    samples = samples,
                    colour = extras.heart,
                    modifier = Modifier.padding(top = 14.dp)
                        .scrubbableAcross(CHART_GUTTER) { atHeart = it },
                    height = 140.dp,
                    axes = axes,
                    xTicks = ticks,
                    selected = atHeart,
                    zones = zoneBands,
                    markPeak = true,
                    peakColour = extras.zonePeak,
                    label = ChartLabel(
                        metric = "Heart rate",
                        unit = "beats per minute",
                        window = if (hours >= 24) "today" else "last $hours hours",
                    ),
                )
                // Under the chart, as its caption. Above it the clock range
                // sat between the pills that set it and the figures that
                // summarise it, belonging to neither.
                Text(
                    spanLabel(samples),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                BeatReadout(samples, atHeart)
            }

            val inZone = timeInZones(samples)
            SectionHeader("Time in zone")
            if (inZone.isEmpty()) {
                Text(
                    "Nothing above the lightest zone today. Sitting still is not a " +
                        "zone, so a quiet day reads as none rather than hours of Light.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                val total = inZone.values.fold(Duration.ZERO, Duration::plus)
                DetailCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Zone.entries.forEach { zone ->
                            val share = inZone[zone]?.seconds ?: 0L
                            if (share > 0) {
                                Box(
                                    Modifier
                                        .weight(share.toFloat())
                                        .fillMaxHeight()
                                        .background(zone.colour(extras)),
                                )
                            }
                        }
                    }
                    Text(
                        "${total.toMinutes()} minutes above resting",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Zone.entries.forEach { zone ->
                    ZoneRow(zone, inZone[zone], zone.colour(extras))
                }
            }
        }

        Box(Modifier.padding(bottom = 24.dp))
    }
}



/**
 * The beat nearest the finger, with the time it was taken.
 *
 * A heart rate chart is a line of thousands of readings, and the question
 * anyone asks of it is "what was that spike at nine?" -- which needs a clock
 * beside the number, not just the number.
 */
@Composable
internal fun BeatReadout(samples: List<HeartSample>, at: Float?) {
    if (at == null || samples.size < 2) {
        Spacer(Modifier.height(26.dp))
        return
    }
    val first = samples.first().atMillis
    val last = samples.last().atMillis
    val want = first + ((last - first) * at).toLong()
    val nearest = samples.minBy { kotlin.math.abs(it.atMillis - want) }
    val clock = remember {
        DateTimeFormatter
            .ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "Hm"))
            .withZone(ZoneId.systemDefault())
    }
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            clock.format(java.time.Instant.ofEpochMilli(nearest.atMillis)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "  ${nearest.bpm} bpm",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        zoneOf(nearest.bpm)?.let { zone ->
            Text(
                "  ·  ${zone.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extras.disabled,
            )
        }
    }
}


/** The clock range a window actually covers, under its pills. */
@Composable
private fun spanLabel(samples: List<HeartSample>): String {
    if (samples.size < 2) return ""
    val clock = remember {
        DateTimeFormatter
            .ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "Hm"))
            .withZone(ZoneId.systemDefault())
    }
    return clock.format(samples.first().atMillis.let(java.time.Instant::ofEpochMilli)) +
        " – " +
        clock.format(samples.last().atMillis.let(java.time.Instant::ofEpochMilli))
}

/** The zone's name, short enough to label a band in a 28dp gutter. */
private val Zone.shortLabel: String
    get() = when (this) {
        Zone.Light -> "Light"
        Zone.Moderate -> "Mod"
        Zone.Vigorous -> "Vig"
        Zone.Peak -> "Peak"
    }

/**
 * The tail of a day's samples, and never a gap at the front pretending to be
 * a flat line.
 *
 * Eight hours, not the whole day: a 24-hour axis on a phone gives each hour
 * twelve pixels, and even twelve hours spent most of its width on the part
 * you were asleep for. Eight is a working day's worth of shape.
 */
internal fun List<HeartSample>.lastHours(hours: Int = 8): List<HeartSample> {
    val newest = maxOfOrNull { it.atMillis } ?: return this
    val from = newest - hours * 60 * 60 * 1000L
    return filter { it.atMillis >= from }
}

/** Whole hours across a span of samples, as x labels. */
internal fun hourTicks(samples: List<HeartSample>): List<Pair<Float, String>> {
    if (samples.size < 2) return emptyList()
    val first = samples.first().atMillis
    val last = samples.last().atMillis
    val across = (last - first).takeIf { it > 0 } ?: return emptyList()
    val zone = java.time.ZoneId.systemDefault()
    val startHour = java.time.Instant.ofEpochMilli(first).atZone(zone)
        .truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1)
    // Four labels at most: enough to place the shape in the day, few enough
    // that they do not collide at phone width.
    val hours = (across / (60 * 60 * 1000.0)).toInt().coerceAtLeast(1)
    val every = (hours / 4).coerceAtLeast(1)
    return generateSequence(startHour) { it.plusHours(every.toLong()) }
        .takeWhile { it.toInstant().toEpochMilli() <= last }
        .map { at ->
            val fraction = (at.toInstant().toEpochMilli() - first).toFloat() / across.toFloat()
            fraction to "%02d".format(at.hour)
        }
        .toList()
}

@Composable
private fun ZoneRow(zone: Zone, length: Duration?, colour: Color) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
) {
    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(colour))
    Text(zone.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(84.dp))
    Text(
        if (zone == Zone.Peak) "${zone.lowBpm(ASSUMED_MAX_HEART_RATE)}+ bpm"
        else "${zone.lowBpm(ASSUMED_MAX_HEART_RATE)}–${zone.highBpm(ASSUMED_MAX_HEART_RATE)} bpm",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.extras.disabled,
        modifier = Modifier.weight(1f),
    )
    Text(
        length.asZoneLength(),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = if (length == null) MaterialTheme.extras.disabled
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(44.dp),
        textAlign = TextAlign.End,
    )
}

/** Under a minute is not nothing, and must not print as "0m". */
private fun Duration?.asZoneLength(): String = when {
    this == null -> "—"
    toMinutes() >= 1 -> "${toMinutes()}m"
    else -> "<1m"
}

internal fun Zone.colour(extras: com.srineere.airbridge.ui.theme.AirExtraColors): Color = when (this) {
    Zone.Light -> extras.zoneLight
    Zone.Moderate -> extras.zoneModerate
    Zone.Vigorous -> extras.zoneVigorous
    Zone.Peak -> extras.zonePeak
}


/**
 * What a live reading costs, around the button that takes it.
 *
 * The button itself is shared with the hero tile; this card is the part that
 * only makes sense with room to explain -- that the band is held while it
 * runs, and which switch to look for when nothing arrives.
 */
@Composable
private fun MeasureCard(axes: Axes) {
    val measure = rememberMeasure()
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Kicker("From the band", MaterialTheme.extras.heart)
                when (val current = measure.state) {
                    is Measure.Live -> {
                        Row(verticalAlignment = Alignment.Bottom) {
                            BigNumber("${current.bpm}")
                            Text(
                                " bpm",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 5.dp),
                            )
                        }
                        Text(
                            "Live, stops in ${measure.secondsLeft}s",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is Measure.Starting -> Measured(
                        "Opening the band. The first reading takes a few seconds.",
                    )

                    is Measure.Failed -> Measured(current.message)

                    Measure.Idle -> Measured(
                        "A reading straight off the Air, without waiting for a sync.",
                    )
                }
            }
            when (measure.state) {
                is Measure.Live -> Button(onClick = measure.stop) { Text("Stop") }

                is Measure.Starting -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )

                else -> {
                    val (askMeasure, measureDialog) =
                        explained(Explain.MeasureHeart) { measure.start() }
                    measureDialog()
                    OutlinedButton(onClick = askMeasure) { Text("Measure") }
                }
            }
        }

        // What the band is sending, as it sends it. A number that replaces
        // itself every second is unreadable; the same readings as a line are
        // a measurement you can watch settle.
        if (measure.samples.size >= 2) {
            val summary = summarise(measure.samples)
            DayHeartLine(
                samples = measure.samples,
                colour = MaterialTheme.extras.heart,
                modifier = Modifier.padding(top = 12.dp),
                height = 92.dp,
                axes = axes,
                xTicks = listOf(0f to "start", 1f to "now"),
                label = ChartLabel(
                    metric = "Live heart rate",
                    unit = "beats per minute",
                    window = "this reading",
                ),
            )
            if (summary != null) {
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    Stat("Lowest", "${summary.lowest} bpm", Modifier.weight(1.1f))
                    Stat("Average", "${summary.average} bpm", Modifier.weight(1.1f))
                    Stat("Highest", "${summary.highest} bpm", Modifier.weight(1.1f))
                    Stat("Beats", "${summary.count}", Modifier.weight(1f))
                }
            }
        }

        if (measure.running) {
            Text(
                "The band is held while this runs, so alerts queue behind it.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extras.hrv,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        (measure.state as? Measure.Failed)?.takeIf { it.sharingHint }?.let {
            Text(
                "Google Health, Connections, Air, Share heart rate.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extras.heart,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Measured(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp),
)

/**
 * The band's error codes in words. The timeout is the one that matters: with
 * sharing off the Air accepts the subscription and then sends nothing at
 * all, which looks exactly like a bug unless it is named.
 */
internal fun reasonFor(code: String, message: String?): String = when (code) {
    "HEART_RATE_TIMEOUT" ->
        "The band took the request and sent nothing back. Heart rate sharing is " +
            "probably off in Google Health."
    "HEART_RATE_UNAVAILABLE" -> "This band does not offer heart rate over Bluetooth."
    "BLUETOOTH_PERMISSION" -> "Nearby devices permission is needed to reach the band."
    "AIR_NOT_FOUND" -> "Pair your Air in Google Health first."
    "BUSY" -> "The band is busy with something else. Try again in a moment."
    else -> message?.takeIf { it.isNotBlank() } ?: "Could not reach the band."
}
