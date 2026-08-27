# SambaS3 — In-Game Menu Recovery Status & Next Steps

## 1. What Was Completed

### Phase 1: Launch Regression Fixed (`3bc4c15`)
- **Root Cause**: Backend commit `5629c55` had a compile error (`g_mainThreadProcessor` used before declaration in `emit_frontend_event` at `rpcsx-android.cpp:204`). Shipped APKs silently packaged an older `.so` lacking frontend menu symbols.
- **Patch fix**: `patches/rpcsx-submodule-changes.patch` was superseded by `5629c55`; updated `build_rpcsx.sh` to tolerate empty patch and emptied obsolete diff.
- **Submodule fix**: Forward-declared `emit_frontend_event` in `app/src/main/cpp/rpcsx` (`55efe2e`).
- **Device Proof**: Game cold launches to `Running`, Vulkan render active, frames visible on device (`7d6afed8`).

### Phases 2–5: Clean Architecture Refactor
- **`InGameMenuCoreGateway`** (`ui/ingame/InGameMenuCoreGateway.kt`): Gateway interface + `RpcsxInGameMenuCoreGateway` adapter on `Dispatchers.IO`. Composables never import `RPCSX`.
- **`InGameMenuCoordinator`** (`ui/ingame/InGameMenuCoordinator.kt`): Single session owner, `StateFlow<InGameMenuUiState>`, `SharedFlow<InGameMenuHostEffect>`, semantic `InGameMenuIntent` dispatch, exactly-once settings transactions, close policy table.
- **`InGameMenuInputRouter`** (`ui/ingame/InGameMenuInputRouter.kt`): Raw Android input -> `MenuCommand`, analog stick repeat state machine (`300ms` initial delay, `100ms` repeat cadence, deadzone edge reset).
- **`PhysicalInputTracker` & `GameplayInputGate`** (`ui/ingame/GameplayInputGate.kt`): Separate physical hardware state from emulated pad state. Neutral re-arm gate prevents held button/stick leaks.
- **Presentation**: `EmulationMenu.kt` rewritten to pure presentational Compose (state in, intents out). Removed all legacy menu classes (`InGameUiState`, `LegacyInGamePage`).
- **Settings/Trophies/Friends**: Migrated to gateway and coordinator intents.
- **Host Adapter**: `RPCSXActivity.kt` reduced to host lifecycle, effect collector, surface binding, and deferred listener registration.
- **Tests Added**:
  - `InGameMenuCoordinatorTest`: Open/close sessions, duplicate open gating, close reason policies, observable navigation, exact item-count selection bounds, settings transactions.
  - `InGameMenuInputRouterTest`: D-pad mapping, button mapping, repeat timing, deadzone reset.
  - `GameplayInputGateTest`: Physical held button/stick neutral gating.
  - `ClosePolicyTest`: Policy table verification.

### Backend Feature Triage & Fixes
- **Screenshot**: Fixed empty stub in `GraphicsFrame::take_screenshot` (`rpcsx-android.cpp`). Encodes RGBA/BGRA via `stb_image_write` to `config/screenshots/<Title>_<timestamp>.png` and emits `FRONTEND_EVENT_SCREENSHOT_RESULT` with file path. Added Toast in `RPCSXActivity`.
- **Recording**: Root caused to dead code upstream (flag `g_user_asked_for_recording` has no consumer in render path). Corrected capability to `recordingSupported = false` (row auto-hidden in Kotlin).
- **Save State Crash**: Root caused via log analysis. Save writes valid `.zst` file (14MB), but continuous-mode auto-restart crashes on restore (`Verification failed (object: 0x0)` in `id_map<lv2_obj>`). Corrected capability to `savestate.supported = false` until core serialization is repaired.

---

## 2. Next Steps Checklist

### Step 1: Finish Background Native Build & Package
1. Let native build finish: `librpcsx-android.so` -> `app/src/main/jniLibs/arm64-v8a/`
2. Build debug APK:
   ```bash
   ./gradlew :app:assembleStandardDebug
   ```
3. Install to device:
   ```bash
   adb -s 7d6afed8 install -r app/build/outputs/apk/standard/debug/samba-s3-standard-debug.apk
   ```

### Step 2: Device Validation Matrix
1. **Cold Launch**: Tap game -> verify boots to gameplay.
2. **PS / Menu Button**: Tap on-screen PS or menu toggle -> verify Kotlin Compose menu appears.
3. **Menu Navigation**:
   - Touch navigation on menu items.
   - Controller D-pad Up / Down / Cross (Activate) / Circle (Back).
4. **Main Actions**:
   - `Resume`: Hides overlay, re-arms input only after physical neutral.
   - `Take Screenshot`: Tap -> verify Toast "Screenshot saved: ..." and check file in `/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/screenshots/`.
   - `Restart Game`: Confirm dialog -> game reboots cleanly.
   - `Exit Game`: Confirm dialog -> graceful shutdown -> activity finishes back to launcher.
5. **Settings Transaction**:
   - Open Settings -> edit a value -> verify `SAVE` / `DISCARD` footer appears.
   - Back while dirty -> verify confirmation dialog (Save / Discard / Cancel).
   - Clean Back -> returns to Main menu.

### Step 3: Submodule Remote Reachability (Plan §19)
1. Push local submodule branch `samba-frontend-menu` (`55efe2e`) to a public/accessible fork:
   ```bash
   git -C app/src/main/cpp/rpcsx push <fork-url> samba-frontend-menu
   ```
2. Update `.gitmodules` URL if necessary (mirroring `libadrenotools` relative URL pattern `../../<org>/rpcsx.git`).
3. Verify clean clone reproducibility:
   ```bash
   git submodule sync --recursive
   git submodule update --init --recursive
   ```

### Step 4: Final Commit & Clean-up
1. Run lint and unit tests:
   ```bash
   ./gradlew :app:testStandardDebugUnitTest
   ```
2. Stage and commit Kotlin refactor:
   ```bash
   git add app/src/main/java app/src/test/java app/src/main/res/values/strings.xml app/src/main/cpp/rpcsx
   git commit -m "feat(ingame-menu): clean gateway coordinator architecture and screenshot support"
   ```
