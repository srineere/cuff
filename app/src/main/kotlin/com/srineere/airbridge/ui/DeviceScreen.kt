package com.srineere.airbridge.ui

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.srineere.airbridge.Band
import com.srineere.airbridge.CalendarRelayService
import com.srineere.airbridge.health.HealthAvailability
import com.srineere.airbridge.health.HealthCache
import com.srineere.airbridge.health.HealthConnect
import com.srineere.airbridge.health.HealthEnabled
import com.srineere.airbridge.health.JournalReminder
import com.srineere.airbridge.health.MorningMoment
import com.srineere.airbridge.widget.WidgetSnapshotStore
import androidx.compose.material.icons.outlined.Palette
import com.srineere.airbridge.ui.theme.Accent
import com.srineere.airbridge.ui.theme.ThemeMode
import androidx.compose.ui.text.style.TextAlign
import com.srineere.airbridge.ui.theme.extras
import com.srineere.airbridge.widget.BandStore
import com.srineere.airbridge.widget.WidgetRefresh
import com.srineere.airbridge.ui.theme.isAvailable
import com.srineere.airbridge.band.bandResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import com.srineere.airbridge.R
import com.srineere.airbridge.BuildConfig

@Composable
internal fun DeviceScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val appearance by AppSettings.appearance.collectAsState()
    val healthOn by HealthEnabled.enabled.collectAsState()

    // Whether the last ask came back with nothing. Held here rather than
    // stored, because it is about what just happened on this screen and
    // not about the install: coming back tomorrow and being told what
    // Health Connect said yesterday would be an app arguing.
    var healthRefused by remember { mutableStateOf(false) }

    // Health Connect's own sheet, which is the only thing that can grant
    // these. The same contract the Permissions screen asks through, on
    // purpose: a second way of asking would be a second set of answers
    // to get right, and this one is already right.
    val askHealth = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        // The switch follows what was actually granted, not what was
        // asked for. A sheet dismissed with nothing allowed leaves it
        // off: a Health tab switched on behind no permissions is a page
        // of empty cards, which is indistinguishable from a band that
        // has not synced, and telling those two apart is most of what
        // this app's permission copy exists to do.
        if (granted.intersect(HealthConnect.readPermissions).isEmpty()) {
            healthRefused = true
        } else {
            healthRefused = false
            healthSwitched(context, on = true)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // The logo in place of the word "Device", at the size the word was.
        //
        // It was set at 26dp with a sentence under it, which gave this tab
        // two headings competing: the app's name in full voice, and then
        // the band's name in full voice directly beneath. A screen cannot
        // have two subjects. The band is the subject here, so the logo
        // takes a title's weight and no more, and the tagline goes — it is
        // on the About screen, which is the screen about the app.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
        ) {
            Wordmark(Modifier.height(19.dp))
        }

        BandCard()

        // No headings. A handful of rows under a card do not need two of
        // them, and "Appearance" over a single row was a label for a
        // label. They run in the order they are actually reached in
        // instead: the one switch, then the doors.
        Box(Modifier.padding(top = 8.dp))

        // First, and the only row here that is a switch rather than a
        // door. It decides whether half the app exists -- a tab, three
        // notifications, two buzzes and most of the widget catalogue --
        // so it sits above the things that only change how the rest of
        // it looks.
        SettingsRow(
            title = "The Health tab",
            subtitle = if (healthOn) {
                "Your steps, sleep and heart rate, read from Health Connect"
            } else {
                "Off. The relay runs without it, and nothing is read"
            },
            icon = Icons.Outlined.FavoriteBorder,
            trailing = {
                RowSwitch(
                    checked = healthOn,
                    onCheckedChange = { wanted ->
                        when {
                            !wanted -> {
                                healthRefused = false
                                healthSwitched(context, on = false)
                            }
                            // Nothing is switched on here. The sheet's
                            // answer is what switches it, above, so a
                            // sheet that is dismissed leaves the switch
                            // exactly where the finger found it.
                            HealthConnect.availability(context) == HealthAvailability.Ready ->
                                runCatching { askHealth.launch(HealthConnect.allPermissions) }
                            // There is nothing to ask. Sending somebody
                            // to where Health Connect comes from beats a
                            // switch that flicks back with no reason
                            // given.
                            else -> context.openHealthConnectListing()
                        }
                    },
                )
            },
        )
        // Only once an ask has come back empty. Said as a row of its own
        // rather than in the subtitle above, because it is about the
        // thing that just happened and it has somewhere to go: once
        // Health Connect has decided it will not ask again, the sheet
        // returns instantly and the switch is a control that visibly
        // does nothing, forever. The app's own settings page is the way
        // back in, as it is for every other permanently refused grant on
        // the Permissions screen.
        if (healthRefused) {
            SettingsRow(
                indent = Indent.UnderIcon,
                title = "Allow it in settings",
                subtitle = "Health Connect allowed nothing, so the tab stays off",
                onClick = { context.openAppSettings() },
            )
        }
        // Said where the switch is, because it is the question anybody
        // turning this off actually has. Nothing in Health Connect is
        // ours to withdraw: the food log is the one thing this app
        // writes, and those entries are the person's.
        if (!healthOn) {
            Text(
                "Food you have already logged stays in Health Connect. Turning this off " +
                    "stops CUFF reading; it deletes nothing.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extras.disabled,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
            )
        }

        SettingsRow(
            title = "Home screen widgets",
            // First of the doors, because it is the only one that changes
            // something you look at every day. The old subtitle described
            // the band widget alone, which is one family of six.
            subtitle = "What the home screen shows, and how it looks",
            icon = Icons.Outlined.Widgets,
            onClick = { onOpen("widgets") },
        )
        SettingsRow(
            title = "Theme",
            subtitle = appearanceSummary(appearance),
            icon = Icons.Outlined.Palette,
            onClick = { onOpen("appearance") },
        )
        SettingsRow(
            title = "Permissions",
            subtitle = permissionsSummary(),
            icon = Icons.Outlined.Lock,
            onClick = { onOpen("permissions") },
        )
        // Only while there is something to export. The screen behind this
        // writes your readings out as files, so with nothing being read
        // it is a page of empty sections and a button that produces an
        // empty file.
        if (healthOn) {
            SettingsRow(
                title = "Export",
                subtitle = "Your readings as files, kept where you choose",
                icon = Icons.Outlined.FileDownload,
                onClick = { onOpen("export") },
            )
        }
        SettingsRow(
            title = "About",
            subtitle = "Your band, this app, and how they talk",
            icon = Icons.Outlined.Info,
            onClick = { onOpen("about") },
        )

        // The version, centred, at the foot of the tab about everything
        // else.
        //
        // It had the launcher icon beside it, left-aligned like a row, on
        // the reasoning that somebody with two installs side by side
        // should be able to tell which one they were holding. True, and
        // answered better elsewhere: the icon is at the top of this tab
        // in the wordmark, and the two builds differ by name in the
        // launcher. What was left was a settings row that could not be
        // tapped, sitting under rows that could.
        //
        // So it reads as a footer now rather than as a row: no icon,
        // centred, labelMedium and the dimmest tone the theme has. The
        // version is the last thing on the screen and the least of it —
        // worth finding when a report needs it, worth nothing the rest of
        // the time.
        //
        // What this app is not — official, approved, supported — is said
        // twice on the About screen, in the two places somebody would
        // look: under what can break it, and under how to report a
        // problem. A third copy here would be a disclaimer, not
        // information.
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.extras.disabled,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 26.dp),
        )

        Box(Modifier.padding(bottom = 28.dp))
    }
}

