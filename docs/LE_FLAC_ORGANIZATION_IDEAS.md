# LE FLAC Organization Ideas

Date: 2026-06-11  
Focus: how to make LE FLAC expressive, dense, and playful while still easy to learn.

## Core Thought

Teenage Engineering products often look chaotic at first glance. Pocket Operators, OP-1, OP-Z, and EP-133 K.O. II all have surfaces full of labels, abbreviations, color, modes, and odd symbols. But they are learnable because the underlying grammar is consistent.

LE FLAC should follow the same principle:

> Messy in texture. Strict in grammar.

The app already has enough personality. The next step is not to add more style. The next step is to organize every visual and interaction choice into a small set of rules.

## The Device Model

Think of LE FLAC as a physical object with four zones.

### 1. Faceplate

The daily playback surface.

Contains:

- Current track.
- Playback state.
- Scrubber.
- Transport controls.
- Time.
- One main expressive visual.

Rule: the faceplate is for now. If something is not about the current listening moment, it should probably move elsewhere.

### 2. Ledger

The collection surface.

Contains:

- Library.
- Albums/folders.
- Mixes.
- Hot Now.
- Search/SCAN.
- Command results.

Rule: the ledger is for choosing. It should be calmer and more readable than the faceplate.

### 3. Back Panel

The manual/configuration surface.

Contains:

- Operator's manual.
- Command legend.
- DIP switches.
- Skin mode.
- Voice.
- Serial number.
- Runtime.
- Version.

Rule: anything advanced, rare, explanatory, or persistent belongs on the back.

### 4. Glyph Deck

The external display surface.

Contains:

- Turntable.
- Cassette.
- Sleep clock.
- Shutter.
- Glyph button behavior.
- Matrix preview/state.

Rule: the Glyph system should be treated as a second screen, not a decorative animation.

## Main Screen Structure

The front screen should use a clear vertical stack:

```text
SYSTEM STRIP
small: power, mode, glyph state, clock

NOW PLAYING
large: title, artist, state, one expressive visual

TRANSPORT DECK
scrubber, time, play, previous, next, random

LEDGER
library, mixes, albums, SCAN
```

This gives every part of the app a job. It also prevents the current issue where telemetry, toy controls, title, scrubber, buttons, and library all compete at the same time.

## One Thing Loud At A Time

The app can be expressive, but only one element should be dominant in each state.

Examples:

- Idle: title is calm, sleep/Glyph state is visible.
- Playing: scrubber and play state are active.
- Drop incoming: telemetry fades, dramatic state takes over briefly.
- Mix playing: cassette is the hero.
- Toy mode: Glyph preview is the hero.
- Library browsing: ledger becomes the hero, playback compresses.

This is the main editorial rule.

## Color Grammar

Keep color meanings strict.

### Orange

Use for:

- Primary action.
- Current playback.
- Selected item.
- Hot/dramatic energy.
- Active hardware state.

Do not use orange as general decoration.

### Cyan

Use for:

- Analysis.
- Glyph state.
- Secondary machine signal.
- Spectral/technical information.

Do not use cyan for primary commands if orange already owns action.

### Ink / Black

Use for:

- Main readable content.
- Track titles.
- Important labels.

### Dim Grey

Use for:

- Engraved labels.
- Inactive scaffolding.
- Secondary metadata.

Dim grey must still be readable. It should feel quiet, not invisible.

### Beige / LCD Green

Use as material:

- Chassis.
- Screen glass.
- Background.

These colors should not carry meaning by themselves.

## Type Grammar

Use typography by role, not by vibe.

### Current Track Display

Big, expressive, allowed to glitch.

Use only for:

- Current title.
- Maybe current mix name.

### Machine Labels

Small caps / monospace.

Use for:

- `SCAN`
- `FIELD`
- `POCKET`
- `GLYPH`
- `PWR`
- `AUTO`
- `RNG`
- Telemetry labels.

### Library Text

Readable first.

Use for:

- Track titles.
- Artists.
- Album/folder names.

This should be calmer than the current big glitch language. The library is where people scan, compare, and choose.

### Numeric Displays

Segment/LCD style.

Use only for:

- Time.
- Duration.
- Maybe cue count or BPM.

### Etched Text

Tiny technical text.

Use for:

- Rear panel.
- Serial.
- Firmware.
- Hardware labels.
- Advanced metadata.

Do not put etched text everywhere. It loses value.

## Control Grammar

Every control shape should imply a behavior.

