# Fast LLVM v3 diagnostic run — failed

## Outcome

The user reported stopped frame rendering. Backend evidence confirms the RSX thread terminated at 93.824 seconds of emulator runtime with `Local RSX REPORT offset out of range`, offset `0x10dca0`, location `0x66626660`. The application process remained alive on a frozen gameplay frame. This run does not meet either performance target.

- Device: OnePlus 13R `d30a1726`.
- Original PID: `26155`; session `1790569943844-3ecd94c4`; request `s3-1790569943723594732-397918-22684`.
- APK: `samba-s3-fastllvm-v3-probe-release.apk`, SHA-256 `b0ecff0a6bd33a7d3011bd7a8cea0ea2b824d870c5d8fac91333ee66505dbad1`.
- Core SHA-256: `66db2ea6861d31f5ac724629100f85c9e6722ef843e23a1300b40a6b58d2b60b`. Runtime S3CORE source digest matches `b6033e31f9434621328d10c0157160521ed0884aa785c6374068252d175fd99b`.
- Release build, native provenance, signature, and 16 KiB APK alignment checks passed.
- Fast LLVM enabled; original warm cache retained; Square, Cross and Circle script acknowledgements captured.
- V2 purge removed. This candidate added sparse Vulkan allocation diagnostics; no rendering or allocation policy changed.

## Failure and stop

[Backend log](all-logs/cache-RPCSX.log) contains the fatal at line 17526; the FIFO dump identifies `NV4097_GET_REPORT: 0x0110dca0`. [Stalled screenshot](stalled.png) shows no current FPS and RSX at 0%. [Thread snapshot](stall-state.txt) confirms the original process was still alive.

The canonical stop script was sent after collecting the failure evidence. The original process then aborted with SIGABRT at epoch 1790570092.227. The host wrapper timed out after 50 seconds waiting for an acknowledgement; this was not a clean-stop success. [Exit info](stop-exit-info.txt) identifies PID26155 and signal6. The launcher recovered as PID26889. No game remains running. A specific allocator failure cause is not established by the captured current-session signal alone.

## Memory evidence

The sampled render rings did **not grow**: attribute64 MiB, upload64 MiB, index16 MiB and combined uniforms128 MiB. The final pre-fatal pool sample reports system336 MiB, surfaces38.9 MiB, textures324.6 MiB, scratch10 MiB. These figures cover the named emulator allocations; they do not include all driver allocations, allocator block slack, or the undefined DMA pool.

[System memory during gameplay](gameplay-proc-meminfo-early.txt): MemAvailable 1,109,492 KiB, Shmem 5,151,296 KiB, Unevictable 5,154,844 KiB. Device-wide RAM used is computed as MemTotal minus MemAvailable, matching the app HUD. GPU-pinned memory or other shared allocations require further attribution; shrinking the render rings is not justified by this run.

[Initial screenshot](gameplay.png) showed 17.3 FPS and RAM10.3 GB. This is a point sample during a changing scene, not a controlled improvement or an average. [Stalled screenshot](stalled.png) showed RAM10.6 GB.

## Evidence and limits

[Launch capture](launch-logcat.txt), [memory samples](vulkan-memory.txt), [full failure diagnostics](all-logs/), [stop-crash diagnostics](stop-crash-logs/), [stop command output](stop.txt), and [stop lifecycle](stop-lifecycle.txt) are preserved. Protected Android sources can be denied; missing tombstones or kernel details do not prove absence of a fault. Older rotated logs must be filtered by original PID/session and timestamps. No continuous gameplay recorder was restarted; screenshots are launch/failure milestones.

## Next actions

Trace the invalid report command and its context, and attribute shared/driver memory beyond the named pools before changing memory budgets. Keep Fast LLVM enabled. No offset clamp, fabricated query result, FPS gain, or clean-stop claim is supported by this run.

## Source comparison

The report-address resolution and RSX thread source match the last version-bump core revision `285402486c314a722243c3b1011c70a34c5993a9`; the next candidate cannot assume these functions recently regressed. The historical upstream [ZCULL underflow fix](https://github.com/RPCS3/rpcs3/pull/12735) is already present locally (`previous_index = (current_index + max_stat_registers - 1) % max_stat_registers`). Reapplying that fix would not address this run. An upstream issue with similar text is not proof of the same cause.
