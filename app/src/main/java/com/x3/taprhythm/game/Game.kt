package com.x3.taprhythm.game

import com.x3.taprhythm.Settings
import com.x3.taprhythm.audio.GameAudio
import com.x3.taprhythm.input.SwipeControl
import com.x3.taprhythm.render.NeonBatch
import com.x3.taprhythm.render.Particles
import com.x3.taprhythm.render.VectorFont
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ============================================================================
 *  TAPRHYTHM — three lanes, three gestures, five tracks.
 * ============================================================================
 *
 * ## Why this shape suits the glasses
 *
 * The temple pad gives exactly three unambiguous inputs: a tap, a swipe one
 * way, a swipe the other. A rhythm game wants a small number of targets hit
 * with precise timing — which is the one genre where a three-button controller
 * is not a compromise but the correct instrument. So there are three lanes and
 * they map one-to-one:
 *
 *     swipe back    left lane
 *     tap           centre lane
 *     swipe forward right lane
 *
 * No gesture means two things, and nothing needs a menu mid-song.
 *
 * ## Notes come toward you, not down
 *
 * On a flat screen notes fall. Here the board is laid back into the room, and
 * notes travel from the far end of it toward a judgement line just in front of
 * the player — the stereo display makes distance real, so approach speed reads
 * as urgency in a way vertical descent does not.
 */