/**
 * Everything that has to move when the health half is switched.
 *
 * In one place because the four things below are not independent of each
 * other. Two preferences files hold readings for a feature that has just
 * stopped existing; two workers are booked against readings that will not
 * be read; the relay keeps a step-check alarm of its own and has to be
 * told rather than left to find out at the next quarter hour; and the
 * home screen is drawn from a snapshot that has just stopped being true.
 * Miss one and the symptom turns up days later, on a phone, as a buzz
 * for a feature somebody switched off.
 *
 * Cheap enough to run on the thread that touched the switch: four
 * preferences edits, two WorkManager calls and a redraw booked for four
 * seconds' time. Nothing here reads Health Connect, which is the only
 * expensive thing in reach.
 *
 * What it deliberately does **not** touch is anything written. The food
 * log lives in Health Connect and those entries are the person's, not
 * this app's to withdraw; and the goals, the tile layout, the saved
 * foods and the journal's answers all stay exactly as they were, so that
 * turning the half back on returns the tab somebody had rather than a
 * new one.
 */
private fun healthSwitched(context: Context, on: Boolean) {
    HealthEnabled.set(context, on)
    if (!on) {
        // The two caches of readings, and only those. Both are caches --
        // one read rebuilds either of them -- so emptying them costs a
        // spinner, and leaving them would mean a month of somebody's
        // nights sitting on disk for a feature they have just turned
        // off.
        runCatching { HealthCache.clear(context) }
        runCatching { WidgetSnapshotStore.clear(context) }
    }
    // Both of these cancel when they are not wanted and book when they
    // are, so one call each way is the whole of it.
    runCatching { MorningMoment.schedule(context) }
    runCatching { JournalReminder.schedule(context) }
    // The relay holds its own step-check alarm and would otherwise keep
    // it until the next rule change.
    CalendarRelayService.active?.settingsChanged()
    // The home screen is full of faces that have just lost their data,
    // or got it back. A poke redraws from what is kept rather than
    // going back to Health Connect, which is right both ways round.
    WidgetRefresh.poke(context)
}

