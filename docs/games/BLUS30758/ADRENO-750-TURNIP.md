# BLUS30758 — Adreno 750 / Turnip

| Field | Verified value |
|---|---|
| Device | OnePlus OPD2403, Snapdragon 8 Gen 3, Android 16 |
| GPU architecture / exact GPU | Adreno A7xx / Adreno 750 |
| Driver | Installed folder `turnip-26.3`; runtime Vulkan version **26.2.99** |
| Core | Samba ee1403a, RPCSX 657b26a0d, Debug; see hash proof below |
| Host synchronization | gpu_label, proven by S3VKSYNC log |
| Furthest verified visible stage | Earlier intro graphics; current runs turn black after startup |
| Menu / controllable world | NOT VERIFIED |
| 10m / 20m stable gameplay | NOT VERIFIED |
| All-Android synchronization safety | NOT PROVEN |

The game prints `Time to Press Start` while the actual surface can be black.
That message alone does not prove a visible Press Start screen. Remaining
verification includes player movement, camera, stability, repeated boot,
resume, clean exit, and restoring settings/driver state.

Evidence, hashes, individual experiments, and native backtrace interpretation:
[validation finding](../../findings/2026-09-04-rdr-adreno750-gpulabel-validation.md).
This result applies to this exact device/driver/title, not all A7xx GPUs.
