# Plan: Vortek Compatibility Layer (Vortek + System Vulkan for Mali/Xclipse/PowerVR)

## Task Summary

Add a **Vortek compatibility renderer** on top of Samba-S3's system Vulkan driver, keeping the OEM driver underneath, to fix/emulate problematic Vulkan behavior for **MediaTek/Mali, Samsung Xclipse and PowerVR** without replacing the kernel driver or abusing Adreno-only tooling.

Desired final UX:

```
Graphics Driver

● System Vulkan              Direct OEM driver — lowest overhead (default)
○ Vortek + System Vulkan     Compatibility layer — Mali / Xclipse / PowerVR / Adreno OEM
○ Custom Vulkan              Turnip / custom Adreno driver — Adreno only (existing)
```

Why Vortek fits: current custom-driver loader is Adreno-oriented (`/dev/kgsl-3d0` + `adrenotools_open_libvulkan`, `native-lib.cpp:295-343`, `GpuDriverHelper.kt:87-104`, `GpuDriverSelection.kt:23-34`) with Turnip-specific `TU_DEBUG=sysmem` handling. Vortek's system-driver path `dlopen("libvulkan.so")` (`winlator.h: LIBVULKAN_PATH`, vortek client `main.c:createVkContext` uses socket + ashmem ring buffers) does **not** require Turnip/KGSL, and Winlator 10/11 already shipped Mali BC texture emulation, vertex explosion fixes and extension exposure fixes that align with RPCSX/PS3 renderer needs on Android system Vulkan. Baseline evidence: Dimensity 8300 Ultra / Mali-G615 MC6 Vulkan 44.1.0 already renders GTA SA correctly — so Vortek must be **optional**, not mandatory default.

Two Vortek architectures exist and must be distinguished:

* **Vortek IPC client/server** (`brunodev85/vortek` client ICD + `brunodev85/winlator` server `libvortekrenderer.so`) — Unix socket + 2× ashmem ring buffers (`SERVER_RING_BUFFER_SIZE 4194304`, `CLIENT_RING_BUFFER_SIZE 262144`, `vortek.h: HEADER_SIZE 8`, `VORTEK_SERVER_PATH`), thread pool `THREAD_POOL_NUM_THREADS 8`, `request_codes.h:254` request codes, `vulkan_calls.c` 179k LOC wrapper. Needed in Winlator because Windows/glibc guest ≠ Bionic host.
* **Vortek-inspired Vulkan Layer** (`WearyConcern1165/ExynosTools` `libVkLayer_VortekXclipse.so`) — in-process `VkLayer`, no IPC, BCn virtualization via CPU/compute (`layer_format_virtualization`, `layer_image_virtualization`, `layer_copy_image_routing`, compute shaders `shaders/*.comp`, `VMA` staging, `VkLayer_vortek_xclipse.json`). Correct for Samba-S3 long-term because RPCSX is already a **native Bionic process** (`GraphicsFrame.kt:8-44` → `RPCSX.surfaceEvent`, `native-lib.cpp:233-236`).

Recommended Samba-S3 strategy: **build the layer first (Phase 1), keep IPC as optional research spike (Phase 2)** — see ExynosTools analysis `VORTEK_ADAPTATION_ANALYSIS.md:44-78` and `VORTEK_IPC_RESEARCH_PLAN.md:7-42` which conclude IPC adds latency/complexity/debug cost and is not justified as primary direction.

## Research Sources

### Local repo archaeology

* `<source: app/src/main/cpp/native-lib.cpp:295-298>` — `supportsCustomDriverLoading()` is `access("/dev/kgsl-3d0", F_OK)==0`.
* `<source: app/src/main/cpp/native-lib.cpp:304-344>` — `setCustomDriver(path, libraryName, hookDir)` → `adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hookDir+"/", path+"/", libraryName, ...)` then `_rpcsx_setCustomDriver(loader)`; x86_64 stub returns false.
* `<source: app/src/main/cpp/CMakeLists.txt:7-19>` — `arm64→ add_subdirectory(libadrenotools)` else `INTERFACE`; `sambas3-android SHARED native-lib.cpp` links `android log adrenotools`.
* `<source: app/src/main/java/com/zenithblue/sambas3/RPCSX.kt:96,100,146>` — `supportsCustomDriverLoading():Boolean`, `setCustomDriver(String,String,String):Boolean`, `System.loadLibrary("sambas3-android")`.
* `<source: app/src/main/java/com/zenithblue/sambas3/utils/GpuDriverHelper.kt:31-81,87-104,250-265>` — install/enumerate drivers, `Default` synthetic entry at `/system/vendor`, `validateInstalledLibrary`, `ALLOW_EXTERNAL_GPU_DRIVERS` gate.
* `<source: app/src/main/java/com/zenithblue/sambas3/utils/GpuDriverSelection.kt:17-35,44-80>` — `applyStoredSelection` clears/sets `TU_DEBUG=sysmem` via `Os.setenv`, `selectDriver` → `RPCSX.setCustomDriver`, `shouldForceSysmemForSelection`.
* `<source: app/src/main/java/com/zenithblue/sambas3/utils/AdrenoGpuDetector.kt:14-40>` — `gpu_model` sysfs paths, `extractGpuId`, `familyFromGpuId`, `isAdreno` via `adreno/kgsl` or `/dev/kgsl-3d0`, `FILE.exists()`.
* `<source: app/src/main/java/com/zenithblue/sambas3/GraphicsFrame.kt:34-44>` — `surfaceCreated/Changed/Destroyed → RPCSX.surfaceEvent(surface, event)`.
* `<source: app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt:49-171>` — cold init, `GraphicsFrame` + overlay, `bootThread` → `RPCSX.boot(gamePath)` after `GameSettingsOverrides.applyForGame`.
* `<source: app/src/main/java/com/zenithblue/sambas3/MainActivity.kt:60-74>` — `RPCSX.openLibrary() → initialize() → syncBundledDrivers → ensureValidSelection → applyStoredSelection` off IO dispatcher.
* `<source: app/build.gradle.kts:11-14,60-75,94-123>` — `ndkVersion 30.0.14904198`, `abiFilters arm64-v8a, x86_64`, `flavorDimensions distribution` (standard/playstore), `BuildConfig ALLOW_EXTERNAL_GPU_DRIVERS / INCLUDE_BUNDLED_TURNIP_DRIVERS`, `externalNativeBuild cmake path src/main/cpp/CMakeLists.txt`, `jniLibs.useLegacyPackaging=true`.
* `<source: app/src/standard/java/com/zenithblue/sambas3/ui/drivers/GpuDriversScreen.kt:86-275>` + `<source: app/src/playstore/java/.../GpuDriversScreen.kt>` — driver list UI, `DeletableListItem`, `GpuDriverSelection.selectDriver`.
* `<source: docs/BUNDLED_TURNIP_DRIVERS.md>` — bundled Turnip packaging and Play flavors.
* `<source: patches/rpcsx-submodule-changes.patch:38-42>` — `android/CMakeLists.txt` adds `atomic-file-copier`/`iso-install-manifest`/`staged-game-installer` to `rpcsx-android` lib.
* `git log --oneline -20` — last 20 commits show recent driver overhauls and ISO install hardening.

