# Plan: De-network GPU Drivers + In-App-Only Patch Import

Status: APPROVED-FOR-REVIEW (plan only — no implementation in this pass)
Date: 2026-09-10
Scope: `standard` flavor (the shipped flavor) + shared main source set.

## 1. Problem

External URLs in shipped APK:
| Where | URL(s) | Feature |
|---|---|---|
| `app/src/standard/java/com/zenithblue/sambas3/drivers/catalog/*` | `raw.githubusercontent.com/K11MCH1|arihany/AdrenoToolsDrivers`, `The412Banner/Banners-Turnip`, `The412Banner/Nightlies/*`, `The412Banner/bannerhub-api` | GPU-driver network catalog & download |
| `app/src/standard/java/com/zenithblue/sambas3/utils/GitHub.kt` | `api.github.com/repos/$repo/releases` | generic GitHub release fetcher |
| `app/src/main/java/com/zenithblue/sambas3/PatchRepository.kt:109-151` | `rpcs3.net/compatibility?patch&api=v1&v=$version` | official patch download |

Goal: remove network links; both features become **in-app import only** (local file), same as playstore variant behavior.

## 2. Current State (verified)

- Patch: `PatchRepository.importLocal(content: String): Boolean` **already exists** (PatchRepository.kt:153) — writes `patch.yml` to `patchesDir()`, invalidates cache.
- Patch UI: `PatchManagerScreen.kt` already has both flows wired: `downloadOfficial()` buttons (5 call sites: lines 113, 200, 268, 357, 388, 395) and local-import path call site at line 157.
- Drivers: `DriverCatalogRepository` composes 3 network sources (`BannerPackJsonSource`, `GitHubReleaseDriverSource`, `BannerHubManifestDriverSource`). Local driver zip import path exists in `GpuDriverHelper` (SAF/file-picker based) — verify exact entry (`ui/drivers/GpuDriversScreen.kt` "import" affordance) before deletion.

## 3. Changes

### 3.1 Patches
1. `PatchRepository`: delete `downloadOfficial()` + its OkHttp `client`, `PatchDownloadResult` variants used only by it. Keep `importLocal`, parsing, `forTitle`, `group`.
2. `PatchManagerScreen`: remove all `downloadOfficial()` button/UI + internal wrapper `fun downloadOfficial()` (line 113); keep import dialog. Replace the network button affordance with a single "Import patch.yml" entry point; empty-state message: "Import a patch.yml from RPCS3 to enable patches."
3. Keep privacy-policy wording referencing rpcs3.net only if patch import docs still mention the source of third-party patches; adjust text: "Patches are imported manually from local files."

### 3.2 GPU drivers (standard flavor)
1. Delete standard-only catalog sources: `BannerPackJsonSource.kt`, `GitHubReleaseDriverSource.kt`, `BannerHubManifestDriverSource.kt`.
2. `DriverCatalogRepository`: reduce to local-only composition — return bundled catalog (`assets/bundled_gpu_drivers/catalog.json`) + user-imported drivers from app storage. Remove `GpuDriverHelper`/caller references to the deleted sources.
3. Delete `utils/GitHub.kt` if no remaining caller (verify: only catalog used it).
4. `ui/drivers/GpuDriversScreen.kt`: collapse the "download/import" dual entry to a single local-import action mirroring playstore's flow. Remove network fetch progress/error states that only served catalog downloads.
5. `AndroidManifest.xml` / advisory: confirm `INTERNET` permission still required elsewhere (PPU/firmware? verify — firmware install is local PUP; if nothing else uses network, optionally drop INTERNET for strictness — decision gate below).

### 3.3 BuildConfig / flags
- No flavor changes needed: standard keeps its `ALLOW_EXTERNAL_GPU_DRIVERS=true` meaning "import zips", playstore unchanged (bundled only, never ships).

## 4. Decision gates (resolve at implementation time)

- G1: Is `INTERNET` still needed after removals? Grep net usage: any remaining `OkHttp`/URL in Kotlin. If none → remove `android.permission.INTERNET`.
- G2: Confirm bundled turnip zip covers the standard flavor audience (driver profile per SoC). Without network fallback, Galileo/Xclipse/older Adreno users must source drivers themselves → acceptable per "no external GPU import" policy alignment with playstore.
- G3: Version pinning gone — patches change = user must re-import new `patch.yml`; document in onboarding/settings helper text. (Note: still triggers PPU recompile via manifest digest — expected, existing behavior.)

## 5. Tests

- Unit: `PatchRepository` import parse (valid yaml, invalid yaml, empty content) — extend existing tests; remove `downloadOfficial` tests if any.
- Unit: `DriverCatalogRepository` local-only composition returns bundled + imported entries, no network init (constructor no longer touches OkHttp).
- Manual: fresh install standard release → no outbound HTTP in logs (`S3PATCH`/`S3GPU` tags silent); import patch.yml via file picker → patches list populates, enabling triggers PPU compile and applies in-game.
- Manual: import driver zip → appears in list, selectable, boots game.

## 6. Rollout

Single PR, no migration needed: removal-only + UI simplification. Reversible via git.

## Out of scope

- Playstore flavor changes.
- In-app "patch browser" (fetch later if policy changes).
- Removing vendored 3rd-party comment URLs (cosmetic, zero runtime effect).
