package com.x3.taprhythm.input

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Temple-pad input, accepting EVERYTHING (field guide §6): touch events
 * (cyttsp5/cyttsp6 device), hover streams, and DPAD key events — firmware
 * builds differ. Screen touches mirror the pad so it's bench-testable.
 *
 * Two parallel outputs from the same finger motion:
 *  - onDrag: raw continuous (x+y) pixel deltas — drives the paddle.
 *  - onSwipe: quantized ±1 steps every 90 px — drives menu navigation.
 * onStrokeEnd fires on finger-up with the total signed stroke length
 * (used by swipe calibration). Taps stay instant (guide §11).
 */
class TemplePad(
    private val onTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onSwipe: (steps: Int) -> Unit,
    private val onDrag: (pxDelta: Float) -> Unit = {},
    private val onStrokeEnd: (strokePx: Float) -> Unit = {}
) {
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var moved = 0f
    private var lastTapT = 0L
    private var swipeAccum = 0f
    private var strokeAccum = 0f
    /** One directional gesture per stroke; see the note on FLICK_PX. */
    private var flicked = false

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                downT = ev.eventTime
                moved = 0f; swipeAccum = 0f; strokeAccum = 0f; flicked = false
            }
            MotionEvent.ACTION_MOVE -> {
                val d = (ev.x - lastX) + (ev.y - lastY)
                lastX = ev.x; lastY = ev.y
                moved = maxOf(moved, abs(ev.x - downX) + abs(ev.y - downY))
                if (d != 0f) onDrag(d)
                strokeAccum += d
                swipeAccum += d
                // ONE FLICK, ONE GESTURE, AND FIRED EARLY.
                //
                // This used to need 90px of travel and emitted a step for every
                // 90px after that. Two things were wrong with it here. A tap is
                // disqualified above 34px of movement, so ANY stroke between
                // 34 and 90px produced nothing at all — a dead zone the hand
                // lands in constantly when it is flicking in time with music.
                // And at three notes a second there is no time to draw 90px;
                // the gesture has to register the moment its direction is
                // unambiguous, not when it is finished.
                //
                // So it fires once, at FLICK_PX, and then stays quiet until the
                // finger lifts — a long stroke is still one note, never four.
                if (!flicked) {
                    if (swipeAccum >= FLICK_PX) { flicked = true; onSwipe(1) }
                    else if (swipeAccum <= -FLICK_PX) { flicked = true; onSwipe(-1) }
                }
            }
            MotionEvent.ACTION_UP -> {
                val dt = ev.eventTime - downT
                // A stroke that already fired a direction must not ALSO count
                // as a tap, or a small flick plays two lanes at once.
                if (!flicked && dt < TAP_MS && moved < TAP_PX) {
                    Log.d(TAG, "IN touch tap dev=${ev.device?.name}")
                    onTap()
                    if (ev.eventTime - lastTapT < DOUBLE_MS) onDoubleTap()
                    lastTapT = ev.eventTime
                } else {
                    onStrokeEnd(strokeAccum)
                }
            }
        }
        return true
    }

    fun onKey(code: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val now = System.currentTimeMillis()
                onTap()
                if (now - lastTapT < DOUBLE_MS) onDoubleTap()
                lastTapT = now
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                onSwipe(-1); onDrag(-KEY_DRAG_PX); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                onSwipe(1); onDrag(KEY_DRAG_PX); return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "TapRhyIn"
        const val TAP_MS = 230L
        const val TAP_PX = 34f
        const val DOUBLE_MS = 300L
        const val SWIPE_PX = 90f
        /** Travel at which a stroke commits to a direction. Small on purpose. */
        const val FLICK_PX = 26f
        const val KEY_DRAG_PX = 60f   // bench: arrow key = small paddle nudge
    }
}