### Cloned reference repos (`/tmp/opencode/`)

* `<source: /tmp/opencode/vortek>` — `brunodev85/vortek` (`git clone https://github.com/brunodev85/vortek`, HEAD `b1730c5`): client ICD `src/main.c: vortekServerConnect() → socket AF_UNIX SOCK_STREAM VORTEK_SERVER_PATH "/data/data/com.winlator/files/rootfs/tmp/.vortek/V0" → write HEADER_SIZE(8) REQUEST_CODE_CREATE_CONTEXT → recv_fds 2 ashmem FDs → RingBuffer_create SERVER_RING 4MiB / CLIENT_RING 256KiB`, `include/vortek.h: VORTEK_H defines MEMORY_POOL_MAX_SIZE 65536, THREAD_POOL_NUM_THREADS 8, findNextVkStructure/invertVkStructuresChain/removeNextVkStructure, vt_alloc`, `include/request_codes.h: 254 codes REQUEST_CODE_VK_CALL_START 100 … 354`, `src/vulkan_calls.c: 179897 LOC, findVkDispatchFuncWithName, VT_CALL_LOCK/UNLOCK, waitForPipelineCreation recv_fds`, `CMakeLists.txt: add_library(vulkan_vortek SHARED src/main.c src/vulkan_calls.c src/vk_object.c src/vk_object_pool.c src/ring_buffer.c src/arrays.c)`.
* `<source: /tmp/opencode/winlator>` — `brunodev85/winlator` empty `vortek/` in current shallow clone but vortek client confirmed via `/tmp/opencode/vortek/src/main.c`; Winlator XServerDisplayActivity vortek env `GALLIUM_DRIVER=zink, ZINK_CONTEXT_THREADED=1, MESA_GL_VERSION_OVERRIDE=3.3, VORTEK_SERVER_PATH` per leegao deep-dive.
* `<source: /tmp/opencode/exynostools>` — `WearyConcern1165/ExynosTools` (`git clone https://github.com/WearyConcern1165/ExynosTools`): `src/layer/` 30 files — `layer_entry.cpp` (layer negotiation `vkNegotiateLoaderLayerInterfaceVersion`), `layer_format_virtualization.cpp/h` (BC1..BC7, `R8G8B8A8_*`→`R16G16B16A16_SFLOAT` for BC6H, `ImageFormatListCreateInfo` patching), `layer_image_virtualization`, `layer_copy_image_routing` (intercept `vkCmdCopyBufferToImage*`, decode via `layer_compute_runtime`/`layer_bcn_cpu_decoder`), `layer_command_buffer_hooks/ownership/resources`, `layer_descriptor_write_builder`, `layer_staging_allocations`, `layer_vma_runtime`, `layer_temp_arena`, `layer_vk_struct_clone`, `layer_vk_struct_utils`, `layer_telemetry`, `VkLayer_vortek_xclipse.json.in`, `shaders/*.comp` (s3tc, rgtc, bc6, bc7 with IV variants), `CMakeLists.txt: add_library(VkLayer_VortekXclipse SHARED ...)`, `docs/VORTEK_ADAPTATION_ANALYSIS.md:44-78` (IPC not worth it, serializer knowledge is), `docs/VORTEK_IPC_RESEARCH_PLAN.md:7-42` (phase-gated IPC validation), `docs/XCLIPSE_COMPAT_MATRIX.md:2.15-8.2` (runtime probe > model name), `docs/ANDROID_VALIDATION.md`.

### Web / official docs

* `<source: https://github.com/brunodev85/vortek>` — "Vulkan wrapper on top of the host … Format emulation, SPIR-V inspection, texture decoding".
* `<source: https://leegao.github.io/winlator-internals/2025/06/01/Vortek1.html>` — 1000-ft overview, IPC ring buffers, `vt_call_*` → `vt_handle_*` dispatch, `vortek_renderer_thread_main_loop` JNI `getWindowWidth/getWindowHeight/getWindowHardwareBuffer/updateWindowContent`.
* `<source: https://leegao.github.io/winlator-internals/2025/06/02/Vortek2.html>` — BCn emulation (replace `VK_FORMAT_BC* → VK_FORMAT_B8G8R8A8_UNORM`, `VK_IMAGE_CREATE_BLOCK_TEXEL_VIEW_COMPATIBLE_BIT` unset, `TextureDecoder::decodeAll` at `vkQueueSubmit`, `ShaderInspector::inspectShaderStages` for `gl_ClipDistance` removal and `_SCALED` emulation via `OpConvertUToF`).
* `<source: https://github.com/WearyConcern1165/ExynosTools>` — layer README: keeps `vulkan.samsung.so` backend, `libVkLayer_VortekXclipse.so` intercepts selected calls, BC4/5/6H/7 paths, driver bundle layout `meta.json + VkLayer_vortek_xclipse.json + vulkan.samsung.so + libVkLayer`.
* `<source: https://vulkan.lunarg.com/doc/view/1.4.313.0/windows/LoaderLayerInterface.html>` — Vulkan Loader layer interface spec (implicit vs explicit layers).
* `<source: https://github.com/brunodev85/winlator/releases>` — Winlator 10 Mali support, 11.1 extension filtering referenced in task.
* `npx ctx7` not required for this plan; Vulkan loader/layer docs fetched via webfetch above. If ctx7 quota hit, note fallback in NEW_RISKS.

