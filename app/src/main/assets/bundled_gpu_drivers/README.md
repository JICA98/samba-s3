# Play Store bundled GPU drivers

This directory is populated by:

```bash
./scripts/package-bundled-turnip-drivers.sh
```

Expected after successful packaging:

```text
catalog.json
turnip-26.1.4-sambas3.zip
turnip-25.3.4-sambas3.zip
turnip-a8xx-v29-sambas3.zip
```

Until the three approved input packages are provided under `drivers/input/`,
this directory intentionally does **not** contain driver binaries.
