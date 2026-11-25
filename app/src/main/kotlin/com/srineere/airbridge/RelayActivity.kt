package com.srineere.airbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strictly metadata: no notification keys, text, sender names, or BLE
 * identifiers. [preset] is the chosen vibration, as an id.
 *
 * [callerId] is the same one-way identity the rules are keyed by, never a name
 * or a number. A person's name is looked up from it when the screen is drawn,
 * so nothing here ever holds who they are.
 *
 * It stays out of [toMap], which is what leaves the app: a hash is not a name,
 * but it is still per-person, and a report carrying it would be a report whose
 * rows can be grouped by who was calling. [toStored] is the on-device file.
 */
internal data class RelayActivity(
    val commandId: String,
    val sourcePackage: String?,
    val stage: String,
    val atMillis: Long,
    val latencyMillis: Long? = null,
    val errorCode: String? = null,
    val preset: String? = null,
    val callerId: String? = null,
    /**
     * Whether a call raised this, which the vibration cannot answer.
     *
     * It was being inferred from [preset] starting with "ring", and that is
     * two mistakes in one: a caller with their own pulse rings as a double
     * buzz and read as a message, while an app whose messages are set to
     * Ring read as a call. What something was and how it felt are different
     * facts, so the one that cannot be derived is stored.
     */
    val wasCall: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "commandId" to commandId, "sourcePackage" to sourcePackage,
        "stage" to stage, "atMillis" to atMillis,
        "latencyMillis" to latencyMillis, "errorCode" to errorCode,
        "preset" to preset,
    )

    /**
     * What the on-device history file holds, which is two fields more.
     *
     * [wasCall] stays out of [toMap] with [callerId]. Nothing outside needs
     * it, and a row saying "a call, at this minute" is a thinner fact than
     * a name but still a fact about who you talk to and when.
     */
    fun toStored(): Map<String, Any?> =
        toMap() + ("callerId" to callerId) + ("wasCall" to wasCall)
}

internal class RelayActivityBuffer(initial: List<RelayActivity> = emptyList()) {
    private val entries = initial.takeLast(100).toMutableList()
    fun add(entry: RelayActivity) {
        entries.add(entry)
        while (entries.size > 100) entries.removeAt(0)
    }
    fun snapshot(): List<RelayActivity> = entries.toList()
}

internal object RelayHistory {
    private const val PREFS = "calendar_relay"
    private const val KEY = "history"

    /**
     * The history once it has been read.
     *
     * Adding an entry used to mean parsing the hundred already stored and
     * encoding all of them again. One relayed call records four times and a
     * burst of messages more, each of them on the service's main looper while
     * it was trying to buzz the band.
     *
     * The file is still written on every add -- a history that loses its last
     * entry is worthless exactly when someone wants it -- but only the
     * encoding is paid for now, and SharedPreferences already puts the disk
     * write on a thread of its own.
     *
     * Safe to hold because the relay and the screens share one process; the
     * manifest declares no other. It does mean a file changed from outside
     * is not noticed until the process restarts.
     */
    private var held: RelayActivityBuffer? = null

    @Synchronized
    fun read(context: Context): List<RelayActivity> = loaded(context).snapshot()

    @Synchronized
    fun add(context: Context, entry: RelayActivity) {
        val history = loaded(context)
        history.add(entry)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(history.snapshot()).toString()).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        held = RelayActivityBuffer()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun loaded(context: Context): RelayActivityBuffer = held ?: RelayActivityBuffer(
        // A file we cannot read is one we start again from, rather than one
        // that stops the relay recording anything.
        runCatching {
            decode(JSONArray(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
            ))
        }.getOrDefault(emptyList())
    ).also { held = it }

    /** The on-device shape: [RelayActivity.toStored] for each entry. */
    internal fun encode(entries: List<RelayActivity>): JSONArray {
        val array = JSONArray()
        entries.forEach { event ->
            val objectValue = JSONObject()
            event.toStored().forEach { (key, value) -> objectValue.put(key, value ?: JSONObject.NULL) }
            array.put(objectValue)
        }
        return array
    }

    internal fun decode(array: JSONArray): List<RelayActivity> =
        (maxOf(0, array.length() - 100) until array.length()).map { i ->
            val value = array.getJSONObject(i)
            RelayActivity(value.getString("commandId"),
                if (value.isNull("sourcePackage")) null else value.getString("sourcePackage"),
                value.getString("stage"), value.getLong("atMillis"),
                if (value.isNull("latencyMillis")) null else value.getLong("latencyMillis"),
                if (value.isNull("errorCode")) null else value.getString("errorCode"),
                // Entries saved before these fields existed have neither key.
                if (value.isNull("preset")) null else value.getString("preset"),
                if (value.isNull("callerId")) null else value.getString("callerId"),
                // An entry written before calls were marked is not a call
                // as far as anything can now tell.
                value.optBoolean("wasCall", false))
        }
}