## Current Architecture

```
Kotlin Compose UI (MainActivity, RPCSXActivity, GraphicsFrame)
        │ JNI (native-lib.cpp → libsambas3-android.so)
        │  RPCSXLibrary::Open dlopen(RPCSX .so) + dlsym 25 funcs (boot/kill/resume/surface/usb/settings/systemInfo/patches/setCustomDriver…)
        ▼
Runtime-loaded RPCSX emulator .so (librpcsx-android.so from nativeLibraryDir)
        │  Vulkan path:
        │   if Custom path non-empty → adrenotools_open_libvulkan(CUSTOM, hookDir, driverDir, libName) → dlopen hooks
        │   else System libvulkan.so (dlopen)
        ▼
Android system libvulkan.so → OEM Vulkan driver
 ├─ Adreno (Turnip custom path via adrenotools)
 └─ Mali / Xclipse / PowerVR → direct system path (no compat layer)
        ▼
GPU
```

Key constraints:

* `supportsCustomDriverLoading` gated on `/dev/kgsl-3d0` (`native-lib.cpp:297`) — Mali/Xclipse/PowerVR falsely report "unsupported" even though their *system* driver is usable; Turnip `TU_DEBUG=sysmem` is Adreno-only (`GpuDriverSelection.kt:123-138`).
* Driver selection persisted as `GeneralSettings["gpu_driver_path"/"gpu_driver_name"/"gpu_driver_bundled_id"/"selected_gpu_driver"/"gpu_driver_force_sysmem"]` → `RPCSX.setCustomDriver` (`GpuDriverSelection.kt:17-34,66-71`).
* Emulator owns `ANativeWindow` directly (`GraphicsFrame.surfaceEvent` → `rpcsx-android.cpp _rpcsx_surfaceEvent ANativeWindow_fromSurface`), so Winlator's `XWindow/GPUImage/XConnector` presentation bridge is unnecessary.
* No Vulkan capability probe exists; `AdrenoGpuDetector.detect()` only inspects `gpu_model` sysfs/build props, not `vkEnumeratePhysicalDevices`/`vkGetPhysicalDeviceProperties2`/`vkGetPhysicalDeviceFormatProperties2`.
* NDK 30, compileSdk 36, ABIs arm64-v8a+x86_64, `jniLibs.useLegacyPackaging true`, playstore flavor forbids external driver install (`ALLOW_EXTERNAL_GPU_DRIVERS false`).

## Affected Components & Dependencies

| Component | Impact | Notes |
|---|---|---|
| `app/src/main/cpp/CMakeLists.txt` | MODIFY | Add `vortek`/`VkLayer` target, Vulkan headers, shader embedding |
| `app/src/main/cpp/native-lib.cpp` | MAJOR MODIFY | Add `HostVulkanDriver {System, AdrenoCustom}` abstraction, `VortekMode` plumbing, capability probe JNI, keep KGSL gate only for Custom path |
| NEW `app/src/main/cpp/vortek/` | NEW | Vulkan layer (`VkLayer_VortekS3.so`) + optional IPC client/server stub; mirrors ExynosTools `src/layer/` |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | MODIFY | New externs: `getVulkanCapabilities():String`, `setVortekMode(String):Boolean`, `supportsVortek():Boolean` |
| `app/src/main/java/com/zenithblue/sambas3/utils/GpuDriverHelper.kt` | MODIFY | Introduce `GraphicsDriverMode` enum, persist vortek selection, keep Custom validation |
| `app/src/main/java/com/zenithblue/sambas3/utils/GpuDriverSelection.kt` | MODIFY | Branch System vs VortekSystem vs Custom; host driver abstraction |
| NEW `app/src/main/java/com/zenithblue/sambas3/utils/VortekManager.kt` | NEW | Lifecycle: start/stop layer, probe, profile selection |
| NEW `app/src/main/java/com/zenithblue/sambas3/utils/GpuCapabilityProbe.kt` | NEW | Vulkan probe (physical device props, formats, extensions, memory) |
| NEW `app/src/main/java/com/zenithblue/sambas3/utils/GpuProfile.kt` | NEW | `Generic/Mali/PowerVR/Xclipse/AdrenoOEM` + extension/format masks |
| `app/src/main/java/com/zenithblue/sambas3/ui/drivers/GpuDriversScreen.kt` (both flavors) | MODIFY | 3-mode radio: System / Vortek+System / Custom (Custom hidden on non-Adreno & playstore filtered) |
| `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt` | MODIFY | Initialize probe + apply stored Vortek mode before `RPCSX.initialize` |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | MODIFY | Ensure vortek mode applied pre-boot, pass-through ANativeWindow path |
| `app/build.gradle.kts` + `gradle/libs.versions.toml` | MODIFY | Add Vulkan headers path, shader codegen, optional VMA dep; no ABI change |
| `app/src/main/AndroidManifest.xml` | NO CHANGE | — |
| `drivers/input/` + `scripts/package-bundled-turnip-drivers.sh` | NO CHANGE | Keep bundled Turnip flow distinct |
| Tests `app/src/test/**` | ADD | New unit tests for helper logic; existing tests untouched |

Dependencies: Khronos `Vulkan-Headers` (via `external/` or `EXYNOS_VULKAN_REPOS_ROOT`), `Vulkan-Utility-Libraries` (pNext utils), `VulkanMemoryAllocator` (staging), Android NDK `libvulkan.so`, `libadrenotools` (Adreno only).

## Implementation Steps (ordered, smallest correct change)