/**
 * What this card is about.
 *
 * Named once so the glyph beside it is chosen from the same string the
 * heading shows. The icon was a watch while the heading said Air, and
 * nothing in the code connected the two -- so there was no place where
 * they could be seen to disagree.
 */
private const val BAND_NAME = "Fitbit Air"

/** Where Buzz the band is in its one short round trip. */
private enum class Buzz { Idle, Sending, Sent }

/**
 * The band's own state, which does not wait on the relay: [Band] borrows the
 * relay's link when there is one and holds its own when there is not. Nothing
 * here is live either way -- every reading costs a session with the band -- so
 * each one carries the moment it was taken.
 */
@Composable
private fun BandCard() {
    val context = LocalContext.current
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var battery by remember { mutableStateOf<Int?>(null) }
    var batteryAt by remember { mutableLongStateOf(0L) }
    var rssi by remember { mutableStateOf<Int?>(null) }
    var rssiAt by remember { mutableLongStateOf(0L) }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var buzz by remember { mutableStateOf(Buzz.Idle) }
    // What the band calls itself, remembered between visits so the heading
    // does not have to wait on a session to say the right thing. Asked for
    // once: the name of a piece of hardware does not change, and every ask
    // costs a session Google Health could have been using to sync.
    var name by remember { mutableStateOf(BandName.read(context)) }

    fun readName() {
        if (name != null) return
        Band.readDeviceInfo(context, bandResult(
            onSuccess = { info ->
                val said = (info as? Map<*, *>)
                val found = listOf("name", "model")
                    .firstNotNullOfOrNull { (said?.get(it) as? String)?.takeIf { v -> v.isNotBlank() } }
                if (found != null) {
                    name = found
                    BandName.write(context, found)
                }
            },
            // Silent. The heading already has something to say, and a
            // failure to learn a better name is not worth a line of error
            // on the card about the band being fine.
            onError = { _, _ -> },
        ))
    }

    // One at a time: the band holds a single session, so the signal read waits
    // for the battery read rather than racing it.
    fun readings() {
        Band.readBattery(context, bandResult(
            onSuccess = { value ->
                // The band answered, so it is reachable whatever the adapter
                // said, and whatever an earlier attempt complained about.
                connected = true
                message = null
                battery = (value as? Map<*, *>)?.get("percent") as? Int
                batteryAt = System.currentTimeMillis()
                // The only place the Air is actually asked. Kept, because no
                // widget is allowed to ask: the band answers one session at a
                // time and Google Health holds that session whenever it syncs,
                // so a widget polling for a fresh charge would quietly cost a
                // day of steps and sleep. The widgets draw this, dated.
                battery?.let { percent ->
                    BandStore.rememberBattery(context, percent, batteryAt)
                    // The other band fact a widget draws, and the other one
                    // nothing used to pass on.
                    WidgetRefresh.poke(context)
                }
                Band.readSignal(context, bandResult(
                    onSuccess = { reading ->
                        syncing = false
                        rssi = (reading as? Map<*, *>)?.get("rssi") as? Int
                        rssiAt = System.currentTimeMillis()
                        // Last in the chain, and at most once ever. The
                        // band holds one session, so this waits its turn
                        // behind the two readings somebody actually came
                        // here to see.
                        readName()
                    },
                    onError = { code, detail -> syncing = false; message = reason(code, detail) },
                ))
            },
            onError = { code, detail -> syncing = false; message = reason(code, detail) },
        ))
    }

    fun sync() {
        if (syncing) return
        syncing = true
        message = null
        // The adapter first, then the band. Firing both at once meant the
        // adapter's answer could land second and put "Disconnected" back on a
        // band that had just replied.
        Band.isConnected(context, bandResult(
            onSuccess = { connected = it as? Boolean; readings() },
            onError = { _, _ -> connected = null; readings() },
        ))
    }

    fun buzzBand() {
        if (buzz != Buzz.Idle) return
        message = null
        buzz = Buzz.Sending
        Band.buzz(context, bandResult(
            onSuccess = { buzz = Buzz.Sent },
            onError = { code, detail ->
                buzz = Buzz.Idle
                message = reason(code, detail)
            },
        ))
    }

    // "Sent" is a confirmation, not a state: it says its piece and goes.
    LaunchedEffect(buzz) {
        if (buzz == Buzz.Sent) {
            delay(2_000)
            buzz = Buzz.Idle
        }
    }
    LaunchedEffect(Unit) { sync() }

    // Both readings say when they were taken, so the card has to keep count
    // of the time passing rather than freeze on the minute it was drawn.
    val now = nowTicking()

    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(
                    BandIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(name ?: BAND_NAME, style = MaterialTheme.typography.headlineSmall)
                Row(
                    // A failure takes this line and can run to two, so the
                    // glyph sits on the first line rather than floating in
                    // the middle of the block.
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    // A failure takes this line, so the readings never have to
                    // carry an error beside a number that is no longer true.
                    Icon(
                        if (connected == true) Icons.Outlined.Bluetooth
                        else Icons.Outlined.BluetoothDisabled,
                        contentDescription = null,
                        tint = if (connected == true) MaterialTheme.extras.positive
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp).size(15.dp),
                    )
                    Text(
                        message ?: when {
                            connected == true -> "Connected"
                            connected == false -> "Disconnected"
                            syncing -> "Checking…"
                            else -> "Disconnected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { sync() }, enabled = !syncing) {
                if (syncing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Sync,
                        contentDescription = "Check the band again",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(top = 22.dp),
        ) {
            Metric(
                icon = Icons.Outlined.BatteryStd,
                label = "Battery",
                value = battery?.let { "$it%" } ?: "\u2014",
                detail = if (batteryAt == 0L) "Not checked yet" else "Checked ${since(batteryAt, now)}",
                modifier = Modifier.weight(1f),
            )
            Metric(
                icon = Icons.Outlined.SignalCellularAlt,
                label = "Signal",
                value = rssi?.let { strength(it) } ?: "\u2014",
                detail = rssi?.let { "$it dBm \u00b7 ${since(rssiAt, now)}" } ?: "Not checked yet",
                modifier = Modifier.weight(1f),
            )
        }

        // Off while a sync is in flight. The band answers one connection
        // at a time, so a buzz asked for mid-sync does not queue -- it
        // comes back BUSY, which looked like the button being broken. It
        // was the one control in the app that could be pressed into a
        // guaranteed failure.
        // And off when the band is not there at all. Unknown counts as not
        // there: before the first read lands nothing has answered, and a
        // button that is live before anything has been heard from is a
        // button whose first press fails.
        val reachable = !syncing && connected == true
        OutlinedButton(
            onClick = { buzzBand() },
            enabled = reachable && buzz == Buzz.Idle,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        ) {
            when (buzz) {
                // The glyph shakes instead of a spinner turning. A
                // spinner says only that something is happening; this
                // says what, and the band is doing the same thing at the
                // other end of it.
                Buzz.Sending -> Icon(
                    Icons.Outlined.Vibration,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).buzzing(),
                )
                Buzz.Sent -> Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    // Lands rather than appears, on the spring the goal
                    // tick uses: both are a thing that worked.
                    modifier = Modifier.size(18.dp).springIn(),
                )
                Buzz.Idle -> Icon(
                    Icons.Outlined.Vibration,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(9.dp))
            AnimatedFigure(
                when {
                    buzz == Buzz.Sending -> "Sending\u2026"
                    buzz == Buzz.Sent -> "Sent"
                    syncing -> "Syncing with the band\u2026"
                    connected != true -> "Band not connected"
                    else -> "Buzz the band"
                },
                rising = false,
            ) { Text(it) }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 22.dp),
        )
    }
}

