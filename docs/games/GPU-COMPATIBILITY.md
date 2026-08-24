# SambaS3 — Android GPU Compatibility Matrix

**Last updated:** 2026-08-24  
**Verified device:** MediaTek Dimensity 8300 Ultra (`MT6897Z_A/ZA`), Arm Mali-G615 MC6, Valhall generation 4, vendor Vulkan driver `44.1.0`  
**Current validation workload:** GTA San Andreas (`BLUS31584`)  
**Verified result:** in-game at Grove Street with correct world geometry, HUD and collision; performance optimization and shader-cache work remain open

This is the global GPU-family matrix for SambaS3. It provides complete **family coverage for the requested Android GPU ranges**, not a promise that every phone using that IP will run every PS3 title. SoC memory bandwidth, GPU core count, Android vendor driver, RPCSX core build, game workload and device firmware matter as much as the architecture name. Only Mali-G615 MC6 has completed the current GTA validation workload.

## Status legend

| Status | Meaning |
|---|---|
| **Verified** | This exact GPU/driver reached correctly rendered gameplay. |
| **Expected** | Modern Vulkan hardware with enough performance; strong test candidate, but GTA is not yet verified. |
| **Candidate** | Vulkan should initialize on suitable drivers, but performance or driver behavior is uncertain. |
| **Borderline** | Backend may initialize, but low performance, old drivers or missing driver alternatives make gameplay doubtful. |
| **Not recommended** | Below the practical target or lacks an appropriate supported Vulkan driver route. |
| **Out of scope** | Not an Android GPU target for the current SambaS3 application/runtime. |

## Qualcomm Adreno

| Family | Public GPU models covered | Vulkan driver route | GTA forecast |
|---|---|---|---|
| Adreno 5xx | 505, 506, 508, 509, 510, 512, 530, 540 | OEM Vulkan only. Mesa Turnip explicitly targets Adreno 6xx and newer and has no plan for 5xx or older. | **Not recommended.** Old vendor stacks and limited performance; no Turnip fallback. |
| Adreno 6xx low/mid | 610, 612, 615, 616, 618, 619/619L, 620 | OEM Vulkan or device-compatible Turnip package in the SambaS3 standard flavor. | **Borderline/candidate.** Backend should be possible; GTA performance likely limited. |
| Adreno 6xx high | 630, 640, 642/642L, 644, 650, 660, 680, 685, 690 | OEM Vulkan or device-compatible Turnip. | **Candidate.** 650/660 and above are stronger; the available Adreno 640 control device has not completed this exact checkpoint yet. |
| Adreno 7xx low/mid | 702, 710, 720, 725 | OEM Vulkan or compatible Turnip. | **Candidate.** 702/710 are likely CPU/GPU constrained; 720/725 are better test targets. |
| Adreno 7xx high | 730, 735, 740, 750 | OEM Vulkan or compatible Turnip. | **Expected.** Adreno 750 / Snapdragon 8 Gen 3 is the primary cross-vendor control still to test. |
| Adreno 8xx | 825, 830, 840 and later public 8xx variants | Current OEM Vulkan; Turnip support depends on the Mesa build and exact GPU. | **Expected.** Highest-priority Qualcomm targets, currently unverified for this title. |

The upstream Linux MSM catalog confirms separate 5xx, 6xx, 7xx and 8xx families, while Mesa documents Turnip Vulkan support as 6xx onward. Model suffixes and OEM-reported names can vary; validate the actual Vulkan physical-device string from the RPCSX log rather than relying on the phone marketing name.

## Arm Mali and Immortalis

Arm officially calls the G720 generation **5th Generation architecture**, not “Valhall v5.” The requested `Valhall v1–v5` range is therefore represented below as Valhall generations 1–4 plus Arm 5th Gen.