### Phase 0 — Scaffolding & policy (no user-visible behavior yet)

1. **Add Vulkan header dep plumbing.** Add `external/Vulkan-Headers` as git submodule (pin commit) or reuse `EXYNOS_VULKAN_REPOS_ROOT` env. Expose `Vulkan-Headers/include` to `native-lib.cpp` and new vortek target. Verify `./gradlew assembleStandardDebug` still builds — no code change yet.
2. **Introduce `GraphicsDriverMode` & persistence.** In `GpuDriverHelper.kt` add:
   ```kotlin
   enum class GraphicsDriverMode { System, VortekSystem, Custom }
   ```
   Persist under new keys: `graphics_driver_mode` (string), `vortek_enabled` (bool legacy compat), `vortek_profile` (string). Migrate existing `gpu_driver_*` keys: `Custom` iff path+name non-empty, else `System`. `VortekSystem` means path/name cleared but vortek flag true. Helpers `getGraphicsDriverMode()`, `setGraphicsDriverMode()`. Unit-test migration. No UI yet.
3. **Split `supportsCustomDriverLoading` from `supportsVortek`.** Keep `native-lib.cpp:295` KGSL check for Custom only. Add new JNI:
   ```cpp
   JNIEXPORT jboolean Java_com_zenithblue_sambas3_RPCSX_supportsVortek(JNIEnv*, jobject) { return true; } // always available; capability decides recommendation
   JNIEXPORT jstring Java_com_zenithblue_sambas3_RPCSX_getVulkanCapabilities(JNIEnv*, jobject)
   ```
   Capabilities as JSON (vendorID/deviceID/deviceName/apiVersion/driverVersion + booleans `textureCompressionBC`, `descriptorIndexing`, `timelineSemaphore`, `bufferDeviceAddress`, etc., + `supportedFormats`, `deviceExtensions`, `queueFamilies`, `memoryTypes/Heaps`). Probe via `vkEnumerateInstanceVersion + vkEnumeratePhysicalDevices + vkGetPhysicalDeviceProperties2 + vkGetPhysicalDeviceFeatures2 + vkGetPhysicalDeviceFormatProperties2 + vkEnumerateDeviceExtensionProperties`. Return empty JSON if probe before `RPCSX.initialize` Vulkan instance. Kotlin `GpuCapabilityProbe.parse(json)` typed data class with safe defaults.

### Phase 1 — Vortek Layer (in-process, recommended primary)

4. **Import & adapt ExynosTools layer skeleton.** Create `app/src/main/cpp/vortek/` with structure:
   ```
   app/src/main/cpp/vortek/
     CMakeLists.txt
     include/               # borrowed vortek.h pNext helpers (findNext/invert/remove), struct clone utils
     src/
       layer_entry.cpp      # vkNegotiateLoaderLayerInterfaceVersion + GetInstanceProcAddr/GetDeviceProcAddr trampolines
       layer_global_state.cpp/h
       layer_dispatch_types.h / layer_device_dispatch_types.h / layer_dispatch_key.h
       layer_format_virtualization.cpp/h
       layer_image_virtualization.cpp/h
       layer_copy_image_routing.cpp/h
       layer_command_buffer_hooks.cpp/h
       layer_command_buffer_ownership.cpp/h
       layer_bcn_cpu_decoder.cpp/h   # bcdec CPU path
       layer_compute_runtime.cpp/h   # BCn compute decode + pipelines
       layer_descriptor_write_builder.cpp/h
       layer_staging_allocations.cpp/h
       layer_vma_runtime.cpp/h
       layer_telemetry.cpp/h
       layer_vk_struct_clone.cpp/h
       layer_settings_runtime.cpp/h
     shaders/               # s3tc.comp, rgtc.comp, bc6.comp, bc7.comp + IV variants + include/
     VkLayer_VortekS3.json.in
   ```
   Start from ExynosTools `src/layer/` exactly; rename `VkLayer_VortekXclipse` → `VkLayer_VortekS3` (layer name `VK_LAYER_VORTEK_S3_compat`). Keep same CMake pattern (`add_library(VkLayer_VortekS3 SHARED ...)`), shader embedding via `GenerateEmbeddedSpirvHeader.cmake`. Strip Xclipse-only quirk that hides `VK_KHR_shader_float_controls`/`VK_EXT_extended_dynamic_state*` unless profile is Xclipse — make it profile-gated.

5. **Wire layer loading without adrenotools.** Two load options (pick one, document the other as fallback):
   * **A — Loader layer (preferred if loader respects `VK_LAYER_PATH`):** Ship `VkLayer_VortekS3.json` in `assets/vulkan_layers/` or `jniLibs/arm64-v8a/`, set env `VK_LAYER_PATH=<nativeLibraryDir>` + `VK_INSTANCE_LAYERS=VK_LAYER_VORTEK_S3_compat` via `Os.setenv` in `VortekManager.enable()` before `RPCSX.initialize`. Verify `vkEnumerateInstanceLayerProperties` sees it.
   * **B — Explicit `vkCreateInstance` hook via emulator:** If loader env not honored under NDK 30, add minimal hook in `native-lib.cpp` that `dlopen("libVortekS3.so")` and interposes `vkCreateInstance`/`vkCreateDevice` via `vkLayer` trampolines, then forwards to real `libvulkan.so`. This keeps `libvulkan.so` as host regardless.
   For Mali/PowerVR/Xclipse/AdrenoOEM → `HostVulkanDriver::System` (`dlopen("libvulkan.so", RTLD_NOW|RTLD_LOCAL)`). For Adreno Custom → `HostVulkanDriver::AdrenoCustom` via `adrenotools_open_libvulkan` (keep existing path untouched).

