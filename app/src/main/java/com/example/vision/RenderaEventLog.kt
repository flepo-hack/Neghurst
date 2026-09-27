package com.example.vision

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records what the engine actually did, so outcomes can be learned from rather
 * than guessed at.
 *
 * ## What is recorded, and why
 *
 * Every previous change to the detection tuning was argued from reasoning, and
 * the reasoning was wrong often enough to be useless. What is missing is the one
 * thing that settles it: for each dodge, what the engine saw, where it chose to
 * go, whether the gesture was actually delivered, and **whether it worked** -
 * whether the threat it was answering stopped being on a collision course.
 *
 * `recordDecision` therefore opens an entry and `recordOutcome` closes it once the
 * verdict is known, and the summary at the end reports dispatched-versus-worked
 * rather than dispatched alone, which is the only number that means anything.
 *
 * ## Format
 *
 * Append-only JSONL, one object per line, in the app's external files directory.
 * JSONL because a truncated final line is still a valid record, so a log pulled
 * off a device mid-write is never unparseable.
 *
 * ## What this is NOT
 *
 * It does not upload anything. Putting a GitHub token in an APK makes it public
 * the moment the APK is published, so the export is a file the user chooses to
 * share, and the repository side is a workflow that reads it.
 */
class RenderaEventLog(appContext: Context) {

