# taprhythm — a rhythm game for the RayNeo X3 Pro

Free software, GPL v3 (see LICENSE). Derived from `x3breakout`, which is GPL v3.

Five tracks, three lanes, three gestures.

## Screenshots

<p>
  <img src="images/title.png" width="45%" alt="taprhythm title screen with track and sync settings">
  <img src="images/gameplay.png" width="45%" alt="taprhythm three-lane perspective with notes approaching">
</p>

## Why a rhythm game suits these glasses

The temple pad gives exactly three unambiguous inputs — a tap, a swipe one way,
a swipe the other. Most genres treat that as a severe limitation. A rhythm game
wants a small number of targets hit with precise **timing**, which makes a
three-input controller not a compromise but the right instrument. So there are
three lanes and they map one-to-one, with no gesture meaning two things and
nothing needing a menu mid-song:

| gesture | lane |
|---|---|
| swipe back | left |
| **tap** | **centre** |
| swipe forward | right |

**One flick is one note, and it fires early.** The chassis originally required
90 px of travel for a swipe and emitted another step every 90 px after that,
while a tap was disqualified above 34 px of movement — so **any stroke between
34 and 90 px produced nothing at all**, which is exactly where the hand lands
when it is flicking in time with music. And at three notes a second there is no
time to draw 90 px anyway. A stroke now commits at 26 px and then stays quiet
until the finger lifts, so a short flick registers and a long one is still one
note rather than four. A stroke that has fired a direction cannot also count as
a tap.

Double tap abandons a song. Notes travel **toward you** from the far end of a
board laid back into the room rather than falling down a flat screen — stereo
makes distance real, so approach speed reads as urgency.

## The tracks were chosen by measurement, not by name

Every mp3 in `~/Projects` was analysed for tempo, beat confidence (how strongly
the onset envelope autocorrelates at the beat period) and onset density. The
five levels are a deliberate tempo ramp of tracks that all scored well:

| level | track | bpm | beat conf | notes | notes/s |
|---|---|---|---|---|---|
| 1 | PULSE | 77 | 0.77 | 196 | 1.5 |
| 2 | BLIP RODEO | 98 | 0.76 | 356 | 1.9 |
| 3 | ASTRO | 120 | 0.73 | 453 | 2.3 |
| 4 | OVERDRIVE | 133 | **0.84** | 464 | 2.7 |
| 5 | IO TOWER | 157 | 0.67 | 189 | 3.1 |

## Charts are generated offline

`tools/make_charts.py` (ffmpeg + numpy) builds `assets/charts/NN.json`. It runs
on a desktop and its output is committed, because onset detection is an FFT over
the whole song and doing that on the glasses would stall for seconds before
every track on a device that is already thermally tight. Committing it also
means a chart can be hand-corrected without touching the app.

What it does, and why each step is there:

- **Onset envelope** counts only energy that *appears* between frames. Counting
  decay as well smears every transient into the one after it.
- **Tempo** by autocorrelation of that envelope.
- **Phase** by sliding a one-period comb and keeping the offset with the most
  onset energy under it. Skip this and the grid has the right spacing in the
  wrong place — every note sits a fraction of a beat off the music.
- **Quantise** to eighth notes and discard anything not near a grid line, which
  is mostly reverb tails and grace notes no human taps.
- **Lane from timbre** — spectral centroid at the onset. A kick and a hi-hat
  feel different and should not ask for the same gesture.
- **Thin** to the level's target density, strongest onsets first, with a 160 ms
  minimum gap because that is about the floor for a temple pad.

## The clock is the whole game

MediaPlayer does not give you one. `currentPosition` advances in visible steps —
tens of milliseconds at a time — because it reports the decoder's position, not
the speaker's. Used directly as per-frame time, notes judder forward in bursts
and judging a tap becomes a lottery decided by which side of a step the frame
landed on.

**And the clock must not start until the music does.** `playLevelMusic` is
asynchronous — prepare and start happen on a background thread — so starting the
clock when the song is *requested* left it hundreds of milliseconds into the
chart while the room was still silent. The first notes arrived before the first
beat, were missed, and then the clock snapped backwards when the player finally
reported a position. It now waits for a real position and anchors to it, showing
READY until then.

So `SongClock` treats the player's position as an **anchor**, not a clock. Between
anchors it runs on the monotonic system clock; a large disagreement (a seek, a
stall) snaps, a small one is eased in over a few frames so nothing visibly
jumps.

**SYNC defaults to 110 ms, which is measured rather than picked.** The glasses
report 109 ms of output latency (`AudioManager.getOutputLatency`, logged at
startup). At zero, every tap read as late by most of a timing window, because
the player is responding to sound that left the mixer a tenth of a second before
they heard it. **SYNC** on the menu shifts judgement by ±130 ms. Bluetooth or a firmware change
can move output latency by more than a whole timing window, at which point every
tap reads as early and the game looks broken rather than the headphones.

## Timing windows

| | window |
|---|---|
| PERFECT | ±75 ms |
| GREAT | ±140 ms |
| GOOD | ±210 ms |

**Deliberately generous.** These began at 48/95/145 ms, which are arcade numbers
for a player gripping a rigid controller and watching a screen a foot from their
face. Here the input is a fingertip on the side of your head and the display
floats in the room; a tap carries more travel and more variance before it ever
reaches the game.

A gesture judges the **nearest** unjudged note in that lane, not the next one in
the list: at three notes a second a player is regularly a whole note early or
late, and punishing the note they were actually aiming at would cost them two
notes for one mistake. A gesture with no note near it breaks the combo, which is
what stops mashing from being a winning strategy.

## Build

```
python3 tools/make_charts.py          # only when changing tracks
./gradlew :app:assembleDebug
adb -s <glasses-serial> install -r app/build/outputs/apk/debug/taprhythm.apk
```

Two devices are usually attached, so `-s` is not optional. The glasses report
`model:ARGF20`, `manufacturer:RayNeo`.

## Layout notes, measured on the device

- Visible field is about **±0.144 across, ±0.117 up** in plane-local metres.
- The vector font advances ~**5.4×** its size parameter per character and a
  glyph is ~**7.3×** that parameter tall. The results-screen rank ran through
  both the track name and the score until it was sized from those numbers.
- **If nothing appears, check `batch.setBasis(...)` is still called in
  `Game.update`.**