6. **Implement minimal viable BCn virtualization.** Forward ExynosTools Rules R1-R4: per-format `vkGetPhysicalDeviceFormatProperties2` probe, `vkCreateImage` format substitution (`BC* → R8G8B8A8_*` / `R16G16B16A16_SFLOAT` for BC6H), `VkImageFormatListCreateInfo` patching, `vkCmdCopyBufferToImage*` decode dispatch (CPU fallback if compute unavailable), `vkCreateImageView` remap, `vkCmdClearDepthStencilImage` aspect fix. Add pNext sanitization (`layer_vk_struct_utils.h`). Keep `Generic` profile conservative (no model-name hard-coding); only probe results drive virtualization. Unit-test struct clone + format logic with off-device JVM mocks.

7. **Add GPU profiles & extension masks.** `enum class VortekGpuProfile { Generic, Mali, PowerVR, Xclipse, AdrenoOEM }`. Map from capability probe: `vendorID` (ARM 0x13B5, Samsung 0x144D, Imagination 0x1010, Qualcomm 0x5143) + `deviceName` contains `Mali`/`Immortalis`/`Xclipse`/`PowerVR`. Profiles control: BCn whitelist, `VK_EXT_transform_feedback`/`VK_KHR_dynamic_rendering`/`VK_KHR_synchronization2` exposure, `VK_KHR_timeline_semaphore` emulation depth, shader patchers (`gl_ClipDistance` strip on Mali, `_SCALED` emulation). No per-game hack table.

8. **Storage & lifecycle.** Vortek layer `.so` + `.json` live alongside `libsambas3-android.so` (no `/files/gpu_drivers/` install dance). `VortekManager.applyStoredMode(nativeLibDir)` called from `MainActivity.onCreate` before `RPCSX.initialize` (same dispatcher as `GpuDriverSelection.applyStoredSelection`) and again in `RPCSXActivity.onCreate` cold path before `RPCSX.openLibrary`. Switching mode requires activity restart (same as Custom driver — kill+reinit). Provide `resetToSystemVortek()` / `isVortekActive()`.

9. **UI — three modes.** Refactor `GpuDriversScreen.kt` (both flavors):
   ```kotlin
   enum class GraphicsDriverOption { System, VortekSystem, Custom }
   ```
   * System — subtitle "Direct OEM driver — lowest overhead". Clears `vortek_enabled` + Custom path, calls `RPCSX.setCustomDriver("", "", hookDir)` + `VortekManager.disable()`.
   * Vortek+System — subtitle "Compatibility layer — Mali / Xclipse / PowerVR / Adreno OEM". Enables layer, clears Custom path, shows detected `GpuProfile` badge + probe summary (BC formats missing, driver version). Available on all devices (never gated by `/dev/kgsl-3d0`), but recommend via probe: if `textureCompressionBC==true` AND no known missing extension → show "System recommended" chip.
   * Custom — visible/enabled only when `isAdreno && isArm64 && fileExists("/dev/kgsl-3d0") || BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS` and pre-filtered Turnip catalog entries. Keep exact existing Turnip list behavior; do not merge Vortek entries into `GpuDriverHelper.getInstalledDrivers()`.
   Selection persists via `GeneralSettings` + immediate `VortekManager`/`GpuDriverSelection` apply; failure → `AlertDialogQueue.showDialog` + reset to System.

10. **Driver Bundle alternative (optional, Play-safe).** Allow Vortek variant as bundled ZIP (`meta.json` + `VkLayer` + optional `vulkan.samsung.so` passthrough) matching ExynosTools layout, discovered via `BundledGpuDriverCatalog` with new `role="vortek-compat"` — but default Vortek ships *inside* APK (no download), so Playstore `ALLOW_EXTERNAL_GPU_DRIVERS=false` still permits Vortek+System.

### Phase 2 — IPC research spike (optional, not on critical path)

11. **Pin Vortek client at known-good commit.** Add submodule `app/src/main/cpp/vortek-client` pointing at `brunodev85/vortek` at commit `b1730c5` (HEAD at plan time). Build `libvulkan_vortek.so` ICD (`vortek_icd.aarch64.json` → `VK_ICD_FILENAMES` env). Keep strictly separate from Phase 1 layer — do not link both active at once.

12. **Port `vortekrenderer` server stub (spike-only).** Clone desired server files into `app/src/main/cpp/vortek/renderer/` (reference list from `leegao/vortek-deep-dive`): `request_handler.c`, `shader_inspector.c`, `texture_decoder.c`, `resource_memory.c`, `async_pipeline_creator.c`, `timeline_semaphore.c`, `vk_context.c`, `vulkan_helper.c`, swapchain. Replace Winlator `VortekRendererComponent` JNI (`getWindowWidth/Height/HardwareBuffer/updateWindowContent`) with RPCSX `ANativeWindow` path (`GraphicsFrame` surface). Replace `VORTEK_SERVER_PATH "/data/data/com.winlator/..."` with `context.getFilesDir()+"/tmp/.vortek/V0"` abstract-namespace socket.

13. **Host driver abstraction in renderer.** As proposed in task:
    ```cpp
    enum class HostVulkanDriver { System, AdrenoCustom };
    void* openHostVulkan(HostVulkanDriver t, const char* hookDir, const char* driverDir, const char* libName) {
      if (t==System) return dlopen("libvulkan.so", RTLD_NOW|RTLD_LOCAL);
      else return adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hookDir, driverDir, libName, nullptr, nullptr);
    }
    ```
    Mali/PowerVR/Xclipse/AdrenoOEM → System; Adreno Turnip → AdrenoCustom.

14. **Measure IPC cost.** Benchmark `vkQueueSubmit` + `vkCmdCopyBufferToImage` + `vkCreateGraphicsPipelines` under IPC vs in-process layer on same device (Mali-G615/G710, Adreno 8 Gen 3). If overhead > 15% frame time or > 2 ms p95 `vkQueueSubmit` or stability regressions, **close IPC line** per `VORTEK_IPC_RESEARCH_PLAN.md:4.50-78` (`Experimento` backlog) and keep layer only. Document in `docs/generated/vortek-ipc-benchmark.md`.

### Phase 3 — Telemetry, docs, hardening