### Isometric Square Buttons

Use for immediate transport actions:

- Play/pause.
- Previous.
- Next.
- Random jump.

These are "press now" controls.

### Two-Rail Transport

The current code has an important idea that should stay: playback can move on two different rails.

#### ORDER Rail

The ORDER rail is deterministic.

It means:

- The next track is the next item in the library/album/folder order.
- The previous track is the previous item in that same order.
- Inside a long mix, previous/next can mean previous/next cue.
- This is the predictable rail.

Current code:

- `playNextSequential(...)`
- `playPreviousSequential(...)`
- cue-aware behavior for long mixes.

#### RNG Rail

The RNG rail is not dumb shuffle. It is a generated future.

It means:

- Songs shuffle with songs.
- Mixes shuffle with mixes.
- Gym mode narrows the pool.
- Hot tracks are re-injected.
- Same-track and same-artist repetition is avoided.
- Favorites decay over time.

Current code:

- `generateSmartQueue(...)`
- `setAutoMode(...)`
- `playNextRandom(...)`

#### Control Rule

Do not hide the second rail. The UI should show both rails clearly.

Recommended front-panel controls:

```text
                 ┌ ORDER ┬ RNG ┐
[PREV]   [PLAY]  [NEXT]   [RNG]
```

But the meaning should be precise:

- `NEXT` follows the active rail.
- If `AUTO` is off, the active rail is ORDER.
- If `AUTO` is on, the active rail is RNG.
- `RNG` is a one-shot random jump when `AUTO` is off.
- `RNG` advances/regenerates the RNG future when `AUTO` is on.
- `AUTO` is a latch, not a transport action.
- Hold `PREV` or `NEXT` forces the ORDER rail as an override.

This keeps the original two-control idea but makes the metaphor learnable:

- `NEXT` means "continue."
- `RNG` means "surprise me once."
- `AUTO` means "keep surprising me."
- Hold means "override the current rail."

#### Best Latch Pattern

Do not show the latch by making `NEXT` transparent. Transparency reads as disabled, not rerouted.

A LoveFrom / Teenage Engineering treatment would keep the buttons physically stable and add a small mechanical selector above the rail it affects:

```text
                 ┌ ORDER ┬ RNG ┐
[PREV]   [PLAY]  [NEXT]   [RNG]
```

Why this is better:

- `NEXT` always looks pressable.
- `RNG` always looks pressable.
- The latch is visibly a mode selector, not another transport button.
- The selector physically spans `NEXT` and `RNG`, so the user learns that it reroutes the future, not playback itself.
- The active rail can be shown by fill, indicator notch, or small LED, not by making one button disappear.

Use language like:

- `ORDER` = album/library order.
- `RNG` = smart random future.

Avoid `AUTO RNG` as the main visible label. It describes implementation, not the user's mental model. The user cares whether the future is ordered or generated.

Color-code the latch by rail:

- `ORDER` is ink/neutral, because it is the printed, deterministic rail.
- `RNG` is cyan, because it is generated signal and machine intelligence.
- The active rail is filled.
- The inactive rail remains outlined and readable.
- The transport buttons themselves do not fade; fading means disabled.
- Orange remains reserved for current/action/hot/selected.
- Cyan can also carry deck signal, such as analysis readouts and time, when it is not competing with primary action.

Composition rule:

- The latch should span `NEXT` and `RNG`, because it changes the future path.
- It should not sit over `PLAY`, because play/pause is not affected by rail choice.
- It should feel like a small mechanical selector printed on the transport plate, not a floating mode badge.
- The system strip can echo `RAIL: ORDER` or `RAIL: RNG`, but the latch is the physical truth.

#### Mix Exception

In a mix, the rail labels should become cue-aware:

```text
[CUE-]   [PLAY]   [CUE+]   [RNG]
                 [ ORDER ┬ RNG ]
```

Meaning:

- Tap `CUE-` / `CUE+` jumps between cue points inside the mix.
- Hold `CUE-` / `CUE+` exits the cue layer and forces previous/next mix file on the ORDER rail.
- `RNG` chooses another mix, not a song.
- `AUTO` means the future queue is random mixes.

This matches the product metaphor: a mix is a cassette with internal marks; the library is the shelf.

### DIP Switches

Use for persistent settings:

- Skin mode.
- Voice.
- Reduced motion.
- Glyph behavior.

These are "set and leave" controls.

### Ledger Rows

Use for selecting music:

- Track.
- Mix.
- Folder.
- Hot item.

