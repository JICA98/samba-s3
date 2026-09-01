# GTA San Andreas performance regression provenance

This record fixes the build, device, runtime, and configuration used for the
OnePlus Pad 2 audit on 2026-09-01. It is intentionally separate from the
historical performance notes in `docs/games/BLUS31584-GTA-San-Andreas.md`.

## Device

- Model: `OPD2403` (OnePlus Pad 2)
- ADB endpoint used for every run: `adb-7d6afed8-mU47CV (2)._adb-tls-connect._tcp`
- Android fingerprint: `OnePlus/OPD2403IN/OP5DAAL1:16/UKQ1.231108.001/U.R4T3.39a7a65-16b7852-170fdcc:user/release-keys`
- App package/version: `com.zenithblue.sambas3`, `2026.07.22`
- Display during testing: 3000x2120, current render rate 120 Hz, supported
  rates 30/48/50/60/90/120/144 Hz

Raw device captures are in
`docs/testers/artifacts/2026-09-01-gta-fps-power-regression/`.

## Build and core identity

- Starting source HEAD: `f1db2f583a1efcb46563ae2aab566ede8e4f0389`
- RPCSX submodule: `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc`
- `patches/rpcsx-submodule-changes.patch` SHA-256:
  `2444bd02c690b4a7901a3d50b7f6ad40249952fe9a1d6eb86d9e8d14fc9bc91e`
- Native libraries in the tested APK:
  - arm64-v8a: `afc8d4a706135c1c00e1b0988abe9888846cd06ef6474c544e9b1cc16579eec9`
  - x86_64: `1ae2bcd04854f34af4b99dbae7b08c0c892f8eb19d0a6da1128295b8fe25011d`
- APK SHA-256 used for the performance captures:
  `225150ae67f6a3c5ae663446bc974b9daaca0e20d98c488c50742665a0ec132a`
- Final APK SHA-256 installed for the last UI/build validation (the only
  difference is Kotlin source indentation/debug metadata):
  `3b86097c61f52d1e5a7f87c1a1e63d1eb7a7bc0f709ca5fa8a3a0e4f489e9415`
- Native build type: `RelWithDebInfo`
- NDK clang reported by the build toolchain: `21.0.0`

The runtime core also logs the submodule and patch identities at startup;
those lines are retained with the test logs where applicable.

## Configuration

- Game: `BLUS31584` / GTA San Andreas
- Game directory: `/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/BLUS31584`
- Pulled global config: `config-current.yml`
- Config SHA-256: `aa6f7de9b5e63df3ca87c23ed6ab311b530773184518994df0a593a835c4cc8c`
- Resolution: 720x576
- PPU: LLVM Legacy, 2 PPU threads, `cortex-a34`
- SPU: LLVM
- Vulkan, shader async enabled, scale 100%
- Frame limit: 60; VSync: off for the baseline

The VSync-on and frame-limit-off runs changed only that setting and restored
the baseline afterward. No resolution reduction, Turbo mode, frame cap, or
other tuning was applied.

## Power and thermal caveat

The tablet was physically connected to power for the audit (`AC powered: true`,
`USB powered: true`, approximately 99–100% battery, 35 C battery temperature).
Consequently, wall/device wattage was not measured and no 2 W versus 8 W power
claim is made. Android reported `Thermal Status: 0`; raw battery, thermal, and
display dumps are included for independent review.
