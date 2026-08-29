# In-game navigation and savestate previews

## Navigation ownership

- Android Back is intercepted for the lifetime of `RPCSXActivity`.
- Running or paused gameplay opens the in-game menu; menu Back is delegated to
  the current page; Back during a recovery transition is consumed.
- Physical Guide/Home and the overlay PS action are frontend-owned. They are
  edge-triggered, consumed before guest gamepad mapping, and do not remain
  pressed when the menu opens.
- The explicit Exit Game action remains the normal graceful activity exit.

## Savestate previews

The pre-save transition `PixelCopy` frame is downscaled to a maximum long edge
of 720 pixels and encoded as WebP quality 84 on `Dispatchers.IO`.

For a savestate at `<path>`, the canonical sidecar is:

```text
<path>.preview.webp
```

Each save request first writes `<path>.preview.<requestId>.tmp.webp`. The final
sidecar is atomically published only after the matching exact savestate
`COMMITTED` event. A failed save removes only its request-owned temporary file;
an overwrite therefore preserves the old slot and preview. If capture fails
but the save commits, the old preview is removed and the UI shows a placeholder.

The UI cache key includes the preview modification time so overwriting a slot
cannot leave a stale bitmap in memory.

## Verification

Run the JVM/Robolectric coverage with:

```bash
./gradlew :app:testStandardDebugUnitTest :app:testPlaystoreDebugUnitTest
```

Build both debug distributions with:

```bash
./gradlew assembleStandardDebug assemblePlaystoreDebug
```

On a connected tablet, validate repeated gameplay Back/menu Back, touch PS,
physical Guide, mixed input, distinct-scene saves, and a slot overwrite. Keep
the long-play exact-slot restore validation as the release gate.