| Architecture | GPU products covered | GTA forecast |
|---|---|---|
| Bifrost | Mali-G31, G51, G52, G71, G72, G76 | **Borderline/not recommended.** Vulkan-capable products exist, but these are older and generally below the performance target. |
| Valhall generation 1 | Mali-G57, G77 | **Candidate.** G77-class devices are more plausible; low-core G57 devices are likely constrained. |
| Valhall generation 2 | Mali-G68, G78, G78AE | **Candidate.** Suitable vendor Vulkan is required; high-core G78 is the stronger target. |
| Valhall generation 3 | Mali-G310, G510, G610, G710 | **Candidate/expected.** G610/G710 are preferred; G310/G510 are likely performance-limited. |
| Valhall generation 4 | Mali-G615, G715, Immortalis-G715 | **Verified on Mali-G615 MC6 / driver 44.1.0.** G715/Immortalis-G715 are expected but not yet tested. |
| Arm 5th Gen (requested “v5”) | Mali-G620, G625, G720, G725, Immortalis-G720, Immortalis-G925, Mali G1 | **Expected** on a conformant Android vendor driver. GTA checkpoint still unverified. |

The verified G615 device has forced tuning: thermal limits are removed and GPU frequency is locked at 1.400 GHz. Its OPP/temperature/`ACTIVE` fields are not stock performance evidence. Vulkan use is instead proven by RPCSX logging the selected physical device and successful renderer initialization.

## Samsung Xclipse / AMD RDNA-derived mobile GPUs

| Samsung GPU | SoC family | AMD lineage stated by Samsung | GTA forecast |
|---|---|---|---|
| Xclipse 920 | Exynos 2200 | AMD RDNA 2 | **Candidate.** Vulkan-conformant Android products exist, but first-generation Xclipse driver behavior needs title testing. |
| Xclipse 530 | Exynos 1480 | Xclipse/AMD-derived | **Borderline/candidate.** Lower-tier implementation; backend likely, performance unknown. |
| Xclipse 540 | Exynos 1580 | Xclipse/AMD-derived | **Candidate.** Unverified. |
| Xclipse 550 | Exynos 1680 | AMD RDNA 3 | **Candidate/expected.** Unverified. |
| Xclipse 940 | Exynos 2400 | AMD RDNA 3 | **Expected.** Khronos lists a conformant Android implementation; GTA unverified. |
| Xclipse 950 | Exynos 2500 | fourth-generation Xclipse, AMD RDNA 3 | **Expected.** GTA unverified. |
| Xclipse 960 | Exynos 2600 | newer Xclipse architecture | **Expected.** Khronos lists Vulkan 1.4 conformance; GTA unverified. |

“Radeon AMD” on ordinary desktop/laptop GPUs is different from Xclipse on Exynos. Radeon GCN and RDNA 1–4 have strong Vulkan implementations on desktop Linux/Windows, but they are **out of scope for the current Android app/runtime validation**. The APK has an `x86_64` ABI, yet that alone does not supply a compatible Android RPCSX core, game environment or Radeon Android Vulkan stack.

## Imagination PowerVR

| Architecture/product family | Public Vulkan-capable products covered | GTA forecast |
|---|---|---|
| Series8XE / 8XEP | GE8100, GE8200, GE8300, GE8320, GE8322 | **Not recommended/borderline.** Vulkan implementations exist on some products, but mobile performance and old OEM drivers are major constraints. |
| Rogue 9XE / 9XM | GE9215, GE9226, GM9446, GE9608, GE9610, GE9710, GE9920, GM9740 | **Borderline/candidate.** Must confirm an Android Vulkan driver; GTA unverified. |
| A-Series | AXM-8-256, AXT-16-512, AXT-32-1024 | **Candidate.** Vulkan-capable IP, but no SambaS3 GTA device result. |
| B-Series BXE/BXM/BXS | BXE-1-16, BXE-2-32, BXE-4-32-MC, BXM-4-64-MC, BXM-8-256, BXS-1-16, BXS-2-32-MC, BXS-4-32-MC, BXS-4-64-MC | **Candidate.** API capability is not enough to predict PS3 performance. |
| B-Series BXT | BXT-32-1024-MC | **Candidate/expected** if deployed with a good Android Vulkan driver; unverified. |
| D-Series | DXT-8-256, DXT-48-1536, DXT-72-2304, DXD-72-2304, DXTP-16-512-MC, DXTP-48-1536-MC, DXTP-64-2048-MC | **Candidate/expected** for higher configurations; current conformance examples are not GTA-on-Android results. |