    companion object {
        private const val TAG = "RenderaLog"
        const val FILE_NAME = "rendera-events.jsonl"
        private const val MAX_BYTES = 4L * 1024L * 1024L
        private const val MAX_ERROR_KINDS = 60

        /** A dodge is judged after this many frames, or this long. */
        private const val OUTCOME_WINDOW_MS = 450L

        fun stamp(): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date())
        }
    }

    private val appContext = appContext.applicationContext
    private val lock = Any()

    /** Distinct error messages seen, with how often. */
    private val errorCounts = LinkedHashMap<String, Int>()

    // Counters, reported in the summary.
    private val threatsSeen = AtomicInteger(0)
    private val dodgesPlanned = AtomicInteger(0)
    private val dodgesDispatched = AtomicInteger(0)
    private val dodgesRefused = AtomicInteger(0)
    private val dodgesWorked = AtomicInteger(0)
    private val dodgesFailed = AtomicInteger(0)
    private val autoDetectRuns = AtomicInteger(0)
    private val autoDetectHits = AtomicInteger(0)

    /** Open decisions, keyed by the engine's track id. */
    private val openDecisions = LinkedHashMap<Int, OpenDecision>()

    private data class OpenDecision(
        val entry: JSONObject,
        val ttiMs: Long,
        val trackId: Int,
        val openedAt: Long
    )

    val file: File get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, FILE_NAME)

    // -----------------------------------------------------------------------
    // Session
    // -----------------------------------------------------------------------

    fun beginSession(@Suppress("UNUSED_PARAMETER") extra: Map<String, Any?> = emptyMap()) {
        val o = JSONObject()
        o.put("type", "session")
        o.put("t", stamp())
        o.put("build", extra["build"] ?: "unknown")
        o.put("device", extra["device"] ?: "unknown")
        o.put("android", android.os.Build.VERSION.SDK_INT)
        o.put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(","))
        extra.forEach { (k, v) -> if (k != "build" && k != "device") o.put(k, v ?: JSONObject.NULL) }
        write(o)
        Log.i(TAG, "session started, log at ${file.absolutePath}")
    }

    // -----------------------------------------------------------------------
    // Errors
    // -----------------------------------------------------------------------

    /**
     * Records an error or warning. Repeated identical messages are collapsed to a
     * count, so a per-frame failure does not flood the file.
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val key = "$tag|$message"
        val count: Int
        synchronized(lock) {
            count = (errorCounts[key] ?: 0) + 1
            errorCounts[key] = count
            // Bounded, so a new failure cannot push every older one out.
            while (errorCounts.size > MAX_ERROR_KINDS) {
                val it = errorCounts.keys.iterator()
                it.next()
                it.remove()
            }
        }
        val o = JSONObject()
        o.put("type", "error")
        o.put("t", stamp())
        o.put("tag", tag)
        o.put("msg", message)
        o.put("count", count)
        throwable?.let {
            o.put("ex", it.javaClass.name)
            o.put("exmsg", it.message ?: "")
        }
        write(o)
    }

    // -----------------------------------------------------------------------
    // Calibration
    // -----------------------------------------------------------------------

    fun calibration(
        source: String,
        ok: Boolean,
        playerX: Float,
        playerY: Float,
        joyX: Float,
        joyY: Float,
        screenW: Int,
        screenH: Int,
        reason: String = ""
    ) {
        val o = JSONObject()
        o.put("type", "calibration")
        o.put("t", stamp())
        o.put("source", source)
        o.put("ok", ok)
        o.put("playerX", playerX.toDouble())
        o.put("playerY", playerY.toDouble())
        o.put("joyX", joyX.toDouble())
        o.put("joyY", joyY.toDouble())
        o.put("screenW", screenW)
        o.put("screenH", screenH)
        o.put("reason", reason)
        if (source == "auto") {
            autoDetectRuns.incrementAndGet()
            if (ok) autoDetectHits.incrementAndGet()
        }
        write(o)
    }

    // -----------------------------------------------------------------------
    // Threats and decisions
    // -----------------------------------------------------------------------

    /** A threat the engine is currently tracking. */
    fun threat(trackId: Int, x: Float, y: Float, vx: Float, vy: Float, ttiMs: Long, severity: String) {
        threatsSeen.incrementAndGet()
        val o = JSONObject()
        o.put("type", "threat")
        o.put("t", stamp())
        o.put("track", trackId)
        o.put("x", x.toDouble())
        o.put("y", y.toDouble())
        o.put("vx", vx.toDouble())
        o.put("vy", vy.toDouble())
        o.put("ttiMs", ttiMs)
        o.put("sev", severity)
        write(o)
    }

    /**
     * The engine has decided to dodge. Returns a decision id to pass to
     * [recordOutcome].
     */
    fun decide(
        trackId: Int,
        headingDeg: Float,
        dragPx: Float,
        holdMs: Long,
        requiredTravelPx: Float,
        expectedTravelPx: Float,
        sufficient: Boolean,
        cleared: Int,
        considered: Int,
        playerX: Float,
        playerY: Float,
        screenW: Int,
        screenH: Int
    ) {
        dodgesPlanned.incrementAndGet()
        val o = JSONObject()
        o.put("type", "decision")
        o.put("t", stamp())
        o.put("track", trackId)
        o.put("headingDeg", headingDeg.toDouble())
        o.put("dragPx", dragPx.toDouble())
        o.put("holdMs", holdMs)
        o.put("requiredPx", requiredTravelPx.toDouble())
        o.put("expectedPx", expectedTravelPx.toDouble())
        o.put("sufficient", sufficient)
        o.put("cleared", cleared)
        o.put("considered", considered)
        o.put("playerX", playerX.toDouble())
        o.put("playerY", playerY.toDouble())
        o.put("screenW", screenW)
        o.put("screenH", screenH)
        val at = System.currentTimeMillis()
        synchronized(lock) { openDecisions[trackId] = OpenDecision(o, 0L, trackId, at) }
    }

    /**
     * The gesture was handed to the system.
     *
     * A refusal is recorded distinctly from a dispatch, because a refusal means
     * the plan never happened and counting it as a dodge is exactly the kind of
     * quiet lie that makes a success number meaningless.
     */
    fun dispatched(trackId: Int, accepted: Boolean) {
        val entry: JSONObject
        synchronized(lock) {
            entry = openDecisions[trackId]?.entry ?: return
        }
        if (accepted) dodgesDispatched.incrementAndGet() else dodgesRefused.incrementAndGet()
        entry.put("dispatched", accepted)
        write(entry)
    }

    /** True when a decision for [trackId] is open and still awaiting a verdict. */
    fun hasOpenDecision(trackId: Int): Boolean = synchronized(lock) { openDecisions.containsKey(trackId) }

    /** How many decisions are awaiting a verdict, for the diagnostics line. */
    fun openDecisionCount(): Int = synchronized(lock) { openDecisions.size }

    /**
     * The verdict on a dodge.
     *
     * `worked` is the question that matters and the one nothing has ever
     * answered: after the dodge, did the threat stop being on a collision
     * course, or did it still arrive?
     */
    fun recordOutcome(trackId: Int, worked: Boolean, reason: String, newTtiMs: Long) {
        val open: OpenDecision?
        synchronized(lock) {
            open = openDecisions.remove(trackId)
        }
        val entry = open?.entry ?: JSONObject()
        if (worked) dodgesWorked.incrementAndGet() else dodgesFailed.incrementAndGet()
        entry.put("type", "outcome")
        entry.put("outcomeT", stamp())
        entry.put("worked", worked)
        entry.put("reason", reason)
        entry.put("newTtiMs", newTtiMs)
        write(entry)
    }

    /** Closes anything still open, so a session's log never has dangling plans. */
    fun flushOpen(reason: String) {
        val open: List<OpenDecision>
        synchronized(lock) {
            open = openDecisions.values.toList()
            openDecisions.clear()
        }
        for (d in open) {
            d.entry.put("type", "outcome")
            d.entry.put("outcomeT", stamp())
            d.entry.put("worked", false)
            d.entry.put("reason", reason)
            write(d.entry)
        }
    }

    // -----------------------------------------------------------------------
    // Periodic snapshot
    // -----------------------------------------------------------------------

    /**
     * A compact per-interval record, so a log shows the trend and not just the
     * events. `native` carries the detector's own view of the world.
     */
    fun sample(
        fps: Int, frames: Long, rejected: Long, analysed: Long, engineMs: Double,
        blobs: Int, projectiles: Int, ball: Int, bouncers: Int, enemies: Int,
        playerDetected: Boolean, playerFromAnchor: Boolean,
        motionDx: Float, motionDy: Float, motionQ: Float, suppressed: Boolean,
        anchors: Boolean
    ) {
        val o = JSONObject()
        o.put("type", "sample")
        o.put("t", stamp())
        o.put("fps", fps)
        o.put("frames", frames)
        o.put("rejected", rejected)
        o.put("analysed", analysed)
        o.put("engineMs", engineMs.toDouble())
        o.put("blobs", blobs)
        o.put("proj", projectiles)
        o.put("ball", ball)
        o.put("bnc", bouncers)
        o.put("foes", enemies)
        o.put("player", playerDetected)
        o.put("fromAnchor", playerFromAnchor)
        o.put("motionDx", motionDx.toDouble())
        o.put("motionDy", motionDy.toDouble())
        o.put("motionQ", motionQ.toDouble())
        o.put("suppBg", suppressed)
        o.put("anchors", anchors)
        write(o)
    }

    // -----------------------------------------------------------------------
    // Summary
    // -----------------------------------------------------------------------

    /** The numbers worth reading: planned, actually sent, and actually worked. */
    fun summary(): JSONObject {
        val o = JSONObject()
        o.put("type", "summary")
        o.put("t", stamp())
        o.put("threats", threatsSeen.get())
        o.put("planned", dodgesPlanned.get())
        o.put("dispatched", dodgesDispatched.get())
        o.put("refused", dodgesRefused.get())
        o.put("worked", dodgesWorked.get())
        o.put("failed", dodgesFailed.get())
        // Only meaningful over decisions that were actually delivered.
        val sent = dodgesDispatched.get()
        o.put("workRate", if (sent > 0) dodgesWorked.get().toDouble() / sent else -1.0)
        o.put("autoDetectRuns", autoDetectRuns.get())
        o.put("autoDetectHits", autoDetectHits.get())
        o.put("distinctErrors", synchronized(lock) { errorCounts.size })
        val errs = JSONArray()
        synchronized(lock) {
            for ((k, v) in errorCounts) {
                val e = JSONObject()
                e.put("msg", k)
                e.put("count", v)
                errs.put(e)
            }
        }
        o.put("errors", errs)
        write(o)
        return o
    }

    /** One readable block, for a bug report. */
    fun summaryText(): String {
        val s = summary()
        return buildString {
            appendLine("Rendera session summary  ${stamp()}")
            appendLine("  threats seen      ${s.optInt("threats")}")
            appendLine("  dodges planned    ${s.optInt("planned")}")
            appendLine("  gestures sent     ${s.optInt("dispatched")}")
            appendLine("  gestures refused  ${s.optInt("refused")}")
            appendLine("  dodges that WORKED ${s.optInt("worked")}")
            appendLine("  dodges that failed ${s.optInt("failed")}")
            val wr = s.optDouble("workRate", -1.0)
            appendLine(
                if (wr < 0) "  work rate         n/a (nothing was sent)"
                else "  work rate         ${"%.0f".format(wr * 100)}% of sent gestures"
            )
            appendLine("  auto detect       ${s.optInt("autoDetectHits")}/${s.optInt("autoDetectRuns")}")
            appendLine("  distinct errors   ${s.optInt("distinctErrors")}")
            val errs = s.optJSONArray("errors") ?: JSONArray()
            for (i in 0 until minOf(errs.length(), 12)) {
                val e = errs.optJSONObject(i) ?: continue
                appendLine("    x${e.optInt("count")}  ${e.optString("msg")}")
            }
        }
    }

    // -----------------------------------------------------------------------

    private fun write(o: JSONObject) {
        try {
            val f = file
            synchronized(lock) {
                if (f.exists() && f.length() > MAX_BYTES) {
                    // Keep the tail: the most recent session is the useful one.
                    val kept = f.readBytes().takeLast(MAX_BYTES / 2)
                    val start = kept.indexOfFirst { it == '\n'.code.toByte() }.let { if (it < 0) 0 else it + 1 }
                    f.writeBytes(kept.copyOfRange(start, kept.size))
                }
                f.appendText(o.toString() + "\n")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not append to the event log", t)
        }
    }
}
