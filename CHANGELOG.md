# Changelog

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
