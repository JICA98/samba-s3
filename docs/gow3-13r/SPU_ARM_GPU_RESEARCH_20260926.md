# LLVM SPU efficiency: ARM and GPU investigation

The current baseline remains the LLVM SPU decoder. The user reports immediate crashes with the alternative JIT decoder and out-of-memory failures with the other alternatives. No decoder switch is proposed as an optimization.

## Measured ARM experiment

W19 compiled an expression-level SHUFB fixture **on the OnePlus 13R with the same LLVM 20.1.3 static library package used by the Android core**. The generated object was linked into a small native test executable. Neither the app nor its core library was replaced. The target was the currently configured `cortex-a34`, with `+fp-armv8,+neon,+crc,+crypto`. This used standard LLVM O3 IR optimization and aggressive machine-code optimization, rather than the full RPCSX translator and GHC pipeline.

The candidate replaces the dynamic two-source shuffle's final bit-selection with two independent NEON TBL1 operations whose indices make the unselected source return zero. Special zero/0xff/0x80 control bytes retain their existing semantics. It avoids the consecutive-register requirement of the previously reverted TBL2 experiment. This does not establish that the historical LLVM register-scavenging failure is fixed.

Both tested CPU cores passed **400,640 full-vector cases**, including every control byte at every lane, random vectors, identical source operands, and a scalar reference. Nine alternating baseline/candidate rounds were measured per mode and CPU, with affinity checked and matching recurrence checksums. Each timed round contained sixteen million shuffle expressions plus identical loop, load and XOR work.

| Fixture | CPU 6: candidate/baseline CPU time | CPU 7: candidate/baseline CPU time |
|---|---:|---:|
| General controls, mask reused within sixteen operations | 0.7655 | 0.7656 |
| General controls, mask changes per operation | 0.7657 | 0.7664 |
| Permutation-only controls, reused mask | 0.9998 | 1.0001 |
| Permutation-only controls, changing mask | 1.0004 | 0.9999 |

The general-control synthetic loops used about **23.3–23.5% less thread CPU time**. Permutation-only loops were effectively unchanged. Thermal snapshots before and after the tests reported status 0. Clocks were not locked, and the second source is loop-invariant in both fixture types. These ratios describe the tested recurrences; they are **not a measured reduction in GOW3 SPU CPU usage or an FPS gain**. The standalone general functions both contain fifteen instructions including return; the permutation-only functions both contain ten. Instruction count alone does not explain or establish runtime benefit.

Independent review approved this fixture as evidence for the next real-block test only. Source review also found a correctness problem in W12 v1: `set_reg_fixed` bypassed runtime destination-register routing when LLVM builds the shared interpreter. The corrected patch uses a `value_t<u8[16]>` wrapper with `set_vr`, preserving that route. Its SHA-256 is `a8b70957463ecb4331ae271fc8932e6acddefa1a29c90ee7ee822cf125674783`; ARM64 syntax validation passes with 76 existing warnings and no errors. Subsequent W21/W23 validation and default-OFF integration are described below.

Private reproducible evidence is in `.git/gow3-13r-private/jobs/W19-SHUFB-CODEGEN-001/`: IR generator, scalar-reference runner, LLVM emitter, object/disassembly, device artifact hashes, raw timing records and `summarize.py`. The script checks all paired records and host/device artifact hashes. This fixture does not cover RPCSX's register routing, GHC transforms, guest execution, cache lifetime, interrupts, save/load, or the historical high-pressure spill case.

## Which guest code warrants testing

W18 independently parsed the read-only GOW3 Mega cache: 2,775 records, 246,474 stored instruction words, all nonzero CRCs valid, two duplicate records, and zero unknown decoded opcodes. SHUFB appears 16,326 times across 1,351 records. LQD is the most common stored opcode, followed by SHUFB. These are static cache occurrences, including overlapping compiled programs; they do not measure runtime instruction frequency.

