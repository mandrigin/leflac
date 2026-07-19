# Changelog

## 1.4.0 — 2026-07-19 — Training profiles, live queue, mix heat

- Replaced the single flat/loud gym heuristic with activity-aware CARDIO,
  WEIGHTS, GRIT, and STATIC profiles. `.gym` now has a visible selector and
  each mode also works as a direct deck command (including `.grift` as a
  forgiving alias for `.grit`).
- Added a tappable, upward-opening result layer to SCAN so the keyboard cannot
  cover command or song/mix matches. Typing `.` lists canonical deck commands,
  partial prefixes filter them, and aliases resolve to the uncluttered entry.
- Repaired JNI FFT input aliasing, stereo downmix, per-window timestamps, tempo
  confidence, pulse/transient extraction, and profile cache invalidation. Gym
  results update as background analysis completes instead of depending on when
  the command was entered.
- Made workout eligibility deterministic and shared between search and
  playback. Listening history is now only an 8% ranking tie-breaker; small
  qualified sets remain small and never fall back to arbitrary songs.
- Kept CARDIO/WEIGHTS/GRIT/STATIC sessions inside their qualified song set
  across ORDER/RNG changes and file-level NEXT/PREV, with the active profile
  engraved in the system strip.
- Expanded the `NEXT nn` ledger from manual picks to the effective Media3
  future: HELD priority items plus the generated ORDER/RNG rail. Every visible
  occurrence has an exact remove control; `[CLR HELD]` remains lossless and
  leaves the generated rail intact.
- Added stable per-occurrence queue identities and a truthful projection that
  hides the temporary natural duplicate of a promoted track while retaining
  intentional RNG repeats.
- Added persisted, cue-bounded listening heat for long mixes. A normal pass is
  neutral; replayed or deliberately revisited segments become HOT, appear as
  jump targets in the cue ladder, and are painted on the scrubber heat map.
- Tightened mix cue extraction to final/near-final analysis, local novelty
  peaks, and a robust significance gate so listening history is not attached
  to arbitrary noise partitions.
- Added unit coverage for workout modes/features, queue projection and repeat
  identity, cue sanitation/boundaries, hot-segment qualification, batched
  listening, and lossless cue-map rebinding, plus device persistence coverage
  for provisional-to-final mix heat migration.

## 1.3.0 — 2026-07-13 — UX consistency pass

- Rechecked Music and audio permission on every Activity resume so returning
  from system Settings immediately restores the phone ledger and refreshes the
  Android Auto library.
- Made Back unwind the visible interaction first: rear manual, Up Next
  selection, search, then album, before leaving the app.
- Preserved ordered Up Next selection and shelf scroll positions across
  rotation/navigation; reconciled selected IDs after MediaStore rescans.
- Added explicit feedback for current, already queued, and wrong-pool track
  holds instead of silently ignoring the gesture or falling through to play.
- Corrected actual-added queue feedback, startup mix classification, empty-deck
  status, scrubber bounds/disabled state, long-title bounds, and symmetric
  previous/next punch-in feedback.
- Re-registered the album header, track badges/times, two-line service readout,
  scrubber artwork, disabled PLAY face, and strip clock to the chassis grid
  after phone screenshot review.
- Standardized control roles, action labels, state descriptions, radio/toggle
  semantics, and practical hit targets without changing the LF-1 faceplate.
- Renamed `CAR SKIN` to `AA ART` and printed the sideload/host rules on the rear
  manual: Android Auto owns its chrome; direct installs require Android Auto
  developer mode, Unknown sources, launcher enablement, and a reconnect.

## 1.2.0 — 2026-07-12 — Up Next priority bus

- Added a long-press track loader with ordered multi-selection and an explicit
  `[QUEUE]` commit step; normal taps remain play-now.
- Added a fourth `NEXT nn` ledger tab with FIFO order, queued-row markers,
  per-item removal, clear-all, empty-state guidance, haptics, and accessibility
  labels.
- Inserted scheduled items into the real Media3/ExoPlayer timeline ahead of
  ORDER or RNG, so the phone, notification, and Android Auto share one queue.
- Preserved scheduled items across rail changes, Activity recreation, service
  resumption, and car reconnection; missing MediaStore items are discarded on
  restore.
- Kept the underlying rail intact so remove/clear are lossless; after a marked
  item is consumed, its first later natural repeat is removed. Repeated
  scheduling is idempotent.
- Corrected phone ORDER queues and index-based transport to preserve song/mix
  pools and honor the actual player timeline.
- Added pure queue-planning coverage for FIFO insertion, promotion,
  idempotency, and rail-priority merging.

## 1.1.0 — 2026-07-12 — Android Auto car deck

- Replaced the playback-only Media3 session with one browsable
  `MediaLibrarySession`, retaining the same `AudioService` and `ExoPlayer`.
- Added projected Android Auto discovery metadata and legacy browser support.
- Added offline Hot now, Albums, Mixes, All songs, item lookup, pagination,
  search, host-delivered voice-query resolution, playable URI resolution, and
  queue resume.
- Added a separate rear-panel `CAR SKIN` setting. POCKET is the default;
  FIELD is optional. Phone skin behavior is unchanged.
- Added local POCKET/FIELD browse artwork and scheme-aware now-playing art.
- Extracted the MediaStore scan into a shared, cached local library used by
  both the phone ledger and car service without changing phone sort order.
- Added permission-safe cold-start behavior: the car tells the driver to
  finish setup on the phone while safely parked.
- Protected phone-only playback intents while keeping the exported media
  library discoverable by projected Android Auto hosts.
- Updated to Media3 1.10.1, AGP 8.10.1, Gradle 8.11.1, Kotlin/Compose 2.2.21,
  compile SDK 36, and NDK 27.0.12077973. Target SDK remains 34 for the first
  Honda e sideload test to avoid unrelated runtime behavior changes.
- Added unit coverage for the visual preference and browser pagination plus
  an on-device cold-service MediaBrowser integration test.

## 1.0.0 — LF-1 initial public release

- FIELD and POCKET phone skins, local playback and analysis, smart queues,
  Hot now, mixes, Glyph Matrix toy, rear-panel manual, and offline-only data.
