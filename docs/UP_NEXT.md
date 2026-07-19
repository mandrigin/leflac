# LE FLAC UP NEXT Operator Guide

UP NEXT is LF-1's effective-future ledger. Its `HELD` rows form the explicit
priority bus, and its `RAIL` rows expose the generated future from the same
Media3 timeline used by ORDER, RNG, the notification, and projected Android Auto:

```text
HISTORY | CURRENT | HELD 01 | HELD 02 | ORDER/RNG RAIL
```

## Load one or several tracks

1. Hold a track row. LF-1 vibrates once and changes the ledger header to
   `LOAD NEXT // 01`.
2. Tap more rows to select or deselect them. Selection order is playback order.
3. Press `[QUEUE]` to commit, or `[X]`/Back to cancel.

Normal taps outside selection mode still play immediately. Rows from the other
pool dim while selecting: songs schedule with songs, and long mixes schedule
with long mixes. If nothing is loaded, the first selected item becomes the
prepared, paused current item and the remaining items become UP NEXT; press Play
when ready.

## Inspect and edit

Open the fourth ledger tab, `NEXT nn`. It is pre-filled from the real Media3
timeline, even when you have not held any tracks yourself.

- The number is the complete effective-future count.
- `HELD` rows are explicit priority picks; `RAIL` rows come from ORDER/RNG.
- Rows are numbered in exact playback order and intentional repeats remain
  distinct occurrences.
- `[X]` removes that exact occurrence without changing the current track or
  position.
- `[CLR HELD]` removes explicit picks and leaves the active rail intact.

Scheduling a track already HELD is an idempotent no-op. Promotion leaves the
underlying rail intact so removing/clearing HELD items is lossless. The UI hides
the promoted track's temporary natural rail copy because LF-1 removes that copy
when the HELD occurrence is consumed; later intentional RNG repeats stay shown.

## Rail and transport rules

- UP NEXT wins over ORDER and RNG.
- Switching ORDER/RNG preserves the explicit segment and rebuilds only the
  generated future after it.
- Natural completion, phone NEXT, Android Auto NEXT, and steering-wheel NEXT
  consume the same player timeline.
- A play-now track tap or new car browse selection starts a new context and
  clears the old explicit schedule.
- During a long mix, the phone's `CUE+` remains an intra-mix cue jump. Scheduled
  mixes apply when the file ends or a file-level car control advances.

## Persistence and the car

Pending MediaStore IDs are saved in order. Activity recreation leaves the live
service queue untouched; service playback resumption reconstructs the matching
same-pool items and drops files that no longer exist. Android Auto receives the
result through the existing MediaSession queue. Some hosts show a queue page and
some do not, but playback order does not depend on that UI.

The physical Honda e queue page and steering-wheel acceptance steps are in the
[Android Auto operator guide](ANDROID_AUTO.md).
