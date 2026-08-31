# Controller UI validation — 2026-08-31

## Source state
Root start: `35924ef` (`fix: finalize achievements recovery and clean exit`)
Root final: `8876cdf6de050da35ab744c2e457b14c4a29fa0c`
Branch: `recovery/ingame-menu-fix`
APK SHA: `bc48438fa7ef12757e06dd4a9ca378875c0f6e4757f38b9f2a500837a556c98f` (`assembleStandardDebug`)
Device under test (only): OnePlus Pad 2 — `OPD2403` / `OP5DAAL1`, ADB serial `adb-7d6afed8-mU47CV (2)._adb-tls-connect._tcp`  
Phone / emulator / Xiaomi duchamp: **not used** (user instruction: test only on OnePlus Pad 2). Never ran `adb kill-server` after that instruction.

## Code audit
Controls screen entry: Settings → `Controls` → `ControllerSettingsScreen` (`AppNavHost` route `controls`, Settings wide pane, in-game menu).
Input device enumeration: `ControllerDeviceRepository` — gamepads/joysticks + non-virtual keyboards; filters OEM system noise (`pmic_*`, haptics, gpio, pogo, stylus, etc.).
Current mapping storage: **`ControllerProfile` / `ControllerProfileRepository`** (`controller_profiles_v1`) is the live truth for remap + `GamepadMapper` + `RPCSXActivity`.
Backend mapping truth: single virtual pad via `overlayPadData` (player 0 only). No multi-pad assignment API.
Profile system: per-device via stable `deviceKey` (descriptor → VID/PID/name → meta → transient id); legacy `InputBindingPrefs` seeds defaults without discarding bindings.
Live input state flow: `ControllerInputMonitor` (Test/Capture) → throttled UI state (~33ms) → hotspot/list highlight.

## New architecture
Classifier: `ControllerClassifier` (VID/PID + name heuristics → `ControllerFamily`).
Stable device key: `ControllerDeviceKey.stableKey`.
Layout resolver: `ControllerLayoutResolver` → asset path + hotspot IDs.
Asset registry: `app/src/main/assets/controllers/controller_{ps,xbox,switch,generic,keyboard}.svg`.
Profile repository: `ControllerProfileSelection` + `ControllerProfileRepository.loadForDevice`.
Test mode: Mapping/Test tabs + live stick/trigger meters; Advanced for deadzone/sensitivity/invert/threshold. Player assignment omitted (backend unsupported).

## Asset implementation
SVG files added: `controller_ps.svg`, `controller_xbox.svg`, `controller_switch.svg`, `controller_generic.svg`, `controller_keyboard.svg`.
Hotspot naming: `btn_*`, `stick_*`, `trigger_*`, `touchpad`, keyboard `key_*` (see worker list).
Why SVG chosen: repo-local, scalable, stable IDs for Compose hotspot binding; no production hotlinked PNGs.

## Device support
PlayStation: classifier + PS asset + labels (unit + asset gates; no DualSense attached on Pad 2).
Xbox: classifier + Xbox asset (unit + asset gates; no Xbox pad attached).
Nintendo: Switch asset + Nintendo VID/name heuristics (unit + asset gates).
Keyboard: dedicated `controller_keyboard.svg` + keyboard defaults (unit tests; no external keyboard on Pad 2 after OEM noise filter).
Generic: fallback asset when unknown / no device selected (verified on Pad 2).

## Multi-controller behavior
Device strip: FilterChip strip of connected classified devices.
Player assignment: **omitted** — Advanced tab states overlay pad is player 0 only.
Reconnect behavior: profiles keyed by stable `deviceKey` (unit-tested).
Profile persistence: SAVE AS / LOAD / DUPLICATE / RESET DEFAULT / DELETE on Profiles tab (verified on Pad 2).

## Auto-mapping
Family detection: `ControllerClassifier`.
Default mappings: `FamilyDefaultMappings` merge-with-legacy (never drops user bindings).
Conflict handling: `MappingConflictResolver` REPLACE / SWAP / CANCEL (unit-tested).

## Keyboard
Layout: dedicated keyboard visual path (`KeyboardVisual`), never gamepad silhouette.
Defaults: WASD / face / shoulder key map in `FamilyDefaultMappings.keyboardDefaults`.
Live highlight: pressed hotspot set from `LogicalPadState`.
Persistence: per-device profile with `ControllerFamily.KEYBOARD`.

## Validation pass 1
Device: OPD2403 only, final debug APK installed via `adb install -r`.
Observed:
- Settings → Controls opens CONTROLS pane with device strip / Mapping|Test|Profiles|Advanced.
- Empty strip shows “No devices” + GENERIC `controller_generic.svg` when no external pad/keyboard.
- Profiles SAVE AS persists a named profile.
- Advanced shows player-assignment omission + stick/trigger tuning.
- Remap capture mode activates on mapping-row long-press (“Press a physical input…”).
Artifacts under `docs/testers/artifacts/2026-08-31-controller-ui/` (Pass 1 then overwritten by Pass 2 on unchanged APK).

## Validation pass 2
Repeated critical navigation on the **same unchanged APK** (Controls open, strip/generic layout, Test/Profiles/Advanced, profile save, capture-mode remap prompt). No code changes between Pass 1 and Pass 2 runs of `pad2_controls_validate.py`.

## Screenshots
| File | Status on OPD2403 |
|---|---|
| `01-device-strip.webp` | Captured |
| `02-ps-layout-mapping.webp` | **Not captured** — no DualSense/PS pad attached |
| `03-ps-layout-test.webp` | Captured (Test tab on available surface) |
| `04-xbox-layout-mapping.webp` | **Not captured** — no Xbox pad attached |
| `05-keyboard-layout-mapping.webp` | Captured mapping surface; external keyboard absent after OEM filter → GENERIC empty-strip state; keyboard asset/defaults proven by unit/asset tests |
| `06-remap-capture-dialog.webp` | Captured capture-mode prompt; REPLACE dialog flaky via adb keyevent without focus — conflict logic unit-tested |
| `07-profile-management.webp` | Captured |
| `08-player-assignment.webp` | Captured Advanced omission message |
| `09-phone-portrait-layout.webp` | Captured on Pad 2 with `user_rotation=0` (not a phone) |
| `10-tablet-landscape-layout.webp` | Captured |
| `11-reconnect-persisted-profile.webp` | Captured after SAVE AS |
| `12-unknown-controller-fallback.webp` | Captured GENERIC fallback |

See also `{SCRATCH}/manual-validation-blocked.txt`.

## Automated tests
`./gradlew :app:testStandardDebugUnitTest` (incl. `com.zenithblue.sambas3.input.*`):
- `ControllerClassifierTest`
- `ControllerDeviceKeyTest` (incl. system-noise filter)
- `ControllerLayoutResolverTest`
- `ControllerProfileSelectionTest`
- `FamilyDefaultMappingsTest`
- `MappingConflictResolverTest`
- `ControllerAssetsPresenceTest`
- existing `GamepadMapperTest`

Logs: `{SCRATCH}/controller-unit-tests.log`, `{SCRATCH}/assemble-standard-debug.log`, `{SCRATCH}/controller-assets.txt`, `{SCRATCH}/controller-ui-structure.txt`.

## Remaining risks
- Physical DualSense/Xbox/external keyboard not present on OPD2403 during this run; family layouts for those devices rely on unit/asset gates until hardware is available.
- Remap confirm dialog via synthetic `adb input keyevent` is focus-sensitive; production path uses Activity `dispatchKeyEvent` + Compose capture when a real key/button is pressed.
- OEM devices that look like keyboards but are not in the noise filter list could still appear in the strip.