15. **Telemetry & validation.** Expose `VortekManager.getStats(): VortekStats` (virtualized image count, decode success/fallback/fail, descriptor pool exhaustion, `VK_TIMEOUT` events, `VK_ERROR_DEVICE_LOST`). Surface in Log Monitor (`LogMonitorScreen`) and logcat tag `VORTEK`. Add Vulkan validation layer CI path (`ENABLE_VALIDATION_LAYER` toggle, `libVkLayer_khronos_validation.so`).

16. **Compat matrix update & targeted testing.** Update `docs/` compat matrix: keep G615 baseline as System-preferred; recommend VortekSystem for G57/G68/G77/G78, G610/G710/G615/G715/G720/G925 and Xclipse 920/530/540/550/940/950/960 where probe shows missing BC or problematic `extended_dynamic_state`. No change for older weak Mali/old PowerVR 8XE/9XE beyond "candidate".

## File-Level Change Map

| File | Change | Rationale |
|---|---|---|
| `app/src/main/cpp/CMakeLists.txt` | MODIFY — add `add_subdirectory(vortek)` when `ANDROID_ABI=="arm64-v8a"`, link `VortekS3`/`Vulkan::Headers` | Build new layer |
| `app/src/main/cpp/native-lib.cpp` | MODIFY — add `supportsVortek`, `getVulkanCapabilities`, `setVortekMode`, `HostVulkanDriver` enum + `openHostVulkan`, keep KGSL gate only for Custom | Host abstraction + probe |
| `app/src/main/cpp/vortek/CMakeLists.txt` | NEW — `add_library(VkLayer_VortekS3 SHARED ...)` + shader codegen, VMA, Vulkan-Utility-Libraries | Layer build |
| `app/src/main/cpp/vortek/src/*` | NEW — ~20 files ported from ExynosTools `src/layer/` (entry, global_state, dispatch, format/image virtualization, copy routing, command_buffer_*, bcn decoders, compute/VMA/staging) | BCn compat core |
| `app/src/main/cpp/vortek/shaders/*` | NEW — `s3tc.comp, rgtc.comp, bc6.comp, bc7.comp` + IV variants | GPU decode |
| `app/src/main/cpp/vortek/VkLayer_VortekS3.json.in` | NEW — layer manifest | Loader discovery |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | MODIFY — externs `supportsVortek():Boolean`, `getVulkanCapabilities():String`, `setVortekMode(String):Boolean` | Kotlin bridge |
| `app/src/main/java/com/zenithblue/sambas3/utils/GpuDriverHelper.kt` | MODIFY — `GraphicsDriverMode`, migration, `getGraphicsDriverMode/set` | Persist 3 modes |
| `app/src/main/java/com/zenithblue/sambas3/utils/GpuDriverSelection.kt` | MODIFY — branch on `GraphicsDriverMode`, keep `adrenotools` only for Custom | Selection logic |
| `app/src/main/java/com/zenithblue/sambas3/utils/VortekManager.kt` | NEW — `applyStoredMode`, `enable/disable`, `isActive`, env/LAYER_PATH handling | Lifecycle |
| `app/src/main/java/com/zenithblue/sambas3/utils/GpuCapabilityProbe.kt` | NEW — probe JSON → typed `VulkanCapabilities` + `formatSupportsBC` helpers | Capability detection |
| `app/src/main/java/com/zenithblue/sambas3/utils/GpuProfile.kt` | NEW — `VortekGpuProfile` + `profileFromCaps` | Per-vendor policy |
| `app/src/main/java/com/zenithblue/sambas3/utils/AdrenoGpuDetector.kt` | MODIFY (minor) — add `isGenericMali/Xclipse/PowerVR` helpers via `Build.*` + `vendorID` fallback | Profile helpers |
| `app/src/main/java/com/zenithblue/sambas3/ui/drivers/GpuDriversScreen.kt` (standard) | MODIFY — 3-mode Card list, probe badge, recommendation chip | UX |
| `app/src/playstore/java/com/zenithblue/sambas3/ui/drivers/GpuDriversScreen.kt` | MODIFY — same 3 modes but Custom filtered to bundled Turnip only (unchanged gate) | UX parity |
| `app/src/main/java/com/zenithblue/sambas3/MainActivity.kt` | MODIFY — probe + `VortekManager.applyStoredMode` before `RPCSX.initialize`, alongside `GpuDriverSelection` | Boot order |
| `app/src/main/java/com/zenithblue/sambas3/RPCSXActivity.kt` | MODIFY — ensure vortek mode applied in cold init path | Cold entry |
| `app/build.gradle.kts` | MODIFY — add `external/Vulkan-Headers` include, shader compile task, `packaging.jniLibs` layer exception | Build plumbing |
| `gradle/libs.versions.toml` | MAYBE — add `vma = "3.1.0"` version pin | VMA dep |
| `external/Vulkan-Headers` | NEW submodule @ pinned commit | Headers |
| `docs/BUNDLED_TURNIP_DRIVERS.md` | MODIFY — note Vortek coexistence + `role=vortek-compat` bundle | Docs |
| `app/src/test/.../GpuDriverHelperTest.kt` + `VortekManagerTest.kt` + `GpuCapabilityProbeTest.kt` + `GpuProfileTest.kt` | NEW | Unit coverage |
| `app/src/main/cpp/vortek-client/` (optional spike) | NEW submodule `brunodev85/vortek@b1730c5` | IPC PoC only |

No changes to: `GameRepository.kt`, `PatchRepository.kt`, `overlay/*`, `GraphicsFrame.kt` surface plumbing (reused as-is), `design/design.md`.

## Testing Strategy

### Unit (JVM, `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest` — `unitTests.isReturnDefaultValues true` already)

