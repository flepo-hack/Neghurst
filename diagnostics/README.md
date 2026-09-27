# Device diagnostics

Put `.jsonl` files here to have them folded into [`docs/LEARNING.md`](../docs/LEARNING.md)
by `.github/workflows/learn.yml`.

## Why this is not automatic

The app cannot write to this repository by itself. That would need a GitHub
personal access token inside the APK, and a token in a published APK is public
the moment the APK is uploaded. So the app records, you share, the workflow
learns. That separation is deliberate.

## How to contribute a log

1. In game: long press the bubble, tap **SEND DIAGNOSTICS**.
2. Copy the file path out of the share sheet text, and pull it:

   ```
   adb shell run-as com.aistudio.rendera.dodge cat files/rendera-events.jsonl > diagnostics/session-$(date +%F).jsonl
   ```

   or just copy it from the device's app folder.
3. Commit it and open a pull request, or push it to `main`.

## Format

One JSON object per line. JSONL so a file pulled off a device mid-write is
still valid, and a truncated final line is skipped rather than failing the fold.

| `type` | what it records |
|---|---|
| `session` | build, device, Android version, ABIs |
| `sample` | once a second: fps, frames received / rejected / analysed, engine ms, blobs, projectiles, ball, bouncers, enemies, player lock, motion shift and quality |
| `threat` | a threat the engine is tracking, with its time to impact |
| `decision` | the escape chosen: heading, drag, hold, required vs achievable travel, whether it was sufficient, how many shots it cleared |
| `outcome` | **the verdict**: did that dodge work, and why not if it did not |
| `calibration` | manual and auto calibration results, including why auto failed |
| `error` | every tagged failure, collapsed to a count so a per-frame fault cannot flood the file |
| `summary` | the session totals, written when the service stops |

`outcome` is the point of all of it. Until a device reported one, every tuning
decision in this project was an argument from reasoning, and the reasoning was
wrong often enough to be worthless.

## Do not fabricate these

A file here that was not produced by a real device is worse than no file: it
becomes the record everyone trusts. Only paste what the app actually wrote.
