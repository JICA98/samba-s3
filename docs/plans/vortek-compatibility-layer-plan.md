# Plan: Samba-S3 Native Vulkan Compatibility Wrapper

> Historical filename: `vortek-compatibility-layer-plan.md`. The shipping feature is no longer
> Vortek-specific; keep this path until references to the old plan have been migrated.

## Status

`REVISED — READY FOR PRE-IMPORT GATES AND M0 PASS-THROUGH SPIKE`

This revision replaces the original "Vortek VkLayer first" proposal with a direct, in-process
Vulkan proxy loaded through RPCSX's existing `_rpcsx_setCustomDriver(void*)` API. It also removes
BC1-BC7 emulation from the first milestone, prohibits DXVK-oriented feature spoofing, separates
raw host capabilities from wrapper-effective capabilities, and makes loader lifetime safety an
explicit invariant.

Implementation beyond the pass-through spike remains gated on:

1. a source-by-source license matrix;
2. a build/size comparison between a reduced leegao/Mesa target and a smaller Samba proxy;
3. device evidence showing which compatibility behavior RPCSX actually needs.

## Decision Summary

Use the architecture of
[`leegao/bionic-vulkan-wrapper`](https://github.com/leegao/bionic-vulkan-wrapper) as the primary
reference for a Samba-specific `libvulkan_samba_compat.so`:

```text
RPCSX Vulkan
    |
    v
libvulkan_samba_compat.so
    |
    v
/system/lib64/libvulkan.so
    |
    +-- Mali / Immortalis
    +-- Xclipse
    +-- PowerVR
    +-- Adreno OEM
```

The wrapper is a Vk-on-Vk in-process proxy. It must export the Vulkan symbols RPCSX resolves,
load Android's system Vulkan loader itself, intercept only the calls Samba needs, and forward the
rest to the OEM stack. `VK_LAYER_PATH`, an ICD JSON, and Android layer discovery are not the
primary integration mechanism.

Keep three distinct backends:

```text
System Direct
    RPCSX -> Android system Vulkan -> OEM driver

System Compatibility
    RPCSX -> Samba compatibility proxy -> Android system Vulkan -> OEM driver

Adreno Custom
    RPCSX -> Adrenotools -> Turnip/custom Vulkan -> KGSL
```

Keep original Vortek client/renderer IPC as optional Phase 4 research only. It solves a guest/host
process boundary that native Android RPCSX does not have and would add serialization, socket,
shared-memory, presentation, and lifecycle costs to the normal path.

Do not ship GameNative's precompiled wrapper archives or bundle an OEM `vulkan.samsung.so`.
GameNative is an integration/profile reference; source used by Samba must come from an audited,
pinned upstream or a Samba-owned implementation.

## Why the Previous Architecture Changed

### RPCSX already accepts a Vulkan loader handle

Samba resolves `_rpcsx_setCustomDriver` in `app/src/main/cpp/native-lib.cpp`. In the current RPCSX
submodule, `_rpcsx_setCustomDriver(void*)`:

1. stores the previous `vk::instance::g_vk_loader`;
2. clears RPCSX's Vulkan symbol cache when replacing a non-null loader;
3. assigns the supplied handle to `g_vk_loader`;
4. initializes the symbol cache from the new handle; and
5. returns the previous loader handle.

This directly matches a proxy shared library. A VkLayer environment is an unnecessary and less
deterministic extra discovery path.

RPCSX currently resolves roughly 125 unique `VK_GET_SYMBOL(...)` names with `dlsym()` from the
loader handle. M0 must generate the exact required-symbol list from the pinned RPCSX source and
compare it with `nm -D --defined-only libvulkan_samba_compat.so`; do not maintain this list by
hand.

### RPCSX already owns the Android presentation path

`GraphicsFrame` forwards surface create/change/destroy events through JNI to RPCSX, which owns the
`ANativeWindow`. Samba does not need Winlator's `XWindow`, `GPUImage`, `XConnector`, or renderer JNI
presentation bridge.

### RPCSX already has the initial BC fallback needed by PS3 textures

RPCSX's PS3 Vulkan texture path uses:

| PS3 format | Native Vulkan format when supported | Existing fallback |
|---|---|---|
| DXT1 | BC1 | `B8G8R8A8_UNORM` + CPU `bcdec_bc1` path |
| DXT23 | BC2 | `B8G8R8A8_UNORM` + CPU `bcdec_bc2` path |
| DXT45 | BC3 | `B8G8R8A8_UNORM` + CPU `bcdec_bc3` path |

RPCSX explicitly states that BC1-BC3 are all it requires. A second wrapper-side BC1-BC7
virtualization system could duplicate conversion, waste memory/bandwidth, or produce incorrect
double conversion. First benchmark and validate RPCSX's current fallback.

[`leegao/bcn_layer`](https://github.com/leegao/bcn_layer) remains a valuable optional reference if
a captured RPCSX title/device failure proves the existing fallback incorrect or too slow. BC4-BC7
support is diagnostic information, not an initial acceptance criterion.

### The leegao/GameNative policy is DXVK-oriented

The evaluated `leegao/bionic-vulkan-wrapper` code has the desired system-loader, dispatch, Android
WSI, object tracking, extension filtering, pNext, image, AHardwareBuffer, device-fault, shader
interception, and driver-workaround infrastructure. It also force-advertises features aimed at
DXVK, including geometry shaders, BC compression, transform feedback, dual-source blend,
multi-draw indirect, and vertex-pipeline stores/atomics.

RPCSX is not DXVK. Samba may expose a capability only when:

- the OEM driver supports it and the wrapper preserves it; or
- Samba implements its complete Vulkan semantics and has conformance/targeted tests for it.

Passing initialization by advertising an unsupported feature, extension, limit, or Vulkan version
is prohibited. A workaround that drops a geometry stage, masks a create request, or changes a
limit without implementing the observable behavior does not satisfy this rule.

GameNative's `wrapper-gamenative` configuration is still useful research for Mali/Exynos controls,
BCn CPU/GPU selection, quality, extension filters, present modes, memory limits, and packaging.
However, current GameNative launch logic excludes wrapper-gamenative BCn compute on Xclipse. The
plan must not require an Xclipse BC4/5/6H/7 compute success counter as proof of correctness.

## Goals and Non-Goals

### Goals

- Preserve System Direct as the default and lowest-overhead path.
- Add an optional in-process System Compatibility backend for proven OEM-driver issues.
- Keep Turnip/custom Vulkan isolated to eligible Adreno/KGSL devices.
- Preserve RPCSX's direct `ANativeWindow` ownership.
- Probe raw OEM and wrapper-effective Vulkan capabilities independently.
- Add only evidence-driven Mali, Xclipse, PowerVR, and Adreno OEM fixes.
- Provide diagnostics sufficient to identify every activated workaround and its cost.
- Keep both Standard and Play Store flavors buildable and behaviorally consistent.

### Non-Goals

- Porting Winlator or GameNative as an application stack.
- Making Vortek IPC the default renderer.
- Pretending Mali, Xclipse, or PowerVR are KGSL/Adreno devices.
- Bundling proprietary OEM Vulkan libraries.
- Shipping GameNative `.tzst` wrapper binaries.
- Advertising Vulkan 1.3/1.4 or extensions merely to satisfy an application check.
- Implementing BC4-BC7 before a real RPCSX workload requires them.
- Hot-switching a Vulkan loader while an instance/device is alive.
- Replacing the kernel GPU driver.

## Current Samba/RPCSX Contracts

The implementation must preserve these verified local behaviors:

- `native-lib.cpp` loads the runtime RPCSX shared library and resolves `_rpcsx_setCustomDriver`.
- Custom Vulkan currently uses `adrenotools_open_libvulkan(...)` on `__aarch64__`.
- `supportsCustomDriverLoading()` currently checks `/dev/kgsl-3d0`.
- `GpuDriverSelection` persists custom driver path/name and manages Turnip-only `TU_DEBUG=sysmem`.
- `MainActivity` currently calls `RPCSX.openLibrary()`, then `RPCSX.initialize()`, then applies the
  stored GPU driver asynchronously. This ordering must become deterministic.
- `_rpcsx_initialize` initializes emulator infrastructure but the code inspected does not create
  the persistent Vulkan renderer. `_rpcsx_systemInfo`, however, creates a temporary Vulkan
  instance, so it is already a Vulkan consumer.
- RPCSX loads `libvulkan.so.1`/`libvulkan.so` itself only when `g_vk_loader` is null.
- The application targets `arm64-v8a` and `x86_64`; custom Adreno loading is arm64-only today.

## Backend Model and UX

Use backend terminology consistently in Kotlin, JNI, native code, settings, logs, and UI.

Suggested Kotlin model:

```kotlin
enum class GraphicsBackend(val persistedValue: String) {
    SYSTEM_DIRECT("system"),
    SYSTEM_COMPAT("compatibility"),
    ADRENO_CUSTOM("custom"),
}
```

Suggested native model:

```cpp
enum class VulkanBackend {
    SystemDirect,
    SystemCompat,
    AdrenoCustom,
};
```

User-facing UI:

```text
Graphics Driver

System Vulkan
  Direct OEM driver - lowest overhead

Compatibility Vulkan
  Samba compatibility wrapper over the OEM system driver

Custom Vulkan
  Turnip/custom Vulkan driver - Adreno only
```

Persist the backend under one new key, for example `graphics_backend`. Existing
`gpu_driver_path`, `gpu_driver_name`, `gpu_driver_bundled_id`, and
`gpu_driver_force_sysmem` remain the metadata for `ADRENO_CUSTOM`. Migration rule:

- valid non-empty custom path/name -> `ADRENO_CUSTOM`;
- otherwise -> `SYSTEM_DIRECT`.

Do not create `vortek_enabled`, `vortek_profile`, or `VortekSystem` state. Profiles are internal,
versioned workaround sets selected from measured capabilities and driver identity, not a new family
of user-visible drivers.

## Backend Loading and Lifetime

Create one native backend controller as the sole owner of injected loader handles. A possible API:

```cpp
struct BackendSelection {
    VulkanBackend backend;
    std::string custom_driver_path;
    std::string custom_driver_name;
    std::string hooks_path;
};

BackendResult configureVulkanBackend(const BackendSelection& selection);
BackendState getVulkanBackendState();
```

Backend behavior:

| Backend | Handle passed to RPCSX | Host loader |
|---|---|---|
| `SystemDirect` | `nullptr` | RPCSX opens Android `libvulkan.so` |
| `SystemCompat` | `dlopen(libvulkan_samba_compat.so)` | wrapper opens absolute system `libvulkan.so` |
| `AdrenoCustom` | result of `adrenotools_open_libvulkan(...)` | custom Turnip/Adreno driver |

Deterministic startup sequence:

```text
RPCSX.openLibrary()
sync/validate bundled custom-driver files if required (no Vulkan calls)
load and validate persisted GraphicsBackend
configure the selected loader handle
run the independent raw host probe if needed
RPCSX.initialize()
allow systemInfo, effective probing, or game boot
```

The file sync/validation may run off the UI thread, but backend configuration must no longer be a
detached coroutine that can race the first Vulkan consumer.

Safety invariants:

1. Configure the selected backend after `RPCSX.openLibrary()` and any non-Vulkan file prerequisites,
   but before every Vulkan consumer, including `RPCSX.systemInfo()`, capability probes routed
   through RPCSX, or game boot.
2. Do not define correctness as merely "before `RPCSX.initialize()`". The actual invariant is
   before the first persistent or temporary RPCSX Vulkan instance.
3. Never replace the loader while a `VkInstance`, `VkDevice`, RPCSX `render_device`, swapchain, or
   Vulkan worker is alive.
4. Backend changes in settings take effect only after emulator stop and verified Vulkan teardown;
   require an app/emulator restart if teardown state cannot be proven.
5. Close the previous injected handle only after RPCSX has released all objects and symbol-cache
   users. Never `dlclose()` the system loader owned internally by RPCSX.
6. The controller must serialize configure/teardown operations and reject re-entry.
7. On compatibility-wrapper load failure, log the reason and fall back to System Direct only before
   Vulkan creation. Never silently switch underneath a running emulator.

Before implementing this controller, document and test ownership of `g_vk_loader`, RPCSX's
`instance::owns_loader`, and Samba's current `dlclose(prevLoader)` behavior. Add a regression test
for repeated cold selection of System -> Compat -> System and Custom -> System without gameplay
hot-swaps.

## Hardware Eligibility vs Distribution Policy

Keep these concepts separate:

```text
supportsAdrenoCustom =
    arm64
    AND detected Adreno
    AND /dev/kgsl-3d0 exists

mayInstallExternalDrivers =
    BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS

hasBundledCustomDrivers =
    BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS
```

`ALLOW_EXTERNAL_GPU_DRIVERS` controls how a package may obtain driver files. It is not evidence that
the hardware can load an Adreno driver. Play Store builds with bundled Turnip still require
`supportsAdrenoCustom`. Never expose Custom Vulkan to Mali/Xclipse/PowerVR because a distribution
allows downloads.

System Compatibility is an app-provided backend and is independent of KGSL and external-driver
download policy.

## Capability Probing

Create two separately named and separately serialized capability reports.

### `HostVulkanCapabilities`

The host probe directly opens the absolute Android loader (`/system/lib64/libvulkan.so` on arm64),
creates its own temporary instance, enumerates the OEM physical device, records raw values, then
destroys everything. It must not depend on `RPCSX.initialize()` or mutate RPCSX's loader handle.

### `EffectiveVulkanCapabilities`

The effective probe opens the selected compatibility wrapper as a client would and records what
RPCSX would see after filtering/emulation. It must use its own temporary lifetime or run only when
RPCSX has no live Vulkan objects.

Store enough identity to join reports:

- vendor ID, device ID, device name;
- driver ID, driver name, driver version;
- Vulkan API version;
- OS/build fingerprint only in privacy-safe local diagnostics;
- compatibility-wrapper version and workaround-set version.

Probe fields should follow RPCSX's actual contract rather than a generic DXVK checklist:

- `shaderFloat16`, `shaderInt8`, `shaderFloat64`;
- descriptor indexing and each update-after-bind feature RPCSX queries;
- `maxUpdateAfterBindDescriptorsInAllPools` and relevant descriptor limits;
- custom border color and border-color swizzle behavior;
- attachment feedback loop layout;
- fragment shader barycentric;
- device fault;
- shader stencil export;
- conditional rendering;
- external memory host;
- sampler mirror clamp to edge;
- synchronization2;
- unrestricted depth range;
- BC1, BC2, and BC3 features/properties individually plus aggregate `textureCompressionBC`;
- depth formats `D16_UNORM`, `D24_UNORM_S8_UINT`, `D32_SFLOAT`,
  `D32_SFLOAT_S8_UINT`;
- color/texture formats used by RPCSX, including `B8G8R8A8_UNORM`, `R8G8_UNORM`,
  `R8G8_SNORM`, and relevant R16/RG16/RGBA16F variants;
- extensions, queue families, memory heaps, and memory types.

BC4-BC7 may be logged for research but must not select the initial backend or activate emulation.

For every field changed by the wrapper, emit a machine-readable reason:

```json
{
  "field": "VK_EXT_extended_dynamic_state",
  "host": true,
  "effective": false,
  "action": "hidden",
  "rule": "mali-rNN-known-driver-bug",
  "evidence": "issue-or-trace-id"
}
```

## Source Strategy

### Primary scaffold: leegao bionic Vulkan wrapper

Evaluate and selectively reuse:

- proxy/ICD entrypoint generation and dispatch;
- absolute Android system Vulkan loading;
- Android WSI forwarding;
- wrapper object and queue state;
- extension enumeration/filtering;
- pNext traversal/cloning/sanitization;
- format/image/depth interception;
- AHardwareBuffer and external-memory safety;
- device-fault and diagnostics infrastructure;
- SPIR-V interception framework only when a captured RPCSX shader requires it;
- optional Adrenotools concepts, while Samba retains its existing custom-driver owner.

Do not copy the wrapper's force-enable feature table or DXVK-specific engine policies. The initial
Samba wrapper must report the same capabilities as the system driver.

### Selective Xclipse source: ExynosTools

Use [`WearyConcern1165/ExynosTools`](https://github.com/WearyConcern1165/ExynosTools) selectively for:

- safe pNext cloning/sanitization patterns;
- image-format and image-view remapping patterns;
- depth/stencil safety;
- AHardwareBuffer/external-memory safety;
- staging/VMA patterns if later required;
- telemetry;
- Xclipse-specific, evidence-backed workarounds.

Do not generalize the full layer to every GPU and do not package its `vulkan.samsung.so` bundle.
The project describes itself as experimental and Xclipse-focused.

### Optional BCn source

Track [`leegao/bcn_layer`](https://github.com/leegao/bcn_layer) and the BC decoder infrastructure
in the bionic wrapper, but add neither to M0. A later BC implementation needs a failing RPCSX trace,
before/after correctness captures, memory and frame-time data, and proof that RPCSX's existing
BC1-BC3 fallback is insufficient.

### Integration/profile reference: GameNative

Use [`utkarshdalal/GameNative`](https://github.com/utkarshdalal/GameNative) for comparison of:

- wrapper selection and configuration UI;
- capability/extension controls;
- present/resource/memory controls;
- wrapper package provenance and duplicate identification;
- Mali/Xclipse/PowerVR integration experiments.

Do not port all five wrapper archive choices. Do not infer RPCSX correctness from DXVK/vkd3d
success. Do not treat GameNative-hosted Adreno packages as universal wrappers.

### Optional Vortek research

Use [`brunodev85/vortek`](https://github.com/brunodev85/vortek) only for serializer, shader
inspection, texture decoder, and comparative IPC research. Do not port its X server or presentation
stack.

## Pre-Import Gates

### Gate A: license and provenance

Create `docs/third-party/vulkan-compat-license-matrix.md` before copying source or shaders. For each
candidate file/module record:

- repository and exact commit;
- source path;
- SPDX/license and copyright owner;
- whether it is original, modified, generated, or vendored;
- notice/source-offer obligations;
- intended Samba destination;
- decision: import, reimplement, reference only, or reject.

Audit at minimum: Mesa-derived wrapper code, leegao changes, ExynosTools, Vortek, Granite-derived
shaders, bcdec, VMA, Vulkan-Headers, Vulkan-Utility-Libraries, SPIRV-Tools, and Adrenotools. Replace
all informal labels such as "MIT-ish" with file-level conclusions. Samba-S3 is GPL-2.0; the matrix
must still preserve all third-party notices and identify any incompatible or unclear component.

### Gate B: build architecture

Compare two prototypes before vendoring a large Mesa tree:

**A. Reduced upstream target**

- pin an audited `leegao/bionic-vulkan-wrapper` commit;
- build only the wrapper and required Mesa Vulkan common/WSI/generated dependencies;
- strip DXVK policy and all unused decoder/shader paths.

**B. Small Samba proxy**

- generate/export RPCSX-required Vulkan entrypoints;
- implement a small dispatch/object layer;
- selectively port audited modules only as failures require them.

Record for both:

- clean and incremental native build time;
- checked-out and compiled source size;
- stripped/unstripped `.so` size;
- APK/AAB size delta for both flavors;
- dynamic symbol count and dependency count;
- startup time and pass-through CPU overhead;
- maintenance cost of syncing Vulkan headers and RPCSX symbol use.

Choose A or B in a short architecture decision record. Do not vendor the full wrapper repository by
default.

## Implementation Phases

### Phase 0: pass-through proxy spike (M0)

Build `libvulkan_samba_compat.so` for arm64 with no compatibility policy:

- export every Vulkan entrypoint RPCSX resolves from the loader handle;
- load `/system/lib64/libvulkan.so` with `RTLD_NOW | RTLD_LOCAL`;
- forward instance/device/queue/WSI calls;
- preserve physical-device identity, features, extensions, properties, formats, limits, and return
  codes;
- perform no shader edits, BC emulation, format remapping, feature spoofing, or version override;
- add only lifecycle/error logs and opt-in call counters;
- keep `ANativeWindow` handling unchanged.

Add backend persistence/migration, deterministic startup application, hardware eligibility fixes,
and loader-lifetime guards in the same milestone. On x86_64, either build a verified pass-through
proxy or expose only System Direct; never show a backend that cannot load.

M0 is an architectural equivalence test, not a compatibility release.

### Phase 1: safe compatibility infrastructure (M1)

After M0 passes, add infrastructure without claiming new GPU features:

- extension hiding/blacklisting;
- pNext validation and safe cloning/sanitization;
- safe format query interception;
- host/effective capability diffing;
- telemetry and workaround activation reasons;
- depth/stencil remaps only for a reproduced failure;
- AHardwareBuffer/external-memory guards only for a reproduced failure.

Every rule must include vendor/driver/capability predicates, a linked issue/trace, a focused test,
and an off switch. Rules default to inactive on unknown drivers.

### Phase 2: device-driven fixes (M2+)

**Mali / Immortalis**

- keep known-good G615 devices on System Direct by default;
- port a SPIR-V pass only after capturing the failing shader and validating unchanged semantics;
- evaluate extended-dynamic-state filtering only against affected driver revisions;
- never blanket-enable missing features.

**Xclipse**

- prioritize proven pNext, format-query, image-view, depth/stencil, and AHB safety;
- A/B test System Direct against System Compatibility;
- add BC compute only if an RPCSX workload reaches an unsupported operation and the existing
  fallback cannot handle it correctly or efficiently.

**PowerVR**

- use conservative extension/feature exposure;
- add format/depth/shader fixes only from real traces;
- never convert a Vulkan 1.0/1.1 device into a reported 1.3 device;
- do not call geometry-stage removal geometry-shader emulation.

**Adreno OEM**

- keep System Direct, System Compatibility, and Adreno Custom independently testable;
- prefer Turnip only when it is available, compatible, and selected/recommended by evidence;
- never route System Compatibility through KGSL-specific custom loading.

### Phase 3: performance and recommendations

Build a versioned compatibility database keyed by raw capabilities plus vendor/device/driver ID and
driver version. GPU marketing name alone is insufficient.

Recommendation order is evidence-driven, not a permanent hard-coded ranking:

```text
Known-good system driver
    -> System Direct

Known system-driver issue fixed by an audited Samba rule
    -> recommend System Compatibility

Eligible Adreno with validated Turnip package
    -> optionally recommend Adreno Custom

Unknown device/driver
    -> System Direct; collect diagnostics; do not auto-enable workarounds
```

Compatibility mode may be slower when it fixes a game that otherwise fails, but it must not become
the default on a known-good device merely because of GPU family.

### Phase 4: optional Vortek IPC research

Benchmark Vortek IPC only if an important compatibility feature cannot be safely implemented
in-process. Compare end-to-end frame time, submission CPU time, memory, shader/pipeline creation,
surface lifecycle, and crash behavior. Stop the spike if the missing feature is unrelated to RPCSX
or if an in-process implementation remains feasible.

## File-Level Implementation Map

Final paths depend on Gate B, but the preferred ownership is:

```text
app/src/main/cpp/
  gpu/
    VulkanBackend.cpp
    VulkanBackend.h
    VulkanCapabilityProbe.cpp
    VulkanCapabilityProbe.h
    VulkanCompatRules.cpp
    VulkanCompatRules.h
  vulkan_compat/
    CMakeLists.txt
    dispatch/                 # generated entrypoints/trampolines
    proxy/                    # system loader + wrapped object dispatch
    diagnostics/
    rules/                    # only evidence-backed rules

app/src/main/java/com/zenithblue/sambas3/utils/
  GraphicsBackend.kt
  GraphicsBackendManager.kt
  GpuCapabilityReport.kt
```

Expected modifications:

| Component | Change |
|---|---|
| `app/src/main/cpp/CMakeLists.txt` | Add selected proxy target and generated-symbol audit |
| `app/src/main/cpp/native-lib.cpp` | Replace path-only custom selection with serialized backend controller; preserve Adrenotools path |
| `app/src/main/java/com/zenithblue/sambas3/RPCSX.kt` | Add backend configure/state and capability probe JNI methods |
| `GpuDriverSelection.kt` | Apply one backend model; keep Turnip env only in custom path |
| `GpuDriverHelper.kt` | Persist/migrate backend separately from custom package metadata |
| `MainActivity.kt` | Sync/validate files, then apply backend after `openLibrary()` and before any Vulkan consumer |
| both `GpuDriversScreen.kt` flavors | Show System, Compatibility, and eligible Custom choices |
| `SettingsScreen.kt` | Report backend availability separately from custom-driver install support |
| unit/native tests | Migration, gates, loader lifetime, capability diff, symbol/export, rule tests |
| `docs/third-party/` | License/provenance matrix and required notices |

`RPCSXActivity` should not independently reapply the backend if `MainActivity` owns initialization.
If cold activity launch can bypass that path, factor one idempotent application routine used before
RPCSX initialization in both entry paths; do not race two backend writes.

## Test and Validation Strategy

### Build and static checks

Run for every milestone:

```bash
./gradlew assembleStandardDebug assemblePlaystoreDebug
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest
```

Add native/static checks for:

- generated RPCSX `VK_GET_SYMBOL` list is a subset of wrapper exports;
- wrapper host path is the absolute Android system Vulkan loader;
- wrapper has no dependency on a bundled OEM Vulkan blob;
- no `VK_LAYER_PATH`/`VK_INSTANCE_LAYERS` requirement in the shipping path;
- no force-enabled features in M0;
- x86_64 UI/backend availability matches the built native capability;
- backend configure is rejected while the emulator/Vulkan state is live.

### Baseline matrix

Compare:

- System Direct;
- System Compatibility pass-through;
- Adreno Custom where eligible.

Minimum physical coverage before recommending compatibility mode:

- known-good Mali-G615 baseline;
- at least one additional Mali/Immortalis driver revision;
- Xclipse target hardware;
- PowerVR target hardware;
- Adreno OEM and Turnip on one eligible device;
- Standard and Play Store distributions.

### Correctness and lifecycle

- same GPU and driver identity in HostCaps, System Direct, and M0 System Compatibility;
- same raw/effective features, extensions, properties, formats, and limits in M0;
- GTA known-good checkpoint renders identically on G615;
- surface create/change/destroy, rotation, background/foreground, pause/resume, and activity
  recreation work;
- no stale dispatch pointers, use-after-`dlclose`, double-close, `VK_ERROR_DEVICE_LOST`, or crash;
- failed compatibility load falls back before Vulkan creation and is visible in logs/UI;
- Custom remains unavailable on non-Adreno devices regardless of flavor policy;
- existing Turnip install, bundled-driver, and `TU_DEBUG=sysmem` behavior remains unchanged.

### Performance measurements

Record:

- average FPS and p50/p95/p99 frame time;
- process CPU and GPU time where available;
- resident memory and native heap;
- shader compilation and pipeline creation time;
- `vkQueueSubmit` CPU overhead;
- startup/backend initialization time;
- timeouts, device-lost events, crashes, and activated workaround counters.

M0 pass-through gate:

- target: <=5% frame-time regression;
- investigate: >5% and <=10%;
- reject/rework as a default-capable path: >10%.

Measure multiple warmed runs and retain raw samples. A compatibility fix may exceed the transparent
wrapper gate only when it makes a title/device work that fails under System Direct, and the UI/logs
must make that trade-off explicit.

## Milestone M0 Acceptance Checklist

- [ ] License/provenance matrix covers every M0 imported/generated component.
- [ ] Gate B records reduced-upstream versus small-proxy decision.
- [ ] System Direct behavior is unchanged.
- [ ] Adrenotools/Turnip behavior is unchanged.
- [ ] KGSL/Adreno hardware eligibility protects only Adreno Custom.
- [ ] Distribution install policy cannot make Custom appear on Mali/Xclipse/PowerVR.
- [ ] `libvulkan_samba_compat.so` loads through `_rpcsx_setCustomDriver`.
- [ ] The wrapper opens `/system/lib64/libvulkan.so` itself on arm64.
- [ ] All RPCSX-required Vulkan symbols are exported and audited automatically.
- [ ] No `VK_LAYER_PATH`, layer manifest, or bundled OEM Vulkan blob is required.
- [ ] M0 advertises no capability absent from the raw host report.
- [ ] Host and Effective capability reports can be diffed field-by-field.
- [ ] Backend is selected before `systemInfo`, probe-through-RPCSX, or renderer creation.
- [ ] Backend cannot switch while any Vulkan/emulator object is live.
- [ ] Previous loader ownership and close behavior pass cold-switch lifecycle tests.
- [ ] Known-good G615 GTA baseline reaches its existing checkpoint under both System Direct and M0.
- [ ] GPU/driver identity is the same under System Direct and M0.
- [ ] Surface and activity lifecycle tests pass.
- [ ] Standard and Play Store debug builds/tests pass.
- [ ] x86_64 has a verified Compatibility build or a clean System Direct-only fallback.
- [ ] M0 performance meets the pass-through gate.

Only after all applicable M0 checks pass may compatibility rules enter M1/M2.

## Risks and Mitigations

| ID | Risk | Mitigation |
|---|---|---|
| R1 | DXVK-oriented wrapper fabricates capabilities inappropriate for RPCSX | Strip force-enable policy; compare HostCaps and EffectiveCaps; require full semantics and tests |
| R2 | Duplicate BC decode reduces performance or corrupts textures | Keep BC out of M0/M1; measure RPCSX BC1-3 fallback first |
| R3 | Loader hot-swap leaves stale dispatch pointers or closes a live library | Serialized backend owner; hard no-live-Vulkan invariant; restart when uncertain |
| R4 | Full Mesa-derived import inflates source, build, and APK | Gate B prototype comparison; pin/build only necessary target or use small proxy |
| R5 | License/provenance is unclear across copied modules and shaders | File-level matrix before import; preserve notices and exact commits |
| R6 | Raw host and wrapper-advertised capabilities are conflated | Separate probe implementations and explicit field-level diffs |
| R7 | GameNative/Winlator success is assumed to transfer from DXVK to RPCSX | Require RPCSX reproduction, trace, test, and device evidence for every rule |
| R8 | Xclipse BCn compute is treated as universally correct | No BC acceptance requirement; note GameNative's Xclipse exclusion; device-driven tests only |
| R9 | Distribution permission is confused with Adreno eligibility | Separate hardware, external-install, and bundled-package predicates |
| R10 | Bundled OEM Vulkan blob causes licensing/ABI/firmware problems | Always use installed Android system Vulkan; never package vendor ICD blobs |
| R11 | Wrapper cannot satisfy RPCSX's dlsym symbol model | Generate required-symbol list and export audit in M0; fail build on missing symbols |
| R12 | Backend is applied after a temporary Vulkan consumer such as `systemInfo` | Central deterministic startup sequence immediately after `openLibrary()` |
| R13 | x86_64 exposes an arm64-only backend | Build/test proxy for x86_64 or hide it and retain System Direct |
| R14 | Rules selected by GPU name regress unknown driver versions | Key on capabilities plus IDs/version; unknown drivers default to no rules |
| R15 | In-process wrapper adds unacceptable overhead | M0 <=5% target, detailed frame/submit profiling, System Direct remains default |

## Research Snapshot and Pinning Rules

Research reviewed for this revision on 2026-08-24:

| Project | Evaluated ref | Role |
|---|---|---|
| Samba RPCSX submodule | `e8ae1481ab7ba04d5c6bef89dd852aabba2c88ff` | loader API, symbol cache, BC fallback, Vulkan contract |
| `leegao/bionic-vulkan-wrapper` `wrapper` branch | `c8baafbd4f4835ca103acb55ec3ac13642b6b7e3` | primary architectural scaffold/reference |
| `leegao/bcn_layer` | `50993a2d51772567de9c36de4d523652773f0899` | optional BC research |
| `leegao/vulkan_wrapper_termux-packages` | `c5c45d88a5f5c403bbe9f4c436139433628210de` | Android/Bionic build reference |
| `utkarshdalal/GameNative` | `504ee7345d8ee86b6db4632e984efcb6097b594e` | integration/profile/package comparison |
| `WearyConcern1165/ExynosTools` | `3dcdbcb2034e5f19a1606e54668e6a87f92476c6` | selective Xclipse reference |
| `brunodev85/vortek` | `b1730c5def9b575672e671aee11d79ae7adc63d1` | optional IPC/serializer research |

These are research snapshots, not automatic dependency selections. Before importing code, recheck
the chosen upstream ref, record it in the license matrix/lock mechanism, and do not track a moving
branch or a GameNative binary archive.

## Final Architecture

```text
                              +-- Android System Vulkan ------------------+
                              |                                           |
RPCSX -- GraphicsBackend -----+-- Samba In-Process Compatibility Proxy ---+--> OEM GPU driver
                              |      + optional proven fixes              |
                              |      + optional BC only if measured       |
                              |                                           |
                              +-- Adrenotools / Turnip --------------------+

Optional research only:
RPCSX -> Vortek client -> IPC/shared memory -> Vortek renderer -> system Vulkan
```

The project should proceed with a transparent in-process proxy first, not a Vortek layer and not a
BCn subsystem. System Direct remains the default, Turnip remains an Adreno-only custom driver, and
every compatibility behavior must be justified by a reproduced RPCSX failure.
