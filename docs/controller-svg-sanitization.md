# Controller SVG sanitization

The original user-supplied files are preserved byte-for-byte in `docs/assets/source/`.
Runtime files are static SVG assets; no WebView, JavaScript execution, or Canvas replacement artwork is used.

## Source files

| Asset | Source file | SHA-256 |
|---|---|---|
| Keyboard | `docs/assets/source/controller-keyboard-user-source.svg` | `3bb83310076b98e9887a32502109c343bd9280257651c7e1f47f66a4573baf05` |
| DualShock 3 | `docs/assets/source/controller-ds3-user-source.svg` | `409b5dadce173275a22364db9e7773faeab40004ad01d11dfedc9214c5f3c4d2` |

## Runtime-only edits

`scripts/sanitize-controller-svgs.py` reproducibly creates the packaged files:

- `controller_keyboard.svg`: removes the browser status text and `<script>`, and converts the source's double-encoded arrow entities to visible arrow glyphs. All 104 `data-code` groups and their geometry remain.
- `controller_ds3.svg`: removes the browser status text and `<script>`, moves the `SELECT` and `START` captions above the small buttons (`y=217` to `y=181`) to avoid the supplied START/Square collision, and crops the unused status band by changing the runtime viewBox from `1000x560` to `1000x500`. Controller geometry and `data-button` groups remain.

| Packaged asset | SHA-256 |
|---|---|
| `app/src/main/assets/controllers/controller_keyboard.svg` | `e288b528993c56052d9eae786dd4729295763c79c59de90120318e0e6e2c1856` |
| `app/src/main/assets/controllers/controller_ds3.svg` | `ee1e282bd12f70d21b2b34f35cb192f1fe17d5198c350aa93120933f5578c23b` |

## Interaction rendering

Coil 3 SVG decoding is provided by `coil-svg`. `AsyncImage` renders the supplied asset, while a transparent Compose layer draws only small press/highlight indicators. Keyboard regions are generated from `data-code` cap rectangles; DS3 regions are generated from `data-button` geometry. `SvgViewportTransform` is shared by rendering, highlighting, and hit testing.

Regenerate the maps after changing a runtime SVG:

```bash
python3 scripts/generate-keyboard-svg-map.py \
  --input app/src/main/assets/controllers/controller_keyboard.svg \
  --output app/src/main/assets/controllers/controller_keyboard_regions.json
python3 scripts/generate-ds3-svg-map.py \
  --input app/src/main/assets/controllers/controller_ds3.svg \
  --output app/src/main/assets/controllers/controller_ds3_regions.json
```
