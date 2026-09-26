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

Independent review approved this fixture as evidence for the next real-block test only. Source review also found a correctness problem in W12 v1: `set_reg_fixed` bypassed runtime destination-register routing when LLVM builds the shared interpreter. The isolated patch now uses a `value_t<u8[16]>` wrapper with `set_vr`, preserving that route. The corrected patch is SHA-256 `a8b70957463ecb4331ae271fc8932e6acddefa1a29c90ee7ee822cf125674783`; ARM64 syntax validation passes with 76 existing warnings and no errors. It remains isolated pending actual interpreter and real-block testing.

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

The lowering is integrated as the default-OFF CMake option `SAMBA_EXPERIMENTAL_SPU_SHUFB_TBL1`. Enabling it on ARM64 selects the reviewed TBL1 lowering and `s3cg4` object-cache tag. Disabling it retains the old lowering and `s3cg2`. This permits an explicitly identified test build while preserving the existing default. W20's capability guards are separate from this switch. No updated app or core has been installed.

Next, execute generated guest cases and build the integrated candidate, then measure matched, profiler-off gameplay with advancing useful frames, CPU time per frame, memory growth and the separate many-enemies-plus-magic crash scenario. Neither compiler success nor the microbenchmark completes the user's game-efficiency objective.