Tap means play or enter. Long press can reveal service details.

### Prompt Field

Use for command/filter:

- `.hot`
- `.gym`
- `.mix`
- `.rng`
- plain search.

The prompt is excellent. Keep it.

### Nameplate

Use for flipping the unit:

- `FD-1` opens the rear panel.

This is a good physical metaphor.

### Glyph/Toy Control

Use for external display state, but do not make it a front-screen hero.

- Indicate readiness in the system strip.
- Keep full Toy behavior on the hardware/back-panel/manual side.
- Do not let a matrix preview compete with track identity.

This should read as "second screen," not "fun overlay."

## Labeling Suggestions

Some labels are charming but less learnable than they could be.

Earlier simplified style to avoid:

```text
[<]   [>]   [>]   >RNG
AUTO RNG
```

Use this grammar, preserving the two-rail behavior:

```text
                 ┌ ORDER ┬ RNG ┐
[PREV]   [PLAY]  [NEXT]   [RNG]
```

If keeping symbols, the same rail latch still spans the future controls:

```text
              ┌ ORDER ┬ RNG ┐
[|<]   [>]    [>|]    [RNG]
```

The app can still use TE-style abbreviations, but the same abbreviation should always mean the same action.

Do not make `AUTO RNG` look like a second play button. Use the `ORDER/RNG` rail latch instead.

## Mode System

Make modes explicit in the system strip.

Examples:

```text
FIELD · SONG · GLYPH: TURNTABLE
FIELD · MIX · GLYPH: CASSETTE
POCKET · GYM · GLYPH: PULSE
REAR · CONFIG
```

This helps the app feel like an instrument. The user should always know:

- What skin they are in.
- What playback mode they are in.
- What the Glyph deck is doing.
- Whether they are browsing, playing, configuring, or inspecting.

## Suggested State Layouts

### Idle

Priority:

1. Track/library context.
2. Sleep/Glyph state.
3. Clock.

Behavior:

- Minimal motion.
- Glitch is reduced.
- Transport is visible but quiet.

### Playing

Priority:

1. Track title.
2. Scrubber and time.
3. Play/pause.
4. Library below.

Behavior:

- Scrubber is active.
- One music-reactive element is active.
- Telemetry remains compact.

### Drop / Dramatic Segment

Priority:

1. Dramatic state.
2. Current track.
3. Haptic/event moment.

Behavior:

- Short expressive event.
- Return to calm quickly.
- Avoid permanent chaos.

### Mix

Priority:

1. Cassette identity.
2. Cue map.
3. Mix duration/resume.

Behavior:

- Previous/next should clearly mean cue navigation when inside a mix.
- Cassette can become the hero.

### Library Browsing

Priority:

1. Ledger.
2. Search/SCAN.
3. Selected/playing item.

Behavior:

- Now-playing area compresses.
- Text readability beats visual effects.
- Rows become calm and consistent.

### Rear Panel

Priority:

1. Manual.
2. Config.
3. Identity.

Behavior:

- No player effects.
- No animation except flip.
- DIP switches should be finger-friendly.

## What To Move Or Reduce

### Move To Back Panel Or Service Mode

- Bitrate.
- File size.
- Deep play stats.
- Analysis failures unless actionable.
- Command legend.
- Rare settings.

### Keep On Front, But Compress

- Battery.
- Clock.
- Track count.
- Glyph state.
- Skin/mode.

### Keep Loud Only In Events

- Owl/drama overlay.
- Punch-in strobe.
- Bass shake.
- Heavy glitch.
- Large cassette/turntable preview.

## A Practical Next UI Pass

1. Define tokens for color roles, type roles, and control roles.
2. Replace hard-coded black/white/alpha assumptions with skin tokens.
3. Redesign the main screen into the four vertical zones.
4. Make the transport cluster readable before making it weird.
5. Convert technical metadata into service mode.
6. Add a mode strip at the top.
7. Make the Glyph deck status visible on the faceplate.
8. Give FIELD and POCKET the same layout but different material rules.

## The Target Feeling

The target is not minimalism. The target is disciplined density.

LE FLAC should feel like:

- A field recorder.
- A cassette deck.
- A Swiss instrument panel.
- A Nothing-specific hardware companion.
- A local music library with memory.

But it should behave like:

- Press obvious button.
- See obvious state.
- Browse readable list.
- Learn one grammar.
- Discover deeper rituals over time.

That is the sweet spot: expressive enough to love, consistent enough to trust.
