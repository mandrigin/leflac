# LE FLAC UP NEXT Operator Guide

UP NEXT is LF-1's explicit priority bus. It sits inside the same Media3 player
timeline as ORDER, RNG, the notification, and projected Android Auto:

```text
HISTORY | CURRENT | UP NEXT 01 | UP NEXT 02 | ORDER/RNG FUTURE
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

Open the fourth ledger tab, `NEXT nn`.

- The number is the pending item count.
- Rows are numbered in exact FIFO order.
- `[X]` removes one item without changing the current track or position.
- `[CLEAR]` removes the explicit future and leaves the active rail intact.

Scheduling a track already pending is an idempotent no-op. Until playback, the
underlying rail stays intact so remove/clear are lossless. Once a marked item is
consumed, LF-1 removes its first later natural repeat so it does not play twice.

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
