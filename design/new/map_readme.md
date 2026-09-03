Here it is — the contributor handoff README, written to sit at the repo root. It maps all 35 files to owners and responsibilities, explains the demo-mode toggle, and walks the exact build path from empty checkout to installed APK.

````markdown
# Samba S3

> **The complete Android frontend for a PS3 emulation environment.**
> Game library · firmware & driver management · per-game config · GPU drivers ·
> controllers & touch overlay · patch manager · log monitor · in-game runtime.
>
> Cut, soldered & lacquered by hand. Design language: **Bossa Noir** (dark) /
> **Copacabana Day** (light) — see [doc index](#documentation).

---

## The one thing to know first

**This app runs completely without an emulator core.**

If `libsambacore.so` is absent, `bestBridge()` (file 29) installs a scripted
`FakeCoreBridge` and the app boots into **demo mode**: a full 12-record
library, the setup wizard, SAF scanning, the firmware install stepper, the
needle-drop launch ritual, live VU meters, the intermission menu, quick rack,
and a streaming log scope — all real UI, real navigation, real persistence,
scripted core. Every screen you can reach is the real screen.

When the core lands, it plugs into **one header file** (§ [The Seam](#the-seam)).
Nothing above the seam changes. Nothing below it knows Compose exists.

```kotlin
// the entire mode switch — samba/s3/data/NativeBridge.kt
fun bestBridge(context: Context): EmuCoreBridge =
    if (NativeCore.available) NativeCoreBridge() else FakeCoreBridge(Demo.bootScript())
```

---

## Repository map

35 source files, four packages. Every file has one job and one owner.

### `samba.s3.design` — the constitution (files 00–10 + Tokens)

Nobody builds UI primitives outside this package. Components may only read
tokens; screens may only compose components. That's the whole law.

| File | Owns | Notes |
|---|---|---|
| `Tokens.kt` | All design tokens — both palettes, type, radii, motion, the theme dimmer | **Source of truth.** From D-002 §5. Byte-identical to the doc. If a value isn't here, it doesn't exist |
| `00 TokensDelta.kt` | Font-family handles, banner/LED color resolvers, wordmark style | Kept separate so Tokens stays doc-identical |
| `01 BossaKit.kt` | Squircle shape · grain tile · haptics map · LED · the Wave | Squircle is **game-art-only** — resist the urge |
| `02 CordaIcons.kt` | Icon set part 1 (24 glyphs) | 24dp grid · 1.75 stroke · no gears |
| `03 Meters.kt` | VU full/strip/led · sparkline · EQ loader | Needle physics reference lives in core (`DampedNeedle`) |
| `04 Buttons.kt` | Primary/ghost/key/danger · **hold-to-confirm** | Hold = 800ms ring, always |
| `05 Controls.kt` | Rocker · tri-state · fader w/ detents · source selector · chip | Fader readout floats — parent must not clip |
| `06 Surfaces.kt` | GlowCard · sheet · dialog · toast (always noir) · banner · stepper | |
| `07 Content.kt` | Game tile/row · amp card · encore empty states | |
| `08 Chrome.kt` | Wordmark · marquee/ticker · deck · scaffolds (single + two-pane) | |
| `09 Settings.kt` | Channel groups · setting rows · option sheets | The channel-strip motif |
| `10 Runtime.kt` | Launch ritual · HUD strip · intermission · quick rack | **Always Bossa Noir** regardless of theme |
| `32 CordaFinal.kt` | Icon set part 2 (final 10 glyphs, set complete at 58) | Manifest in D-006 §32 |

### `samba.s3.app` — the screens (files 11–23, 28, 30–31)

| File | Owns | Notes |
|---|---|---|
| `11 State.kt` | Domain models · `AppHealth` → attention LED reducer | Pending-changes facade: use the one in file 28 |
| `12 Inputs.kt` | `BossaField` (the recessed jack) · search field | Errata #4 applied |
| `13 IconsMore.kt` | Screen-supporting glyphs | |
| `14 CrateScreen.kt` | Library home — stage rail, grid⇄list, filters, quick sheet | |
| `15 SleeveScreen.kt` | Game detail — **Redline A transcribed** | Condensed-header hysteresis: arms 240dp, releases 200dp |
| `16 EmuSettings.kt` | The declarative settings catalog + one page renderer + presets + per-game tri-state | §5.5 as data — edit the catalog, not the renderer |
| `17 PartsScreens.kt` | Amp room · firmware · stitching room · the cast | |
| `18 ScopeScreen.kt` | Log monitor w/ follow pill | Scope never shows the wave (§3.1) |
| `19 PadScreens.kt` | Band list · remap diagram · test bench | |
| `20 WizardScreen.kt` | First-night onboarding, 7 steps | |
| `21 Blueprint.kt` | Overlay editor screen + runtime touch overlay | Engine is in core; this wraps it |
| `23 Router.kt` | Hand-rolled nav, deep links (`samba://game/{id}`) | Superseded as root by file 30 — keep for the Router class + tests |
| `28 ViewModels.kt` | Prefs · GameStore (JSON persistence) · Session/Scan/Install holders · `AppViewModel` | The single tree root |
| `30 AppHost.kt` | `MainActivity` · `SambaRoot` · `AppTree` (production routing) · `SessionLayer` · `ScanScreen` | |
| `31 DemoData.kt` | Scripted everything — games, drivers, patches, boot/log/scan scripts | Delete when the core is real (or keep for screenshots) |
| `33 Assets.kt` | Wizard art spots (part c) | Synth & fonts live elsewhere (see below) |

### `samba.s3.core` — pure logic, framework-free (file 24)

Runs on the JVM in milliseconds. **This is what the test suite pins.**

| Owns | Contents |
|---|---|
| Meter math | peak-hold decay, red zone, VU a11y strings |
| Needle physics | `DampedNeedle` — spring 170 / ζ 0.75, the reference model |
| Snap math | pixel lattice, detents (3% window, ties → lower) |
| Superellipse | squircle point/residual math |
| Parsers | `SfoParser` (PARAM.SFO) · `Pup` (SCEUF magic) |
| `RingBuffer` | the scope's 50k-row guarantee |
| Game rules | serial regex · EBOOT detection · skip reasons |
| `PendingChangesCore` | §6.7 semantics — dedupe, revert-to-original drops |
| `BlueprintEditor` + `OverlayLayout` | overlay model, snap/clamp/undo(20)/hit-test engine |
| `OverlayJson` | layout serialization round-trip |

### `samba.s3.data` — the world outside Compose (files 25–27, 29)

| File | Owns | Notes |
|---|---|---|
| `25 Scanning.kt` | SAF tree walker (`DocumentsContract`), SFO-backed game detection | Never uses `findFile` — O(n²) |
| `26 Transfer.kt` | Fetch · sha256 · PUP verify · install pipeline · foreground service · driver catalog | |
| `27 Bridge.kt` | `EmuCoreBridge` contract · `LogPipeline` + `LogParse` · `FakeCoreBridge` | |
| `29 NativeBridge.kt` | **The Seam** — JNI contract, `NativeCore`, real/fake selector | See below |

### `samba.s3.sound`

| File | Owns | Notes |
|---|---|---|
| `33a` (in Assets pack) | `BossaSynth` + `Wav` — the five UI sounds, synthesized from math | Writes to `filesDir/sounds` on first run; `res/raw` optional |

### Tests

| Location | What |
|---|---|
| `src/test/` (17 classes) | Meters · needle · snap · squircle · SFO · PUP · ring · game rules · hashing · presets · pending · blueprint · overlay JSON · log parse/pipeline · attention reducer · router |
| `src/androidTest/` | Compose semantics: VU a11y, rocker toggle, fader range, **hold-button timing** (300ms ≠ fired, 1000ms = fired), 9+ badge, LED a11y |
| `ContrastLint.kt` (file 22b) | The build gate — all token pairings ≥ 4.5:1 text / 3:1 glyphs. **Fails the build, not the review** |

### On disk

| Path | Contents |
|---|---|
| `res/font/` | 4 font binaries (fetched — see build steps) |
| `filesDir/sounds/` | 5 generated WAVs |
| `filesDir/library.json` | The crate (user favorites/stats survive rescans) |
| `filesDir/firmware/` | PUP + extracted |

---

## The Seam — core team instructions

Everything the emulator core needs to implement is one header,
`samba/s3/data/NativeBridge.kt` (comment block, file 29). Summary:

```c
nativeVulkanAvailable() -> jboolean
nativeDeviceVerdict()   -> jint        // 0 main stage · 1 standing room · 2 no vulkan
nativeBoot(gameId)      -> jlong       // handle
nativePoll(handle)      -> jstring     // polled ~60Hz, NULL when idle:
                                       //  "B:<line>"  boot line  → ritual ticker
                                       //  "R"         ready      → ritual ends
                                       //  "P:<fps>;<cpu>;<gpu>"   → HUD + meters
                                       //  "L:<log>"   log        → the scope
                                       //  "E:<msg>"   fatal      → banner + scope
nativePause/Resume/Exit(handle)
nativePushInput(handle, control, value)
nativeDecryptPup(pup, outDir) -> jint  // 0 = ok
nativeInstallFirmware(dir)   -> jint  // 0 = ok
```

Build it as `libsambacore.so`, drop it in `app/src/main/jniLibs/<abi>/` — the
selector notices it on next launch. That's the whole integration.
*Neither side knows the other's name.*

---

## Build steps

**Prerequisites:** JDK 17 · Android SDK 34+ · a device or emulator (API 26+).

```bash
# 1 · clone
git clone https://github.com/JICA98/samba-s3 && cd samba-s3

# 2 · fonts (the app bundles them — emulator apps work offline)
sh scripts/fetch_fonts.sh

# 3 · sounds — generated on first run by BossaSynth; to pre-generate in CI:
#    (optional) a small JVM runner that calls BossaSynth.writeAll(dir)

# 4 · build & install — demo mode until a core exists
./gradlew assembleDebug
./gradlew installDebug

# 5 · the test suite (JVM, fast — run this before every push)
./gradlew test

# 6 · the contrast gate (fails the build on any bad pairing)
java -jar lint.jar            # or wire as a CI step before ./gradlew test

# 7 · instrumented semantics (device/emulator)
./gradlew connectedAndroidTest
```

**First run flow:** wizard (7 steps) → crate with 12 demo records → tap a
sleeve → *play* → the needle drops. Everything you touch is real.

**Demo-mode tells** (all in file 31, one place to change): `Demo.games()`,
`Demo.bootScript()`, `Demo.logScript()`, `Demo.statsFor()`, and the
"demo core · surface" stamp on the runtime surface.

---

## Conventions (the short version)

1. **No component hardcodes a color, font, radius, or curve.** Tokens only.
2. **Screens are assembly.** If you're writing a `drawRoundRect` in a screen,
   it belongs in `design`.
3. **One Primary Lit button per screen.** One serif eyebrow per region.
4. **Destructive = hold-to-confirm (800ms).** No confirm dialogs.
5. **Glow only on powered-on things.** Game running, active driver, lit controls.
6. **The runtime is always Noir.** The needle drop always happens in the dark.
7. **Light theme is a token swap.** If your component branches on `isLight`
   beyond the documented cases, stop — it's a palette problem.
8. **New setting?** Add a row to `EmuCatalog` (file 16). The renderer, pending
   changes, presets, and per-game tri-state all pick it up for free.
9. **New screen?** Use `BossaScaffold` (phone) or `BossaTwoPaneScaffold`
   (≥600×480dp), pick a deck, pick a domain tint, done.
10. **Every state changes a token, every LED has a label, every list has an
    encore.** If a state isn't drawn, it doesn't ship.

Full rules: SS3-D-001 §1.2 (the ten rules) · §10 (ship gate, 20 items).

---

## Documentation

| Doc | Contents |
|---|---|
| **SS3-D-001** | The design spec — concept, tokens, all components, 17 screens, a11y, lexicon, QA gate |
| **SS3-D-002** | Copacabana Day (light) · Redlines A (Sleeve) & B (Blueprint) · two-pane atlas · Compose token file |
| **SS3-D-003** | Component library, files 00–10 + runtime |
| **SS3-D-004** | Screens & wiring, files 11–23 |
| **SS3-D-005** | Pure seams, scanner, transfer, bridge, test suites |
| **SS3-D-006** | ViewModels, the Seam, app host, demo data, 58-glyph set, assets, errata applied |

All errata across D-003…D-006 are **applied** — the tables in each doc are
historical. When in doubt: **the code in the latest pack wins, then the docs.**

---

## Lexicon (read this before writing copy)

| App-speak | Means |
|---|---|
| the crate | game library |
| on stage | running / active |
| the needle drop | launching a game |
| intermission | pause menu |
| tuning | changing settings |
| the amp room | GPU driver manager |
| encore | empty state (never in body copy) |

Body copy stays plain. Personality lives in eyebrows, stamps, and empty
states — never in labels, errors, or destructive confirms.

---

## Status

| Area | State |
|---|---|
| Design system (2 themes, 58 icons, all components) | ✅ complete |
| Screens (all five decks + wizard + sleeve + blueprint + scan) | ✅ complete |
| Logic seams + tests (17 JVM + 8 instrumented + contrast gate) | ✅ complete |
| Data layer (SAF scan, download/verify/install, logs) | ✅ complete |
| Emulator core (`libsambacore.so`) | ⏳ external — one header away |
| Assets (fonts · sounds) | ⏳ one script / one synth run |
| Device QA pass (§10, 20 items, screenshot-diff) | ⏳ |

---

*Same studio, daylight hours. Cut on the dotted line.*
````

That's the full handoff. The map covers all 35 files with owners, the demo-mode story is up top (since it's the first thing a contributor needs to understand), the build path runs checkout → fonts → build → test → contrast gate → install in copy-pasteable order, and the conventions section condenses the ten rules into the ten things that actually stop bad PRs.

The three deliberate omissions, in case you want them added later: a screenshots section (needs the app built and running — placeholder until the QA pass produces both-theme captures), a CHANGELOG, and license/attribution text for the four Google Fonts (all OFL — one line each in a THIRD_PARTY file whenever you're ready to publish).

Anything else, or is the program closed?
