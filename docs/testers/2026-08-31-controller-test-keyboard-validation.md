# SambaS3 controller keyboard validation

Date: 2026-09-01  
Device: OnePlus Pad 2 (`OPD2403`, `OP5DAAL1`)  
Build: `standardDebug`, package `com.zenithblue.sambas3`

This report follows `/home/abhaybyte/Downloads/samba-s3-controller-test-keyboard-next-worker.md`.

## Summary

The keyboard classification, profile migration, keyboard-to-analog mapping, per-device routing, dedicated Test page, UI cleanup, and monotonic START hold logic are implemented and covered by unit tests. The final standard-debug APK was built, installed on the Pad 2, and the emulator was launched with the Vulkan renderer/custom Turnip driver.

The complete external-input acceptance pass is not claimed: the Optimus keyboard disconnected before the final targeted checks, no external gamepad was available, and ADB-injected key events are intentionally rejected as virtual/non-selected input. Therefore physical key highlight, physical five-second hold exit, ten-minute keyboard gameplay, gamepad regression, and two consecutive full device-validation passes remain open.

## Root causes reproduced

- `Optimus 1 Keyboard` reported `KEYBOARD | DPAD | MOUSE | JOYSTICK` with `KeyboardType: 2`. The old capability-first classifier therefore treated it as a generic gamepad.
- Generated profiles retained the generic gamepad map after the device was recognized as a keyboard, so normal keyboard keys were absent or did not route to the expected logical controls.
- Gameplay used one global mapper and filtered keyboard-source events before they could reach the keyboard profile.
- The first Test-page implementation had a lifecycle race: leaving Controls stopped the newly opened Test session. The Controls disposal path now only stops capture, while the dedicated Test page owns the test session.

## Implemented repair

- Classifier precedence now uses known gamepad identity, strong keyboard identity, capabilities, keyboard source, mouse, and virtual touch in that order. Ambiguous Optimus metadata resolves to `KEYBOARD`.
- Keyboard generated defaults are named `PC Gamepad`; the profile has WASD left-stick bindings, arrow-key right-stick bindings, J/K/U/I face buttons, Q/E shoulders, 1/3 triggers, Enter Start, Tab Select, and Esc emulator menu.
- NUMPAD 8/2/4/6 remains the secondary keyboard D-pad mapping.
- Generated stale keyboard profiles are refreshed only when they still match the generated shape. User-edited profiles are marked custom and are not overwritten by migration.
- `DeviceInputMapperRegistry` selects the mapper from the physical event's device key.
- `ControllerTestScreen` is a dedicated selected-device route. Test input updates visualization only; Back/Circle/PS do not navigate. START hold uses `SystemClock.elapsedRealtime()` and completes once at 5 seconds.
- Normal Controls UI uses friendly key labels and keeps descriptor/source/raw diagnostics in Advanced.

## Automated validation

Command:

```text
./gradlew :app:testStandardDebugUnitTest :app:packageStandardDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' -Dkotlin.daemon.jvm.options='-Xmx768m' -Dorg.gradle.workers.max=1
```

Result: `BUILD SUCCESSFUL` (51 tasks).

Covered by `ControllerKeyboardRepairTest` and existing mapper/profile tests:

- ambiguous keyboard classification and DualSense gamepad precedence;
- keyboard PC Gamepad defaults and generated-profile migration;
- virtual analog opposition and key-up release;
- J, Enter, Esc, and unmapped-key routing;
- per-device/test-session state;
- monotonic START hold progress, early release reset, and one-shot completion;
- legacy gamepad frontend-home reservation regression.

`git diff --check` is clean.

## Pad 2 validation

### Corrected keyboard UI

With the Optimus keyboard connected, the app showed:

- `Optimus 1 Keyboard · KEYBOARD · PC Gamepad`;
- the keyboard layout rather than the generic controller layout;
- WASD movement and arrow-key camera summaries;
- friendly keycaps for the logical mapping list;
- descriptor and VID/PID only in Advanced diagnostics.

