package com.x3.taprhythm.game

import android.content.Context
import org.json.JSONObject

const val LANE_LEFT = 0
const val LANE_CENTRE = 1
const val LANE_RIGHT = 2

/** One playable track: the song, and where its notes fall. */
class Chart(
    @JvmField val level: Int,
    @JvmField val name: String,
    @JvmField val bpm: Float,
    @JvmField val durSec: Float,
    /** Note times in seconds, ascending. Parallel to [lane]. */
    @JvmField val time: FloatArray,
    @JvmField val lane: IntArray
) {
    val count: Int get() = time.size

    companion object {
        /**
         * Charts are generated offline, not at runtime.
         *
         * Onset detection means an FFT over the whole song, and doing that on
         * the glasses would cost several seconds of stall before every track on
         * a device that is already thermally tight. The generator lives in
         * tools/, runs on a desktop, and commits its output — which also means
         * a chart can be hand-corrected without touching the app.
         */
        fun load(ctx: Context, level: Int): Chart {
            val txt = ctx.assets.open("charts/%02d.json".format(level))
                .bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            val arr = o.getJSONArray("notes")
            val t = FloatArray(arr.length())
            val l = IntArray(arr.length())
            for (i in 0 until arr.length()) {
                val n = arr.getJSONArray(i)
                t[i] = n.getDouble(0).toFloat()
                l[i] = n.getInt(1).coerceIn(0, 2)
            }
            return Chart(
                level = o.optInt("level", level),
                name = o.optString("name", "TRACK $level"),
                bpm = o.optDouble("bpm", 120.0).toFloat(),
                durSec = o.optDouble("dur", 0.0).toFloat(),
                time = t, lane = l
            )
        }
    }
}

/**
 * ============================================================================
 *  The song clock.
 * ============================================================================
 *
 * A rhythm game is only as good as its clock, and MediaPlayer does not provide
 * one: `currentPosition` advances in visible steps — tens of milliseconds at a
 * time — because it reports the decoder's position, not the speaker's. Used
 * directly as the per-frame time it makes every note judder forward in bursts,
 * and the judgement of a tap becomes a lottery decided by which side of a step
 * the frame landed on.
 *
 * So the player's position is an ANCHOR, not a clock. Between anchors this runs
 * on the monotonic system clock, which is smooth and never jumps; when the
 * player reports a genuinely new position the anchor is reset. A large
 * disagreement (a seek, a stall, a buffer underrun) snaps; a small one is eased
 * out over a few frames so the notes never visibly jump.
 */
class SongClock {

    private var anchorMs = 0.0
    private var anchorNano = 0L
    private var lastPolled = -1
    private var correction = 0.0
    var running = false; private set

    /**
     * Starts the clock AT a known song position.
     *
     * It used to start at zero the instant the song was requested, and that was
     * wrong in a way that felt like bad timing rather than a bug: music starts
     * ASYNCHRONOUSLY — the audio layer hands prepare() and start() to a
     * background thread — so the clock was already several hundred milliseconds
     * into the song while the room was still silent. The first notes arrived
     * before the first beat, the player missed them, and then the clock snapped
     * backwards when the player finally reported a position.
     *
     * Now the caller waits for the player to report a real position and anchors
     * to it, so bar one of the chart lines up with bar one of the music.
     */
    fun start(atMs: Int = 0) {
        anchorMs = atMs.toDouble()
        anchorNano = System.nanoTime()
        lastPolled = atMs
        correction = 0.0
        running = true
    }

    fun stop() { running = false }

    /** @param posMs what the player says, or -1 if it is not playing. */
    fun poll(posMs: Int) {
        if (!running || posMs < 0) return
        if (posMs == lastPolled) return          // no new information
        lastPolled = posMs
        val predicted = rawNow()
        val diff = posMs - predicted
        if (kotlin.math.abs(diff) > SNAP_MS) {
            // A seek or a stall. Believe the player.
            anchorMs = posMs.toDouble()
            anchorNano = System.nanoTime()
            correction = 0.0
        } else {
            // Ordinary jitter: fold it in gradually so nothing on screen jumps.
            correction = diff
        }
    }

    private fun rawNow(): Double =
        anchorMs + (System.nanoTime() - anchorNano) / 1_000_000.0

    /** Song position in SECONDS, smooth, monotonic between snaps. */
    fun now(): Float {
        if (!running) return 0f
        if (correction != 0.0) {
            val step = correction * EASE
            anchorMs += step
            correction -= step
            if (kotlin.math.abs(correction) < 0.05) correction = 0.0
        }
        return (rawNow() / 1000.0).toFloat()
    }

    private companion object {
        /** Beyond this the player and the clock disagree about reality. */
        const val SNAP_MS = 120.0
        /** Fraction of the remaining error folded in per frame. */
        const val EASE = 0.08
    }
}
