# Bundled Turnip driver inputs (Play Store)

Place the **three approved** Turnip packages here before packaging into the Play Store
source set. Do **not** commit the raw upstream ZIPs or unpack `.so` binaries into git
unless your release process explicitly allows it.

## Required input files

| File | Role | UI label |
|------|------|----------|
| `turnip-26.1.4.zip` **or** any archive containing Mesa Turnip **26.1.4** (`libvulkan_freedreno.so` + `meta.json`) | Recommended for Adreno 6xx/7xx | `Turnip 26.1.4 — Recommended` |
| `turnip-25.3.4.zip` **or** archive for Mesa Turnip **25.3.4** | Compatibility fallback | `Turnip 25.3.4 — Compatibility` |
| `a8xx-turnip-gen8-V29.zip` | Experimental A8XX (**normal/non-sync**, not the sync package) | `Turnip A8XX v29 — Experimental` |

Exact filenames accepted by `scripts/package-bundled-turnip-drivers.sh`:

```text
drivers/input/turnip-26.1.4.zip
drivers/input/turnip-25.3.4.zip
drivers/input/a8xx-turnip-gen8-V29.zip
```

Alternative names (auto-detected by the packaging script if the exact names are absent):

```text
*26.1.4*.zip
*25.3.4*.zip
*a8xx*V29*.zip   (must NOT contain "sync" in the name)
```

## Packaging

```bash
./scripts/package-bundled-turnip-drivers.sh
```

This rewrites each package into Samba S3 ADPKG format under:

```text
app/src/playstore/assets/bundled_gpu_drivers/
├── catalog.json
├── turnip-26.1.4-sambas3.zip
├── turnip-25.3.4-sambas3.zip
└── turnip-a8xx-v29-sambas3.zip
```

Each packaged ZIP contains at least:

```text
meta.json
libvulkan_freedreno.so
LICENSE-MESA   (when present upstream)
SOURCE.txt
```

## Missing files

If any of the three inputs is missing, packaging aborts and the Play Store artifact
must **not** be claimed complete. The application code and unit tests still build
using synthetic fixtures under `app/src/test/`.

## Provenance

Record upstream source repository, version, and commit (when known) in
`SOURCE.txt` / `catalog.json`. Do not invent checksums or commits.
