# Rendera — what is verified, what is not, and what broke before

This file exists because the same mistakes were made repeatedly across many
sessions, and because the most expensive part of this project has been *guessing*
which stage stopped. It is written to be read before changing anything.

**Truth rule for this document:** a claim is only listed as verified if I checked
it. Everything else is listed as UNVERIFIED, with the reason. Do not promote an
UNVERIFIED item to verified because the build is green.

---

## 1. The one thing that cannot be checked here

There is no Android device, no JDK, no NDK and no emulator in the development
environment. Therefore:

- **The C++ has only ever been compiled by GitHub Actions.** A green run proves
  it compiles and links; it proves nothing about what it sees.
- **The unit tests exercise pure logic only.** They cannot exercise a `Bitmap`,
  a `WindowManager` view, MediaProjection, or anything that needs a real display.
- **No screenshot of the game has ever been analysed.** Every colour threshold
  and every grid dimension is derived from reasoning about how Brawl Stars looks,
  not from measurement of it.

Everything below is split accordingly.

---

## 2. Verified by simulation or by a real build

| Claim | How it was checked |
|---|---|
| The phase correlation's sign convention is correct: `conj(F_current)·F_previous`, peak = the shift that maps previous onto current | The radix-2 FFT was unit tested against a direct DFT, and a synthetic delay of 0/3/−5/11 samples was correlated and the peak read back. All four correct. |
| A single-threaded pure-python port of the whole pipeline cancels a static texture panned 1–8 cells in both axes, producing **zero** blobs | Ten pans, end to end through the same correlation, refinement, fallback search, difference and connected components. The fallback search was needed on 5 of 10. |
| A shift wrong by ONE cell scores alignment quality ≈ 0.40; a correct shift scores ≈ 1.00; no shift scores 0.00 | Measured across all ten pans. The threshold is 0.75, above the one-cell-error figure. This number came from measurement, not from a guess. |
| Falling back to shift (0,0) on an ambiguous frame lights up 9389 of 17600 grid cells as phantom motion | Measured. 24 % of the frame. This was the single worst failure mode. |
| The opponent signals `G − max(R,B)` and `R − max(G,B)` separate the real Brawl Stars palette | Computed for 12 sampled colours: lime ring, bright green, grass, bush, red bar, purple, blue, sand, grey, orange crate. Grass and bush fall below the saturation gate; purple and blue are neither green nor red. |
| A saturated green pushes R and G both past 1.0, so per-channel RGB must not be clamped before the difference is taken | Arithmetic on the BT.601 inverse. The code does not clamp. |
| The Kotlin and native escape planners are the same formulation | The C++ `chooseEscapeHeading` and Kotlin `CollisionSolver.chooseEscapeHeading` were written from one derivation and their terms compared field by field after every change. |
| The tuning wire format cannot drift between C++ and Kotlin | `VisionTuning.WIRE_ORDER` is compared against the `EngineConfig` declaration order by `.github/scripts/check_cpp.py`. It has caught a real drift. |
| 117 unit tests pass, the C++ compiles, the `.so` is in the APK | GitHub Actions, run V.43, 30/30 steps green. |

---

## 3. UNVERIFIED — reasoning only, never measured

These are the things that most likely still need real-device work, ordered by how
much damage they do if wrong.

1. **The green player signature.** Assumed to be a saturated lime ring on the
   ground plus a green health bar. Never seen in a captured frame. If the actual
   ring colour or the surrounding palette differs, `playerDetected` stays false
   and the app falls back to the calibrated anchor. The reticle banner says
   which of the two is happening.
2. **Enemy red marks.** Same reasoning. A red-dominant crate is also detected as
   an "enemy", which only biases the escape heading.
3. **Grid geometry.** 200×108 → currently 200×88 for a 2.22:1 capture, chosen so
   a grid cell is ~12×9.6 screen px on a 2400×1080 display. The one-cell
   resolution of a bullet is a guess.
4. **Timings.** `lethalTtiSec` 0.17, `imminentTtiSec` 0.29, hold 70–220 ms,
   gesture press/drag 45/35 ms. These are plausible for Brawl Stars but nobody
   has measured a real bullet.
5. **Brawler top speed** 0.67 screen widths/s. This decides whether a dodge can
   physically get clear in time.
6. **The own-effect radii** (0.055 / 0.085 of screen width). Too large and real
   point-blank shots are discarded; too small and the brawler dodges its own
   footstep splash.

---

## 4. Bugs found by the user in a real session, and what actually caused them

Recorded because the symptom and the cause were never close together, and
re-deriving that mapping cost many sessions.

