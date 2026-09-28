# Fast LLVM v4b attribution probe — 2026-09-28

## Outcome

**Failed rendering; goal unmet.** At epoch 1790572603.004, PID 31088 logged `FIFO: RET found without corresponding CALL (last cmd = 0x20000)`. Four milliseconds later it logged an invalid local report context, followed by an RSX-thread fatal at 1790572603.020: offset `0x10f7d0`, DMA `0x66626660`. This occurred about 53 seconds after the first memory snapshot/initial rendering.

The terminal image is frozen with RSX CPU at 0%. Its retained 13.7 FPS value is stale and must not count as gameplay FPS. RAM shown is 10.1 GB total device usage. No performance or stability gain is claimed.

Canonical stop caused Scudo `invalid chunk state when deallocating address 0x2000071e053c000` and SIGABRT at 1790572620.829. The stop bridge returned 1 with no successful acknowledgment. The launcher recovered as PID 31966 in MainActivity. Game is no longer running. Full logs were collected before stop, and live stop logs preserve the abort.

## Identity

- OnePlus 13R `d30a1726`; standard release `2026.09.28-fastllvm-v4b-probe`.
- PID 31088; session `1790572477456-ee2772cd`.
- Request `s3-1790572477306113842-464685-13759`.
- APK SHA-256 `880d35af172a490b4fb7ab27589a8be1abc77601a8b483a545dff2328812c16b`.
- Core SHA-256 `2bda1ff2ee59ab9afb2fb3080e00def90843cad1283d22d2897845a562e46c86`.
- Source digest `aff6e5c9ef3f3bebdae358546147a1f838123c7ebba4a91cbcc004cced78f3fe` matched the loaded core.
- Native/app builds, provenance verifiers, release signature and 16 KiB alignment passed.
- Same saved slot 0, preserved warm cache, Fast LLVM ON, Qualcomm system Vulkan driver. No Settings/UI interaction; canonical launch/pad/stop scripts and passive screenshots.

## Changes and diagnostic cost

Retained v4's 64 MiB/best-fit VMA policy. Added two bounded `/proc/self/smaps` copies and limited report-context writer/reset logging. Copies completed in **102.213 ms** and **336.956 ms**, respectively; this intentionally perturbs those frames and disqualifies this run as a clean performance comparison. Both copies completed without the 32 MiB cap being reached.

## Resident memory attribution

Raw smaps is read by the application itself, then pulled through its own external cache directory. Values below are PSS in KiB, not virtual reservation sizes.

| Mapping group | Initial snapshot | 25-second snapshot |
|---|---:|---:|
| Scudo primary + secondary | 603,086 | 1,149,823 |
| KGSL GPU mappings | 126,272 | 1,374,700 |
| Unnamed memfd shared mappings | 379,678 | 381,258 |
| Entire process PSS | 1,312,097 | 3,091,723 |
| Entire process RSS | 1,471,132 | 3,489,444 |

At the second snapshot these groups are approximately **1.10 GiB native allocator, 1.31 GiB mapped GPU PSS, and 0.36 GiB memfd PSS**. These exact conversions supersede the rough numbers in the progress message. RSS for aliased memfd mappings grew while PSS stayed almost constant; virtual reservations must not be treated as committed RAM.

KGSL mappings increased from 497 to 9,939. Their combined virtual Size was 4,759,060 KiB at the second snapshot, while mapped RSS/PSS was 1,374,700 KiB. This suggests further driver/resource attribution is useful; it does not prove the virtual Size is resident or that all system Shmem belongs to the application. Native allocator PSS also grew about 534 MiB after initial rendering.

Raw files: `android-smaps-31088-0.txt`, `android-smaps-31088-1.txt`; parsed totals/groups: matching `.summary.json` files. `analyze_smaps.py` preserves the parser. Five-second system memory samples remain separate.

## Report-context findings

The first 64 observed context writes repeatedly alternate local `0x66626660` (FIFO command `0x3c0180`) and main-host `0xfeed0001` (`0x401a8`), with `is_hle=0`. No `ANDROID_REPORT_RESET` occurred. Normal guest command batches therefore change this context; there is no evidence that an HLE renderer reset caused this failure. The writer trace was capped and does not identify the last context write before the fatal.

The unmatched RET immediately preceding FIFO recovery and the report fatal is new evidence. Next candidate: Ordered & Atomic FIFO fetch for this title through the reversible settings lease, retaining Fast LLVM and v4 memory packing. This is a hypothesis, not a proven fix. No invalid report address is clamped and no GPU command is skipped.

## Harness correction

The first result JSON incorrectly accepted the literal success text inside the bridge's **Missing acknowledgment** error. It has been corrected to `clean_stop_confirmed=false`; the runner now requires exit code 0 as well as the success marker. v4 had independent clean-stop journal evidence and remains valid. Original stop logs are retained.

## Evidence

`launch-logcat.txt`, `bounded-live.log`, `stop-logcat.txt`, `all-logs/`, `gameplay.png`, `terminal.png`, `capture-result.json`, memory snapshots/samples, build manifests and source patch are retained. Native Fast LLVM session totals are unavailable in this aborted stop; configured ON alone is not proof of baseline execution.
