# LE FLAC Android Auto Operator Guide

This guide covers LF-1 1.2.0 on projected Android Auto, with the first live
acceptance run targeted at a Honda e. LE FLAC remains offline-only: the car
browser reads the phone's MediaStore and the app still declares no `INTERNET`
permission.

## What the car gets

LE FLAC exposes one Media3 `MediaLibrarySession` backed by the same ExoPlayer
used by the phone UI. The car can start the service without starting
`MainActivity` and can:

- browse the relevant Hot now, Albums, Mixes, and All songs destinations;
- search by title, artist, or folder;
- resolve voice requests delivered by the host into local playable URIs;
- play, pause, seek, and use next/previous steering-wheel controls;
- resume the last local item and position after session recreation;
- show a safe phone-setup message when Music and audio permission is missing.

Songs and mixes keep separate queues. A song selection queues songs; a mix
selection queues mixes. Android Auto never autoplays merely because it connects.
Tracks scheduled with the phone's long-press UP NEXT loader occupy the priority
segment immediately after the current item. Natural completion and car NEXT
consume that FIFO segment before returning to the selected ORDER/RNG rail. The
host may expose it through its standard queue page; LE FLAC does not duplicate
mutable queue state as a fifth browse destination.

LF-1 does not expose ExoPlayer's native shuffle toggle to car controllers. RNG
is a materialized smart rail, selected on the parked phone, and native shuffle
would reorder the shared timeline around explicit UP NEXT items.

## POCKET and FIELD in the car

Android Auto and the Honda head unit render the driving-safe chrome, typography,
and templates. A media app cannot place the phone's Compose faceplate on the
dashboard. The `CAR SKIN` switch controls the app-owned surfaces that the host
allows: browse artwork, now-playing artwork, metadata, and grid/list hints.

- `POCKET` is the default: aged four-tone LCD glass and dark pixel ink.
- `FIELD` is optional: chassis beige, safety orange, cyan, and dark instrument
  ink.
- The phone's brightness-driven `SKIN` switch remains independent.
- Some hosts ignore content-style hints. That changes presentation, never the
  library or playback behavior.

To change it, open LE FLAC on the phone, tap the `LF-1` nameplate to flip to the
rear panel, then select `CAR SKIN [POCKET] [FIELD]`. The active media session and
subscribed car browsers are refreshed immediately.