* `GpuDriverHelperTest` — `GraphicsDriverMode` migration (`gpu_driver_path` non-empty → Custom, empty+vortekEnabled→VortekSystem else System), `validateInstalledLibrary` still rejects `..`/`/` listings, playstore `ALLOW_EXTERNAL` gate unchanged for Custom.
* `GpuCapabilityProbeTest` — parse synthetic Vulkan caps JSON (Mali-G615 with `textureCompressionBC false`, Xclipse 940 with missing `BC7`, PowerVR with old `apiVersion 1.0`); assert `isFormatSupported(VK_FORMAT_BC7_UNORM_BLOCK, sampled=true)` false → virtualized, `descriptorIndexing` false → hidden extension.
* `GpuProfileTest` — `profileFromCaps(vendor=0x13B5, name="Mali-G615")→Mali`, `0x144D Xclipse 940→Xclipse`, `0x1010→PowerVR`, `0x5143→AdrenoOEM`, unknown→Generic; ensure profile does not alter probe result, only mask.
* `VortekManagerTest` — `applyStoredMode` with mocked `RPCSX.setVortekMode` records correct env (`VK_LAYER_PATH`/`VK_INSTANCE_LAYERS`) and calls count; `disable()` clears env.
* `layer_vk_struct_clone` native gtest (under `app/src/main/cpp/vortek/tests/`) — clone `VkImageCreateInfo+pNext ImageFormatListCreateInfo` + `VkBufferImageCopy2` round-trip size-correct.

Build gates: `./gradlew assembleStandardDebug assemblePlaystoreDebug` must pass both flavors; `abiFilters` still includes `x86_64` (layer no-op shim on x86_64 returns System).

### Integration (on-device, `adb`)

* Probe smoke: install `samba-s3-standard-debug.apk`, launch, `adb logcat -s VORTEK:V RPCSX-UI:V` shows `VulkanCapabilities vendor=... device=... api=1.3.x BC=false/true` for test devices.
* Modes switching: System→VortekSystem→System toggle persists after kill+relaunch; `getInstalledDrivers` still lists Default + Turnip on Adreno; Vortek not in that map.
* Presentation: `GraphicsFrame` surface recreated (rotate/split) while Vortek active — no `VK_ERROR_DEVICE_LOST`, swapchain via `ANativeWindow` still works.

### Manual device matrix (requires hardware lab)

| Device | GPU | Vulkan | Expected default | Vortek opt check |
|---|---|---|---|---|
| Poco X6 Pro (Dimensity 8300 Ultra) | Mali-G615 MC6 | 44.1.0 | System (already correct GTA SA Grove Street) | Compare frame time / artifact check; should be identical or slightly slower with Vortek |
| Mid Mali (e.g. G57/G68) | Mali-G57 | 1.3.x, BC missing | Vortek recommended | BC1-7 game boots where System shows black/pink textures or vkCreateImage EINVAL |
| Xclipse 940 (S24) | Xclipse 940 | 1.3.279 | System first, Vortek if BC missing | BC4/5/6H/7 virtualization success telemetry >0 |
| PowerVR A/B-series | PowerVR BXM | 1.3.x | System | Probe shows PowerVR caps accurately; graceful fallback if BC missing and staging VMA fails |
| Adreno 830 / 740 | Adreno 8xx/7xx | Turnip optional | System or Custom Turnip | Vortek+System fallback path distinct from Turnip; no KGSL false-negative |

For each: boot GTA SA (or other BC-heavy title) 2 min, capture `VORTEK` log stats + fps via `GPUImage` (if available) + `dumpsys meminfo`, check `vkCmdCopyBufferToImage` decode count and `VkTimeout` 0.

## Acceptance Criteria (objective, verifiable)

* [ ] `grep -rn "supportsCustomDriverLoading\|adrenotools_open_libvulkan" app/src/main/cpp app/src/main/java` shows KGSL gate only guards Custom path; new symbols `supportsVortek`, `getVulkanCapabilities`, `setVortekMode`, `HostVulkanDriver` exist and are tested.
* [ ] Three modes persist: `GraphicsDriverMode` enum stored under `graphics_driver_mode`; `getInstalledDrivers()` still returns exactly `Default + Custom(Turnip)` on Adreno; Vortek is NOT counted as a driver directory.
* [ ] `Vortek + System Vulkan` loads `libvulkan.so` via System host on Mali/Xclipse/PowerVR/AdrenoOEM (`openHostVulkan(System)`), and `AdrenoCustom` still routes through `adrenotools_open_libvulkan` only for Turnip (`native-lib.cpp` branch covered by `__aarch64__` guard).
* [ ] Capability probe returns non-empty JSON on arm64 device with ≥1 physical device, containing `vendorID, deviceID, deviceName, apiVersion, driverVersion, textureCompressionBC, descriptorIndexing, timelineSemaphore, bufferDeviceAddress, supportedFormats, deviceExtensions, queueFamilies`; `VortekGpuProfile` derived from it without hard-coding game hacks.
* [ ] Layer `libVkLayer_VortekS3.so` + `VkLayer_VortekS3.json` shipped in APK (`jniLibs` or `assets`) and appears in `aapt dump`/`apk analyzer`; `VK_LAYER_PATH` set before `RPCSX.initialize` on arm64, no-op on x86_64.
* [ ] BCn virtualization functional: on a BC-missing device, `vkGetPhysicalDeviceFormatProperties2(BC7)` false → `vkCreateImage(BC7)` virtualizes to `R8G8B8A8_*` and `vkCmdCopyBufferToImage2` routes through compute/CPU decode (telemetry `decodeSuccess>0`), while on BC-present G615 device System path shows `decodeSuccess==0`.
* [ ] `GraphicsFrame` ANativeWindow presentation works under Vortek (game renders, no black screen after surfaceCreated/Changed/Destroyed cycle, logcat `VORTEK` no `DEVICE_LOST`).
* [ ] GpuDriversScreen shows 3 cards: System (lowest overhead chip), Vortek+System (compatibility badge + profile name), Custom (only when `isAdreno && isArm64 && /dev/kgsl-3d0` or playstore bundled catalog non-empty); selection survives `kill`+relaunch.
* [ ] Both flavors assemble clean: `./gradlew assembleStandardDebug assemblePlaystoreDebug` and `./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest` pass.
* [ ] No emoji in new Kotlin/XML strings; icons via `painterResource(R.drawable.*)` only (grep audit `grep -rP "[\x{1F000}-\x{1FAFF}]" app/src/main/java/com/zenithblue/sambas3/utils/Vortek* app/src/main/java/.../GpuDriversScreen.kt` empty).
* [ ] If IPC spike built, benchmark doc `docs/generated/vortek-ipc-benchmark.md` exists with p50/p95 `vkQueueSubmit` and frame-time delta vs layer; decision (keep layer only or dual) recorded.