Evidence: [keyboard/profile migration](artifacts/2026-08-31-controller-keyboard/08-pc-gamepad-migrated.png), [stable Test session](artifacts/2026-08-31-controller-keyboard/09-test-session-stable.png), [Advanced diagnostics](artifacts/2026-08-31-controller-keyboard/10-advanced-diagnostics.png), [Profiles without device descriptor](artifacts/2026-08-31-controller-keyboard/11-profiles-no-raw-device-key.png).

### Test lifecycle and isolation

The corrected run logged a Test session start after leaving Controls without an immediate session stop. The later direct route check, after the keyboard disconnected, showed `Device disconnected` and `Selected device only`; ADB `KEYCODE_A` input did not reconnect or update the selected physical device. This is negative isolation evidence, not a physical-key highlight pass.

Evidence: [stable connected Test page](artifacts/2026-08-31-controller-keyboard/09-test-session-stable.png), [disconnected selected-device state](artifacts/2026-08-31-controller-keyboard/27-test-route-after-gta.png), [ADB virtual key ignored](artifacts/2026-08-31-controller-keyboard/28-test-virtual-key-ignored.png).

### GTA/Vulkan smoke validation

The game was launched through the MainActivity library flow on the Pad 2. The app reached GTA boot/EULA/title screens and rendered at approximately 55–60 FPS during the stable portions. The selected renderer configuration was Vulkan, and the Vulkan log recorded the custom Turnip 26.3 driver load. A saved-state restore remained on `Restoring...`, so a fresh launch was used for the boot smoke test.

Evidence: [library launch](artifacts/2026-08-31-controller-keyboard/14-gta-library-launch.png), [fresh boot](artifacts/2026-08-31-controller-keyboard/24-gta-fresh-boot.png), [fresh EULA](artifacts/2026-08-31-controller-keyboard/25-gta-fresh-eula.png), [app log](artifacts/2026-08-31-controller-test-keyboard/rpcsx_app.log), [backend log](artifacts/2026-08-31-controller-test-keyboard/rpcsx_backend.log), [Vulkan log](artifacts/2026-08-31-controller-test-keyboard/rpcsx_vulkan.log).

The log also contains repeated Qualcomm KGSL `CP: AHB bus error` warnings while the emulator remained alive and continued presenting frames. This is a device/driver risk to revisit separately.

## Pass status

### Pass 1 — initial implementation check

Failed and corrected: the first connected-device run exposed the Controls/Test lifecycle race, and the keyboard profile still displayed the old generated name. The disposal ownership and generated-profile migration were fixed.

### Pass 2 — corrected feature check

Connected-device UI, profile migration, dedicated Test-page rendering, session-start lifecycle, diagnostics placement, and Vulkan boot smoke passed. The keyboard disconnected before physical key/hold checks. This is not a complete acceptance pass.

## Screenshot inventory

Normalized evidence is in [the plan-named artifact folder](artifacts/2026-08-31-controller-test-keyboard/). Available captures:

- [01 keyboard detected](artifacts/2026-08-31-controller-test-keyboard/01-keyboard-detected-correctly.webp)
- [02 keyboard quick setup](artifacts/2026-08-31-controller-test-keyboard/02-keyboard-quick-setup.webp)
- [03 keyboard mapping](artifacts/2026-08-31-controller-test-keyboard/03-keyboard-mapping-page.webp)
- [04 Test page](artifacts/2026-08-31-controller-test-keyboard/04-keyboard-test-page.webp)
- [13 clean mapping UI](artifacts/2026-08-31-controller-test-keyboard/13-mapping-clean-ui.webp)
- [14 Advanced device info](artifacts/2026-08-31-controller-test-keyboard/14-device-advanced-info.webp)

The following required screenshots were not fabricated because their input conditions were unavailable: physical WASD/face highlights, 25%/75%/complete START hold, gamepad Test/face/hold, and a genuine two-device strip.

## Remaining acceptance work

- Reconnect Optimus 1 Keyboard and capture positive W/A/S/D, arrow, face, and Enter highlights, key-up release, and 25%/75%/complete START hold.
- Run at least ten minutes of GTA keyboard gameplay and verify movement, camera, face buttons, Start, Select, emulator menu, and no stuck keys.
- Attach a real gamepad or fixture and run family, mapping, Test, START hold, gameplay, and multi-device isolation regression.
- Repeat the complete unchanged-build validation twice after those inputs are available.
