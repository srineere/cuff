package com.srineere.airbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import com.srineere.airbridge.widget.ROUTE_EXTRA
import com.srineere.airbridge.widget.WidgetRefresh
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.util.Log
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.srineere.airbridge.band.BandLink
import com.srineere.airbridge.health.HealthEnabled
import com.srineere.airbridge.health.HealthReader
import com.srineere.airbridge.health.HealthSettings
import com.srineere.airbridge.health.StepGoalSettings
import com.srineere.airbridge.health.canPulseBand
import com.srineere.airbridge.health.goalBuzzWanted
import com.srineere.airbridge.health.nextStepCheckMillis
import com.srineere.airbridge.health.stepWorkWanted
import com.srineere.airbridge.health.shouldBuzzForGoal
import com.srineere.airbridge.health.shouldNudgeForHour
import com.srineere.airbridge.band.BandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.srineere.airbridge.band.BandSink
import com.srineere.airbridge.band.Bands

/** User-started example relay; never starts work from a notification callback. */
class CalendarRelayService : Service() {
    private var band: BandLink? = null
    /**
     * The Air runs one secure session at a time, so band work is serialised
     * here. A message arriving mid-ring waits rather than failing BUSY, which
     * is what the Dart operation queue used to do.
     */
    private val bandQueue = ArrayDeque<(BandLink) -> Unit>()
    private var bandBusy = false
    private var heartStreaming = false
    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stepCheck = Runnable { checkStepGoals() }
    private var lastStepCheck = 0L
    private var filter: CalendarAlertFilter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pending = 0
    private var ready = false
    private var stopping = false
    private var sequence = 0
    private val inFlight = mutableMapOf<String, Pair<String?, Long>>()
    private var selectedPackages = emptySet<String>()
    private var pulseStyle = "single"
    private val previewReplies = mutableMapOf<String, (RelayWorkerResult) -> Unit>()
    private val choices = mutableMapOf<String, RelayBurstChoice>()
    private var displayedSilence: String? = null
    private val silenceTick = Runnable { settingsChanged() }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { settingsChanged() }
    }

    /** One call ringing on the Air. [key] is the notification that started it. */
    private class RingSession(
        val key: String,
        val packageName: String,
        val callerId: String?,
        val match: RelayMatch,
        /**
         * Whether a call started this, which the preset cannot answer: a
         * message rule set to Ring rings exactly the same way, and only one
         * of the two may cross a quiet window set to let calls through.
         */
        val kind: RelayAlertKind,
        val startedAt: Long,
        /**
         * The audio mode when the ring began. Some apps already hold the audio
         * session while they are still ringing, so only a *change* into an
         * in-call mode means the call was picked up.
         */
        val startMode: Int,
        /** Resolved when the ring starts, so settings cannot change under it. */
        val settings: RelayRingSettings,
    ) {
        /** The phone was heard ringing, so a return to silence means it ended. */
        var sawRingtone: Boolean = startMode == RelayRingWatch.MODE_RINGTONE
    }

    private var ring: RingSession? = null
    private var ringSettings = RelayRingSettings()

    /**
     * The last call that rang. Calls no longer go through the message dedupe,
     * so without this a phone still ringing after the duration ran out would
     * start a fresh ring on its next notification update, forever.
     */
    private var lastRingKey: String? = null
    private var lastRingStoppedAt = 0L
    private var lastRingTimedOut = false

    /**
     * Unlocking is NOT a reason to stop ringing. On a phone with face unlock,
     * an incoming call wakes the screen and unlocks it within a second, which
     * killed every ring that arrived while the phone was locked. Unlocking to
     * see who is calling is not the same as dealing with the call; answering
     * is caught by the audio mode and by the notification going away.
     */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ring != null) Log.i(TAG, "unlocked while ringing; ring continues")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // onCreate, not onStartCommand: the five-second startForeground rule
        // means nothing may come before enterForeground() there.
        CrashLog.install(this)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(clockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(clockReceiver, filter)
        val present = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(userPresentReceiver, present, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(userPresentReceiver, present)
    }

    fun settingsChanged() {
        scheduleStepCheck()
        val config = RelayRuleStore.read(this)
        val packages = config.selectedPackages
        ringSettings = config.ring
        if (packages != selectedPackages) {
            selectedPackages = packages
            if (ready) filter = CalendarAlertFilter(System.currentTimeMillis(),
                CalendarNotificationListener.existingKeys(packages), packages)
        }
        val now = System.currentTimeMillis()
        val reason = config.silence.reason(now)
        if (reason != null) cancelWaiting(reason)
        if (ready && reason != displayedSilence) {
            displayedSilence = reason
            getSystemService(NotificationManager::class.java).notify(902, buildNotification(reason))
        }
        // Once, when the answer can actually change, rather than every minute
        // for as long as the relay runs. Anything that edits the rules comes
        // back through here, and so does a change of clock or timezone.
        handler.removeCallbacks(silenceTick)
        if (!stopping) {
            config.silence.nextChange(now)?.let {
                handler.postDelayed(silenceTick, maxOf(1L, it - now))
            }
        }
    }

    /** The silence as it stands, for the paths that have not just read it. */
    private fun currentSilence(): String? =
        RelayRuleStore.read(this).silence.reason(System.currentTimeMillis())

    /**
     * [silence] is passed in by anything that has just read the config, so
     * that rewording the notification does not mean reading it twice.
     */
    private fun buildNotification(silence: String? = currentSilence()): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 0, Intent(this, CalendarRelayService::class.java).setAction(STOP), flags)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val pause = PendingIntent.getService(this, 2, Intent(this, CalendarRelayService::class.java)
            .setAction(if (silence == "paused") RESUME else PAUSE), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_cuff_notification)
            .setContentTitle("CUFF")
            .setContentText(when {
                ring != null -> "Ringing your Air · tap Silence to stop"
                silence == "paused" -> "Paused · No notification vibrations"
                silence == "quiet_hours" && RelayRuleStore.read(this).silence.callsStillRing ->
                    "Quiet hours · Calls only"
                silence == "quiet_hours" -> "Quiet hours · No notification vibrations"
                else -> "Listening for your notification rules"
            })
            .setContentIntent(open).setOngoing(true)
        // Silencing a ring is the only useful action while the wrist is buzzing.
        if (ring != null) {
            val silenceRing = PendingIntent.getService(this, 3, Intent(this, CalendarRelayService::class.java).setAction(SILENCE), flags)
            return builder.addAction(Notification.Action.Builder(null, "Silence", silenceRing).build())
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        }
        return builder
            .addAction(Notification.Action.Builder(null, if (silence == "paused") "Resume" else "Pause 15 min", pause).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun refreshNotification() {
        if (!ready) return
        runCatching { getSystemService(NotificationManager::class.java).notify(902, buildNotification()) }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val coalescer = RelayCoalescer()
    private var holdingFollowUp = false
    /**
     * Whether a call is among the alerts waiting out the cooldown.
     *
     * The follow-up asks about the silence before it knows what it is holding
     * -- it has to, because being silenced means dropping the burst rather
     * than taking it -- so the one fact it needs is carried here instead.
     */
    private var followUpHasCall = false
    private val followUp = Runnable { sendFollowUp() }
    private val watchdog = Runnable {
        saveOutcome("Relay command exceeded its deadline; stopped. Delivery is uncertain; no retry.")
        stopSelf()
    }

    /**
     * Posts the ongoing notification. Android gives a service started with
     * startForegroundService about five seconds to do this and kills the whole
     * app if it does not -- stopping ourselves first is not an excuse -- so
     * every path through [onStartCommand] comes through here before it decides
     * anything, including deciding to quit.
     */
    private fun enterForeground(): Boolean = try {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Notifications to Air", NotificationManager.IMPORTANCE_LOW)
        )
        // Anything left over from a previous process. A nudge is about the
        // hour you are in and the process that could replace or cancel it is
        // gone, so starting fresh is the honest state -- and this is the one
        // moment that is guaranteed to run, where settingsChanged() only
        // fires when something actually changes.
        runCatching { manager.cancel(903) }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(902, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(902, notification)
        true
    } catch (error: Exception) {
        Log.w(TAG, "could not enter the foreground", error)
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        if (!enterForeground() && band == null) {
            saveOutcome("Android would not let the relay start. Allow notifications for CUFF, then start again.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (cancelStart) {
            cancelStart = false
            saveOutcome("Relay stopped.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            // An explicit stop is the user's decision and must survive a
            // reboot, unlike a crash or an OEM kill.
            setWanted(this, false)
            requestStop("Relay stopped.")
            return START_NOT_STICKY
        }
        // These two leave the relay running, so they must not answer
        // START_NOT_STICKY: Android keeps one restart policy per service,
        // taken from the latest start, and the answer here would turn off
        // the sticky restart the whole relay depends on. Tapping Pause used
        // to mean the relay never came back from an OEM kill.
        if (intent?.action == SILENCE) {
            stopRing("silenced")
            if (band == null) { stopSelf(); return START_NOT_STICKY }
            return START_STICKY
        }
        if (intent?.action == PAUSE || intent?.action == RESUME) {
            RelayRuleStore.update(this) { it.copy(silence = it.silence.copy(pauseUntil = if (intent.action == RESUME) 0 else System.currentTimeMillis() + 900_000)) }
            if (band == null) { stopSelf(); return START_NOT_STICKY }
            return START_STICKY
        }
        if (band != null && !stopping) return START_STICKY
        // Starting again while the last stop is still draining. The link is
        // already closed and nulled; what is left is the stopping flag, and
        // leaving it set would make startWorker() undo itself on its last line.
        stopping = false
        selectedPackages = RelayRuleStore.read(this).selectedPackages
        pulseStyle = RelayAppSelection.pulseStyle(this)
        if (selectedPackages.isEmpty()) {
            saveOutcome("Select at least one notification app before starting.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasAccess(this)) {
            saveOutcome("Enable notification access, then start again.")
            stopSelf()
            return START_NOT_STICKY
        }
        active = this
        // After a reboot or an OEM restart the system rebinds the listener a
        // moment after this service starts, so a listener that is not up yet
        // is worth waiting for rather than a reason to give up.
        if (CalendarNotificationListener.connected) startWorker()
        else {
            saveOutcome("Waiting for notification access…")
            listenerWaitUntil = System.currentTimeMillis() + LISTENER_WAIT_MILLIS
            handler.postDelayed(listenerWait, LISTENER_POLL_MILLIS)
        }
        return START_STICKY
    }

    /** Waits for the notification listener before committing to an engine. */
    private var listenerWaitUntil = 0L
    private val listenerWait = object : Runnable {
        override fun run() {
            if (band != null || stopping) return
            if (CalendarNotificationListener.connected) { startWorker(); return }
            if (System.currentTimeMillis() > listenerWaitUntil) {
                requestStop("Notification access did not come back. Start the relay again.")
                return
            }
            handler.postDelayed(this, LISTENER_POLL_MILLIS)
        }
    }

    private fun startWorker() {
        try {
            saveOutcome("Starting notification relay…")
            check(hasAccess(this) && CalendarNotificationListener.connected)
            // Only one session to the band, ever: if the UI is holding a link
            // of its own, it lets go here.
            Band.close()
            band = Bands.open(applicationContext)
            filter = CalendarAlertFilter(
                System.currentTimeMillis(),
                CalendarNotificationListener.existingKeys(selectedPackages), selectedPackages,
            )
            ready = true
            settingsChanged()
            handler.removeCallbacks(watchdog)
            saveOutcome("Listening for alerts from ${selectedPackages.size} selected app(s).")
            if (stopping) requestStop("Relay stopped.")
        } catch (error: IllegalStateException) {
            requestStop("Notification listener unavailable. Enable access and start again.")
        } catch (error: Exception) {
            saveOutcome("Relay startup failed: ${error.javaClass.simpleName}")
            stopSelf()
        }
    }

    /**
     * Runs [work] against the band once nothing else holds it. [work] must
     * call [bandDone] when its operation completes, however it ends.
     *
     * False when there is no link to run against, so that the caller can
     * release what it has already taken. Returning silently left the wake
     * lock held and the alert sitting in flight until the watchdog fired.
     */
    private fun onBand(work: (BandLink) -> Unit): Boolean {
        val link = band ?: return false
        if (bandBusy) { bandQueue.addLast(work); return true }
        bandBusy = true
        work(link)
        return true
    }

    private fun bandDone() {
        val next = bandQueue.removeFirstOrNull()
        val link = band
        if (next == null || link == null) { bandBusy = false; return }
        next(link)
    }

    /** Builds the reply for one band operation, freeing the queue either way. */
    private fun bandReply(onResult: (RelayWorkerResult) -> Unit) = object : BandResult {
        override fun success(result: Any?) {
            bandDone()
            onResult(RelayWorkerResult("verified", null))
        }
        override fun error(code: String, message: String?, details: Any?) {
            bandDone()
            onResult(RelayWorkerResult("failed", code.takeIf { it.matches(ERROR_CODE) } ?: "WORKER_ERROR"))
        }
    }

    fun acceptsPackage(packageName: String): Boolean = ready && !stopping && packageName in selectedPackages

    internal fun onAlert(packageName: String, key: String, postedAt: Long, ongoing: Boolean, summary: Boolean, messageTimestamp: Long?, conversationIds: Set<String>, kind: RelayAlertKind = RelayAlertKind.MESSAGE,
                           callerId: String? = null) {
        if (!ready || stopping) return
        val now = System.currentTimeMillis()
        if (filter?.accept(packageName, key, postedAt, now, ongoing, summary, messageTimestamp, kind) != true) {
            Log.i(TAG, "dropped pkg=$packageName kind=$kind by=filter " +
                "age=${now - postedAt}ms ongoing=$ongoing summary=$summary")
            return
        }
        val config = RelayRuleStore.read(this)
        val match = config.match(
            packageName, conversationIds, kind, callerId,
            dialer = RelayAppSelection.dialer(this),
        )
        if (match == null) {
            Log.i(TAG, "dropped pkg=$packageName kind=$kind by=rule (silent or app off)")
            record(nextId(), packageName, postedAt, "filtered")
            return
        }
        val silence = config.silence.reason(now, isCall = kind == RelayAlertKind.CALL)
        if (silence != null) {
            Log.i(TAG, "dropped pkg=$packageName kind=$kind by=$silence")
            cancelWaiting(silence)
            record(nextId(), packageName, postedAt, silence)
            return
        }
        // One ring at a time, and nothing else buzzes during it: a message pulse
        // in the middle of a ring is indistinguishable from the ring itself.
        Log.i(TAG, "alert pkg=$packageName kind=$kind ongoing=$ongoing preset=${match.preset.id}")
        if (ring != null) { record(nextId(), packageName, postedAt, "ringing"); return }
        if (match.preset == RelayPreset.RING) {
            if (rangAlready(key, now)) { record(nextId(), packageName, postedAt, "ring_done"); return }
            startRing(key, packageName, match, callerId, kind)
            return
        }
        submit(packageName, postedAt, match, callerId = callerId, kind = kind)
    }

    /**
     * This call already had its ring: either it has only just stopped, or it
     * ran its full duration and the phone is still ringing. Either way the
     * wrist has said its piece until the call ends.
     */
    private fun rangAlready(key: String, now: Long): Boolean =
        key == lastRingKey && (lastRingTimedOut || now - lastRingStoppedAt < RING_REARM_MILLIS)

    /**
     * Polls the audio mode, because once the ringing notification has been
     * replaced there is nothing left to tell us how the call ended.
     */
    private val ringWatch = object : Runnable {
        override fun run() {
            val session = ring ?: return
            val mode = audioMode()
            if (mode == RelayRingWatch.MODE_RINGTONE) session.sawRingtone = true
            val reason = RelayRingWatch.reason(session.startMode, session.sawRingtone, mode)
            if (reason != null) stopRing(reason) else handler.postDelayed(this, RING_WATCH_MILLIS)
        }
    }

    /**
     * A notification that means the ringing is over, whatever the audio
     * mode says.
     *
     * The second witness, and on a silent phone the only one that fires
     * for a call nobody picked up: Telecom posts the missed call on its
     * own channel the moment it gives up. Without this the ring would run
     * to its maximum after a phone that had already stopped ringing.
     */
    internal fun onSettledCall(packageName: String, channelId: String?) {
        val session = ring ?: return
        if (RelayRingWatch.endsRing(session.packageName, packageName, channelId)) {
            Log.i(TAG, "ring stopped by a settled call notification on $channelId")
            stopRing("ended")
        }
    }

    /** A ring stops when the call is answered, declined, cleared, or times out. */
    internal fun onAlertRemoved(key: String) {
        if (ring?.key == key) {
            // A locked phone swaps the ringing notification for its full-screen
            // call UI. That is not the call ending, and the ringer says so.
            if (RelayRingWatch.stopOnRemoval(audioMode(), ringerAudible())) stopRing("cleared")
            else Log.i(TAG, "ringing notification replaced; phone still ringing")
        }
        // The call is over, so this key may ring again when it next comes in.
        if (key == lastRingKey) { lastRingKey = null; lastRingTimedOut = false }
    }

    private fun startRing(
        key: String,
        packageName: String,
        match: RelayMatch,
        callerId: String?,
        kind: RelayAlertKind,
    ) {
        val session = RingSession(key, packageName, callerId, match, kind,
            System.currentTimeMillis(),
            audioMode(), RelayRuleStore.read(this).ringFor(packageName, callerId))
        ring = session
        Log.i(TAG, "ring start pkg=$packageName gap=${session.settings.gapMillis}ms max=${session.settings.maxSeconds}s mode=${session.startMode}")
        record(nextId(), packageName, session.startedAt, "ring_started", match = match,
            callerId = callerId, kind = kind)
        refreshNotification()
        // Nothing else buzzes during a ring, and a follow-up already scheduled
        // would have. It keeps waiting; stopRing picks it up when the call is
        // over.
        handler.removeCallbacks(followUp)
        handler.postDelayed(ringWatch, RING_WATCH_MILLIS)
        sendRing()
    }

    private fun stopRing(reason: String) {
        val session = ring ?: return
        ring = null
        handler.removeCallbacks(ringWatch)
        lastRingKey = session.key
        lastRingStoppedAt = System.currentTimeMillis()
        lastRingTimedOut = reason == "ring_timeout"
        Log.i(TAG, "ring stop reason=$reason after=${System.currentTimeMillis() - session.startedAt}ms")
        // The ring is over, so the numbers resolved for it are too. See
        // RelayNumberMemo: its whole point is that it lasts one call.
        RelayContacts.forgetNumbers()
        record(nextId(), session.packageName, session.startedAt, "ring_stopped", reason,
            session.match, session.callerId)
        // Releases the held session; the in-flight ring call then completes on
        // its own and does its own bookkeeping.
        // Deliberately not queued: the queue is busy holding the ring, and
        // this is what releases it.
        runCatching { band?.stopRing() }
        // Give Google Health the usual quiet window now that the ring is over,
        // and pick up anything that was waiting while it ran. Dropping this
        // answer left those alerts waiting for a follow-up nobody scheduled.
        val now = System.currentTimeMillis()
        coalescer.onFinished(now)?.let { scheduleFollowUp(it, now) }
        refreshNotification()
    }

    private fun audioMode(): Int = runCatching {
        getSystemService(AudioManager::class.java).mode
    }.getOrDefault(AudioManager.MODE_NORMAL)

    /**
     * Whether the phone would actually play a ringtone.
     *
     * The question behind it is not about sound: it is whether
     * `MODE_RINGTONE` is a witness worth consulting. Telecom takes ringing
     * audio focus in order to play a ringtone, so with the ringer on
     * silent or vibrate it never does, and the mode sits at
     * `MODE_NORMAL` through a ringing call.
     *
     * Volume as well as mode, because a phone left on Normal with the ring
     * stream pulled to zero is silent by every measure except the one
     * `getRingerMode` reports.
     *
     * Defaults to audible when it cannot be read: that is the behaviour
     * this app has always had, and it is the half that works.
     */
    private fun ringerAudible(): Boolean = runCatching {
        val audio = getSystemService(AudioManager::class.java)
        audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(AudioManager.STREAM_RING) > 0
    }.getOrDefault(true)

    /** True once this call was picked up, including WhatsApp and Instagram calls. */
    private fun answered(session: RingSession): Boolean =
        RelayRingWatch.reason(session.startMode, session.sawRingtone, audioMode()) != null

    /**
     * Starts one held ring. The worker keeps the secure session open and
     * pulses inside it, so this is a single long call rather than a pulse
     * schedule: stopping means telling the worker, not cancelling a timer.
     */
    private fun sendRing() {
        val session = ring ?: return
        if (stopping || !ready) { stopRing("cancelled"); return }
        if (answered(session)) { stopRing("answered"); return }
        RelayRuleStore.read(this).silence
            .reason(System.currentTimeMillis(), isCall = session.kind == RelayAlertKind.CALL)
            ?.let { stopRing(it); return }
        if (band == null) { stopRing("worker_unavailable"); return }
        val id = nextId()
        begin()
        refreshDeadline()
        inFlight[id] = session.packageName to session.startedAt
        record(id, session.packageName, session.startedAt, "queued", match = session.match,
            callerId = session.callerId, kind = session.kind)
        fun finished(result: RelayWorkerResult) {
            inFlight.remove(id)
            record(id, session.packageName, session.startedAt, result.stage, result.errorCode,
                session.match, session.callerId, session.kind)
            Log.i(TAG, "ring result stage=${result.stage} code=${result.errorCode}")
            saveOutcome("Ring ${result.stage}${result.errorCode?.let { ": $it" } ?: ""}.")
            end()
            // Reaching the duration ends the ring without anyone asking.
            // A ring the band acknowledged ran its full length, which is the
            // one case lastRingTimedOut exists for: the phone is very likely
            // still ringing, and the wrist has already said its piece. A ring
            // that failed said nothing, so it must be allowed to re-arm.
            if (ring === session)
                stopRing(if (result.stage == "verified") "ring_timeout" else "ring_finished")
        }
        val sent = onBand { link ->
            link.ring(
                intensity = session.match.preset.intensity,
                gapMillis = session.settings.gapMillis.toLong(),
                maxMillis = session.settings.maxMillis,
                pulses = null,
                result = bandReply { finished(it) },
            )
        }
        if (!sent) finished(RelayWorkerResult("failed", "WORKER_UNAVAILABLE"))
    }

    private fun submit(sbnPackage: String?, postedAt: Long, match: RelayMatch,
                       completion: ((RelayWorkerResult) -> Unit)? = null, callerId: String? = null,
                       kind: RelayAlertKind = RelayAlertKind.MESSAGE) {
        val id = nextId()
        if (choices.size >= 256) {
            record(id, sbnPackage, postedAt, "queue_full")
            completion?.invoke(RelayWorkerResult("failed", "QUEUE_FULL"))
            return
        }
        if (completion != null) previewReplies[id] = completion
        choices[id] = RelayBurstChoice(match, System.currentTimeMillis(), kind)
        record(id, sbnPackage, postedAt, "detected", match = match, callerId = callerId)
        val now = System.currentTimeMillis()
        when (val decision = coalescer.onAlert(id, now)) {
            RelayCoalescer.Decision.DispatchNow ->
                dispatch(id, sbnPackage, postedAt, postedAt + ALERT_LIFETIME_MILLIS, match, callerId, kind)
            is RelayCoalescer.Decision.Merged -> {
                if (kind == RelayAlertKind.CALL) followUpHasCall = true
                if (!holdingFollowUp) { holdingFollowUp = true; begin() }
                inFlight[id] = sbnPackage to postedAt
                record(id, sbnPackage, postedAt, "merged", match = match, callerId = callerId)
                decision.scheduleAtMillis?.let { scheduleFollowUp(it, now) }
            }
        }
    }

    private fun cancelWaiting(reason: String) {
        handler.removeCallbacks(followUp)
        followUpHasCall = false
        coalescer.cancelFollowUp().forEach { id ->
            choices.remove(id)
            previewReplies.remove(id)?.invoke(RelayWorkerResult("failed", "SUPPRESSED"))
            inFlight.remove(id)?.let { (source, postedAt) -> record(id, source, postedAt, reason) }
        }
        if (holdingFollowUp) { holdingFollowUp = false; end() }
    }

    /**
     * Posts the follow-up for a burst, no later than the oldest alert in it
     * can survive. [cooldownEndsAt] is the coalescer's answer; [followUpAt]
     * says why that answer is not always the one to use.
     */
    private fun scheduleFollowUp(cooldownEndsAt: Long, now: Long) {
        if (stopping) return
        val at = followUpAt(cooldownEndsAt, oldestWaitingPostedAt())
        handler.postDelayed(followUp, maxOf(0, at - now))
    }

    /**
     * When the oldest alert now waiting out the cooldown was posted, or null
     * when nothing is waiting.
     *
     * Read from the two maps together rather than from either alone, because
     * neither holds the burst by itself: [choices] holds every alert that has
     * not finished, and [inFlight] the moment each one was posted. A command
     * that has just finished is out of both by the time this is asked, and a
     * ring is in [inFlight] and never in [choices], so what is in both is
     * exactly the alerts the follow-up is holding.
     */
    private fun oldestWaitingPostedAt(): Long? =
        choices.keys.mapNotNull { inFlight[it]?.second }.minOrNull()

    private fun sendFollowUp() {
        if (stopping || !coalescer.hasFollowUp) return
        // A ring started after this was scheduled. It keeps its hold and its
        // place; stopRing schedules it again once the call is over.
        if (ring != null) return
        val now = System.currentTimeMillis()
        val silence = RelayRuleStore.read(this).silence.reason(now, isCall = followUpHasCall)
        if (silence != null) { cancelWaiting(silence); return }
        followUpHasCall = false
        val members = coalescer.takeFollowUp().mapNotNull { id ->
            val source = inFlight.remove(id)
            val choice = choices.remove(id)
            if (source == null || choice == null) null else Triple(id, source, choice)
        }
        val fresh = members.filter {
            now >= it.second.second && now - it.second.second < ALERT_LIFETIME_MILLIS
        }
        members.filterNot { it in fresh }.forEach {
            record(it.first, it.second.first, it.second.second, "expired")
            previewReplies.remove(it.first)?.invoke(RelayWorkerResult("failed", "EXPIRED"))
        }
        val winner = fresh.reduceOrNull { a, b -> if (a.third.prefer(b.third) === b.third) b else a }
        fresh.forEach { record(it.first, it.second.first, it.second.second, "combined", match = it.third.match) }
        if (winner != null) {
            val id = nextId()
            val replies = fresh.mapNotNull { previewReplies.remove(it.first) }
            if (replies.isNotEmpty()) previewReplies[id] = { result -> replies.forEach { it(result) } }
            dispatch(id, winner.second.first, winner.second.second,
                winner.second.second + ALERT_LIFETIME_MILLIS,
                winner.third.match, kind = winner.third.kind)
        } else coalescer.onFinished(now)
        holdingFollowUp = false
        end()
    }

    /**
     * Whether the band is linked right now. Unlike the readings below this
     * asks the adapter rather than the band, so it costs no session and does
     * not queue behind a notification.
     */
    internal fun readConnection(reply: BandResult) {
        val link = band
        if (link == null) reply.error("NOT_RUNNING", "The relay is not running.", null)
        else link.isConnected(reply)
    }

    internal fun readBattery(reply: BandResult) = readDeviceStatus(reply) { link, r -> link.readBattery(r) }
    internal fun readSignal(reply: BandResult) = readDeviceStatus(reply) { link, r -> link.readSignalStrength(r) }
    internal fun readDeviceInfo(reply: BandResult) = readDeviceStatus(reply) { link, r -> link.readDeviceInfo(r) }

    /**
     * Holds the band open for a live heart rate reading.
     *
     * Unlike every other band call this one does not finish on its own, so
     * it takes the same slot a notification would and keeps it: begin() so
     * the watchdog and wake lock cover it, onBand so alerts queue behind it
     * rather than fighting it for the one session the Air allows. The
     * watchdog bounds it at four minutes even if the screen never calls
     * stop, which is the backstop behind the UI's own two-minute limit.
     */
    /**
     * The step goals, checked on a timer rather than pushed.
     *
     * Health Connect notifies nobody when a figure changes, so this polls --
     * but it polls from the service that is already running for
     * notifications, so it costs one read a quarter of an hour rather than a
     * background job of its own. Both buzzes obey quiet hours and pause,
     * because a goal is not more important than the quiet.
     */
    private fun scheduleStepCheck() {
        handler.removeCallbacks(stepCheck)
        val settings = HealthSettings.read(this)
        // The step goal belongs to the health half; the hourly nudge does
        // not, and can be switched on with the tab off -- it asks Health
        // Connect for the Steps permission on its own. So the check runs
        // whenever either of them has something to do, and the two rules
        // below decide which.
        //
        // With neither, nothing is scheduled at all: the alarm is dropped
        // rather than rearmed, and a nudge already sitting in the shade
        // goes with it, for the same reason the next few lines drop one
        // whose own switch has just been turned off.
        if (!stepChecksWanted(settings)) {
            runCatching { getSystemService(NotificationManager::class.java).cancel(903) }
            cancelStepAlarm()
            return
        }
        // A nudge still sitting in the shade after the thing that posts it
        // was switched off is a notification about a setting that no longer
        // exists. Cleared here because this runs on every settings change.
        if (!settings.hourlyNudge || !settings.nudgeOnPhone) {
            runCatching { getSystemService(NotificationManager::class.java).cancel(903) }
        }
        // Not a flat quarter of an hour: see nextStepCheckMillis. A strict
        // period samples the same four minutes of every hour and can miss
        // the nudge's window for good.
        val clock = java.time.LocalTime.now()
        val wait = nextStepCheckMillis(
            settings.hourlyNudge, clock.minute, clock.second, STEP_CHECK_MILLIS,
        )
        // The wait is the one nextStepCheckMillis chose and is unchanged;
        // what has changed is the clock it runs on. handler.postDelayed
        // counts in SystemClock.uptimeMillis, which stops while the device
        // is suspended, so on a phone that spends the night in Doze this
        // drifted by however long it was asleep: the daily goal was checked
        // whenever the phone next happened to be awake, and the nudge's
        // ten-minute window was missed rather than merely late. An alarm
        // counts in wall time, and setExactAndAllowWhileIdle is the one
        // kind Doze lets through.
        if (!armStepAlarm(wait)) {
            // No exact alarm to be had -- see armStepAlarm for when that
            // happens. The handler is what this always did, and a check
            // that drifts is still a great deal better than none.
            cancelStepAlarm()
            handler.postDelayed(stepCheck, wait)
        }

        // Check now as well as in a quarter of an hour. Someone who switches
        // this on having already walked ten thousand steps should not wait
        // fifteen minutes to hear about it. Throttled, because
        // settingsChanged() also fires on clock ticks and silence changes,
        // and Health Connect rate-limits callers.
        val now = SystemClock.elapsedRealtime()
        if (now - lastStepCheck >= STEP_CHECK_FLOOR_MILLIS) {
            lastStepCheck = now
            checkStepGoals(reschedule = false)
        }
    }

    /**
     * Arms the next step check on the wall clock, answering false when
     * Android will not give us an exact alarm.
     *
     * From API 31 `setExactAndAllowWhileIdle` throws SecurityException
     * unless the app holds the exact-alarm right. This app is not an alarm
     * clock and never claims USE_EXACT_ALARM, so it can hold that right two
     * ways: the SCHEDULE_EXACT_ALARM permission in the manifest, which is
     * granted at install on 31 and 32 and off by default from 33 until
     * somebody turns "Alarms & reminders" on by hand; or the
     * battery-optimisation exemption the Device screen already asks for,
     * which carries the exact-alarm right with it and is the path this app
     * actually walks. Neither is required. With neither, this answers false
     * and [scheduleStepCheck] goes back to the handler -- a drifting check
     * rather than a SecurityException thrown out of a settings change,
     * which would take the whole relay down with it.
     *
     * The call is guarded as well as asked about, because the two can
     * disagree: the permission can be revoked while the relay is running,
     * and OEM builds have their own ideas about alarm quotas. A refused
     * alarm must be no worse than the handler it replaced.
     */
    private fun armStepAlarm(waitMillis: Long): Boolean {
        val manager = getSystemService(AlarmManager::class.java) ?: return false
        val allowed = canArmExactAlarm(
            Build.VERSION.SDK_INT, exactAlarmsAllowed(manager), batteryExempt(this),
        )
        if (!allowed) {
            Log.i(TAG, "no exact alarm right; step check stays on the handler")
            return false
        }
        return runCatching {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + waitMillis,
                stepAlarmIntent(),
            )
            true
        }.getOrElse {
            Log.w(TAG, "exact step alarm refused; falling back to the handler", it)
            false
        }
    }

    /**
     * What the platform says about exact alarms.
     *
     * `canScheduleExactAlarms` arrived in API 31 together with the right it
     * reports on, so below that there is nothing to ask and the answer here
     * is not the one that matters -- [canArmExactAlarm] says yes on those
     * versions for its own reason.
     */
    private fun exactAlarmsAllowed(manager: AlarmManager): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return manager.canScheduleExactAlarms()
    }

    /**
     * The one alarm this service owns.
     *
     * Request code 7 because it is the first one free: 0 to 3 are the
     * ongoing notification's actions, 4 and 5 belong to the morning report
     * and the journal reminder, and 6 is the hourly nudge. Sharing a code
     * is a bug this file has already had -- `Intent.filterEquals` ignores
     * extras, so two intents on one code are a single PendingIntent and
     * FLAG_UPDATE_CURRENT silently rewrites whichever was made first.
     *
     * FLAG_UPDATE_CURRENT rather than a new token each time, so rearming
     * replaces the alarm already set instead of leaving one behind: the
     * system holds these across process restarts, and a leaked one is an
     * alarm nobody can cancel any more.
     */
    private fun stepAlarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        7,
        Intent(this, StepCheckReceiver::class.java).setAction(STEP_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Drops the alarm and the token behind it.
     *
     * Both, not just the alarm. A PendingIntent lives in the system's own
     * table and outlives this process, so cancelling the alarm alone would
     * leave the token for the next start to find and reuse.
     */
    private fun cancelStepAlarm() {
        runCatching {
            val alarm = stepAlarmIntent()
            getSystemService(AlarmManager::class.java)?.cancel(alarm)
            alarm.cancel()
        }
    }

    /**
     * The alarm went off. Called by [StepCheckReceiver] and by nothing
     * else; the check reschedules as it always did, so each alarm is what
     * sets the next one.
     */
    internal fun onStepAlarm() {
        Log.i(TAG, "step check woken by its alarm")
        checkStepGoals()
    }

    /**
     * Read here rather than passed in, because the switch can move while
     * an alarm is already in flight: the system holds a PendingIntent
     * across a process restart, so a check armed before the health half
     * went off still arrives after it.
     */
    private fun stepChecksWanted(settings: StepGoalSettings): Boolean =
        stepWorkWanted(settings, HealthEnabled.isOn(this))

    private fun goalWanted(settings: StepGoalSettings): Boolean =
        goalBuzzWanted(settings, HealthEnabled.isOn(this))

    private fun checkStepGoals(reschedule: Boolean = true) {
        if (reschedule) scheduleStepCheck()
        val settings = HealthSettings.read(this)
        // Asked again here and not only in the schedule above, because
        // [onStepAlarm] comes in from a broadcast and goes straight
        // through. The system holds a PendingIntent across a process
        // restart, so an alarm armed before the health half was switched
        // off can still arrive after it.
        if (!stepChecksWanted(settings)) return
        // A missing band used to end this outright. It still does when the
        // wrist is the only destination -- but a nudge the phone is meant to
        // show has somewhere to go without one. Relaying being off still
        // ends it either way: that switch is what the rows promise holds
        // these, and a nudge that outlived it would make them liars.
        val phoneToo = settings.hourlyNudge && settings.nudgeOnPhone
        if (!ready || stopping) return
        if (!canPulseBand(ready, stopping, band != null, heartStreaming) && !phoneToo) return

        healthScope.launch {
            val steps = withContext(Dispatchers.IO) { HealthReader().stepsNow(this@CalendarRelayService) }
                ?: return@launch
            val (today, thisHour) = steps
            val now = java.time.ZonedDateTime.now()
            val day = now.toLocalDate().toEpochDay()
            val silenced = currentSilence() != null
            val history = HealthSettings.history(this@CalendarRelayService)

            // Recorded only once something has actually been handed over.
            //
            // The ordering the handover defends -- record before the result
            // comes back -- still holds, and for its original reason: a pulse
            // that is sent and fails to verify may still have been felt, and
            // retrying that every quarter hour for the rest of the day would
            // be worse than missing it. What has changed is that a pulse
            // which was never *sent* no longer counts. Those are different
            // failures and only the first is worth giving up on.
            // The goal is asked only while the health half is on. Its
            // switch lives on the Steps screen, which goes with the tab,
            // so a `buzzOnGoal` left true by somebody who then turned the
            // tab off is a setting with nowhere to be turned back off.
            if (goalWanted(settings) &&
                shouldBuzzForGoal(settings, history, today, day, silenced)
            ) {
                if (buzzForSteps("goal")) {
                    HealthSettings.recordGoalFired(this@CalendarRelayService, day)
                }
                return@launch
            }
            if (shouldNudgeForHour(settings, history, thisHour, day, now.hour, now.minute, silenced)) {
                // Delivered anywhere is delivered. The phone copy is enough
                // on its own, which is the whole point of it.
                if (settings.nudgeOnPhone) notifyNudge(thisHour, settings.hourlyGoal)
                val buzzed = buzzForSteps("nudge")
                if (settings.nudgeOnPhone || buzzed) {
                    HealthSettings.recordNudgeFired(this@CalendarRelayService, day, now.hour)
                }
            }
        }
    }

    /**
     * One pulse, in the shape the person already chose for everything else,
     * and recorded like every other buzz.
     *
     * The record is not bookkeeping. A step goal fires unprompted while the
     * phone is in a pocket, so "did it actually buzz?" has no other answer:
     * Recent is the only place that can say it reached the band, and the
     * only place a failure can be seen at all.
     */
    /**
     * The hourly nudge, on the phone as well as the wrist.
     *
     * Its own channel, so Android's own per-channel controls are what change
     * its sound and importance rather than settings this app would have to
     * build and keep. Low importance to begin with: the band is already
     * buzzing, and an hourly chime is the fastest way to have the whole
     * feature switched off. Anybody who wants it louder can say so where
     * every other app's notifications are configured.
     *
     * One id, so each hour replaces the last rather than stacking twelve of
     * them by evening.
     */
    private fun notifyNudge(stepsThisHour: Long, goal: Int) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(NUDGE_CHANNEL, "Hourly nudge", NotificationManager.IMPORTANCE_LOW)
            )
            // Request code 6, not 1: Intent.filterEquals ignores extras, so
            // sharing the ongoing notification's code made these one
            // PendingIntent, and FLAG_UPDATE_CURRENT then rewrote whichever
            // was posted first. The nudge sent the relay's own notification
            // to the Steps screen, and the next rule change sent the nudge
            // nowhere.
            val open = PendingIntent.getActivity(
                this,
                6,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(ROUTE_EXTRA, "healthsteps"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val short = (goal - stepsThisHour).coerceAtLeast(0)
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, NUDGE_CHANNEL)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(this)
            }
            manager.notify(
                903,
                builder
                    .setSmallIcon(R.drawable.ic_cuff_notification)
                    .setContentTitle("Time to move")
                    .setContentText(
                        // The figure, not an exhortation. What is missing is
                        // the only part that is actually about you.
                        "$short more steps for this hour's $goal",
                    )
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(false)
                    .build(),
            )
        }
    }

    /**
     * Returns whether the pulse was handed to the band, which is what the
     * caller records. False means nothing was sent and the hour or the day
     * is still owed one; it does not mean the wrist felt nothing, because
     * the result of a sent pulse arrives later and is its own question.
     */
    private fun buzzForSteps(reason: String): Boolean {
        if (!canPulseBand(ready, stopping, band != null, heartStreaming)) return false
        // Each of the two has its own pulse, so a goal crossed and an hour
        // missed can be told apart without looking at the phone.
        val config = RelayRuleStore.read(this)
        val preset = if (reason == "goal") config.stepGoalPreset else config.hourlyNudgePreset
        // Silence is a choice about how this is delivered, not a failure to
        // deliver it, so it counts as done. Otherwise switching the preset
        // to a real pulse at two in the afternoon would buzz for a goal
        // crossed at ten in the morning.
        if (preset.silent) return true
        val at = System.currentTimeMillis()
        fun record(stage: String, code: String?) = runCatching {
            RelayHistory.add(
                this,
                RelayActivity(
                    commandId = "$reason-$at",
                    sourcePackage = null,
                    stage = stage,
                    atMillis = System.currentTimeMillis(),
                    latencyMillis = System.currentTimeMillis() - at,
                    errorCode = code,
                    preset = preset.id,
                ),
            )
            // This path writes its own entry rather than going through the
            // service's record(), so it has to say so itself: a goal buzz
            // is still the last buzz a widget should show.
            if (stage == "verified") WidgetRefresh.poke(this)
        }
        begin()
        onBand { link ->
            link.tap(preset.intensity, object : BandResult {
                override fun success(result: Any?) {
                    record("verified", null)
                    bandDone()
                    end()
                }

                override fun error(code: String, message: String?, details: Any?) {
                    record("failed", code)
                    bandDone()
                    end()
                }
            })
        }
        return true
    }

    /**
     * Whether the service is actually holding the band's one link.
     *
     * The service object outlives its link -- it exists while starting, and
     * ColorOS brings it back after a force stop -- so "the service is alive"
     * and "the band is taken" are different questions. Only the second one
     * stops the UI opening a link of its own.
     */
    internal val holdsBand: Boolean get() = band != null

    internal fun startHeartRate(sink: BandSink): Boolean {
        if (!ready || stopping || pending != 0 || band == null || heartStreaming) return false
        heartStreaming = true
        begin()
        val held = object : BandSink {
            override fun success(event: Any?) = sink.success(event)
            override fun error(code: String, message: String?, details: Any?) {
                releaseHeartRate()
                sink.error(code, message, details)
            }
            override fun endOfStream() {
                releaseHeartRate()
                sink.endOfStream()
            }
        }
        onBand { link -> link.readHeartRate(held) }
        return true
    }

    internal fun stopHeartRate() {
        if (!heartStreaming) return
        band?.stopHeartRate()
        releaseHeartRate()
    }

    /** Frees the queue and the pending count exactly once. */
    private fun releaseHeartRate() {
        if (!heartStreaming) return
        heartStreaming = false
        bandDone()
        end()
    }

    internal fun readDeviceStatus(reply: BandResult, operation: (BandLink, BandResult) -> Unit) {
        if (!ready || stopping || pending != 0 || band == null) {
            reply.error("BUSY", "The relay is delivering a notification. Try again shortly.", null)
            return
        }
        begin()
        onBand { link ->
            operation(link, object : BandResult {
                override fun success(result: Any?) { bandDone(); end(); reply.success(result) }
                override fun error(code: String, message: String?, details: Any?) {
                    bandDone(); end(); reply.error(code, message, details)
                }
            })
        }
    }

    private fun dispatch(id: String, sbnPackage: String?, postedAt: Long, expiresAt: Long,
                         match: RelayMatch, callerId: String? = null,
                         kind: RelayAlertKind = RelayAlertKind.MESSAGE) {
        val preset = match.preset
        begin()
        refreshDeadline()
        inFlight[id] = sbnPackage to postedAt
        record(id, sbnPackage, postedAt, "queued", match = match, callerId = callerId, kind = kind)
        fun finished(result: RelayWorkerResult) {
            previewReplies.remove(id)?.invoke(result)
            choices.remove(id)
            inFlight.remove(id)
            record(id, sbnPackage, postedAt, result.stage, result.errorCode, match, callerId, kind)
            saveOutcome("Haptic ${result.stage}${result.errorCode?.let { ": $it" } ?: ""}.")
            val now = System.currentTimeMillis()
            coalescer.onFinished(now)?.let { scheduleFollowUp(it, now) }
            end()
        }
        val silence = RelayRuleStore.read(this).silence
            .reason(System.currentTimeMillis(), isCall = kind == RelayAlertKind.CALL)
        if (silence != null || System.currentTimeMillis() >= expiresAt) {
            finished(RelayWorkerResult(silence ?: "expired", null)); return
        }
        // More than one pulse is a short bounded ring: both pulses share one
        // session, so they land close enough together to read as a pattern,
        // and both play at the strength the rule asked for.
        val pulses = preset.pattern.pulses ?: 1
        val sent = onBand { link ->
            if (pulses > 1) {
                // A pattern is a short bounded ring: every pulse shares one
                // session, so they land close enough together to read as one
                // shape, and all play at the strength the rule asked for.
                link.ring(
                    intensity = preset.intensity,
                    gapMillis = PATTERN_GAP_MILLIS.toLong(),
                    maxMillis = 20_000L,
                    pulses = pulses,
                    result = object : BandResult {
                        override fun success(result: Any?) {
                            bandDone()
                            val played = ((result as? Map<*, *>)?.get("pulses") as? Int) ?: 0
                            finished(
                                if (played >= pulses) RelayWorkerResult("verified", null)
                                else RelayWorkerResult("failed", "PARTIAL")
                            )
                        }
                        override fun error(code: String, message: String?, details: Any?) {
                            bandDone()
                            finished(RelayWorkerResult("failed", code.takeIf { it.matches(ERROR_CODE) } ?: "WORKER_ERROR"))
                        }
                    },
                )
            } else {
                link.tap(preset.intensity, bandReply { finished(it) })
            }
        }
        if (!sent) finished(RelayWorkerResult("failed", "WORKER_UNAVAILABLE"))
    }

    /** Running commands and a waiting follow-up each hold the wake lock and watchdog. */
    private fun begin() {
        if (pending++ == 0) refreshDeadline()
    }

    /** The watchdog bounds one hung command, so a long burst re-arms it per dispatch. */
    private fun refreshDeadline() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 240_000)
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "air_bridge:Relay")
            .apply { setReferenceCounted(false) }
        wakeLock = lock
        lock.acquire(240_000)
    }

    private fun end() {
        if (--pending == 0) {
            handler.removeCallbacks(watchdog)
            releaseWakeLock()
            if (stopping) stopSelf()
        }
    }

    private fun nextId() = "relay-${System.currentTimeMillis()}-${++sequence}"

    private fun record(id: String, sbnPackage: String?, postedAt: Long, stage: String, code: String? = null,
                       match: RelayMatch? = null, callerId: String? = null,
                       kind: RelayAlertKind = RelayAlertKind.MESSAGE) {
        val now = System.currentTimeMillis()
        RelayHistory.add(this, RelayActivity(id, sbnPackage, stage, now, maxOf(0, now - postedAt), code,
            match?.preset?.id, callerId, kind == RelayAlertKind.CALL))
        // The band widgets draw their last buzz out of this history, and
        // nothing else was telling them it had moved. Only the stage the
        // band acknowledged: the widgets count verified buzzes, so a queued
        // or failed attempt changes nothing they show.
        if (stage == "verified") WidgetRefresh.poke(this)
    }

    fun requestStop(reason: String) {
        stopping = true
        stopRing("cancelled")
        saveOutcome(reason)
        // Nulled as well as closed. holdsBand is `band != null`, so a closed
        // link left in the field says the relay still holds the band's one
        // session: the Device screen's reads answer BUSY, Band refuses to
        // open a link of its own, and Start is swallowed -- for the whole
        // drain, which is up to the watchdog's four minutes when an
        // in-flight command's callback was cut off by the close.
        runCatching { band?.close() }
        band = null
        handler.removeCallbacks(silenceTick)
        // Here as well as in onDestroy, and alone among the timers, because
        // it is the only one that outlives this process: a handler callback
        // dies with the service whether anybody remembers it or not, and an
        // alarm sits in the system waiting to start us again.
        cancelStepAlarm()
        cancelWaiting("cancelled")
        if (pending == 0) stopSelf()
    }

    /**
     * Swiping the app out of recents does not stop a foreground service on
     * stock Android, but several OEM builds kill the process anyway. Asking to
     * be started again costs nothing when we were not killed, and is the
     * difference between working and not when we were.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; relay wanted=${isWanted(this)} stopping=$stopping")
        if (!stopping && isWanted(this)) {
            runCatching {
                val again = Intent(this, CalendarRelayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(again) else startService(again)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(stepCheck)
        cancelStepAlarm()
        healthScope.cancel()
        ready = false
        stopping = true
        val endedAt = System.currentTimeMillis()
        inFlight.forEach { (id, source) ->
            RelayHistory.add(this, RelayActivity(id, source.first, "interrupted", endedAt,
                maxOf(0, endedAt - source.second), "SERVICE_STOPPED"))
        }
        inFlight.clear()
        choices.clear()
        val replies = previewReplies.values.toList()
        previewReplies.clear()
        replies.forEach { it(RelayWorkerResult("failed", "SERVICE_STOPPED")) }
        ring = null
        handler.removeCallbacks(ringWatch)
        // Guarded like the rest of this method: a receiver that never
        // registered throws here, and taking onDestroy down with it loses
        // every clean-up below.
        runCatching { unregisterReceiver(clockReceiver) }
        runCatching { unregisterReceiver(userPresentReceiver) }
        handler.removeCallbacks(silenceTick)
        handler.removeCallbacks(listenerWait)
        if (active === this) active = null
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(followUp)
        bandQueue.clear()
        bandBusy = false
        band?.close()
        band = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun saveOutcome(value: String) {
        getSharedPreferences("calendar_relay", MODE_PRIVATE).edit().putString("outcome", value).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /**
         * How often the step goals are checked. Health Connect batches step
         * writes about once a minute, so anything tighter than this spends
         * reads on a figure that has not moved.
         */
        private const val STEP_CHECK_MILLIS = 15 * 60 * 1000L

        /** The soonest two immediate checks may be run back to back. */
        private const val STEP_CHECK_FLOOR_MILLIS = 60 * 1000L

        private var starting = false
            set(value) { field = value; publishRunning() }
        private var cancelStart = false
        val isRunning: Boolean get() = active != null || starting

        private val _running = MutableStateFlow(false)

        /**
         * Whether the relay is up, as something a screen can watch. A snapshot
         * taken when the screen was built goes stale the moment the service
         * stops itself -- which it does whenever it cannot run -- and leaves
         * the screen claiming to relay something it is not.
         */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private fun publishRunning() { _running.value = active != null || starting }

        private const val PREFS = "calendar_relay"
        private const val LISTENER_WAIT_MILLIS = 90_000L
        private const val LISTENER_POLL_MILLIS = 1_500L

        /**
         * Whether the user wants the relay running. Android restarts a sticky
         * service by itself, but a reboot needs this to tell "the user stopped
         * it" apart from "something killed it".
         */
        fun setWanted(context: Context, wanted: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("wanted", wanted).apply()
        }

        fun isWanted(context: Context): Boolean = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("wanted", false)

        fun start(context: Context) {
            if (active != null || starting) return
            setWanted(context, true)
            starting = true
            cancelStart = false
            try {
                val intent = Intent(context, CalendarRelayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (error: Exception) {
                starting = false
                throw error
            }
        }

        /**
         * [context] because an explicit stop must be recorded whether or not
         * the service has reached [onStartCommand] yet. Stopping inside the
         * start window used to set cancelStart and nothing else, leaving
         * wanted true, and the next reboot started a relay the user had
         * already turned off.
         */
        fun stop(context: Context) {
            if (starting) cancelStart = true
            setWanted(context, false)
            active?.requestStop("Relay stopped.")
        }

        private const val TAG = "AirBridge"
        private const val RING_REARM_MILLIS = 15_000L
        private const val RING_WATCH_MILLIS = 1_000L
        /** Spacing inside a fixed pattern, close enough to feel like one alert. */
        private const val PATTERN_GAP_MILLIS = 300
        /** Codes we pass through to the log; anything else becomes WORKER_ERROR. */
        private val ERROR_CODE = Regex("[A-Z0-9_]{1,64}")
        private const val CHANNEL = "calendar_relay"
        private const val NUDGE_CHANNEL = "hourly_nudge"
        private const val STOP = "calendar_relay.STOP"
        private const val SILENCE = "calendar_relay.SILENCE"
        private const val PAUSE = "calendar_relay.PAUSE"
        private const val RESUME = "calendar_relay.RESUME"
        /**
         * Only a label: [StepCheckReceiver] is named explicitly and has no
         * intent filter. It is here so that the alarm's intent reads like
         * the four above it in a log or a dumpsys.
         */
        private const val STEP_CHECK = "calendar_relay.STEP_CHECK"
        var active: CalendarRelayService? = null
            private set(value) { field = value; publishRunning() }

        fun hasAccess(context: Context): Boolean {
            val expected = ComponentName(context, CalendarNotificationListener::class.java)
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
        }

        /**
         * Whether Android has been told to leave this app alone. Without it,
         * Doze and OEM battery management can stop the relay whenever the
         * screen has been off for a while, whatever the service asks for.
         */
        fun batteryExempt(context: Context): Boolean = runCatching {
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

    }
}

/**
 * How old an alert may be, counted from the moment the notification was
 * posted, and still be worth sending to the wrist.
 *
 * Named rather than repeated, because it is one window measured in three
 * places -- when a burst is held, when the follow-up decides what is still
 * worth sending, and once more inside [CalendarRelayService.dispatch] -- and
 * because [followUpAt] has to reason about it against a number chosen for an
 * entirely different reason.
 *
 * **Ninety seconds, and the figure is argued rather than inherited.** It was
 * thirty, which arrived whole with the Flutter port carrying no comment, no
 * test and no mention in the handover -- and thirty is shorter than one slow
 * band operation, which made it the sharp edge in the arithmetic above.
 * `AirLink` waits out forty-five seconds before it calls an operation timed
 * out, and the coalescer adds its fifteen on top, so sixty is the worst a
 * burst can honestly be made to wait by a band that is merely struggling.
 * Ninety clears that with room, and nothing the band can do on its own now
 * outlives the window.
 *
 * The ceiling is not technical but human: this is how late a buzz may be and
 * still be worth feeling. Past a minute and a half the wrist is answering a
 * message you have already read on the phone in your hand, which is noise
 * rather than news. If it ever wants raising again, that is the argument to
 * have, not the timeout arithmetic.
 *
 * Not to be confused with [CalendarAlertFilter]'s own staleness gate, which
 * is a different window that happened to share this number. See it.
 */
internal const val ALERT_LIFETIME_MILLIS = 90_000L

/**
 * How much of that window the follow-up keeps in hand.
 *
 * `dispatch` tests the same deadline again when it is handed the winner, on
 * a clock read a looper turn or two later, so a follow-up aimed at the exact
 * moment of expiry would hand over an alert that is then thrown away by the
 * line it was woken for.
 */
internal const val FOLLOW_UP_MARGIN_MILLIS = 2_000L

/**
 * When a burst that is waiting out the cooldown must actually be sent.
 *
 * Two numbers decide this and they were chosen for different reasons.
 * [RelayCoalescer] measures its cooldown from the moment the last command
 * *finished*, so that Google Health gets a quiet window to resync the
 * tracker channel in; an alert is thrown away once it is
 * [ALERT_LIFETIME_MILLIS] old, counted from the moment it was posted. While
 * the band answers quickly the two never meet -- a two-second tap and
 * fifteen seconds of cooldown leave half the window spare.
 *
 * A slow band is what puts them in each other's way, and a locked phone is
 * where a slow band comes from: the Air holds one secure session, Google
 * Health takes it whenever it syncs, and `AirLink` waits out a full
 * forty-five seconds before it gives up on an operation. Every alert that
 * arrived during such a write was already past its lifetime by the time the
 * cooldown ended, so the follow-up found nothing fresh, recorded the lot as
 * `expired` and sent nothing at all. The burst came out as one late buzz for
 * the first message and silence for every one after it -- which is exactly
 * what "some notifications are missed when the phone has been locked a
 * while" feels like from the wrist.
 *
 * The cooldown is a courtesy to somebody else's sync; the alert is the thing
 * this app exists for. So when the two disagree the alert wins and the
 * follow-up goes early. A deadline already in the past is left in the past
 * rather than clamped: the caller turns it into a delay of zero, and sending
 * at once is what lets the fresher alerts behind the stale one through.
 *
 * This reaches exactly as far as the cooldown and no further, which is worth
 * being plain about. It saves a burst held behind a write that took anything
 * up to [ALERT_LIFETIME_MILLIS] -- the fifteen-second reads the handover
 * describes, where the cooldown is the straw that breaks it. It cannot save
 * one held behind a write that took longer than the lifetime itself, because
 * by the time that write returns the alert is already older than this app
 * will buzz for. That case is the lifetime's own to answer and is left to a
 * decision about the lifetime, not taken here.
 *
 * [oldestPostedAt] null means nothing is waiting, and the cooldown stands.
 */
internal fun followUpAt(
    cooldownEndsAt: Long,
    oldestPostedAt: Long?,
    lifetimeMillis: Long = ALERT_LIFETIME_MILLIS,
    marginMillis: Long = FOLLOW_UP_MARGIN_MILLIS,
): Long = if (oldestPostedAt == null) cooldownEndsAt
    else minOf(cooldownEndsAt, oldestPostedAt + lifetimeMillis - marginMillis)

/**
 * Whether `setExactAndAllowWhileIdle` may be called at all.
 *
 * Three facts, kept apart from the system services that answer them so the
 * rule can be read -- and tested -- without a device:
 *
 * - below API 31 every app could set an exact alarm and there was no
 *   permission to ask about, so the question does not arise;
 * - from 31 the platform answers for itself through
 *   `AlarmManager.canScheduleExactAlarms()`, which is already true for an
 *   app the user has exempted from battery optimisation, because that
 *   exemption carries the exact-alarm right with it;
 * - [batteryExempt] is asked anyway, as a second yes. It costs nothing, and
 *   the two have been known to disagree on OEM builds. A wrong yes here is
 *   a caught SecurityException and a fall back to the handler; a wrong no
 *   is a step check that drifts all night.
 */
internal fun canArmExactAlarm(
    sdkInt: Int,
    systemAllowsExact: Boolean,
    batteryExempt: Boolean,
): Boolean = sdkInt < 31 || systemAllowsExact || batteryExempt

/**
 * Wakes the step check on the wall clock.
 *
 * A broadcast rather than a service start, for two reasons. The relay is
 * already running whenever this alarm exists -- [CalendarRelayService]
 * cancels it on the way down -- so there is nothing to start, only
 * something to tell. And `onStartCommand` runs `enterForeground()` before
 * it looks at the action, which clears the hourly nudge's own notification
 * on the way past; a fifteen-minute wake-up has no business dismissing the
 * notification it just posted.
 *
 * Nothing to do when the relay is gone. The alarm is one-shot, so a
 * delivery that finds no service is simply the last one: that happens when
 * the process was killed outright rather than destroyed, which is the one
 * path where the cancel never ran.
 */
class StepCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CalendarRelayService.active?.onStepAlarm()
    }
}