The offline analyzer maps 234 real rows from W17's first displayed aggregate chart uniquely to full cache hashes and compile-log hashes. Two other rows are synthetic profiler markers. A 1,016-word cached block at entry `0x10760` contains 145 SHUFB instructions and is a useful code-generation stress fixture. Selection as a stress fixture does not establish gameplay hotness. Cache bytes and per-block metadata remain private under W18.

W17 is explicitly excluded from gameplay attribution: the assistant foregrounded MainActivity while setting up a screenshot and interrupted the run. See `W14_W15_DEVICE_FINDINGS.md`. Its first flush covers startup/intro. The CPU profiler does export block samples, separately from the compiler's internal sampling mechanism. It requests a 60-microsecond sampling wait and polls emulator state; it does not measure Linux scheduled CPU time. A nonzero reservation address indicates a live reservation, not time spent executing or spinning in GETLLAR. Profiler-on FPS cannot score an optimization. The final aggregate also re-adds cumulative counters after a prior flush, so the analyzer uses only the first flush.

## Optional ARM features and CPU tuning

All eight on-device `/proc/cpuinfo` entries report ASIMD, dot-product, I8MM and BF16, with no SVE/SVE2. A standalone `getauxval` probe confirms `AT_HWCAP=0xefbfffff`, `AT_HWCAP2=0x1ae181`, and no SVE/SVE2. The hardware timer reports 19,200,000 Hz. Linux's supported mechanism for checking these features is the auxiliary vector; CPU identity alone is insufficient. [Linux ARM64 HWCAP documentation](https://docs.kernel.org/arch/arm64/elf_hwcaps.html)

The observed A34 target is explicitly configured, rather than evidence that CPU detection failed. The source already recognizes the device's three CPU part IDs. Its auto path can select A520, but LLVM's CPU defaults can include SVE/SVE2 despite their absence from the kernel-exposed feature set. W20 implements negative OS capability guards for ten modeled LLVM features, preserving explicit CPU selection. Linux ARM64 object-cache keys include a deterministic feature fingerprint. The installed setting stays unchanged. A34 already uses NEON; newer CPU tuning does not itself prove a speedup.

Host policy tests, ARM64 and x86_64 syntax checks, and an exact LLVM 20.1.3 on-device feature probe passed. The expanded probe verifies that disabling SVE alone also disables SVE2/SVE2BitPerm, and disabling full FP16 disables FP16FML. A34 retains its baseline; A520 with the captured OnePlus capabilities retains the tested supported features while losing SVE/SVE2. Independent review approved source integration for testing. This is a prerequisite for newer CPU tuning, not a measured speedup or a complete guard for every ARM extension. The SPU feature label can say `base` despite inherited NEON; it is not authoritative ISA evidence.

