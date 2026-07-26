# Play Store bundled Turnip drivers

## Architecture (selection → load)

```text
GpuDriversScreen (playstore|standard)
  → GeneralSettings[selected_gpu_driver, gpu_driver_path, gpu_driver_name]
  → MainActivity / GpuDriverSelection.applyStoredSelection
  → RPCSX.setCustomDriver(path, libraryName, hookDir=nativeLibraryDir)
  → native-lib.cpp JNI
  → adrenotools_open_libvulkan(CUSTOM, hookDir, driverDir, libraryName)
  → libvulkan_freedreno.so from app-private filesDir/gpu_drivers/<id>/
```

System driver: empty path / empty library name → adrenotools path skipped, RPCSX uses system Vulkan.

## Flavors

| Flavor | `INCLUDE_BUNDLED_TURNIP_DRIVERS` | `ALLOW_EXTERNAL_GPU_DRIVERS` |
|--------|----------------------------------|------------------------------|
| `standard` (default) | false | true |
| `playstore` | true | false |

Play assets live only under `app/src/playstore/`. Standard APKs must not contain the three ZIPs.

## Secure install

`ZipUtil` rejects ZIP-slip, absolute paths, oversized entries, and excess entry counts.
`GpuDriverHelper.installDriver` for external streams returns `ExternalInstallDisabled` on Play builds.
Bundled sync verifies SHA-256 from local `catalog.json` before extraction, uses staging + rename, and marks packages with `sambas3_bundled.json` so they cannot be deleted.
