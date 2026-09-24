# Samba S3 backend review

Reviewed 2026-09-24. **Verdict: integration needs fixes; performance gap unquantified.**

## Scope

Inspected the initialized local backend, build integration, dirty source, and selected ARM/SPU/PPU/Vulkan paths. No source ZIP found in this workspace; ZIP equivalence is unverified. No builds, device benchmarks, or binary disassembly performed. This is a targeted static review, not an exhaustive emulator audit.

Samba uses **RPCSX/rpcsx**, containing RPCS3-derived code. Fork RPCSX to preserve its Android integration; ordinary RPCS3 is not a drop-in replacement.

## Repository state and freshness

Live GitHub commit endpoints checked during review:

| Component | Revision | Commit date |
|---|---|---|
| Local backend | `657b26a0d197c29d42cdcf3b3f6e8ad5c6765bbc` | Local history |
| RPCSX master | `e8ae1481ab7ba04d5c6bef89dd852aabba2c88ff` | 2026-06-29 |
| RPCSX dev | `b41e09a047549a5506b0501194fd16919c0d615f` | 2026-01-14 |
| RPCS3 master | `c82221f292880272d11302ecf1206f81a970a5ab` | 2026-09-24 |

Sources: `https://api.github.com/repos/RPCSX/rpcsx/commits/master`, corresponding `/dev`, and `https://api.github.com/repos/RPCS3/rpcs3/commits/master`.

Local HEAD is detached; origin is `https://github.com/RPCSX/rpcsx.git`. Comparison against local origin/master: **0 upstream-only / 14 local-only commits**. Working diff: **40 files, +2408 / -430**.

Pushing the frontend does not publish backend objects or dirty files. Current account write permissions and availability of the local pin on every remote were not verified. Preserve the checkout before changing history.

The backend is **not verified current with RPCS3**. Its older version marker is evidence of lineage, not an exact missing-commit count. Local work is ahead of RPCSX's reference, so “behind both upstreams” is misleading. A file/function-level comparison is required before claiming particular upstream optimizations are absent.

## Prioritized findings

Backend paths below are relative to `app/src/main/cpp/rpcsx/`.

### P1: Existing libraries bypass rebuilding — confirmed

Parent `app/build.gradle.kts:162–198` skips rebuilding when both ABI libraries exist. Source, patch, configuration, and toolchain changes are not checked. Verification rejects only when both libraries are missing; printed hashes are not checked against provenance.

The task-name substring gate also excludes a direct `buildRpcsxCore` request or a request named `installStandardRelease`. This is a static observation, not an executed Gradle test.

**Impact:** backend edits can leave an APK containing the old core. Wrapper rebuilds do not establish core freshness.

**Fix:** source-aware inputs, verification for every required ABI, reproducible source/configuration/toolchain identity, output hashes, and confirmation of the library actually loaded.

### P1: Patch preflight fails both directions — reproduced

Parent `build_rpcsx.sh:55–100` reverse-checks, otherwise forward-checks, otherwise aborts. It regenerates `android/src/samba-build-id.cpp`, although the persistent patch includes that file.

Read-only checks executed from the parent repository:

```bash
git -C app/src/main/cpp/rpcsx apply --check "$PWD/patches/rpcsx-submodule-changes.patch"
git -C app/src/main/cpp/rpcsx apply --check --reverse "$PWD/patches/rpcsx-submodule-changes.patch"
```

Both returned **1**. Reverse check reports `android/src/samba-build-id.cpp`; forward check reports multiple files. This reproduces the preflight failure, not an entire build. Generated-file mismatch is involved; remaining divergence must also be reconciled.

Build identity records HEADs and patch hash, not arbitrary dirty source changes. Different binaries can report the same identity.

**Fix:** preserve local work; move generated identity outside patched source; commit backend modifications; reconcile patch handling; prove fresh and repeated builds before retiring the patch.

### P1: Initialization forces LLVM CPU target — confirmed

`android/src/rpcsx-android.cpp:2319–2327` sets `g_cfg.core.llvm_cpu` to `"cortex-a34"` after initialization and saves settings. This is stronger than the fallback described at `rpcs3/util/JITLLVM.cpp:985–999`.

PPU consumes this configuration at `rpcs3/Emu/Cell/PPUThread.cpp:6266,6356`; SPU at `rpcs3/Emu/Cell/SPULLVMRecompiler.cpp:70`. Later configuration can override it; not every compilation is proved to use A34.

**Impact:** automatic targeting/user choice is overwritten at initialization. Potentially unsuitable scheduling/features on newer cores; FPS impact unmeasured.

**Fix:** establish why the override exists; log effective target/features; test safe target selection. Generated instructions must remain supported on CPUs where the code can execute.