@Composable
private fun Metric(
    icon: ImageVector,
    label: String,
    value: String?,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // A missing reading stays blank rather than showing a dash: at this
        // size a dash draws a rule across the card, and the line underneath
        // already says it has not been read. The height is held either way so
        // the two tiles keep one baseline.
        Box(Modifier.padding(top = 5.dp).height(34.dp), contentAlignment = Alignment.CenterStart) {
            if (value != null) Text(value, style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            detail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.extras.disabled,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** A proximity hint, not a measurement, so it is worded as one. */
private fun strength(rssi: Int): String = when {
    rssi >= -60 -> "Strong"
    rssi >= -80 -> "Good"
    else -> "Weak"
}

private fun since(atMillis: Long, now: Long): String {
    val delta = (now - atMillis).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        else -> "${TimeUnit.MILLISECONDS.toDays(delta)} d ago"
    }
}

private fun reason(code: String?, detail: String?): String = when (code) {
    "BUSY" -> "Busy delivering a notification; try again shortly"
    // The one way a reading is dropped mid-flight: the relay started and took
    // the band's single session for itself.
    "CLOSED" -> "The relay took the band over; try again"
    "BLUETOOTH_PERMISSION" -> "Nearby devices permission is needed"
    "AIR_NOT_FOUND" -> "Pair your Air in Google Health first"
    "SILENT" -> "Your default vibration is silent"
    "CALLS_ONLY" -> "Your default repeats until a call is answered"
    // Anything we did not anticipate keeps what it said, rather than being
    // flattened into a sentence about reach that may not be true.
    else -> detail?.takeIf { it.isNotBlank() } ?: "Could not reach the band"
}

/** The theme in a line, so the row says what it holds without opening. */
private fun appearanceSummary(appearance: Appearance): String {
    val preset = appearance.preset
    val mode = when (appearance.mode) {
        ThemeMode.System -> "follows the system"
        ThemeMode.Light -> "always light"
        ThemeMode.Dark -> "always dark"
    }
    val look = preset?.label ?: if (appearance.custom != null) "Custom colour" else "Custom"
    return "$look · $mode"
}

/** What the Permissions row says without having to be opened. */
@Composable
private fun permissionsSummary(): String {
    val context = LocalContext.current
    val blocking = remember(resumeTicks()) { blockingGrants(context) }
    return when (blocking) {
        0 -> "Everything the relay needs is allowed"
        1 -> "One thing still to allow"
        else -> "$blocking things still to allow"
    }
}

