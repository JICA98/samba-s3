# SambaS3 — Play Store Release Notes (v2026.09.09+, code 20260909)

## What's New (short, for Play Store)

- New animated boot splash with the SambaS3 logo and home backdrop
- Advanced settings: gamepad focus stays on the highlighted control with a visible highlight; settings rail text alignment + better spacing
- Improved game loading transition polish
- Home screen polish: bottom bar tint, adaptive battery indicator, clearer refresh button
- Trophies: ISO-only titles (never booted) now show trophy lists; launcher trophy overlay regressions fixed, Back closes trophies correctly
- Stability: PPU lifecycle fixes — improved savestate/save-slot handling, safe resume after interrupted PPU compilation, no duplicate recompilation after watchdog timeout

## Suggested QA summary bullets (internal)

- Boot splash renders logo + logotype with animations over default home wallpaper
- PPU: stop-game → apply patch → start still expects recompile (patch fingerprint by design); same-patch relaunch loads cache
- Advanced settings retain gamepad/dpad focus while scrolling