### P1: Affinity overrides OS mode and assumes topology — confirmed

Android applies explicit affinity at `rpcs3/Emu/CPU/CPUThread.cpp:638–645`, `rpcs3/Emu/RSX/RSXThread.cpp:1080–1087`, and `rpcs3/Emu/RSX/RSXOffload.cpp:36–43` even with OS scheduler mode selected.

`rpcs3/util/Thread.cpp:2902–2948` excludes CPUs 0–1 from heavy classes on qualifying systems with at least eight threads; an empty mask becomes `0xFC`. CPU numbering does not establish performance class or process-allowed CPUs. Failure logging at `3262–3268` prints the syscall return rather than useful errno details.

**Fix:** genuine OS-managed mode, topology-aware optional policy intersected with allowed CPUs, effective-mask/error logging. Benefit/regression requires same-device testing.

### P1: Fixed ARM timer conversion — confirmed

`rx/include/rx/asm.hpp:282–298` divides by 182 for ARM64, assuming 19.2 MHz versus 3.5 GHz. Counter reads use `cntvct_el0` (`rx/include/rx/tsc.hpp:15–18`); actual frequency is already available via `cntfrq_el0` (`rpcs3/util/sysinfo.cpp:801–808`). This affects ARM beyond Android.

**Impact:** inconsistent spin/backoff duration across hosts; small values collapse to one tick. Not evidence of a 182× gameplay slowdown or incorrect guest clocks.

**Fix:** audit caller units first; use explicit durations and actual frequency where appropriate. Measure contention, power, frame-time tails.

### P1: Readback ignores wait failure — confirmed control flow

`rpcs3/Emu/RSX/VK/vkutils/sync.cpp:612–661` can return `VK_TIMEOUT`; `rpcs3/Emu/RSX/VK/VKTextureCache.h:298–325` ignores the result before continuing toward DMA flush.

**Impact:** this path can continue without this wait establishing GPU completion. Stale/incomplete readback is a correctness concern; no observed corruption attributed. Relevant to both architectures.

**Fix:** propagate/recover from failure before consuming data. Separately validate skipped DMA, skipped pipeline work, and null-event success against caller contracts; avoiding a crash does not establish correct emulation.

### P2: ARM lacks x86 SPU first-tier compilation — confirmed, cost unmeasured

Constructors select `make_fast_llvm_recompiler()` on x86 and `make_llvm_recompiler()` on ARM (`rpcs3/Emu/Cell/SPUThread.cpp:2110–2122,2176–2188`).

“Fast” means tiering: `rpcs3/Emu/Cell/SPUCommonRecompiler.cpp:7675–8022` emits an executable x86 first tier, including interpreter calls, then queues ordinary LLVM. Workers compile and replace the entry (`7378–7429`). ARM dispatch compiles synchronously (`2137–2146`). Cache preparation uses ordinary LLVM on both (`833–853`).

**Supported:** ARM may stall longer on uncached blocks. **Unsupported:** ordinary LLVM necessarily produces slower final code, or this is the largest sustained-gameplay bottleneck.

Measure cold compilation versus warm-cache execution before undertaking an ARM first-tier compiler.

### P2: Additional investigations

| Area | Evidence | Assessment |
|---|---|---|
| ARM instruction publication | `rpcs3/Emu/Cell/SPUCommonRecompiler.cpp:2031–2058,2093–2124` | Writes instructions, issues barriers without explicit cache maintenance at these sites. Audit surrounding publication/coherence guarantees. Barriers alone are not a portable cache flush; no attributed crash. Compare `rpcs3/util/JITLLVM.cpp:341–349`. |
| Manifest validation | `rpcs3/Emu/Cell/PPUThread.cpp:247–280,329–333,4529–4535` | Inventory precedes manifest-hit selection; recursive traversal and full MSELF hashing can remain. Cost per reached invocation, not established every boot. |
| JIT reservations | `rpcs3/util/JITLLVM.cpp:186–232,292–310` | Reserves 768 MiB, commits incrementally, decommits without releasing on destruction. Repeated arenas can retain virtual ranges; not equivalent RSS leakage. |
| GPU-label lifetime | `rpcs3/Emu/RSX/VK/vkutils/sync.cpp:35–40,407–443` | Pool rollover unmaps old backing while label objects retain pointers. Conditional lifetime concern if labels survive rollover; overlap not demonstrated. |

## Corrections to the earlier review