class Game(
    private val settings: Settings,
    private val audio: GameAudio,
    private val swipe: SwipeControl
) {

    val batch = NeonBatch()
    @JvmField var fps: Float = 0f
    @JvmField var tempC: Int = 0

    /** Set by MainActivity so charts can be read from assets. */
    @JvmField var ctx: android.content.Context? = null

    private companion object {
        const val ISO_TILT = 0.55f          // laid back further than the others
        const val LANE_DX = 0.042f
        const val HIT_V = -0.070f           // the judgement line
        const val FAR_V = 0.115f
        const val FAR_SCALE = 0.34f
        const val APPROACH_S = 1.5f         // seconds a note is visible

        // Timing windows, in seconds either side of the note.
        // GENEROUS ON PURPOSE. These started at 48/95/145 ms, which are arcade
        // numbers for a player holding a rigid controller and watching a screen
        // a foot away. Here the input is a fingertip on the side of your head
        // and the display floats in the room; a tap has more travel and more
        // variance before it ever reaches the game. Widening them costs
        // nothing except making a good run likelier, which is the point.
        const val W_PERFECT = 0.075f
        const val W_GREAT = 0.140f
        const val W_GOOD = 0.210f

        const val ST_MENU = 0
        const val ST_PLAY = 1
        const val ST_DONE = 2

        const val ROW_TRACK = 0
        const val ROW_OFFSET = 1
        const val ROW_START = 2
        const val ROWS = 3
        const val ROW_GAP_MS = 150L

        const val LEVELS = 5
    }

    private val parts = Particles(512)
    private val clock = SongClock()

    private var state = ST_MENU
    private var stateT = 0f
    private var row = ROW_START
    private var lastRowMs = 0L
    private var level = 1
    private var chart: Chart? = null
    private var waitingForMusic = false

    /** Index of the first note not yet judged or expired. */
    private var head = 0
    private var score = 0
    private var combo = 0
    private var best = 0
    private var perfect = 0; private var great = 0; private var good = 0; private var missed = 0

    /** Per-lane flash on the judgement line, seconds remaining. */
    private val laneFlash = FloatArray(3)
    private val laneJudge = arrayOfNulls<String>(3)
    private val laneJudgeT = FloatArray(3)

    private val events = ArrayList<Char>(8)
    private val windowScales = floatArrayOf(70f, 92f, 115f)

    init { parts.scaleLengths(0.013f) }

    // ---- input ------------------------------------------------------------

    fun tap() { synchronized(events) { events.add('T') } }
    fun doubleTap() { synchronized(events) { events.add('D') } }
    fun swipe(steps: Int) { synchronized(events) { events.add(if (steps > 0) '+' else '-') } }

    private fun drainInput() {
        synchronized(events) {
            for (e in events) when (e) {
                '+' -> onGesture(LANE_RIGHT, 1)
                '-' -> onGesture(LANE_LEFT, -1)
                'T' -> onGesture(LANE_CENTRE, 0)
                'D' -> onDouble()
            }
            events.clear()
        }
    }

    private fun onGesture(lane: Int, dir: Int) {
        when (state) {
            ST_MENU -> menuInput(lane, dir)
            ST_PLAY -> judge(lane)
            // Results: any gesture returns to the menu.
            else -> { state = ST_MENU; stateT = 0f; audio.sfx("ui") }
        }
    }

    private fun menuInput(lane: Int, dir: Int) {
        if (dir != 0) {
            // A swipe inside one stroke arrives as several steps; one flick
            // should move one row.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRowMs < ROW_GAP_MS) return
            lastRowMs = now
            row = ((row + dir) % ROWS + ROWS) % ROWS
            audio.sfx("ui")
            return
        }
        when (row) {
            ROW_TRACK -> { level = level % LEVELS + 1; audio.sfx("ui") }
            ROW_OFFSET -> { settings.audioOffsetMs = ((settings.audioOffsetMs + 10) + 130) % 260 - 130; audio.sfx("ui") }
            else -> startSong()
        }
    }

    /**
     * Deliberately does nothing. See the note in MainActivity: a double tap is
     * indistinguishable from two centre-lane notes, so it cannot be given a
     * meaning without the game firing it at itself.
     */
    private fun onDouble() { }

    /**
     * BACK: out of a song, out of the results, and out of the app from the menu.
     * @return true if this was consumed.
     */
    fun back(): Boolean = when (state) {
        ST_PLAY -> { endSong(false); true }
        ST_DONE -> { state = ST_MENU; stateT = 0f; audio.sfx("ui"); true }
        else -> false
    }

    // ---- song flow --------------------------------------------------------

    private fun startSong() {
        val c = ctx ?: return
        val ch = runCatching { Chart.load(c, level) }.getOrNull() ?: return
        chart = ch
        // ALLOCATED WITH THE CHART, not on the first simulation tick. It used to
        // be sized inside stepSong, and once the board started drawing before
        // stepSong ran — while waiting for the music — the very first frame
        // indexed an empty array and took the GL thread down with it.
        judged = BooleanArray(ch.count)
        head = 0; score = 0; combo = 0; best = 0
        perfect = 0; great = 0; good = 0; missed = 0
        java.util.Arrays.fill(laneFlash, 0f)
        parts.clear()
        state = ST_PLAY
        stateT = 0f
        audio.playLevelMusic(level)
        audio.setMusicLooping(false)
        // Do NOT start the clock here: the music has only been REQUESTED. It
        // starts when the player reports a position (see update).
        waitingForMusic = true
    }

    private fun endSong(finished: Boolean) {
        clock.stop()
        audio.stopLevelMusic()
        state = ST_DONE
        stateT = 0f
        if (finished) audio.sfx("done")
    }

    // ---- judgement --------------------------------------------------------

    /**
     * A gesture judges the NEAREST unjudged note in that lane, not the next one
     * in the list: at three notes a second the player is regularly a whole note
     * early or late, and punishing the note they were actually aiming at would
     * make a small timing error cost two notes instead of one.
     */
    private fun judge(lane: Int) {
        val c = chart ?: return
        val t = songTime()
        var bestI = -1
        var bestD = Float.MAX_VALUE
        var i = head
        while (i < c.count && c.time[i] < t + W_GOOD + 0.02f) {
            if (c.lane[i] == lane && !judged[i]) {
                val d = abs(c.time[i] - t)
                if (d < bestD) { bestD = d; bestI = i }
            }
            i++
        }
        laneFlash[lane] = 0.12f
        if (bestI < 0 || bestD > W_GOOD) {
            // A gesture with no note near it. Breaking the combo for it is what
            // stops mashing from being a winning strategy.
            combo = 0
            audio.sfx("miss")
            showJudge(lane, "MISS")
            return
        }
        judged[bestI] = true
        val (name, pts) = when {
            bestD <= W_PERFECT -> { perfect++; "PERFECT" to 300 }
            bestD <= W_GREAT -> { great++; "GREAT" to 200 }
            else -> { good++; "GOOD" to 100 }
        }
        combo++
        if (combo > best) best = combo
        score += pts + (combo / 10) * 10
        audio.sfx(if (bestD <= W_PERFECT) "perfect" else when (lane) {
            LANE_LEFT -> "hit_l"; LANE_RIGHT -> "hit_r"; else -> "hit_c"
        })
        if (combo > 0 && combo % 25 == 0) audio.sfx("combo")
        showJudge(lane, name)
        val u = laneU(lane)
        parts.burst(u, HIT_V, if (bestD <= W_PERFECT) 22 else 12, 0.06f,
            if (bestD <= W_PERFECT) 1f else 0.4f, 0.9f, if (bestD <= W_PERFECT) 0.4f else 1f, 0.55f)
    }

    private fun showJudge(lane: Int, text: String) {
        laneJudge[lane] = text
        laneJudgeT[lane] = 0.6f
    }

    private var judged = BooleanArray(0)

    private fun songTime(): Float = clock.now() - settings.audioOffsetMs / 1000f

    // ---- frame ------------------------------------------------------------

    fun update(dt: Float) {
        stateT += dt
        drainInput()

        if (state == ST_PLAY) {
            val pos = audio.musicPosMs()
            if (waitingForMusic) {
                if (pos >= 0) {
                    clock.start(pos)
                    waitingForMusic = false
                    android.util.Log.i("TapRhy", "song clock anchored at ${pos}ms")
                } else if (stateT > 5f) {
                    // The track never started. Better to bail than to leave the
                    // player staring at a board that will never move.
                    endSong(false)
                }
            } else {
                clock.poll(pos)
                stepSong(dt)
            }
        }

        parts.update(dt)
        for (i in 0..2) {
            if (laneFlash[i] > 0f) laneFlash[i] = (laneFlash[i] - dt).coerceAtLeast(0f)
            if (laneJudgeT[i] > 0f) laneJudgeT[i] = (laneJudgeT[i] - dt).coerceAtLeast(0f)
        }

        val s = windowScales[settings.windowSize.coerceIn(0, 2)]
        val ct = cos(ISO_TILT); val st = sin(ISO_TILT)
        batch.setBasis(0f, 0f, 0f, s, 0f, 0f, 0f, s * ct, -s * st, 0f, s * st, s * ct)
        batch.lift = 0f

        batch.begin()
        when (state) {
            ST_MENU -> drawMenu()
            ST_PLAY -> {
                drawBoard(); drawHud()
                if (waitingForMusic) {
                    VectorFont.draw(batch, "READY", 0f, 0.020f, 0.0044f,
                        1f, 1f, 0.6f, 0.55f + 0.45f * sin(stateT * 6f), true)
                }
            }
            else -> drawResults()
        }
        parts.draw(batch)
    }

    private fun stepSong(dt: Float) {
        val c = chart ?: return
        if (judged.size != c.count) judged = BooleanArray(c.count)
        val t = songTime()

        // Retire notes that have gone past the window unhit.
        while (head < c.count && c.time[head] < t - W_GOOD) {
            if (!judged[head]) {
                judged[head] = true
                missed++
                combo = 0
                showJudge(c.lane[head], "MISS")
            }
            head++
        }

        // The song is over when the music stops or the chart runs out; the
        // player should not sit staring at an empty board either way.
        val ended = (!audio.musicIsPlaying() && stateT > 1.5f) ||
                    (head >= c.count && t > c.durSec - 0.2f)
        if (ended) endSong(true)
    }

    // ---- geometry ---------------------------------------------------------

    private fun laneU(lane: Int) = (lane - 1) * LANE_DX

    /** 0 at the judgement line, 1 at the far end. */
    private fun noteV(prog: Float) = HIT_V + prog * (FAR_V - HIT_V)
    private fun noteScale(prog: Float) = 1f - prog * (1f - FAR_SCALE)

    // ---- drawing ----------------------------------------------------------

    private fun drawBoard() {
        val c = chart ?: return
        val t = songTime()

        // Lane rails, converging into the distance.
        for (i in 0..2) {
            val uNear = laneU(i)
            val a = 0.20f + laneFlash[i] * 3.5f
            batch.line(uNear * FAR_SCALE, FAR_V, uNear, HIT_V, 0.0007f, 0.35f, 0.8f, 1f, a.coerceAtMost(1f))
        }
        // Outer edges, so the board has a shape rather than three floating lines.
        for (s in intArrayOf(-1, 1)) {
            val uNear = s * (LANE_DX * 1.5f)
            batch.line(uNear * FAR_SCALE, FAR_V, uNear, HIT_V, 0.0006f, 0.3f, 0.6f, 1f, 0.28f)
        }

        // Beat rungs, so the board has a pulse even where there are no notes.
        val beat = 60f / c.bpm
        var k = 0
        while (k < 16) {
            val bt = (kotlin.math.floor(t / beat) + k) * beat
            val prog = (bt - t) / APPROACH_S
            if (prog in 0f..1f) {
                val w = LANE_DX * 1.5f * noteScale(prog)
                val v = noteV(prog)
                val onBar = (kotlin.math.floor(bt / beat).toInt() % 4) == 0
                batch.line(-w, v, w, v, 0.0005f, 0.25f, 0.55f, 0.9f,
                    (if (onBar) 0.26f else 0.12f) * (1f - prog * 0.5f))
            }
            k++
        }

        // The judgement line: the only place on the board that matters.
        val hw = LANE_DX * 1.55f
        batch.line(-hw, HIT_V, hw, HIT_V, 0.0016f, 0.6f, 1f, 1f, 0.9f)
        for (i in 0..2) {
            val u = laneU(i)
            val f = laneFlash[i] / 0.12f
            batch.circle(u, HIT_V, LANE_DX * 0.36f, 0.0009f, 0.5f, 1f, 1f, 0.30f + f * 0.7f)
        }

        // Notes.
        var i = head
        while (i < c.count) {
            val prog = (c.time[i] - t) / APPROACH_S
            if (prog > 1f) break
            if (prog >= -0.06f && !judged[i]) drawNote(c.lane[i], prog)
            i++
        }

        // Judgement text, per lane, fading.
        for (l in 0..2) {
            if (laneJudgeT[l] <= 0f) continue
            val a = (laneJudgeT[l] / 0.6f).coerceIn(0f, 1f)
            val txt = laneJudge[l] ?: continue
            val col = when (txt) {
                "PERFECT" -> floatArrayOf(1f, 0.9f, 0.35f)
                "GREAT" -> floatArrayOf(0.4f, 1f, 0.7f)
                "GOOD" -> floatArrayOf(0.5f, 0.8f, 1f)
                else -> floatArrayOf(1f, 0.35f, 0.35f)
            }
            VectorFont.draw(batch, txt, laneU(l), HIT_V - 0.020f, 0.0018f,
                col[0], col[1], col[2], a * 0.9f, true)
        }
    }

    private fun drawNote(lane: Int, prog: Float) {
        val uu = laneU(lane) * (FAR_SCALE + (1f - FAR_SCALE) * (1f - prog))
        val v = noteV(prog)
        val sc = noteScale(prog)
        val hw = LANE_DX * 0.40f * sc
        val hh = 0.0075f * sc
        val r = when (lane) { LANE_LEFT -> 1f; LANE_RIGHT -> 0.35f; else -> 1f }
        val g = when (lane) { LANE_LEFT -> 0.35f; LANE_RIGHT -> 1f; else -> 0.85f }
        val b = when (lane) { LANE_LEFT -> 0.85f; LANE_RIGHT -> 0.85f; else -> 0.30f }
        // Brighter as it approaches: the note you must play next is the
        // brightest thing on the board.
        val a = (0.35f + (1f - prog) * 0.65f).coerceIn(0f, 1f)
        batch.fill(uu, v, hw, hh, 0f, r * 0.5f, g * 0.5f, b * 0.5f, a * 0.8f)
        batch.line(uu - hw, v - hh, uu + hw, v - hh, 0.0009f * sc, r, g, b, a)
        batch.line(uu + hw, v - hh, uu + hw, v + hh, 0.0009f * sc, r, g, b, a)
        batch.line(uu + hw, v + hh, uu - hw, v + hh, 0.0009f * sc, r, g, b, a)
        batch.line(uu - hw, v + hh, uu - hw, v - hh, 0.0009f * sc, r, g, b, a)
    }

    private fun drawHud() {
        val c = chart ?: return
        VectorFont.draw(batch, "$score", -0.132f, 0.100f, 0.0026f, 0.6f, 0.95f, 1f, 0.85f)
        if (combo >= 5) {
            VectorFont.draw(batch, "$combo", 0f, 0.055f, 0.0050f, 1f, 0.85f, 0.35f, 0.75f, true)
            VectorFont.draw(batch, "COMBO", 0f, 0.035f, 0.0016f, 1f, 0.85f, 0.35f, 0.5f, true)
        }
        VectorFont.draw(batch, c.name, 0.140f - c.name.length * 0.0106f, 0.100f, 0.0020f,
            1f, 0.5f, 0.9f, 0.6f)

        // Progress through the song, as a bar under the judgement line.
        val p = (songTime() / c.durSec).coerceIn(0f, 1f)
        val w = 0.120f
        batch.line(-w, -0.096f, w, -0.096f, 0.0005f, 0.4f, 0.6f, 0.9f, 0.25f)
        batch.line(-w, -0.096f, -w + 2 * w * p, -0.096f, 0.0011f, 0.5f, 1f, 1f, 0.8f)
    }

    private fun drawMenu() {
        VectorFont.draw(batch, "TAPRHYTHM", 0f, 0.070f, 0.0052f, 0.5f, 1f, 1f, 1f, true)

        val names = arrayOf("PULSE", "BLIP RODEO", "ASTRO", "OVERDRIVE", "IO TOWER")
        drawRow(ROW_TRACK, 0.026f, "TRACK", "$level ${names[(level - 1).coerceIn(0, 4)]}")
        drawRow(ROW_OFFSET, 0.000f, "SYNC", "${settings.audioOffsetMs} MS")

        val sel = row == ROW_START
        val pulse = if (sel) 0.55f + 0.45f * sin(stateT * 3f) else 0.4f
        VectorFont.draw(batch, "PLAY", 0f, -0.032f, 0.0042f, 1f, 1f, 0.6f, pulse, true)
        if (sel) caret(-0.078f, -0.032f, 0.0042f)

        VectorFont.draw(batch, "SWIPE BACK    LEFT LANE", -0.130f, -0.066f, 0.0018f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "TAP           CENTRE", -0.130f, -0.082f, 0.0018f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "SWIPE FORWARD RIGHT LANE", -0.130f, -0.098f, 0.0018f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "BACK          QUIT SONG", -0.130f, -0.114f, 0.0018f, 0.5f, 0.9f, 1f, 0.38f)
    }

    private fun drawRow(which: Int, v: Float, label: String, value: String) {
        val sel = row == which
        val a = if (sel) 1f else 0.45f
        VectorFont.draw(batch, label, -0.108f, v, 0.0026f, 0.55f, 0.9f, 1f, a)
        VectorFont.draw(batch, value, -0.010f, v, 0.0026f, 1f, 0.85f, 0.35f, a)
        if (sel) caret(-0.128f, v, 0.0026f)
    }

    private fun caret(u: Float, v: Float, size: Float) {
        val h = size * 1.3f
        val cy = v + size * 1.4f
        batch.line(u, cy + h, u + h * 1.2f, cy, 0.0010f, 1f, 1f, 0.6f, 0.9f)
        batch.line(u, cy - h, u + h * 1.2f, cy, 0.0010f, 1f, 1f, 0.6f, 0.9f)
    }

    private fun drawResults() {
        val c = chart
        val total = perfect + great + good + missed
        val acc = if (total == 0) 0f else (perfect + great * 0.7f + good * 0.4f) / total * 100f
        val rank = when {
            acc >= 95f -> "S"; acc >= 88f -> "A"; acc >= 78f -> "B"; acc >= 65f -> "C"; else -> "D"
        }
        // A glyph is about 7.3x its size parameter tall, so the rank at 0.0110
        // stood 80mm high and ran through both the track name above it and the
        // score below. Sized and spaced from that number rather than by eye.
        VectorFont.draw(batch, c?.name ?: "TRACK", 0f, 0.100f, 0.0030f, 1f, 0.5f, 0.9f, 0.9f, true)
        VectorFont.draw(batch, rank, 0f, 0.026f, 0.0080f, 1f, 0.9f, 0.35f, 1f, true)
        VectorFont.draw(batch, "$score", 0f, -0.008f, 0.0040f, 0.6f, 0.95f, 1f, 0.95f, true)

        VectorFont.draw(batch, "PERFECT $perfect", -0.128f, -0.030f, 0.0020f, 1f, 0.9f, 0.35f, 0.8f)
        VectorFont.draw(batch, "GREAT   $great", -0.128f, -0.048f, 0.0020f, 0.4f, 1f, 0.7f, 0.8f)
        VectorFont.draw(batch, "GOOD    $good", -0.128f, -0.066f, 0.0020f, 0.5f, 0.8f, 1f, 0.8f)
        VectorFont.draw(batch, "MISS    $missed", -0.128f, -0.084f, 0.0020f, 1f, 0.4f, 0.4f, 0.8f)
        VectorFont.draw(batch, "COMBO $best", 0.030f, -0.030f, 0.0020f, 1f, 0.85f, 0.5f, 0.8f)
        VectorFont.draw(batch, "ACC ${acc.toInt()}%", 0.030f, -0.048f, 0.0020f, 0.7f, 0.9f, 1f, 0.8f)

        val pulse = 0.5f + 0.5f * sin(stateT * 3f)
        VectorFont.draw(batch, "TAP FOR MENU", 0f, -0.104f, 0.0022f, 1f, 1f, 0.6f, pulse, true)
    }
}
