# Fix: In-game Emulator Gameplay Menu Coinciding with Controller UI

## Problem Analysis
1. **Unintended Home Menu on Game Launch**:
   - In `app/src/main/cpp/rpcsx/android/src/rpcsx-android.cpp`, `_rpcsx_surfaceEvent` with `event == 2` (surface destroyed) was invoking `padThread->open_home_menu()`.
   - When launching games, previous activity lifecycle events triggered `event == 2`, causing the RPCSX in-game Home Menu to open automatically and remain on screen when the game launched.
   - This caused the Home Menu to appear directly beneath the on-screen controller overlay (`PadOverlay`).

2. **Touch Interception and Conflict**:
   - `PadOverlay` is full-screen (`match_parent` x `match_parent`) on top of `GraphicsFrame`.
   - The touch listener in `PadOverlay` captured touches across the screen (including `inFloatingArea` spanning the entire middle region).
   - When touching the screen while the menu was active, `PadOverlay` intercepted the touches, spawned floating analog sticks, and sent pad data instead of allowing intended interaction.
   - Home Menu navigation in RPCSX is designed to use pad buttons (D-pad and action buttons: Cross = Enter, Circle = Back, Square = Save, Triangle = Discard).

## Solution Plan
1. **Remove Automatic Home Menu Trigger in `surfaceEvent`**:
   - In `rpcsx-android.cpp`, remove `padThread->open_home_menu()` from `event == 2` in `_rpcsx_surfaceEvent`.
   - Ensure the Home Menu is only opened intentionally when the user presses the `PS` button (`CELL_PAD_CTRL_PS`) on the controller overlay or physical controller.
   - Update `patches/rpcsx-submodule-changes.patch` to reflect the change cleanly.

2. **Ensure Clean Boot & State Handling**:
   - In `RPCSXActivity.kt`, ensure when a game is booted or resumed, the emulator starts cleanly without leftover menu state.
   - Ensure `padOverlay` visibility toggle (`osc_toggle`) properly disables touch consumption when hidden.

3. **Verify on Connected Devices**:
   - Rebuild RPCSX core and standard debug APK.
   - Install APK on connected device(s).
   - Launch GTA San Andreas and verify that the game boots cleanly into gameplay without the Home Menu being automatically stuck on screen under the controller.
   - Test pressing the PS button to verify Home Menu opens when explicitly requested, and navigates correctly using the D-pad and face buttons.

4. **Validation via Subagent**:
   - Spawn subagent to inspect the implementation, verify diffs, and ensure no regressions.