| Symptom | Real cause |
|---|---|
| "Nothing happens, it never starts" | `onConfigurationChanged` fires as soon as Brawl Stars forces landscape. The code resized the `VirtualDisplay` but never rebuilt the `ImageReader`, whose size is fixed at construction, so every frame was rejected by the size check. Capture was dead for the rest of the session. Fixed by rebuilding the reader and calling `VirtualDisplay.setSurface`. |
| "Auto calibrate does nothing" | It needs a live analysis, and there was no analysis because of the above. Compounded by the calibration overlay being **opaque**, so MediaProjection saw the overlay's own dimmed scrim instead of the game. Both fixed. |
| "The debug menu says paused and draws nothing" | The HUD was a 232×140 dp text panel. The code to draw the brawler, joystick, enemies and ammo existed and was never called, and could not have worked in a panel that size. Added a separate full-screen non-touchable reticle layer. |
| "Detection is dead with no error" | The vision loop only analysed a frame when the accessibility service's idea of the foreground app matched, and called `reset()` every frame otherwise. That gate can be wrong forever, and a wrong "paused" is invisible. It now suppresses *dodging*, not analysis. |
| "The status pill is wrong and never updates" | It read a plain `var` during composition. Compose does not recompose on that, so it showed whatever was true at first render. Now a `StateFlow`. |
| "Permission rows never update" | `remember { checkPermission() }` is evaluated once, so enabling the accessibility service in Settings left the row wrong. Re-read on every resume. |
| "The player ring cannot go to the right of the screen" | The calibration clamps used the display geometry while the reticles are drawn in the overlay view's own coordinate space. When the two disagree, the right edge becomes unreachable. Clamps are now view-relative. |
| "It crashes on start" | `ImageReader.newInstance(0, 0, …)` throws `IllegalArgumentException` when the display has not resolved yet. Guarded. |
| "The overlay is laggy" | The HUD posted an invalidate for every analysed frame, up to 60/s. Throttled to 10 Hz and skipped when nothing it draws changed. |
| Everything above was invisible from outside | There was no way to tell "no frames arriving" from "frames arriving and rejected" from "frames analysed, no player lock". There is now a one-line-per-interval diagnostic in logcat (`adb logcat -s RenderaOverlay`) and in `rendera-diagnostics.txt` in the app's files directory. |
| "It crashes when I pick a game" | Picking a game sends a second `ACTION_START` carrying the **already spent** MediaProjection token. On Android 14+ that token is single use: re-acquiring invalidates the live projection, its callback fires, and the app tears itself down. A second start is now a retarget that never touches consent. |
| "It says vision engine missing" | That message appeared whenever `detector` was null, which is also true when the capture never started, and it was the **first** branch, so it masked capture, anchors and the accessibility service. It named a missing library that was present and loaded. |
| "It says NO CAPTURE but I enabled it" | The banner was guessing its own reason and the menu was guessing a different one. Both now use the service's single `buildAdvice()`, which names the first thing actually wrong in stage order. |
| "Sometimes it just crashes" | `onStartCommand` had no error handling at all. Any throw from `startForeground`, `getParcelableExtra` or `setupCapture` killed the service with a black screen. The whole path is guarded and reports the failure instead. |

---

## 5. Traps in this repository, so they are not re-introduced

- **`git checkout main` matters.** The environment repeatedly parks the work on a
  side branch that predates the fixes. Confirm `git log --oneline -1` before
  editing anything.
- **A silent `str.replace` that does not match leaves the file looking changed
  while the change is absent.** This cost several cycles. Every replacement in
  this project now asserts its target, and the tests were rewritten to assert
  behaviour rather than the presence of a literal.
- **A build that compiles is not a detection that works.** Every "it should work
  now" in this project has needed a device to confirm, and has usually been wrong
  in a way no static check could see.
- **A green test run used to mean the tests never ran.** `:app:testReleaseUnitTest`
  does not exist in AGP 9; the task fails task-selection and the log ends in a
  stack trace. The workflow now runs `:app:test`, and a step that lists the unit
  test tasks and fails if there are none.
- **The four static checks in `.github/scripts/` are load bearing.** Each was
  written after a real failure and each was verified by injecting the failure it
  detects. Two of them were wrong on their first attempt.

---

## 5b. The capture format, settled by measurement

`ImageReader` accepts a short list of formats, and `YUV_420_888` is **not on it**.
An `ImageReader` built with 19 throws `IllegalArgumentException: Invalid format
specified 19` the moment it is constructed. 19 is a camera and codec format.

Of the accepted formats:

- `PRIVATE` (1) exposes its pixels only through a hardware buffer type that is not
  in the public SDK, so it cannot be read from a public-API app.
- `RGBA_8888` is **also 1**, so a reader built with it is a PRIVATE reader with
  no planes. That coincidence is a genuine trap.
- the grayscale formats carry no colour, which is the one thing the player
  detector needs.
- `JPEG` (0x100) is accepted and publicly readable.

So a MediaProjection capture on the public SDK is a JPEG stream that has to be
decoded per frame. The engine then computes luma and both opponent signals
directly from the interleaved RGBA, in one pass, with no colour space round trip
at all - which makes the decoded path *cheaper* for this detector than a YUV
path would have been, if one had worked.

**This means the YUV plane path this project began with could never have worked
on any device.** It was not a tuning problem and not a device quirk: the format
was never valid for an `ImageReader`.

## 5c. If it crashes, the crash is recorded

`RenderaApp` installs an uncaught exception handler that writes the thread, the
exception, its cause chain and the first forty stack frames to
`rendera-crash.txt` in the app's own external files directory - the same place
`rendera-events.jsonl` goes - and *then* delegates to the previous handler, so
the crash still looks like a crash.

It is synchronous because after it returns the process is gone, and anything
queued dies with it. `SEND DIAGNOSTICS` names the file in the shared report.

This exists because the last report was a crash with nothing in the log: six
`session` records and not one error, because whatever threw was outside every
`try`/`catch` in the service. The capture callback, the failure reporting
*inside* that callback, and the geometry change that re-points a live
VirtualDisplay are now all contained, because a throw while describing a different
throw is how the process dies with nothing to read.

## 6. The size question, answered once

The APK is 19 MB. That is the three real ABIs (`arm64-v8a`, `armeabi-v7a`,
`x86_64`) of `librendera_native.so` plus the app.

Making it ~70 MB means adding the `org.opencv:opencv` AAR from Maven Central,
which ships `libopencv_java4.so` for four ABIs. Two things are routinely
confused:

1. That AAR **does not export the C++ `cv::` symbols.** OpenCV's Android builds
   are compiled with `-fvisibility=hidden`, so only the JNI bridge is exported.
   NDK code in this project **cannot** call `cv::*` with the AAR, however the
   include paths are arranged.
2. Calling `cv::*` from NDK needs the OpenCV **Android SDK** (headers plus
   static libraries), which is what `-DRENDERA_OPENCV_SDK=…` selects. The core
   engine is complete without it and the default build has zero external
   dependencies.

So the size and the C++ API are two different products. The 19 MB build uses the
hand-written core, which is a real implementation of every stage: windowed phase
correlation, motion-compensated background subtraction, connected components,
constant-velocity Kalman tracking, and closest-point-of-approach solving.

---

## 6b. The learning loop

The gap this project kept hitting was that every tuning value was argued from
reasoning. So the app now records what it actually did, and the repository keeps
the record:

| where | what |
|---|---|
| `RenderaEventLog` (app) | append-only JSONL: session header, per second samples, each threat, each decision with the escape chosen, **and the verdict on whether that dodge worked**, every tagged error collapsed to a count, every calibration with its reason, and a session summary on stop |
| **SEND DIAGNOSTICS** (in-game menu) | shares the summary as text plus the log's path, so it can be pulled off the device |
| `diagnostics/*.jsonl` | where a pulled log is committed |
| `fold_learning.py` + `learn.yml` | folds the logs into `docs/LEARNING.md`: work rate, throughput, player lock rate, error counts, and a ranked list of distinct failures |

**It is deliberately not automatic from the app.** Pushing to a repository needs
a personal access token inside the APK, and a token in a published APK is public
the moment the APK is uploaded. The app records, the user shares, the workflow
learns.

`outcome` records are the whole point. Until a device reported one, every
tuning decision here was an argument from reasoning, and the reasoning was wrong
often enough to be worthless.

---

## 7. What to do next, in priority order

1. Install the APK and read the banner and the diagnostic line. That single line
   names the stage that is failing; do not guess.
2. The status pill and the banner now name the failing stage directly, in the
   order the stages run. Read that line first; it is the same string as the menu
   and the `note=` field in logcat.
3. If `got=0`, the capture is not delivering frames. `rejected>0` means the
   reader size still disagrees with the frames. `analysed>0` but `player=false`
   means the green signature needs work, and section 3 item 1 is where to look.
4. Capture a frame of a Brawl Stars match and measure the actual colours and the
   actual bullet size. That converts section 3 from reasoning into measurement,
   and it is the single highest-value thing anyone can do next.