The portable policy regression can be run from the repository root with:

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pedantic -Iapp/src/main/cpp/rpcsx scripts/tests/aarch64-feature-policy.cpp -o /tmp/samba-aarch64-feature-policy
/tmp/samba-aarch64-feature-policy
```

## GPU offload conclusion

A general SPU GPU decoder is not the next candidate. SPU execution interacts with local store, channels, DMA, reservations, events and guest scheduling. Moving short blocks between CPU and Vulkan would require explicit ordering and completion handling while sharing GPU resources with RSX rendering. This is an engineering inference from the emulator source and Vulkan synchronization requirements, not a measured proof that every offload is slower. [Khronos synchronization examples](https://github.com/KhronosGroup/Vulkan-Docs/wiki/Synchronization-Examples)

A GPU experiment would need a measured, long-running, batchable pure-data guest kernel with no intermediate host interaction. No such GOW3 kernel is established by the current evidence. Any future test must include preparation, dispatch, synchronization and readback costs and must preserve RSX frame pacing.

## Real SPU compiler validation and implementation

W21 links the corrected SPU recompiler translation unit ahead of an existing production support DSO in a standalone Android executable. It uses LLVM 20, the actual translator/GHC pipeline, `cortex-a34`, and Mega blocks. Both the 1,016-word shuffle-pressure fixture and a 118-word reservation-related fixture pass byte-for-byte analyzer comparison and complete MCJIT finalization on the phone. A private test hook returns before publishing or executing guest functions. The support DSO is from core `63e343bd8`; the candidate translation unit is based on `f2c35d714` plus W12. This hybrid is compiler evidence, not an integrated emulator build.

The actual `make_llvm_recompiler(11)` interpreter also compiles successfully. Independent review of its captured `spu_SHUFB` IR confirms that the destination address is derived from the runtime opcode before storing the result. This verifies the routing path missed by W12 v1. It does not execute the generated interpreter function. Initial harness failures were fixed by matching FXO initialization, Android heap-tagging policy and Mega block settings; standalone teardown is deliberately bypassed after flushing results.

W23 subsequently executes that captured SHUFB body on the OnePlus. The fixture converts its calling convention to C and removes interpreter dispatch setup and the terminal dispatch path, preserving the register-address calculations, source loads, TBL1 operations and result store. The exact LLVM 20.1.3 emitter produces the object; the NDK compiler links the C test driver. All **9,106 cases pass**, including every selector at every lane, eight alias patterns, explicit destination-register changes and 5,000 random cases. The test also checks that other registers are unchanged. Independent scalar replay matches digest `f4ae686f103f6396`. This proves the adapted body and routing semantics, not GHC entry/return behavior, whole guest execution or performance. The post-test thermal status is 0.

The lowering is integrated as the default-OFF CMake option `SAMBA_EXPERIMENTAL_SPU_SHUFB_TBL1`. Enabling it on ARM64 selects the reviewed TBL1 lowering and `s3cg4` object-cache tag. Disabling it retains the old lowering and `s3cg2`. This permits an explicitly identified test build while preserving the existing default. W20's capability guards are separate from this switch. No updated app or core has been installed.

W24 makes the embedded core ID and artifact manifest record the option from the actual CMake cache, so a source-local compiler definition cannot leave the baseline and candidate with indistinguishable configuration identities. ON, OFF, absent and unavailable states are distinct; requested JSON metadata cannot override the cache. The 19-test provenance suite passes, including missing/malformed metadata and option-order regressions. The existing build directory was restored to OFF; no build was performed during configure verification.

## Frame evidence for the integrated comparison

The checked-in core does not call the bridge's exported guest-flip callback. Existing bridge fallbacks count Vulkan/ANativeWindow presentations, and S3BENCH previously labeled its start message `source=emu_flip` unconditionally. That start message now says `pending`, and each sample includes the native JSON's actual `fpsSource`. Coarse host-present data must not be relabeled as useful guest FPS.

Both planned test cores include the previously reviewed W07 opt-in diagnostic trace. Set `debug.rpcsx.guest_present_trace=1` **before starting the app process or any renderer flip** to collect `GOW3_GUEST_PRESENT` records; leave it unset or `0` for normal operation. The trace pairs non-skipped guest flip metadata with the synchronous Vulkan present call and its exact result. It is disabled by default. The integrated diff matches reviewed patch SHA-256 `e94379869a6d39db1a7989e9136905edc1201b3170cc56de76ad4600bcbea728`, and ARM64 syntax checking passes.

This is diagnostic correlation only: an accepted present does not establish GPU completion, scanout, unique useful pixels or normal guest speed. Trace-on logging cost and on-device correlation still require validation. A claimed FPS improvement needs matched active gameplay and further frame-truth evidence; this trace alone cannot satisfy the final frame gate.

## Integrated core artifacts and pending release testing

W25 built both integrated ARM64 cores successfully from parent `2f667ccd5b7243270f50da7ac9643a289a139b11` and core `20a8d2abd9ceef9c1f260ba41d2f14af7c757d23`. Both use the same toolchain and single-job ThinLTO link setting. Independent artifact review approved the corrected pair for testing:

| Variant | Core library SHA-256 |
|---|---|
| Baseline, SHUFB TBL1 OFF | `cae94f5a72c6fa687064df504347cdbe03df3f2985d7164fd0e497e2cc2b45dc` |
| Candidate, SHUFB TBL1 ON | `7e94c503014a56dcb1d02de3d3d94dbaa61a23211a8112e96f5078e7de8d19a2` |

Each manifest matches its library bytes and embedded option identity. Both carry integration digest `a7b57096ce893d698c85273c6fc4cb0b47450bf4f2df0089d1c152643963c503`. Earlier provisional artifacts omitted a CMake integration input and are excluded. The corrected ON cache and compiler database were independently inspected live, but were not archived before restoring OFF; the result record preserves that limitation. The build cache, identity stamp and output were restored to the accepted OFF baseline. Main packaged libraries remain unchanged.

W27 built isolated standard release APKs from this pair, preserving provenance checks and skipping only the task that would rebuild and overwrite the selected core. Both builds and packaged-core verification passed. Both APK signatures verify and match the signing certificate of the currently installed release, allowing an in-place update without uninstalling.

| Standard release APK | APK SHA-256 |
|---|---|
| Baseline OFF | `543d9373c207c4f4674d1e9840519778233f8bdc2094f25f3db867daab1bbead` |
| Candidate ON | `f07b98ba4ecf16483fdf18942b4d365f170126dc7eec393cd5c51fb02692ecbd` |

Private artifacts are under `.git/gow3-13r-private/jobs/W27-RELEASE-PACKAGING-001/artifacts/`. No optimized app or core has been installed yet. Only the OnePlus is connected. The Poco remains unreachable, so installation is pending either both-device availability or an explicit exception to the repository's both-device update requirement.

Next, execute the integrated candidate and measure matched, profiler-off gameplay with advancing useful frames, scheduled CPU time per frame, memory growth and the separate many-enemies-plus-magic crash scenario. Neither compiler success nor the microbenchmark completes the user's game-efficiency objective.

## Additional baseline and water-horse report

At the user's request to start looping tests immediately, W28 run 1 used the **already installed release**, not either W25 artifact. Its effective log confirms LLVM SPU, `cortex-a34`, Mega blocks and Turnip `SambaS3-A7xx-V3` version 26.2.99. The opening sequence advanced into the water-horse scene. Two R2 pulses were delivered, but the screenshots do not establish magic executing against a large enemy group; this is not a reproduction or dismissal of the reported magic crash.

The run ended with an intentional successful stop after approximately 5 minutes 45 seconds of backend lifetime. The captured backend log contains no fatal, access-violation, device-lost or frozen-emulation marker. Thermal monitoring reached SEVERE and did not reach the CRITICAL stop threshold. Overlay snapshots and surface-present counters are exploratory evidence only; they do not establish useful guest FPS or an optimization improvement. Private evidence is under `.git/gow3-13r-private/jobs/W28-LIVE-TEST-LOOP-001/run1/`.

The installed package was subsequently hashed before any update: APK `fce24de193e40dc772570204e2f4f8061ed28a72453747b9fd97d09ac792f821`, packaged core `6027981f508bb11183190b01b6d38501b43c0ffd6889fac4b9ee83eee2300b44`. The fresh backend log identifies `v20260926-63e343b`; active process maps were unavailable on the non-debuggable release.

W28 run 2 reused the process for a warm restart. An intentional stop during startup at 17:50:02 local triggered a `vkDestroyBuffer` read at address `0x120`, reported as SIGTRAP by the fatal handler. The stop acknowledgement was absent. The captured startup log ends before a game frame, so this is a lifecycle failure, not magic or phase-3 reproduction. A native caller backtrace is missing; source inspection alone does not identify which buffer owner supplied the invalid state. Further old-release testing was stopped to prioritize the optimization APK. Evidence is preserved in the adjacent `run2/` directory.

The user separately reports that **Kratos becomes bugged or invisible during phase 3 of the water-horse battle**. Phase 3 and that failure are not verified in W28 run 1. The next reproduction must preserve the scene before/after disappearance, delivered inputs, exact core/driver/settings, renderer errors, whether enemies and effects still advance, and whether Kratos still responds or takes damage. This distinguishes a missing character draw from a guest-state or whole-renderer failure without assigning a cause prematurely.
