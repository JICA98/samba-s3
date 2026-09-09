# Uncharted 2: Among Thieves (BCUS98123) — OnePlus 13R

| Field | Value |
|---|---|
| Title ID | `BCUS98123` |
| Device | OnePlus 13R (`CPH2691`), Snapdragon 8 Gen 3 / Adreno 750 |
| Test date | 2026-09-09 |
| Result | Second cached boot reached the title screen, then stalled at the START prompt; not in-game |

The user-observed run reached the Uncharted 2 boot logo and then returned through SambaS3's **Load Failed** recovery UI. The original core logs had already rotated before collection, so that earlier termination cannot be attributed confidently.

A fresh, cleared-buffer run on the current core did **not** crash. It passed the logo, rendered black, and remained alive for more than four minutes. The game repeatedly reported `RsxKick: Timeout while waiting on RSX SPU kicks`, followed by `[SPU-PM] Error: Got too many flags`. At collection time both `rsx::thread` and the main PPU thread were consuming approximately one full host core, with 2.10 GB RSS / 1.94 GB PSS and no fatal signal, Vulkan error, LMK record, or sleepy-thread report. This is a live RSX/SPURS progress stall, not evidence of an out-of-memory kill.

A second and final bounded boot, using the caches accumulated by the first run, progressed further. It rendered the autosave notice at approximately 50 seconds and reached the Uncharted 2 title screen at approximately 105 seconds. The controller bridge acknowledged START twice, but the guest did not advance. The title frame was strongly green/corrupt with rectangular artifacts; the performance overlay stopped reporting FPS and showed RSX at 100%. The process remained alive, with no backend fatal/access violation, frontend/JNI error, Vulkan error, sleepy-RSX report, LMK event, or new crash exit. This is a title-menu render/progress stall, not a successful in-game result.

The test already used the safe baseline relevant to known Uncharted 2 failures: SPU block size `Safe`, multithreaded RSX disabled, and asynchronous texture streaming disabled. Current upstream ARM reports also describe cache-build hangs for Uncharted 2 and note that progress may accumulate over several boots; that is the next bounded hypothesis, not a verified Android fix: <https://github.com/rpcs3/rpcs3/issues/18769>. Upstream also records Uncharted 2 as broken with SPU block size `Mega`: <https://github.com/RPCS3/rpcs3/issues/17774>.

Evidence:

- Fresh live-stall bundle: `/tmp/samba-logs-20260909-090514`
- Second cached-boot/title-stall bundle: `/tmp/uncharted2-attempt2-menu-stall-20260909`
- Earlier audit: `/tmp/uncharted2-user-failure-20260909` (original failure logs unavailable)

Status: **intro/title screen reached after cache accumulation; frozen at the START prompt with corrupted rendering; no fresh crash or OOM evidence**.