See Android's official notes on [content styles](https://developer.android.com/training/cars/media/create-media-browser/content-styles)
and [media artwork](https://developer.android.com/training/cars/media/create-media-browser/media-artwork).

## Build and install

The checked-in build uses:

| Component | Version |
|---|---:|
| App | 1.2.0 (version code 3) |
| Media3 | 1.10.1 |
| compile SDK | 36 |
| target SDK | 34 |
| Android Gradle Plugin | 8.10.1 |
| Gradle | 8.11.1 |
| Kotlin + Compose compiler | 2.2.21 |
| NDK | 27.0.12077973 |

Target SDK stays at 34 for the first sideloaded Honda e validation so this
feature does not opt the rest of the phone UI into unrelated platform behavior
changes. Before a Google Play update, raise and validate the target against the
current [Play target API requirement](https://developer.android.com/google/play/requirements/target-sdk).

```sh
scripts/fetch_glyph_sdk.sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.nogarbo.leflac/.MainActivity
```

`adb install -r` upgrades in place and preserves the app's preferences, play
history, and analysis cache only when the installed and replacement APKs use
the same signing key. A different debug key produces
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and requires an uninstall, which clears
app data. The GitHub test APK is development/debug signed; it is not a store
distribution build. The Glyph Matrix SDK is downloaded separately because
Nothing's license does not allow this repository to redistribute it.

## One-time phone preparation

Do this while parked:

1. Install and open LE FLAC once.
2. Grant **Music and audio** when Android asks. A car screen cannot grant phone
   runtime permissions.
3. Confirm at least one supported local file appears in the phone ledger.
4. In the Android Auto phone settings, enable developer mode and **Unknown
   sources** for a directly sideloaded build. A Play internal-test install does
   not need this sideload exception.
5. Leave battery optimization at its normal setting initially; Media3 owns the
   foreground media session while playback is active.

Android documents the sideload and test path in [Test Android apps for cars](https://developer.android.com/training/cars/testing).

## Honda e first live run

Honda's owner material describes projected Android Auto over the car's USB data
connection. Plan the initial run as wired, even if a phone also offers wireless
Android Auto.

1. Park the car and keep it parked for setup.
2. Use a short, USB-IF-certified USB 2.0-or-better **data** cable, not a
   charge-only cable.
3. Connect to the Honda e's Android Auto-capable front USB port.
4. Accept the phone and Honda CONNECT first-run prompts.
5. Open the Android Auto app launcher and select **Le FLAC**.
6. Confirm the root opens without first bringing the phone Activity to the
   foreground.
7. Confirm the default artwork is POCKET.
8. Browse Albums, start a song, and test play/pause, scrub, next, previous, and
   a steering-wheel command.
9. Start a long mix and confirm next/previous remains within the mixes queue.
10. While a song is playing, use the parked phone to hold one song row, tap a
    second row, press `[QUEUE]`, and confirm the host queue (when shown)
    lists them in that order. Steering-wheel NEXT must reach both before the
    normal rail resumes.
11. Switch ORDER/RNG on the phone and confirm the two explicit entries remain
    ahead of the rebuilt rail.
12. On the parked phone, move `CAR SKIN` to FIELD and return to the car UI;
    confirm app-owned art refreshes. Put it back to the preferred setting.

Honda publishes owner manuals from its [official manuals and guides page](https://www.honda.co.uk/cars/owners/manuals-and-guides/honda-owners-manuals.html).
Android Auto intentionally abstracts OEM differences, so there is no Honda-only
code path in LE FLAC. Phone-side library integration is verified; Honda e head
unit, steering-wheel, voice, day/night, and rotary acceptance remain pending
until this live matrix is completed.

## Live acceptance matrix

Run every item while parked unless it explicitly tests an interruption:

- **Discovery:** Le FLAC appears once, not once per controller/session.
- **Cold start:** force-stop the phone app, then open it from Android Auto.
- **Permission denied:** after a clean-data test, the car shows the parked-safe
  phone setup instruction instead of crashing or showing an endless spinner.
- **Library:** root content loads promptly; no empty Hot now dead end; folders,
  mixes, and track metadata are correct.
- **Voice:** “Play _track_ on Le FLAC” and a broad “Play music on Le FLAC” both
  resolve locally.
- **Transport:** play/pause/seek/next/previous and steering-wheel controls track
  the phone UI.
- **Audio focus:** navigation guidance ducks or pauses cleanly; playback
  recovers afterward. An incoming call does not produce simultaneous audio.
- **Route loss:** unplugging USB or headphones triggers safe noisy-route
  handling; reconnecting does not autoplay unexpectedly.
- **Resume:** disconnect/reconnect and process recreation retain the last item
  and a sensible position without starting playback on connection; pending UP
  NEXT IDs retain order and missing files are discarded.
- **Priority queue:** phone long-press selection commits A then B as
  `CURRENT → A → B → ORDER/RNG`; rail changes preserve A/B, removal and clear
  update the car's standard queue, and a play-now selection clears the schedule.
- **Visuals:** POCKET is the fresh-install default; FIELD is selectable; text
  and host-tinted icons remain readable in day and night modes.
- **Existing app:** phone FIELD/POCKET behavior, smart queues, local analysis,
  mixes, and the Glyph Toy still work.

The official quality and safety gates live in the [car app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality?category=media).

## Desktop Head Unit preflight

Use the current DHU from Android Studio's SDK Tools before the live-car run.
With DHU 2.x and a USB-connected phone, Android recommends accessory mode:

```sh
desktop-head-unit --usb
```

The legacy tunnel remains useful when accessory mode is unavailable:

```sh
adb forward tcp:5277 tcp:5277
desktop-head-unit
```

Exercise touch, rotary/controller input, day/night, narrow/wide profiles, phone
calls, navigation focus, USB disconnect, and a cold process. See the official
[DHU guide](https://developer.android.com/training/cars/testing/dhu).

## Diagnostics

```sh
# Discovery and service declaration
adb shell dumpsys package app.nogarbo.leflac

# Active Media3/legacy session state
adb shell dumpsys media_session

# Focused service logs
adb logcat -s SessionLifecycleService MediaSessionService MediaLibraryService FLAC_DEBUG

# Confirm Android Auto can see sideloaded media apps only after developer setup
adb shell am force-stop app.nogarbo.leflac
```

If the app does not appear, check Android Auto **Unknown sources**, reconnect
USB, and verify both `androidx.media3.session.MediaLibraryService` and
`android.media.browse.MediaBrowserService` appear in the package dump. If the
root says phone setup is required, unlock the phone and grant Music and audio.
If art does not change immediately, back out one browser level; the service
notifies subscribed nodes, but some OEM hosts cache artwork until navigation.

## Architecture notes

- `LocalAudioLibrary` owns the shared MediaStore query and an observer-backed
  service cache; `LibraryViewModel` uses the same query without changing the
  original phone sort order.
- `AndroidAutoLibrary` creates stable browse IDs, local resource artwork URIs,
  content hints, search results, and playable queues.
- `AudioService` remains the single playback owner and now hosts one
  `MediaLibrarySession`. ID-only and host-delivered voice-query requests are
  resolved before they reach ExoPlayer.
- Marked UP NEXT items live inside the ExoPlayer timeline, are published by
  `PlaybackBus`, and are persisted as ordered MediaStore IDs for resumption.
- Missing permission is a valid library state, not a service failure.
- The manifest declares projected Android Auto media support only. It does not
  claim a native Android Automotive OS activity or the beta templated-media
  category.

The implementation follows Android's production [MediaLibraryService guidance](https://developer.android.com/media/media3/session/serve-content)
and [Android Auto media manifest guidance](https://developer.android.com/training/cars/media/configure-manifest).
