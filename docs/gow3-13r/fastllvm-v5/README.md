# Fast LLVM campaign stopped after v5

Date: 2026-09-28. User requested testing through v5, then commit and push. No v6 was built. The performance goal remains paused and unmet.

## Result

[Final gameplay report](runs/v5/GAMEPLAY_REPORT.md): v5 completed a 120-second observation and stopped cleanly. All eight temporary settings were restored. Final HUD: **11.1 FPS / 9.8 GB device RAM used**. The earlier rendering fatal did not recur in this bounded run; a permanent fix is not established.

Fast LLVM was enabled, but the warm cache used ordinary LLVM programs: the final native summary recorded zero baseline attempts, entries and promotions. No Fast LLVM FPS gain is demonstrated. The retained-selector self-modification fixture timeout remains unresolved.

## Published source

| Repository | Branch | Checkpoint |
|---|---|---|
| `jica98/samba-s3` | `master` | App, scripts, evidence and this report |
| `zenithblue-oss/samba-s3-core` | `samba-android` | `24c33f825e1fc7e0ba23943db112a91f409f86fb` |
| `zenithblue-oss/samba-turnip-drivers` | `main` | `5f55fd82d7cf01d3ce1d7ba8896d0afebfc4faf4` |

The 47 promoted files match the tested private build views byte for byte; [inventory](promoted-files.json). The complete native diff also matched before its documentation commit. Earlier unused shared-checkout prototypes and the untested Mega-to-Safe override are preserved in [superseded](superseded/README.md). Runtime uses the tested Mega policy. No dependency symlinks from private build workspaces were committed.

The installed APK was built before these commits, from app base `ac28e24f895f40259e3515071d00b536ac6d1eba` and core base `39c89414bb3cd2f19f8d779437afe5f92517e968` plus the tested changes. Its embedded identity is not the new commit identity. APK/native/source hashes are recorded in [the build manifest](runs/v5/build-manifest.json).

## Checks

- v5 ARM64 native and standard release build passed; packaged-core provenance, signer and 16 KiB alignment passed. Release installed only on OnePlus 13R with data preserved.
- Integration: 49 JVM tests passed, including cache leases, boot continuation, diagnostic routing, saved-slot policy and title settings. [Results](integration-unit-tests.json).
- 34 frame-analysis tests and 28 benchmark-runner tests passed. Failure messages in mocked negative cases are expected test output.
- Modified launch and zram scripts passed Bash syntax checks. The host zram script was preserved but not executed during closure.
- Core whitespace check passed with `cr-at-eol` for existing CRLF files; app and Turnip diff checks passed.
- Turnip scripts passed Bash syntax checks; both existing driver ZIPs passed archive integrity checks. v1–v5 used the Qualcomm system driver, so no new Turnip device validation is claimed.

## Evidence retention

Reports and hash inventories for v1, v2, v3, v3b, v4, v4b and v5 are under [runs](runs/). Selected v5 screenshots, measurements and manifests are tracked. Full logs, collector output, APKs and diagnostic smaps remain in local ignored `artifacts/fastllvm-*` directories, indexed by each run's `evidence-sha256.txt`. Those inventories refer to the full local artifact directories; not every listed file is copied here.

Historical M7 screenshots and prior ARM64 reviews retain their original context. They do not qualify v5 performance. The final game is closed and no capture loop remains active.