/**
 * How a buzz that never reached the band is worded in the activity list.
 *
 * The word first, because it is the one thing every one of these rows has in
 * common and the one thing the list never said: a failed entry showed only
 * its code, humanised, so "Handshake timeout" sat in the same column as "One
 * pulse · strong" and read like a kind of vibration.
 *
 * Then why, because the codes do not mean remotely the same thing to the
 * person reading them. A band that was busy is worth trying again in a
 * minute; a band nobody has paired is not; a pattern that played half way
 * did reach the wrist. Only codes traced to the line that raises them are
 * given a sentence -- everything else keeps the humanised code it has always
 * shown, because inventing a meaning for a code nobody has read is how a
 * list of facts starts lying.
 *
 * [stage] is the entry's own stage, for the one failure that carries no code
 * at all. Call it for the stages that are not a success; "verified",
 * "ring_started" and "ring_stopped" have their own words.
 */
internal fun describeRelayFailure(stage: String, errorCode: String?): String {
    val why = when (stage) {
        // Recorded with no code, because nothing was ever sent: the burst sat
        // out its cooldown and was older than the relay will buzz for by the
        // time the band was free. See followUpAt.
        "expired" -> "it was already too old to send"
        else -> errorCode?.let { code ->
            FAILURE_NOTES[code] ?: code.lowercase().replace('_', ' ')
        }
    }
    return if (why == null) "Failed" else "Failed · $why"
}

/**
 * The failure codes that have been read back to where they are raised.
 *
 * The first five are this app's own, from [CalendarRelayService] and
 * [RelayWorkerResult]; the rest are `AirLink`'s, and each sentence says
 * what the line that throws it says.
 *
 * **Written for whoever is holding the phone, not for whoever wrote the
 * code.** These rows are read by somebody wondering why their wrist
 * stayed still, so none of them uses a word that only means something
 * inside this repository: no "relay", no "secure session", no "GATT", no
 * "Air" where "band" will do. The codes stay, in the log and in the
 * export: they are how a report gets traced back here.
 *
 * **One short phrase each, and no second clause.** This is a list to be
 * scanned down, not read, and a sentence that wrapped onto a third line
 * pushed the row below it off the screen. Advice about what to do -- "it
 * may be out of range" -- went with the wrapping: it was a guess
 * appended to a fact, it was the same guess on half these lines, and it
 * cost more room than it was worth. The fact is what distinguishes one
 * row from the next, so the fact is what stayed.
 *
 * Every code reachable from a buzz is covered, which is the point --
 * anything missing falls through to the humanised code, and a row reading
 * "Failed · gatt connect" is how this list looked before.
 */
internal val FAILURE_NOTES = mapOf(
    "WORKER_UNAVAILABLE" to "Cuff wasn't connected to the band",
    "SERVICE_STOPPED" to "Cuff stopped before it could send",
    // The band did answer, and the wrist did feel something -- just not
    // all of it. Worth distinguishing from every other line here.
    "PARTIAL" to "only part of the buzz played",
    "WORKER_ERROR" to "the band refused it and didn't say why",
    "WORKER_PROTOCOL" to "the band's answer couldn't be read",
    "TIMEOUT" to "the band didn't answer in time",
    // Not "couldn't connect", which is what GATT_CONNECT says: these are
    // two different failures and the only thing separating them on
    // screen is which name is in the line.
    "HANDSHAKE_TIMEOUT" to "Google Health may have been using the band",
    "BUSY" to "the band was busy with something else",
    "AIR_NOT_FOUND" to "no band is set up in Google Health",
    "BLUETOOTH_PERMISSION" to "Cuff isn't allowed to use nearby devices",
    "SCAN_FAILED" to "Android couldn't search for the band",
    "GATT_CONNECT" to "couldn't connect over Bluetooth",
    "GATT_DISCONNECTED" to "the band disconnected part-way through",
    "GATT_DISCOVERY" to "the band connected but didn't answer properly",
    "HAPTIC_TAP_FAILED" to "the band wouldn't play the buzz",
    "HAPTIC_PULSE_FAILED" to "the band wouldn't play the buzz",
    "HAPTIC_FAILED" to "the band wouldn't play the buzz",
    "RING_FAILED" to "the band wouldn't ring",
    // The Helio Strap's two. Everything else it can fail at is one of the
    // codes above happening to a different radio, which is why it reports
    // those rather than inventing a second vocabulary for one fault.
    "STRAP_REFUSED_KEY" to "the strap wouldn't accept its pairing key",
    "NOT_ON_THIS_BAND" to "this band can't do that",
)

internal data class RelayWorkerResult(val stage: String, val errorCode: String?) {
    companion object {
        fun decode(result: Any?): RelayWorkerResult {
            val map = result as? Map<*, *> ?: return RelayWorkerResult("failed", "WORKER_PROTOCOL")
            val stage = when (map["status"]) {
                "completed" -> "verified"
                "expired" -> "expired"
                "queueFull" -> "queue_full"
                "closed" -> "cancelled"
                "duplicate" -> "duplicate"
                "failed" -> "failed"
                else -> return RelayWorkerResult("failed", "WORKER_PROTOCOL")
            }
            val code = (map["errorCode"] as? String)?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
            return RelayWorkerResult(stage, code)
        }
    }
}