- **Both Vulkan host event paths poll.** `sync.cpp:383–390,612–661` polls driver event status or mapped-label status. Forced Android labels (`208–220`) do not replace a blocking x86 wait. Net cost requires measurement. Preserve transfer-to-host dependencies (`483–502`).
- **8 MiB stack size is not 8 MiB RSS.** `rpcs3/util/Thread.cpp:2122–2141` configures threads created through this wrapper, not every pthread. No evidence ties stack residency to the compile cap.
- **Two-worker limit is scoped.** `rpcs3/Emu/Cell/PPUThread.cpp:117–147` clamps bounded install/prelaunch contexts, not all gameplay compilation. Preserve memory workarounds until replacement behavior is tested.
- **`std::atomic::fetch_or` does not prove a library call.** `rx/include/rx/asm.hpp:357–369` requires binary inspection to distinguish LSE, helpers, and LL/SC. The write-fault probe must still attempt a write.
- **No measured bottleneck ranking exists.** The previous “largest gameplay gap” claim is withdrawn.
- Parent `build_rpcsx.sh:10–13` uses `RelWithDebInfo` for release. A Debug-default explanation is unsupported.

## Safely create a writable backend fork

Instructions only: no fork, remote change, commit, or push performed. GitHub CLI fork flags checked against current Context7 documentation: https://cli.github.com/manual/gh_repo_fork.

### 1. Preserve original work

Back up the whole working checkout, including the parent's `.git`, `.git/modules`, and untracked files. A source ZIP or Git bundle alone does not preserve both dirty files and submodule history. Do not reset or replace the backend before preservation.

Run from the original Samba checkout, in one shell. Stop on any error:

```bash
ROOT="$(git rev-parse --show-toplevel)"
CORE="$ROOT/app/src/main/cpp/rpcsx"
test "$(git -C "$CORE" rev-parse --show-toplevel)" = "$CORE" || exit 1
git -C "$CORE" status --short
git -C "$CORE" log -5 --oneline
git -C "$CORE" remote -v
```

### 2. Create the fork

```bash
gh auth status
OWNER="$(gh api user --jq .login)"
test -n "$OWNER" || exit 1
FORK_URL="https://github.com/$OWNER/samba-s3-core.git"
gh repo fork RPCSX/rpcsx --fork-name samba-s3-core --clone=false --remote=false
```

Inspect an existing fork rather than assuming creation succeeded. Keep non-default branches; do not select default-branch-only.

### 3. Publish recovered local history and reviewed changes

```bash
git -C "$CORE" switch -c samba/android-core
git -C "$CORE" remote add samba "$FORK_URL"
git -C "$CORE" add -p
```

If the branch or remote exists, stop and inspect it. Add intended new source files explicitly. Do not blindly stage generated identities, binaries, credentials, or unrelated work. Review any previously staged changes too:

```bash
git -C "$CORE" diff --cached --check
git -C "$CORE" diff --cached --stat
git -C "$CORE" diff --cached
```

Commit only if reviewed staged changes exist; otherwise skip the commit:

```bash
git -C "$CORE" commit -m "Preserve Samba S3 Android backend changes"
git -C "$CORE" push -u samba HEAD:refs/heads/samba/android-core
```

Do not force-push. Publishing preserves ancestor commits, not remaining dirty files. Reconcile remaining source changes before treating the fork as reproducible.

### 4. Update the parent only after backend push succeeds

```bash
cd "$ROOT"
git submodule set-url app/src/main/cpp/rpcsx "$FORK_URL"
git submodule sync -- app/src/main/cpp/rpcsx
git add .gitmodules app/src/main/cpp/rpcsx
git diff --cached --submodule=log
```

Ensure staged changes contain only the intended migration, then:

```bash
git commit -m "Use writable Samba S3 backend fork"
git push --recurse-submodules=check
```

Keep the exact commit pin. Test a fresh recursive clone, reconcile the patch against committed modifications, build required ABIs without copied libraries, and verify loaded identity. A fork alone does not fix provenance or patch handling.

## Work order

1. Back up and publish the recovered backend.
2. Repair patch preflight and stale-library reuse; prove fresh and repeated builds.
3. Establish loaded identity, effective LLVM target/features, affinity, settings, recompilers, and driver.
4. Resolve correctness issues; benchmark target, affinity, and timer changes independently.
5. Compare current RPCS3 ARM/SPU/PPU/Vulkan changes against the fork; port verified missing changes with dependency tracking.

Baseline: same phone, game version, scene, settings, cache state, driver, and thermal conditions. Record cold preparation, warm launch, sustained frame-time distributions, guest progression, compilation time, CPU waits, GPU time, RSS, temperature. Separately compare this fork and current RPCS3 on the same x86 host to isolate source-age effects.

**Bottom line:** real integration defects and questionable ARM policies exist. Their performance costs remain unmeasured. Publish and identify the backend reliably before claiming optimization wins.