PowerVR product availability is implementation-specific. Khronos conformance on Linux or a reference platform proves API conformance for that product/driver combination, not compatibility on every Android device using related IP.

## Required test gate for every device

A device moves from forecast to **Vulkan verified** only when gates 1–2 pass. Each game receives its own gameplay result using the remaining gates and its per-game document:

1. RPCSX log contains `Renderer: Vulkan`.
2. Log contains `Found Vulkan-compatible GPU: '<expected hardware GPU>'` and `Vulkan: Renderer initialized on device '<same GPU>'`; a software rasterizer does not qualify.
3. Validate the selected game's large files against its source; for GTA, `PS3DataMain.obb` is `1,479,813,213` bytes and `PS3Data.obb` is `708,640,703` bytes.
4. Record the active game patches; for GTA, the temporary `Skip null modelinfo crash` patch must be disabled.
5. Reach the per-game controllable checkpoint with correct geometry and collision; for GTA, that checkpoint is Grove Street with HUD and radar intact.
6. Record the title ID, Android version, SoC, exact GPU string, Vulkan driver version, first-run FPS/frame time, warm-run FPS/frame time, and whether shader compilation is still active.

Suggested log check:

```bash
grep -E "Renderer: Vulkan|Found Vulkan-compatible GPU|Vulkan: Renderer initialized|Program compiled successfully" rpcsx_backend.log
```

## Sources

- [Arm Mali Offline Compiler supported-GPU table](https://developer.arm.com/tools-and-software/mali-offline-compiler) — official Bifrost, Valhall and 5th Gen product coverage.
- [Arm: new fifth-generation GPU architecture](https://developer.arm.com/community/arm-community-blogs/b/announcements/posts/arm-gpus-built-on-new-fifth-gen-architecture) — official naming distinction after four Valhall generations.
- [Arm Mali-G615 product page](https://www.arm.com/products/silicon-ip-multimedia/gpu/mali-g615) — G615 is fourth-generation Valhall.
- [MediaTek Dimensity 8300 specifications](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8300) — Cortex-A715/A510 and Mali-G615 MC6.
- [Linux MSM Adreno device catalog](https://github.com/torvalds/linux/blob/master/drivers/gpu/drm/msm/adreno/adreno_device.c) and [A5xx catalog](https://github.com/torvalds/linux/blob/master/drivers/gpu/drm/msm/adreno/a5xx_catalog.c) — upstream family/model identifiers.
- [Mesa Freedreno/Turnip documentation](https://docs.mesa3d.org/drivers/freedreno.html) — Turnip Vulkan generation coverage and the explicit lack of plans for A5xx and older.
- [Khronos Vulkan conformant-products register](https://www.khronos.org/conformance/adopters/conformant-products/vulkan) — driver/product evidence for Qualcomm, Samsung Xclipse, PowerVR and AMD implementations.
- Samsung official product pages: [Exynos 2200 / Xclipse 920](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2200/), [Exynos 1480 / Xclipse 530](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1480/), [Exynos 1580 / Xclipse 540](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1580/), [Exynos 1680 / Xclipse 550](https://semiconductor.samsung.com/processor/mobile-processor/exynos-1680/), [Exynos 2400 / Xclipse 940](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2400/), [Exynos 2500 / Xclipse 950](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2500/), and [Exynos 2600 / Xclipse 960](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2600/).
- [AMD and Samsung extend mobile graphics partnership](https://www.amd.com/en/newsroom/press-releases/2023-4-5-samsung-electronics-and-amd-extend-strategic-ip.html) — official AMD RDNA IP relationship.
- [Imagination developer documentation](https://docs.imgtec.com/) — official PowerVR technical-documentation index.
