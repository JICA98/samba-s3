# Bundled GPU drivers — Samba S3

This directory is populated by:

```bash
./scripts/sync-bundled-turnip.sh
```

Pinned provenance: The412Banner/Banners-Turnip v26.3.0-20260826-r3
Upstream asset: Turnip-v26.3.0-20260826-r3.zip
SHA-256: 94641a7e496f5d1f21d92d587d2f9336c0773582f38601e9d666b44240e3c8b8

Contains exactly one prebaked package:

```text
catalog.json
turnip-26.3-sambas3.zip   // wrapper with identical libvulkan_freedreno.so bytes
```

System Vulkan remains default; Turnip is never auto-selected.