## Risks & Mitigations (NEW_RISKS)

* **R1 Vortek ≠ turnkey library.** `brunodev85/vortek` client alone is insufficient; full server (`request_handler.c`, `shader_inspector.c`, etc.) is large and Winlator-specific (XWindow/GPUImage/JNI). Mitigation: **avoid IPC as primary**, reuse ExynosTools layer skeleton which already removed Winlator X dependencies and targets `ANativeWindow` directly; IPC kept as isolated optional spike.
* **R2 IPC overhead unsuitable for PS3 emulator.** Draw-call and synchronization heavy RPCSX may not tolerate ring-buffer + socket + thread-pool hops (`leegao Part 1` notes command-buffer batching only at `vkQueueSubmit`). Mitigation: layer-first, measure before merging IPC; if p95 overhead >2 ms, drop IPC line.
* **R3 Layer loader env not honored.** `VK_LAYER_PATH`/`VK_INSTANCE_LAYERS` via `Os.setenv` may be ignored if loader was already initialized before `RPCSX.initialize`, or under certain vendor loaders. Mitigation: set env in `MainActivity.onCreate` *and* `RPCSXActivity` cold path before any Vulkan `dlopen`; fallback hook in `native-lib.cpp` interposing `vkCreateInstance`.
* **R4 Vulkan probe before instance.** `getVulkanCapabilities` called too early → empty JSON → profile falls to Generic. Mitigation: probe lazily after `RPCSX.initialize` and cache; UI shows "probe pending" until first success; Vortek enable defers to next launch if probe empty.
* **R5 BCn decode cost on weak Mali.** Compute shaders for BC6H/BC7 may be slower than System path on G615 where BC already works. Mitigation: default System for G615-proven devices, Vortek only when probe shows missing format; telemetry exposes `decodeFallback` to guide auto-recommendation later.
* **R6 PowerVR old driver caps too low.** `apiVersion 1.0`, no `descriptorIndexing`/`timelineSemaphore` — cannot emulate to 1.3. Mitigation: document as "candidate but driver-limited"; vortek hides missing extensions rather than fabricating them; manual matrix marks older 8XE/9XE as low feasibility.
* **R7 NDK/VMA/shader toolchain version drift.** `Vulkan-Utility-Libraries`/`VMA` APIs change across releases; shader `glslc` vs `glslangValidator` version matters. Mitigation: pin Vulkan-Headers/VMA commits in `libs.versions.toml` and CI `gradle/libs.versions.toml` bump policy; shader embedding build fails fast if NDK missing.
* **R8 Play Store policy on external drivers.** `ALLOW_EXTERNAL_GPU_DRIVERS=false` must still allow Vortek+System (which is in-APK, not external). Mitigation: Vortek binaries shipped in APK are not subject to external-install gate; Custom path gate unchanged.
* **R9 Submodule + licensing divergence.** `brunodev85/vortek` (MIT-ish) vs `ExynosTools` (own) vs Samba-S3 Apache-2. Mitigation: keep Vortek client pins at commit `b1730c5` with LICENSE notice; layer from ExynosTools credited per its Credits (Mesa/Granite/bionic-vulkan-wrapper).
* **R10 ctx7 quota / Vulkan spec drift.** If `npx ctx7` unavailable, Vulkan loader docs fallback is `vulkan.lunarg.com` + Khronos specs. Mitigation: record fallback in build log; no code depends on ctx7 at runtime.

## Handoff to Plan Reviewer

Validate:

1. Architecture choice — **layer-first, IPC-spike-second** — correctly maps RPCSX being Bionic-native (unlike Winlator glibc), and that file-level map reuses ExynosTools `src/layer` without pulling `vulkan_calls.c` IPC serializer wholesale (per `VORTEK_ADAPTATION_ANALYSIS.md` 5-15% reuse guidance).
2. `HostVulkanDriver` split cleanly isolates `adrenotools_open_libvulkan` to Adreno Custom only, while `System` path is pure `dlopen("libvulkan.so")` for Mali/Xclipse/PowerVR/AdrenoOEM — check `native-lib.cpp` edit preserves `__aarch64__` guards and `jniLibs.useLegacyPackaging`.
3. Three-mode persistence (`GraphicsDriverMode`) migration does not break existing `gpu_driver_path/name` keys or Play flavor `INCLUDE_BUNDLED_TURNIP_DRIVERS` flow; `getInstalledDrivers` not polluted by Vortek entries.
4. Capability probe JSON shape covers minimum required fields (`textureCompressionBC`, `descriptorIndexing`, `timelineSemaphore`, `bufferDeviceAddress`, `shaderFloat16`, `storageBuffer16BitAccess`, `transformFeedback`, `dynamicRendering`, `synchronization2`, `memory types/heaps`, `supported formats`, `device extensions`, `queue families`) and is invoked after `RPCSX.initialize` so it never races with `MainActivity.kt:60-74` init order.
5. Layer load order `MainActivity.onCreate → VortekManager.applyStoredMode → RPCSX.initialize → startMainThreadProcessor/processCompilationQueue` and cold `RPCSXActivity` path both set `VK_LAYER_PATH` before first `vkCreateInstance`; fallback interpose via `native-lib.cpp` is documented if loader env ignored.
6. BCn virtualization scope (R1-R4, Xclipse matrix rules) is probe-driven not model-name-driven; no per-game hack table; Xclipse/Mali/PowerVR feasibility tiers match baseline G615 evidence.
7. No native surface/presentation regression: `GraphicsFrame → ANativeWindow` path unchanged, Winlator `XWindow/GPUImage/XConnector` correctly deleted from port.
8. Build/test gates for both flavors and acceptance greps are objective and cover the "not replace system driver" invariant.
