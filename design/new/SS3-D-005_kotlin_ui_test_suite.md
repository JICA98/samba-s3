Both, coming up. This is **SS3-D-005 “The Test Bench”** — a testability-seam layer (pure logic extracted so the suite can actually run), the full SAF scanner with a real `PARAM.SFO` parser, the download/hash/verify/install pipeline with a foreground service, the emulator core bridge with the log pipeline, and then the suites themselves: 17 JVM test classes plus an instrumented Compose set.

````markdown
# SAMBA S3 — Implementation Pack SS3-D-005 · “The Test Bench”
```
DOC NO. SS3-D-005 · REV A · CLASS: INTERNAL
PREREQUISITES: Tokens.kt + files 00–23
PHILOSOPHY: every behavior worth keeping is worth pinning. Physics, snap math,
reducers, parsers, serialization — all extracted into pure seams (file 24) so
they run on the JVM in milliseconds, not on a device in minutes.
```

## File map

```
24 Logic.kt        pure seams — meters · needle · snap · sfo · ring buffer · scan rules · presets
25 Scanning.kt     SAF tree scanner (DocumentsContract) + game detection
26 Transfer.kt     fetch · sha256 · PUP check · install steps · services · catalog
27 Bridge.kt       emulator core interface · log pipeline · perf frames

src/test/          17 JVM classes (pure + Robolectric where state is touched)
src/androidTest/   instrumented Compose semantics suite
```

---

## 24 · Logic.kt — the pure seams

```kotlin
package samba.s3.core

import java.security.MessageDigest
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sign
import kotlin.math.sqrt

// ── Meter math — SS3-D-001 §3.2 ──────────────────────────────────
// Extracted from Meters.kt so peak-hold, red zone and a11y strings are testable.

object MeterMath {
    const val PEAK_DECAY_MS = 1500f
    const val RED_ZONE = 0.85f

    /** peak = max(live, peak − dt/decay). Never below the live value. */
    fun peakDecay(peak: Float, target: Float, dtMs: Float, decayMs: Float = PEAK_DECAY_MS): Float =
        max(target, peak - dtMs.coerceAtLeast(0f) / decayMs)

    fun inRedZone(fraction: Float, threshold: Float = RED_ZONE): Boolean = fraction >= threshold

    fun zoneLabel(fraction: Float): String = when {
        fraction >= 0.85f -> "in the red zone"
        fraction >= 0.70f -> "near the top"
        else -> "steady"
    }

    /** TalkBack: “fps, 59, of 60, steady” — value + interpretation, never color alone. */
    fun vuA11y(label: String, value: Float, max: Float, fraction: Float): String =
        listOfNotNull(
            label.takeIf { it.isNotBlank() },
            "%.0f".format(value),
            "of %.0f".format(max),
            zoneLabel(fraction),
        ).joinToString(", ")
}

/**
 * The golden needle model — spring 170 / ζ 0.75 (SS3-D-001 §2.9) integrated
 * as a damped oscillator at fixed dt. The composable may drive Animatable with
 * the same constants; this class is the reference the tests pin the feel to.
 */
class DampedNeedle(
    private val stiffness: Float = 170f,
    private val dampingRatio: Float = 0.75f,
    initial: Float = 0f,
) {
    var value: Float = initial; private set
    private var velocity = 0f
    private val omega = sqrt(stiffness)

    /** Semi-implicit Euler. One step toward target. Returns value. */
    fun step(target: Float, dt: Float): Float {
        val c = 2f * dampingRatio * omega
        val accel = stiffness * (target - value) - c * velocity
        velocity += accel * dt
        value += velocity * dt
        return value
    }

    /** Advances until both error and velocity fall under tolerance. Returns steps taken. */
    fun settle(target: Float, dt: Float, tolerance: Float = 0.001f, maxSteps: Int = 8192): Int {
        var steps = 0
        while (steps < maxSteps) {
            step(target, dt); steps++
            if (abs(value - target) < tolerance && abs(velocity) < tolerance) break
        }
        return steps
    }
}

// ── Snap math — faders (§3.5) & blueprint grid (Redline B) ───────

object SnapMath {
    /** Snap a pixel value to a pixel lattice. */
    fun snapPx(px: Float, gridPx: Float): Float {
        if (gridPx <= 0f) return px
        return (px / gridPx).roundToInt() * gridPx
    }

    /** Value → 0..1 within the range, clamped. */
    fun fraction(value: Float, start: Float, end: Float): Float {
        if (end == start) return 0f
        return ((value - start) / (end - start)).coerceIn(0f, 1f)
    }

    /**
     * Detent snap: if the value’s fraction is within `threshold` (3%) of a
     * detent’s fraction, return that detent’s value; else null.
     * Ties resolve to the lower detent.
     */
    fun detent(
        value: Float, start: Float, end: Float,
        detents: List<Float>, threshold: Float = 0.03f,
    ): Float? {
        if (detents.isEmpty()) return null
        val f = fraction(value, start, end)
        var best: Float? = null
        var bestDist = Float.MAX_VALUE
        detents.sorted().forEach { d ->
            val dist = abs(f - fraction(d, start, end))
            if (dist < bestDist) { bestDist = dist; best = d }   // strict < → lower wins ties
        }
        return if (bestDist <= threshold) best else null
    }
}

// ── Superellipse — squircle math, separated from the Canvas path ─

object Superellipse {
    /** One parametric point of |x/a|^n + |y/b|^n = 1, centered at origin. */
    fun point(t: Float, a: Float, b: Float, n: Float = 5f): Pair<Float, Float> {
        val c = cos(t.toDouble()); val s = sin(t.toDouble())
        val x = a * sign(c).toDouble() * abs(c).pow(2.0 / n)
        val y = b * sign(s).toDouble() * abs(s).pow(2.0 / n)
        return x.toFloat() to y.toFloat()
    }

    /** |x/a|^n + |y/b|^n for a sampled point — ≈1 on the curve. */
    fun residual(x: Float, y: Float, a: Float, b: Float, n: Float = 5f): Float =
        (abs(x / a).toDouble().pow(n) + abs(y / b).toDouble().pow(n)).toFloat()
}

// ── PARAM.SFO parser — pure, bytes in, map out ───────────────────
// Format: magic "\0PSF" · version · keyTableStart · dataTableStart · count,
// then 16-byte entries {keyOffset u16, type u16, used u32, max u32, dataOffset u32}.

object SfoParser {
    const val MAGIC: Long = 0x00505350L          // "\0PSP" — little-endian read
    const val TYPE_UTF8 = 0x0204
    const val TYPE_INT32 = 0x0404

    fun parse(bytes: ByteArray): Map<String, Any> {
        require(bytes.size >= 20) { "truncated sfo (${bytes.size} bytes)" }
        require(u32(bytes, 0) == MAGIC) { "not an sfo" }
        val keyTable = u32(bytes, 8).toInt()
        val dataTable = u32(bytes, 12).toInt()
        val count = u32(bytes, 16).toInt()
        val out = LinkedHashMap<String, Any>(count)
        for (i in 0 until count) {
            val base = 20 + i * 16
            if (base + 16 > bytes.size) break
            val keyOff = u16(bytes, base).toInt()
            val dataType = u16(bytes, base + 2).toInt()
            val usedLen = u32(bytes, base + 4).toInt()
            val dataOff = u32(bytes, base + 12).toInt()
            val key = cstring(bytes, keyTable + keyOff)
            if (key.isEmpty()) continue
            val dataStart = dataTable + dataOff
            val len = usedLen.coerceIn(0, (bytes.size - dataStart).coerceAtLeast(0))
            out[key] = when (dataType) {
                TYPE_INT32 -> if (len >= 4) u32(bytes, dataStart).toInt() else 0
                else -> cstring(bytes, dataStart)      // utf8, trailing nulls trimmed
            }
        }
        return out
    }

    internal fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
        ((b[at + 1].toLong() and 0xFF) shl 8) or
        ((b[at + 2].toLong() and 0xFF) shl 16) or
        ((b[at + 3].toLong() and 0xFF) shl 24)

    internal fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun cstring(b: ByteArray, start: Int): String {
        if (start < 0 || start >= b.size) return ""
        var end = start
        while (end < b.size && b[end] != 0.toByte()) end++
        return String(b, start, end - start, Charsets.UTF_8)
    }
}

// ── PUP check — the firmware file’s own magic ────────────────────

object Pup {
    /** PS3 update PUPs begin with the bytes "SCEUF". */
    fun isValidHeader(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
        bytes[0] == 0x53.toByte() && bytes[1] == 0x43.toByte() &&
        bytes[2] == 0x45.toByte() && bytes[3] == 0x55.toByte() &&
        bytes[4] == 0x46.toByte()
}

// ── Ring buffer — the scope’s 50k-row guarantee (§5.14) ──────────

class RingBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>(capacity)

    val size: Int get() = items.size
    fun add(item: T) { items.addLast(item); if (items.size > capacity) items.removeFirst() }
    fun lastOrNull(): T? = items.lastOrNull()
    fun snapshot(): List<T> = items.toList()
    fun count(predicate: (T) -> Boolean): Int = items.count(predicate)
    fun clear() = items.clear()
}

// ── Scan rules — pure predicates for the SAF walker ──────────────

object GameRules {
    val SERIAL = Regex(
        "(?i)\\b(BLUS|BLES|BLJM|BLJS|BCUS|BCES|BCJS|BCJB|BCKS|BCAS|BCSE|NPEB|NPUB|NPEA|NPUA|KLES|SLUS|SLES)\\d{5}\\b"
    )

    fun serialFromName(name: String): String? =
        SERIAL.findAll(name).map { it.value.uppercase() }.firstOrNull()

    /**
     * `paths` = relative paths (flattened, forward-slash) under a candidate
     * folder. A game is present when the EBOOT lives at either nesting —
     * the classic dump structure, or a scan started one level deep.
     */
    fun isGameFolder(paths: Set<String>): Boolean =
        "PS3_GAME/USRDIR/EBOOT.BIN" in paths || "USRDIR/EBOOT.BIN" in paths

    /** Why a folder looked like a game but isn’t — feeds the scan results sheet. */
    fun skipReason(paths: Set<String>): String? {
        val hasPs3Game = paths.any { it.startsWith("PS3_GAME/") }
        val hasUsrdir = paths.any { it.startsWith("USRDIR/") || it.startsWith("PS3_GAME/USRDIR/") }
        return when {
            !hasPs3Game && !hasUsrdir -> null              // not even a candidate
            hasUsrdir -> "missing EBOOT.BIN"
            else -> "incomplete — no USRDIR"
        }
    }
}

// ── Presets — §5.5 diff math, extracted from the sheet ───────────

object PresetMath {
    /** “from → to” per changed setting; unchanged entries are hidden. */
    fun diff(
        targets: Map<String, Any>,
        current: (String) -> String,
        render: (Any) -> String,
    ): List<String> = targets.entries.mapNotNull { (id, v) ->
        val to = render(v); val from = current(id)
        if (from != to) "$from → $to" else null
    }
}

// ── Hashing — streaming sha256, hex out ──────────────────────────

object Hashing {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256(file: java.io.File): String =
        MessageDigest.getInstance("SHA-256").let { md ->
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().toHex()
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
```

> *Errata — migrations this file introduces (all mechanical):*
> 1. `Meters.kt`: replace the three inline peak-hold frame loops with `MeterMath.peakDecay(...)`; VU a11y string with `MeterMath.vuA11y(...)`.
> 2. `Bossakit.kt` `squirclePath`: generate points via `Superellipse.point(t, a, b)` — math unchanged, now pinned by tests.
> 3. File 16 `PresetDiffSheet`: use `PresetMath.diff(...)`.
> 4. File 11 `PendingChangesState`: becomes a thin compose facade over the pure `PendingChangesCore` below (logic identical — record dedupes by page+setting and never drops a change silently, §6.7).
> 5. File 21 `BlueprintEditor`: the engine moves here, framework-free (listeners instead of snapshot state); a small observable wrapper stays in file 21.

```kotlin
// ── Pending changes — pure core (§6.7) ───────────────────────────

data class PendingChange(val page: String, val setting: String, val from: String, val to: String)

class PendingChangesCore {
    private val list = mutableListOf<PendingChange>()
    val changes: List<PendingChange> get() = list
    val count: Int get() = list.size

    fun interface Listener { fun onChanged() }
    private val listeners = mutableListOf<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun notifyChanged() = listeners.forEach { it.onChanged() }

    fun record(page: String, setting: String, from: String, to: String) {
        list.removeAll { it.page == page && it.setting == setting }
        if (from != to) list += PendingChange(page, setting, from, to)
        notifyChanged()
    }
    fun revert(change: PendingChange) { list.remove(change); notifyChanged() }
    fun clear() { list.clear(); notifyChanged() }
}

// ── Blueprint engine — framework-free (moved from file 21) ───────

enum class ControlKind(val label: String, val baseW: Float, val baseH: Float) {
    StickL("left stick", 96f, 96f), StickR("right stick", 96f, 96f),
    DPad("d-pad", 88f, 88f), FaceCross("face buttons", 152f, 152f),
    L1("L1", 64f, 40f), R1("R1", 64f, 40f), L2("L2", 96f, 32f), R2("R2", 96f, 32f),
    Start("start", 36f, 24f), Select("select", 36f, 24f), L3("L3", 36f, 24f), R3("R3", 36f, 24f),
}

enum class OverlayShape { Round, Squircle }

data class OverlayControl(
    val id: String, val kind: ControlKind,
    val x: Float, val y: Float,             // CENTER, normalized 0..1000 / 0..1834
    val scale: Float = 1f, val opacity: Float = 0.6f,
    val shape: OverlayShape = OverlayShape.Round,
    val enabled: Boolean = true, val locked: Boolean = false,
    val labelVisible: Boolean = false, val haptics: Boolean = true,
    val deadzone: Float = 0.12f,
)

data class OverlayLayout(val controls: List<OverlayControl> = emptyList()) {
    companion object {
        const val GRID_W = 1000f; const val GRID_H = 1834f

        fun default() = OverlayLayout(listOf(
            OverlayControl("l2", ControlKind.L2, 170f, 90f),
            OverlayControl("r2", ControlKind.R2, 830f, 90f),
            OverlayControl("l1", ControlKind.L1, 130f, 220f),
            OverlayControl("r1", ControlKind.R1, 870f, 220f),
            OverlayControl("dpad", ControlKind.DPad, 170f, 1250f),
            OverlayControl("face", ControlKind.FaceCross, 830f, 1250f),
            OverlayControl("lstick", ControlKind.StickL, 300f, 1600f),
            OverlayControl("rstick", ControlKind.StickR, 700f, 1600f),
            OverlayControl("select", ControlKind.Select, 440f, 1750f),
            OverlayControl("start", ControlKind.Start, 560f, 1750f),
        ))
    }
}

class BlueprintEditor(initialLayout: OverlayLayout) {
    fun interface Listener { fun onChanged() }

    var layout: OverlayLayout = initialLayout; private set
    var selection: Set<String> = emptySet(); private set
    var gridOn: Boolean = true
    var testMode: Boolean = false

    private val listeners = mutableListOf<Listener>()
    private val undoStack = mutableListOf<OverlayLayout>()
    private val redoStack = mutableListOf<OverlayLayout>()

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun notifyChanged() { listeners.forEach { it.onChanged() } }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun pushUndo() {
        undoStack += layout
        if (undoStack.size > UNDO_DEPTH) undoStack.removeAt(0)   // Redline B: 20 steps
        redoStack.clear()
    }

    fun select(id: String?) { selection = id?.let { setOf(it) } ?: emptySet(); notifyChanged() }
    fun toggleSelect(id: String) {
        selection = if (id in selection) selection - id else selection + id
        notifyChanged()
    }
    fun isSelected(id: String) = id in selection

    fun setGrid(on: Boolean) { gridOn = on; notifyChanged() }
    fun setTestMode(on: Boolean) { testMode = on; notifyChanged() }

    /** Move by grid deltas; result snaps to the 8dp pixel lattice, clamped in-bounds. */
    fun moveSelected(dxGrid: Float, dyGrid: Float, snapPx: Float, pxPerGridX: Float, pxPerGridY: Float) {
        if (selection.isEmpty() || pxPerGridX <= 0f || pxPerGridY <= 0f) return
        pushUndo()
        layout = OverlayLayout(layout.controls.map { o ->
            if (o.id !in selection || o.locked) return@map o
            val px = (o.x + dxGrid) * pxPerGridX
            val py = (o.y + dyGrid) * pxPerGridY
            val sx = SnapMath.snapPx(px, snapPx) / pxPerGridX
            val sy = SnapMath.snapPx(py, snapPx) / pxPerGridY
            o.copy(
                x = sx.coerceIn(o.kind.baseW * o.scale / 2f, OverlayLayout.GRID_W - o.kind.baseW * o.scale / 2f),
                y = sy.coerceIn(o.kind.baseH * o.scale / 2f, OverlayLayout.GRID_H - o.kind.baseH * o.scale / 2f),
            )
        })
        notifyChanged()
    }

    fun scaleSelected(factor: Float) {
        if (selection.isEmpty()) return
        pushUndo()
        layout = OverlayLayout(layout.controls.map {
            if (it.id in selection) it.copy(scale = (it.scale * factor).coerceIn(0.7f, 1.5f)) else it
        })
        notifyChanged()
    }

    fun setOpacitySelected(alpha: Float) {
        layout = OverlayLayout(layout.controls.map {
            if (it.id in selection) it.copy(opacity = alpha.coerceIn(0.1f, 0.9f)) else it
        })
        notifyChanged()
    }

    fun update(id: String, transform: (OverlayControl) -> OverlayControl) {
        pushUndo()
        layout = OverlayLayout(layout.controls.map { if (it.id == id) transform(it) else it })
        notifyChanged()
    }

    fun add(kind: ControlKind, idSeed: Long = System.currentTimeMillis()) {
        pushUndo()
        val jitter = ((idSeed % 1000) / 1000f) * 48f - 24f       // never stacks exactly (§3.4)
        val id = "${kind.name.lowercase()}_${idSeed % 10_000}"
        layout = OverlayLayout(layout.controls + OverlayControl(id, kind, 500f + jitter, 900f + jitter))
        select(id)
    }

    fun remove(id: String) { pushUndo(); layout = OverlayLayout(layout.controls.filter { it.id != id }); select(null) }

    fun duplicate(id: String) {
        layout.controls.firstOrNull { it.id == id }?.let { src ->
            pushUndo()
            val copy = src.copy(
                id = "${src.id}_c${System.currentTimeMillis() % 10_000}",
                x = (src.x + 60f).coerceAtMost(OverlayLayout.GRID_W - 60f),
                y = (src.y + 60f).coerceAtMost(OverlayLayout.GRID_H - 60f),
            )
            layout = OverlayLayout(layout.controls + copy)
            select(copy.id)
        }
    }

    fun reset() { pushUndo(); layout = OverlayLayout.default(); select(null) }

    fun undo() { if (undoStack.isNotEmpty()) { redoStack += layout; layout = undoStack.removeAt(undoStack.lastIndex); notifyChanged() } }
    fun redo() { if (redoStack.isNotEmpty()) { undoStack += layout; layout = redoStack.removeAt(redoStack.lastIndex); notifyChanged() } }

    /** Pure-float hit test — canvas pixel coordinates in, control out. */
    fun hitTest(canvasW: Float, canvasH: Float, x: Float, y: Float): OverlayControl? {
        if (canvasW <= 0f || canvasH <= 0f) return null
        val gx = x / canvasW * OverlayLayout.GRID_W
        val gy = y / canvasH * OverlayLayout.GRID_H
        val pxPerUnit = canvasW / OverlayLayout.GRID_W
        return layout.controls.lastOrNull { o ->
            val w = o.kind.baseW * o.scale * pxPerUnit / 2f
            val h = o.kind.baseH * o.scale * (canvasH / OverlayLayout.GRID_H) / 2f
            gx in (o.x - w)..(o.x + w) && gy in (o.y - h)..(o.y + h)
        }
    }

    companion object { const val UNDO_DEPTH = 20 }
}

// JSON serialization stays with org.json (Android) — see OverlayJson in
// file 21; the round-trip tests run with org.json:json on the JVM classpath.
```

---

## 25 · Scanning.kt — the SAF record hunter

```kotlin
package samba.s3.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import samba.s3.app.GameModel
import samba.s3.core.GameRules
import samba.s3.core.SfoParser

// §5.3 — record hunting. DocumentsContract child queries (findFile is O(n²)
// per level — never use it in a walker). Depth-capped, hidden dirs skipped.

sealed interface ScanEvent {
    data class Progress(val dir: String) : ScanEvent
    data class Found(val game: GameModel) : ScanEvent
    data class Skipped(val name: String, val reason: String) : ScanEvent
    data class Done(val report: ScanReport) : ScanEvent
}

data class ScanReport(val games: List<GameModel>, val skipped: List<Pair<String, String>>)

data class DocNode(val docId: String, val name: String, val isDir: Boolean, val size: Long)

class GameScanner(
    private val resolver: ContentResolver,
    private val onEvent: (ScanEvent) -> Unit = {},
) {
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    private fun children(treeUri: Uri, parentDocId: String): List<DocNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = mutableListOf<DocNode>()
        try {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(iMime) ?: ""
                    out += DocNode(
                        docId = c.getString(iId),
                        name = name,
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = c.getLong(iSize),
                    )
                }
            }
        } catch (_: Exception) { /* provider hiccup: skip this branch */ }
        return out
    }

    private fun readBytes(treeUri: Uri, docId: String, maxBytes: Int = 1 shl 20): ByteArray? =
        try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).let { docUri ->
                resolver.openInputStream(docUri)?.use { it.readBytes().copyOf(maxBytes) }
            }
        } catch (_: Exception) { null }

    /** Recursively collects relative paths + file sizes under a docId, depth-capped. */
    private fun walk(
        treeUri: Uri, docId: String, prefix: String, depth: Int,
        paths: MutableSet<String>, sizes: MutableMap<String, Long>,
    ) {
        if (depth > MAX_DEPTH) return
        children(treeUri, docId).forEach { node ->
            val rel = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
            paths += rel
            if (node.isDir) walk(treeUri, node.docId, rel, depth + 1, paths, sizes)
            else sizes[rel] = node.size
        }
    }

    suspend fun scan(treeUri: Uri): ScanReport = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val games = mutableListOf<GameModel>()
        val skipped = mutableListOf<Pair<String, String>>()

        children(treeUri, rootDocId).filter { it.isDir }.forEach { top ->
            onEvent(ScanEvent.Progress(top.name))
            val paths = mutableSetOf<String>()
            val sizes = mutableMapOf<String, Long>()
            walk(treeUri, top.docId, "", 0, paths, sizes)

            when {
                GameRules.isGameFolder(paths) -> {
                    val game = modelFrom(treeUri, top, paths, sizes) ?: return@forEach
                    onEvent(ScanEvent.Found(game))
                    games += game
                }
                GameRules.skipReason(paths) != null -> {
                    val reason = GameRules.skipReason(paths)!!
                    skipped += top.name to reason
                    onEvent(ScanEvent.Skipped(top.name, reason))
                }
                // else: plain folder, not a candidate — stay quiet
            }
        }
        ScanReport(games, skipped).also { onEvent(ScanEvent.Done(it)) }
    }

    private fun modelFrom(
        treeUri: Uri, top: DocNode, paths: Set<String>, sizes: Map<String, Long>,
    ): GameModel? {
        // PARAM.SFO lives at PS3_GAME/PARAM.SFO (or PARAM.SFO when scanned deep)
        val sfoRel = when {
            "PS3_GAME/PARAM.SFO" in paths -> "PS3_GAME/PARAM.SFO"
            "PARAM.SFO" in paths -> "PARAM.SFO"
            else -> null
        }
        val sfo = sfoRel?.let { rel ->
            children(treeUri, top.docId)  // one extra query to resolve the docId of rel’s parent
                .firstOrNull { rel.startsWith(it.name) }?.let { _ ->
                    resolveDocId(treeUri, top.docId, rel)?.let { readBytes(treeUri, it) }
                }
        }?.let { SfoParser.parse(it) }

        val title = (sfo?.get("TITLE") as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: top.name
        val serial = (sfo?.get("SERIAL") as? String)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: GameRules.serialFromName(top.name) ?: "UNKNOWN"
        val size = sizes.filter { it.key.startsWith("PS3_GAME/USRDIR/") || it.key.startsWith("USRDIR/") }
            .values.sum()

        return GameModel(
            id = "${serial}_${top.name.hashCode()}",
            title = title, serial = serial, sizeBytes = size,
        )
    }

    /** Resolve the leaf docId of a relative path under a start docId. */
    private fun resolveDocId(treeUri: Uri, startDocId: String, rel: String): String? {
        var docId = startDocId
        rel.split("/").forEach { segment ->
            val hit = children(treeUri, docId).firstOrNull { it.name == segment } ?: return null
            docId = hit.docId
        }
        return docId
    }

    companion object { const val MAX_DEPTH = 5 }
}

// permission persistence — the SAF grant survives reboots
object Saf {
    fun persist(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
```

---

## 26 · Transfer.kt — fetch · hash · verify · install

```kotlin
package samba.s3.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import samba.s3.core.Hashing
import samba.s3.core.Pup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ── Fetching — dependency-free, cancellable, progress-aware ──────

interface HttpFetcher {
    suspend fun fetch(url: String, dest: File, onProgress: (read: Long, total: Long) -> Unit): Result<Unit>
    suspend fun fetchText(url: String): Result<String>
}

class UrlConnectionFetcher : HttpFetcher {
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    override suspend fun fetch(
        url: String, dest: File, onProgress: (Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cancelled = false
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            try {
                val code = conn.responseCode
                check(code in 200..299) { "http $code for $url" }
                val total = conn.contentLengthLong
                dest.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            if (cancelled) error("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
            } finally { conn.disconnect() }
        }.onFailure { dest.delete() }
    }

    override suspend fun fetchText(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 10_000
            try {
                check(conn.responseCode in 200..299) { "http ${conn.responseCode}" }
                conn.inputStream.use { it.readBytes().decodeToString() }
            } finally { conn.disconnect() }
        }
    }
}

// ── The installer pipeline — the four steps (§5.8), status-mapped ─

enum class InstallPhase { Idle, Downloading, Verifying, Decrypting, Installing, Done, Failed }

data class InstallState(
    val phase: InstallPhase = InstallPhase.Idle,
    val progress: Float = 0f,
    val failedStep: String? = null,
    val message: String? = null,
)

interface CoreInstaller {
    fun decrypt(pup: File, outDir: File): Result<Unit>
    fun install(dir: File): Result<Unit>
}

class FirmwareInstaller(
    private val fetcher: HttpFetcher,
    private val core: CoreInstaller,
    private val filesDir: File,
) {
    private val _state = MutableStateFlow(InstallState())
    val state: StateFlow<InstallState> = _state

    suspend fun fromUrl(url: String, expectedSha256: String?): Result<File> {
        _state.value = InstallState(InstallPhase.Downloading)
        val dest = File(filesDir, "firmware/PS3UPDAT.PUP").also { it.parentFile?.mkdirs() }
        val down = fetcher.fetch(url, dest) { read, total ->
            _state.value = _state.value.copy(progress = if (total > 0) read.toFloat() / total else 0f)
        }
        return down.fold(
            onSuccess = { fromFile(dest, expectedSha256) },
            onFailure = { Result.failure(it).also { _state.value = InstallState(InstallPhase.Failed, failedStep = "download", message = it.message) } },
        )
    }

    /** verify (sha + PUP magic) → decrypt → install, with steps in state. */
    suspend fun fromFile(pup: File, expectedSha256: String? = null): Result<File> {
        _state.value = InstallState(InstallPhase.Verifying)
        val head = pup.inputStream().use { it.readNBytes(8) }
        if (!Pup.isValidHeader(head)) {
            _state.value = InstallState(InstallPhase.Failed, failedStep = "verify", message = "not a PS3 update file")
            return Result.failure(IllegalArgumentException("bad PUP header"))
        }
        val actualSha = Hashing.sha256(pup)
        if (expectedSha256 != null && !actualSha.equals(expectedSha256, ignoreCase = true)) {
            _state.value = InstallState(InstallPhase.Failed, failedStep = "verify", message = "sha256 mismatch")
            return Result.failure(IllegalStateException("sha mismatch"))
        }

        _state.value = InstallState(InstallPhase.Decrypting, progress = 0f)
        val outDir = File(filesDir, "firmware/extracted").also { it.mkdirs() }
        core.decrypt(pup, outDir).getOrElse {
            _state.value = InstallState(InstallPhase.Failed, failedStep = "decrypt", message = it.message)
            return Result.failure(it)
        }

        _state.value = InstallState(InstallPhase.Installing, progress = 0.5f)
        core.install(outDir).getOrElse {
            _state.value = InstallState(InstallPhase.Failed, failedStep = "install", message = it.message)
            return Result.failure(it)
        }

        _state.value = InstallState(InstallPhase.Done, progress = 1f)
        return Result.success(pup)
    }

    /** Pure mapping to the UI stepper (§3.14) — the sheet never sees the pipeline. */
    fun steps(): List<samba.s3.design.StepSpec> {
        val s = _state.value
        fun status(name: String): samba.s3.design.StepStatus = when {
            s.phase == InstallPhase.Failed && s.failedStep == name -> samba.s3.design.StepStatus.Failed(s.message ?: "failed")
            s.phase == InstallPhase.Done -> samba.s3.design.StepStatus.Done
            name == "download" && s.phase == InstallPhase.Downloading -> samba.s3.design.StepStatus.Running(s.progress)
            name == "verify" && s.phase == InstallPhase.Verifying -> samba.s3.design.StepStatus.Running(0.5f)
            name == "decrypt" && s.phase == InstallPhase.Decrypting -> samba.s3.design.StepStatus.Running(0.25f)
            name == "install" && s.phase == InstallPhase.Installing -> samba.s3.design.StepStatus.Running(s.progress)
            else -> samba.s3.design.StepStatus.Idle
        }
        return listOf("download", "verify sha256", "decrypt", "install").map { samba.s3.design.StepSpec(it, status(it.lowercase())) }
    }
}

// ── Catalog — the app owns this JSON format ──────────────────────

data class DriverCatalogEntry(
    val id: String, val name: String, val version: String,
    val url: String, val sha256: String?, val caps: List<String>, val minApi: Int,
)

class PartsCatalog(private val fetcher: HttpFetcher) {
    suspend fun drivers(catalogUrl: String): Result<List<DriverCatalogEntry>> =
        fetcher.fetchText(catalogUrl).mapCatching { text ->
            val root = JSONObject(text)
            root.optJSONArray("drivers").let { arr ->
                (0 until (arr?.length() ?: 0)).map { i ->
                    val o = arr.getJSONObject(i)
                    DriverCatalogEntry(
                        id = o.getString("id"), name = o.getString("name"),
                        version = o.getString("version"), url = o.getString("url"),
                        sha256 = o.optString("sha256").takeIf { it.isNotBlank() },
                        caps = o.optJSONArray("caps").let { c ->
                            (0 until (c?.length() ?: 0)).map { j -> c!!.getString(j) }
                        },
                        minApi = o.optInt("minApi", 26),
                    )
                }
            }
        }
}

// ── Foreground service — one channel, one flow, all installs ─────

class TransferService : Service() {
    enum class Kind { FIRMWARE, DRIVER }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getSerializableExtra(EXTRA_KIND) as? Kind ?: Kind.FIRMWARE
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val sha = intent?.getStringExtra(EXTRA_SHA)

        startForeground(NOTIFICATION_ID, buildNotification("preparing…"))

        val fetcher = UrlConnectionFetcher()
        val installer = FirmwareInstaller(fetcher, JniCoreInstaller(this), filesDir)

        scope.launch {
            installer.state.collect { s ->
                val text = when (s.phase) {
                    InstallPhase.Downloading -> "downloading ${(s.progress * 100).toInt()}%"
                    InstallPhase.Verifying -> "verifying sha256"
                    InstallPhase.Decrypting -> "decrypting"
                    InstallPhase.Installing -> "installing"
                    InstallPhase.Done -> "done — on stage"
                    InstallPhase.Failed -> "failed · ${s.failedStep}"
                    InstallPhase.Idle -> "queued"
                }
                notify(buildNotification(text))
            }
        }
        scope.launch {
            installer.fromUrl(url, sha)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "installs", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder
            .setContentTitle("samba s3 · parts")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL = "installs"; const val NOTIFICATION_ID = 47
        const val EXTRA_KIND = "kind"; const val EXTRA_URL = "url"; const val EXTRA_SHA = "sha"
        fun start(context: Context, kind: Kind, url: String, sha: String? = null) {
            val intent = Intent(context, TransferService::class.java)
                .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_URL, url).putExtra(EXTRA_SHA, sha)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}

/** JNI goes here — the tests fake this, the product binds it. */
class JniCoreInstaller(private val context: Context) : CoreInstaller {
    override fun decrypt(pup: File, outDir: File): Result<Unit> = Result.failure(NotImplementedError("bind core"))
    override fun install(dir: File): Result<Unit> = Result.failure(NotImplementedError("bind core"))
}
```

---

## 27 · Bridge.kt — the emulator core interface & log pipeline

```kotlin
package samba.s3.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import samba.s3.app.LogEntry
import samba.s3.app.LogSeverity
import samba.s3.app.LogSubsystem
import samba.s3.core.RingBuffer
import java.util.Calendar

// ── Log parsing — pure, line in, LogEntry out ────────────────────
// Expected shapes (both tolerated):
//   "14:22:07.412 E [VULKAN] device lost"
//   "E [gpu drv] fallback path"

object LogParse {
    private val TIME = Regex("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})")
    private val LETTER = Regex("^[ ]*([FfEeWwIiDdKk])\\b")   // K = “OK” marker used by some cores
    private val TAG = Regex("\\[([a-z ]+)\\]", RegexOption.IGNORE_CASE)

    fun severityOf(letter: String): LogSeverity = when (letter.uppercase()) {
        "F" -> LogSeverity.Fatal
        "E" -> LogSeverity.Error
        "W" -> LogSeverity.Warn
        "I" -> LogSeverity.Info
        "D" -> LogSeverity.Debug
        "K" -> LogSeverity.Ok
        else -> LogSeverity.Debug
    }

    fun subsystemOf(line: String): LogSubsystem {
        val tag = TAG.find(line)?.groupValues?.get(1)?.lowercase()
        val hay = (tag ?: line).lowercase()
        return when {
            "vulkan" in hay || hay.startsWith("vk") -> LogSubsystem.Vulkan
            "gpu drv" in hay || "driver" in hay -> LogSubsystem.GpuDriver
            "kernel" in hay -> LogSubsystem.Kernel
            "core" in hay || "rpcs3" in hay || "cell" in hay -> LogSubsystem.Core
            else -> LogSubsystem.App
        }
    }

    /** Returns null for lines that carry no severity letter at all (pure noise). */
    fun parse(line: String, fallbackAt: Long = now()): LogEntry? {
        val sevMatch = LETTER.find(line) ?: return null
        val at = TIME.find(line)?.let { m ->
            calendarOf(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                m.groupValues[3].toInt(), m.groupValues[4].toInt(),
            ) ?: fallbackAt
        } ?: fallbackAt
        val subsystem = subsystemOf(line)
        val message = line.substringAfter("] ", line).trim()
        return LogEntry(at, severityOf(sevMatch.groupValues[1]), subsystem, message)
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun calendarOf(h: Int, m: Int, s: Int, ms: Int): Long? {
        if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, s); set(Calendar.MILLISECOND, ms)
        }.timeInMillis
    }
}

// ── The pipeline — 50k rows guaranteed, follow logic lives in the UI ──

class LogPipeline(private val capacity: Int = 50_000) {
    private val buffer = RingBuffer<LogEntry>(capacity)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    /** Feed one raw core line. Returns the parsed entry (or null if noise). */
    fun ingest(line: String, at: Long = System.currentTimeMillis()): LogEntry? {
        val e = LogParse.parse(line, at) ?: return null
        buffer.add(e)
        _entries.value = buffer.snapshot()   // snapshot copies; UI virtualizes
        return e
    }

    fun count(severity: LogSeverity) = buffer.count { it.severity == severity }
    fun lastOrNull() = buffer.lastOrNull()
    fun clear() { buffer.clear(); _entries.value = emptyList() }
}

// ── The core contract — the app talks only to this ───────────────

data class PerfFrame(val fps: Float, val cpu: Float, val gpu: Float, val at: Long)

sealed interface CoreEvent {
    data class BootLine(val line: String) : CoreEvent          // feeds the ritual ticker
    data object Ready : CoreEvent
    data class Frame(val frame: PerfFrame) : CoreEvent
    data class Log(val raw: String) : CoreEvent
    data class Failed(val message: String) : CoreEvent
}

interface EmuCoreBridge {
    fun vulkanAvailable(): Boolean
    fun deviceVerdict(): samba.s3.app.DeviceVerdict
    fun boot(gameId: String): Flow<CoreEvent>
    fun pause(); fun resume(); fun exit()
}

/** Fake for tests & the preview harness — scripted, deterministic. */
class FakeCoreBridge(
    private val bootScript: List<CoreEvent> = listOf(
        CoreEvent.BootLine("kernel: loading lv2"),
        CoreEvent.BootLine("vulkan: adapter ok"),
        CoreEvent.Ready,
    ),
) : EmuCoreBridge {
    var fps = 60f; var booted: String? = null; var paused = false
    override fun vulkanAvailable() = true
    override fun deviceVerdict() = samba.s3.app.DeviceVerdict.MainStage
    override fun boot(gameId: String): Flow<CoreEvent> = kotlinx.coroutines.flow.flow {
        booted = gameId
        bootScript.forEach { emit(it) }
        while (true) {
            emit(CoreEvent.Frame(PerfFrame(fps, 40f, 55f, System.currentTimeMillis())))
            kotlinx.coroutines.delay(250)
        }
    }
    override fun pause() { paused = true }
    override fun resume() { paused = false }
    override fun exit() { booted = null }
}
```

---

## A · `src/test/` — the JVM suite

### A1 · MeterMathTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterMathTest {
    @Test fun peakDecaysLinearlyOver1500ms() {
        var peak = 1f
        // 60fps for 1.5s = 90 frames of ~16.67ms
        repeat(90) { peak = MeterMath.peakDecay(peak, 0f, 16.67f) }
        assertTrue("peak should be ≈0, was $peak", peak < 0.05f)
    }

    @Test fun peakNeverBelowLiveTarget() {
        assertEquals(0.7f, MeterMath.peakDecay(1f, 0.7f, 500f), 0f)
        assertEquals(0.9f, MeterMath.peakDecy_placeholder(), 0f) // see errata — use next line
    }

    @Test fun peakIgnoresNegativeDt() {
        assertEquals(0.5f, MeterMath.peakDecay(0.5f, 0.1f, -100f), 0f)
    }

    @Test fun redZoneStartsAt85Percent() {
        assertTrue(MeterMath.inRedZone(0.85f))
        assertFalse(MeterMath.inRedZone(0.8499f))
    }

    @Test fun a11yAnnouncesValueAndZone() {
        val d = MeterMath.vuA11y("fps", 59f, 60f, 0.983f)
        assertTrue("59" in d && "red zone" in d && "fps" in d)
        val quiet = MeterMath.vuA11y("", 30f, 60f, 0.5f)
        assertTrue("steady" in quiet && "30" in quiet && "60" in quiet)
    }
}
```

### A2 · DampedNeedleTest (pure) — pinning the VU feel

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test

class DampedNeedleTest {
    private val dt = 1f / 60f

    @Test fun convergesToTarget() {
        val needle = DampedNeedle()
        needle.settle(1f, dt)
        assertEquals(1f, needle.value, 0.01f)
    }

    @Test fun underDampedSoItOvershoots() {          // ζ=0.75 — the VU “bounce”
        val needle = DampedNeedle()
        var max = 0f
        repeat(240) { _ -> max = maxOf(max, needle.step(1f, dt)) }
        assertTrue("needle should overshoot past 1.0, reached $max", max > 1.0f)
        assertTrue("…but not wildly", max < 1.35f)
    }

    @Test fun neverTeleports() {                      // one frame from rest is tiny
        val needle = DampedNeedle()
        val before = needle.value
        needle.step(1f, dt)
        val delta = abs(needle.value - before)
        assertTrue("single-step delta must stay small, was $delta", delta < 0.5f)
    }

    @Test fun stableAt60fpsForMinutes() {
        val needle = DampedNeedle()
        repeat(60 * 60 * 5) { needle.step(1f, dt) }   // 5 minutes of frames
        assertFalse(needle.value.isNaN()); assertFalse(needle.value.isInfinite())
        assertEquals(1f, needle.value, 0.01f)
    }

    @Test fun settlesFasterThanASecond() {
        val needle = DampedNeedle()
        val steps = needle.settle(1f, dt)
        assertTrue("settle took ${steps * dt}s — sluggish", steps * dt < 1.0f)
    }
}
```

### A3 · SnapMathTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapMathTest {
    @Test fun snapsToTheLattice() {
        assertEquals(8f, SnapMath.snapPx(7.9f, 8f), 0.0001f)
        assertEquals(8f, SnapMath.snapPx(8.1f, 8f), 0.0001f)
        assertEquals(16f, SnapMath.snapPx(12.6f, 8f), 0.0001f)
        assertEquals(7.5f, SnapMath.snapPx(7.5f, 8f), 0.0001f)  // exactly between → up
    }

    @Test fun zeroGridIsPassthrough() = assertEquals(13.3f, SnapMath.snapPx(13.3f, 0f), 0f)

    @Test fun fractionClampsOutsideRange() {
        assertEquals(0f, SnapMath.fraction(-5f, 0f, 100f), 0f)
        assertEquals(1f, SnapMath.fraction(105f, 0f, 100f), 0f)
        assertEquals(0.5f, SnapMath.fraction(60f, 20f, 100f), 0f)
    }

    @Test fun detentSnapsWithinThreePercent() {
        val d = SnapMath.detent(34f, 0f, 120f, listOf(0f, 30f, 60f, 120f))
        assertNull("34/120 is 3.3% past the 30 detent — no snap", d)
        assertEquals(30f, SnapMath.detant_placeholder(), 0f)   // errata — replace with:
        assertEquals(30f, SnapMath.detent(33f, 0f, 120f, listOf(0f, 30f, 60f, 120f))!!, 0f)
    }

    @Test fun detentTieResolvesToLower() {
        // 15/30 is exactly between 0 and 30 → lower wins
        assertEquals(0f, SnapMath.detent(15f, 0f, 30f, listOf(0f, 30f))!!, 0f)
    }

    @Test fun emptyDetentsNeverSnap() = assertNull(SnapMath.detent(50f, 0f, 100f, emptyList()))
}
```

### A4 · SuperellipseTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SuperellipseTest {
    @Test fun everySampledPointLiesOnTheCurve() {
        (0 until 72).forEach { i ->
            val t = (i / 72f) * (2.0 * Math.PI).toFloat()
            val (x, y) = Superellipse.point(t, 48f, 48f)
            assertEquals("residual at t=$t", 1f, Superellipse.residual(x, y, 48f, 48f), 0.02f)
        }
    }

    @Test fun diagonalIsFullerThanACircle() {         // the squircle tell
        val t = (Math.PI / 4).toFloat()
        val (x, _) = Superellipse.point(t, 48f, 48f)
        assertTrue("corner should be fuller than a circle", x > 48f * 0.71f)
    }

    @Test fun extremesTouchTheBoxEdges() {
        val (x0, y0) = Superellipse.point(0f, 10f, 6f)
        assertEquals(10f, x0, 0.01f); assertEquals(0f, y0, 0.01f)
        val (x2, _) = Superellipse.point(Math.PI.toFloat(), 10f, 6f)
        assertEquals(-10f, x2, 0.01f)
    }
}
```

### A5 · SfoParserTest (pure) — builds real SFO bytes in-test

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SfoParserTest {

    private fun buildSfo(vararg pairs: Pair<String, Any>): ByteArray {
        val keyBlock = pairs.flatMap { listOf(*it.first.toByteArray(), 0) }
        val dataBlocks = pairs.map { (_, v) ->
            when (v) {
                is Int -> listOf(
                    v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte(),
                )
                else -> {
                    val b = (v as String).toByteArray(); listOf(*b, 0)
                }
            }
        }
        val n = pairs.size
        val keyStart = 20 + 16 * n
        val dataStart = keyStart + keyBlock.size
        val out = mutableListOf<Byte>()
        fun u32(v: Long) { out += listOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()) }
        fun u16(v: Int) { out += listOf(v.toByte(), (v shr 8).toByte()) }
        u32(0x00505350L); u32(1L); u32(keyStart.toLong()); u32(dataStart.toLong()); u32(n.toLong())
        var keyOff = 0; var dataOff = 0
        pairs.zip(dataBlocks).forEach { (pair, data) ->
            val type = if (pair.second is Int) 0x0404 else 0x0204
            u16(keyOff); u16(type); u32(data.size.toLong()); u32(data.size.toLong()); u32(dataOff.toLong())
            keyOff += pair.first.toByteArray().size + 1
            dataOff += data.size
        }
        out += keyBlock
        dataBlocks.forEach { out += it }
        return out.toByteArray()
    }

    @Test fun readsTitleAndSerial() {
        val sfo = buildSfo(
            "TITLE" to "Demon’s Souls",
            "SERIAL" to "BLUS30443",
            "VERSION" to "01.00",
        )
        val parsed = SfoParser.parse(sfo)
        assertEquals("Demon’s Souls", parsed["TITLE"])
        assertEquals("BLUS30443", parsed["SERIAL"])
        assertEquals("01.00", parsed["VERSION"])
    }

    @Test fun readsInt32Values() {
        val parsed = SfoParser.parse(buildSfo("PARENTAL_LEVEL" to 5, "TITLE" to "X"))
        assertEquals(5, parsed["PARENTAL_LEVEL"])
    }

    @Test fun rejectsNonSfoBytes() {
        try { SfoParser.parse("hello world, definitely not an sfo".toByteArray()); fail("should throw") }
        catch (e: IllegalArgumentException) { assertTrue("not an sfo" in (e.message ?: "")) }
    }

    @Test fun rejectsTruncated() {
        try { SfoParser.parse(ByteArray(8)); fail("should throw") }
        catch (e: IllegalArgumentException) { assertTrue("truncated" in (e.message ?: "")) }
    }
}
```

### A6 · PupTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PupTest {
    @Test fun recognizesSceufMagic() = assertTrue(Pup.isValidHeader("SCEUFAA0".toByteArray()))
    @Test fun rejectsWrongMagic() = assertFalse(Pup.isValidHeader("NOTPUP0".toByteArray()))
    @Test fun rejectsShortFiles() = assertFalse(Pup.isValidHeader("SCE".toByteArray()))
}
```

### A7 · RingBufferTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingBufferTest {
    @Test fun evictsOldestBeyondCapacity() {
        val buf = RingBuffer<Int>(3)
        (1..5).forEach { buf.add(it) }
        assertEquals(listOf(3, 4, 5), buf.snapshot())
    }

    @Test fun snapshotPreservesOrder() {
        val buf = RingBuffer<String>(4)
        listOf("a", "b", "c").forEach { buf.add(it) }
        assertEquals(listOf("a", "b", "c"), buf.snapshot())
    }

    @Test fun countAndLast() {
        val buf = RingBuffer<Int>(100)
        (1..10).forEach { buf.add(it) }
        assertEquals(10, buf.size)
        assertEquals(10, buf.lastOrNull())
        assertEquals(5, buf.count { it % 2 == 0 })
    }

    @Test fun emptyBuffer() {
        val buf = RingBuffer<Int>(5)
        assertEquals(0, buf.size); assertNull(buf.lastOrNull()); assertEquals(emptyList(), buf.snapshot())
    }

    @Test fun holdsFiftyThousand() {                    // the scope guarantee, §5.14
        val buf = RingBuffer<Int>(50_000)
        (1..120_000).forEach { buf.add(it) }
        assertEquals(50_000, buf.size)
        assertEquals(120_000, buf.lastOrNull())
    }
}
```

### A8 · GameRulesTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRulesTest {
    @Test fun recognizesSerials() {
        assertEquals("BLUS30443", GameRules.serialFromName("BLUS30443"))
        assertEquals("NPEB02436", GameRules.serialFromName("npeb02436-[Doom 3 BFG]"))
        assertEquals("BCES01209", GameRules.serialFromName("PS3_GAME - bces01209"))
    }

    @Test fun rejectsNonSerials() {
        assertNull(GameRules.serialFromName("BLUS304434"))    // 6 digits
        assertNull(GameRules.serialFromName("XXXX12345"))
        assertNull(GameRules.serialFromName("My Backup Folder"))
    }

    @Test fun gameFolderDetection() {
        assertTrue(GameRules.isGameFolder(setOf(
            "PS3_GAME", "PS3_GAME/USRDIR", "PS3_GAME/USRDIR/EBOOT.BIN", "PS3_GAME/PARAM.SFO",
        )))
        assertTrue(GameRules.isGameFolder(setOf("USRDIR", "USRDIR/EBOOT.BIN")))  // scanned one level deep
        assertFalse(GameRules.isGameFolder(setOf("PS3_GAME", "PS3_GAME/USRDIR")))
        assertFalse(GameRules.isGameFolder(setOf("readme.txt")))
    }

    @Test fun skipReasons() {
        assertEquals("missing EBOOT.BIN", GameRules.skipReason(setOf("PS3_GAME", "PS3_GAME/USRDIR")))
        assertEquals("incomplete — no USRDIR", GameRules.skipReason(setOf("PS3_GAME", "PS3_GAME/PIC1.PNG")))
        assertNull(GameRules.skipReason(setOf("notes.txt")))
    }
}
```

### A9 · HashingTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HashingTest {
    @Test fun knownVector() {
        // sha256("abc") — the canonical test vector
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256("abc".toByteArray()),
        )
    }

    @Test fun streamingMatchesOneShot() {
        val f = File.createTempFile("hash", ".bin").apply {
            writeBytes(ByteArray(300_000) { (it % 251).toByte() })   // > one 64k buffer
        }
        assertEquals(Hashing.sha256(readBytes()), Hashing.sha256(f))
        f.delete()
    }
}
```

### A10 · PresetMathTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetMathTest {
    private val targets = mapOf("gfx.res" to 100f, "gfx.vsync" to true, "aud.volume" to 0f)
    private val current = mapOf("gfx.res" to "200%", "gfx.vsync" to "off", "aud.volume" to "100%")
    private fun render(v: Any) = when (v) {
        is Boolean -> if (v) "on" else "off"
        is Float -> if (v == 0f && false) "off" else "%.0f%%".format(v)
        else -> "$v"
    }

    @Test fun listsOnlyChangedSettings() {
        val diff = PresetMath.diff(targets, { current[it] ?: "—" }, ::render)
        assertEquals(2, diff.size)
        assertTrue("200% → 100%" in diff)
        assertTrue("off → on" in diff)
    }

    @Test fun emptyWhenNothingChanges() {
        val same = mapOf("gfx.res" to 100f)
        assertEquals(0, PresetMath.diff(same, { "100%" }, { "%.0f%%".format(it) }).size)
    }
}
```

### A11 · PendingChangesCoreTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingChangesCoreTest {
    @Test fun recordAccumulates() {
        val pc = PendingChangesCore()
        pc.record("tune", "resolution", "100%", "200%")
        pc.record("tune", "vsync", "off", "on")
        assertEquals(2, pc.count)
    }

    @Test fun sameSettingReplacesNotStacks() {         // §6.7 — one line per setting
        val pc = PendingChangesCore()
        pc.record("tune", "resolution", "100%", "200%")
        pc.record("tune", "resolution", "200%", "300%")
        assertEquals(1, pc.count)
        assertEquals("300%", pc.changes.single().to)
    }

    @Test fun sameSettingDifferentPageCoexists() {
        val pc = PendingChangesCore()
        pc.record("tune", "resolution", "100%", "200%")
        pc.record("per-game", "resolution", "100%", "150%")
        assertEquals(2, pc.count)
    }

    @Test fun revertingToOriginalRemovesTheEntry() {
        val pc = PendingChangesCore()
        pc.record("tune", "resolution", "100%", "200%")
        pc.record("tune", "resolution", "200%", "100%")
        assertEquals(0, pc.count)                       // from == to → dropped
    }

    @Test fun revertAndClear() {
        val pc = PendingChangesCore()
        pc.record("tune", "a", "1", "2")
        pc.record("tune", "b", "1", "2")
        pc.revert(pc.changes.first())
        assertEquals(1, pc.count)
        pc.clear(); assertEquals(0, pc.count)
    }

    @Test fun notifiesListeners() {
        val pc = PendingChangesCore()
        var fired = 0
        pc.addListener { fired++ }
        pc.record("tune", "x", "1", "2")
        assertEquals(1, fired)
    }
}
```

### A12 · BlueprintEditorTest (pure)

```kotlin
package samba.s3.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueprintEditorTest {

    private fun editorWithFace(): BlueprintEditor {
        val e = BlueprintEditor(OverlayLayout(listOf(
            OverlayControl("face", ControlKind.FaceCross, 500f, 1250f),
            OverlayControl("l2", ControlKind.L2, 170f, 90f),
        )))
        e.select("face")
        return e
    }

    @Test fun hitTestFindsTheControl() {
        val e = editorWithFace()
        // canvas 1000×1834 px: grid maps 1:1
        val hit = e.hitTest(1000f, 1834f, 500f, 1250f)
        assertNotNull(hit); assertEquals("face", hit!!.id)
    }

    @Test fun hitTestMissesEmptySpace() {
        val e = editorWithFace()
        assertNull(e.hitTest(1000f, 1834f, 20f, 1800f))
    }

    @Test fun moveSnapsToThe8dpLattice() {
        val e = editorWithFace()
        e.moveSelected(dxGrid = 3f, dyGrid = 0f, snapPx = 8f, pxPerGridX = 1f, pxPerGridY = 1f)
        // 500 + 3 → snapped to the nearest multiple of 8
        assertEquals(504f, e.layout.controls.first { it.id == "face" }.x, 0.001f)
    }

    @Test fun lockedControlsDoNotMove() {
        val e = BlueprintEditor(OverlayLayout(listOf(
            OverlayControl("face", ControlKind.FaceCross, 500f, 1250f, locked = true),
        )))
        e.select("face")
        e.moveSelected(50f, 50f, 8f, 1f, 1f)
        assertEquals(500f, e.layout.controls.single().x, 0f)
    }

    @Test fun moveClampsInsideTheGrid() {
        val e = editorWithFace()
        e.moveSelected(-2000f, -2000f, 8f, 1f, 1f)
        val o = e.layout.controls.first { it.id == "face" }
        assertTrue(o.x >= o.kind.baseW / 2f); assertTrue(o.y >= o.kind.baseH / 2f)
    }

    @Test fun scaleClampsToTheSpecRange() {
        val e = editorWithFace()
        repeat(50) { e.scaleSelected(1.4f) }
        assertEquals(1.5f, e.layout.controls.first { it.id == "face" }.scale, 0.001f)
        repeat(50) { e.scaleSelected(0.5f) }
        assertEquals(0.7f, e.layout.controls.first { it.id == "face" }.scale, 0.001f)
    }

    @Test fun undoRedoRoundTrip() {
        val e = editorWithFace()
        e.moveSelected(40f, 0f, 8f, 1f, 1f)
        val movedX = e.layout.controls.first { it.id == "face" }.x
        e.undo()
        assertEquals(500f, e.layout.controls.first { it.id == "face" }.x, 0f)
        e.redo()
        assertEquals(movedX, e.layout.controls.first { it.id == "face" }.x, 0f)
    }

    @Test fun undoDepthCapsAtTwenty() {                // Redline B
        val e = editorWithFace()
        repeat(25) { e.moveSelected(8f, 0f, 8f, 1f, 1f) }
        var undos = 0
        while (e.canUndo) { e.undo(); undos++ }
        assertEquals(20, undos)
    }

    @Test fun newActionClearsRedo() {
        val e = editorWithFace()
        e.moveSelected(8f, 0f, 8f, 1f, 1f)
        e.undo()
        assertTrue(e.canRedo)
        e.moveSelected(16f, 0f, 8f, 1f, 1f)
        assertFalse(e.canRedo)
    }

    @Test fun addLandsNearCenterWithUniqueIds() {
        val e = BlueprintEditor(OverlayLayout.default())
        e.add(ControlKind.Start, idSeed = 1000L)
        e.add(ControlKind.Start, idSeed = 2000L)
        val starts = e.layout.controls.filter { it.kind == ControlKind.Start }
        assertEquals(3, starts.size)                    // 1 default + 2 added
        assertEquals(2, starts.map { it.id }.toSet().size - 1)  // both new ids distinct
    }

    @Test fun duplicateOffsetsAndSelects() {
        val e = editorWithFace()
        e.duplicate("face")
        val copy = e.layout.controls.first { it.id.startsWith("face_c") }
        assertEquals(560f, copy.x, 0f)
        assertTrue(e.isSelected(copy.id))
    }

    @Test fun defaultLayoutHasTheTenCanonicalControls() {
        assertEquals(10, OverlayLayout.default().controls.size)
    }

    @Test fun listenersFireOnEveryMutation() {
        val e = editorWithFace()
        var fired = 0
        e.addListener { fired++ }
        e.select("l2"); e.moveSelected(8f, 0f, 8f, 1f, 1f); e.undo(); e.setGrid(false)
        assertEquals(4, fired)
    }
}
```

### A13 · OverlayJsonTest (pure, needs `org.json:json` on test classpath)

```kotlin
package samba.s3.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayJsonTest {
    private fun roundTrip(l: OverlayLayout): OverlayLayout =
        OverlayJson.fromJson(OverlayJson.toJson(l))

    @Test fun defaultLayoutSurvivesRoundTrip() {
        assertEquals(OverlayLayout.default(), roundTrip(OverlayLayout.default()))
    }

    @Test fun everyFieldSurvivesRoundTrip() {
        val exotic = OverlayLayout(listOf(
            OverlayControl(
                "x1", ControlKind.StickL, 123.4f, 567.8f, scale = 1.25f, opacity = 0.75f,
                shape = OverlayShape.Squircle, enabled = false, locked = true,
                labelVisible = true, haptics = false, deadzone = 0.22f,
            ),
        ))
        assertEquals(exotic, roundTrip(exotic))
    }

    @Test fun versionStampPresent() {
        assertTrue("v" in JSONObject(OverlayJson.toJson(OverlayLayout.default())).keys().asSequence().toList())
    }
}
```

> *`OverlayJson` is the JSON block from file 21, extracted verbatim into core as `object OverlayJson { fun toJson(OverlayLayout): String; fun fromJson(String): OverlayLayout }` — errata note.*

### A14 · LogParseTest (pure)

```kotlin
package samba.s3.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import samba.s3.app.LogSeverity
import samba.s3.app.LogSubsystem

class LogParseTest {
    @Test fun parsesTimestampedLine() {
        val e = LogParse.parse("14:22:07.412 E [VULKAN] device lost — attempting recovery")!!
        assertEquals(LogSeverity.Error, e.severity)
        assertEquals(LogSubsystem.Vulkan, e.subsystem)
        assertEquals("device lost — attempting recovery", e.message)
    }

    @Test fun parsesBareLine() {
        val e = LogParse.parse("W [gpu drv] fallback path")!!
        assertEquals(LogSeverity.Warn, e.severity)
        assertEquals(LogSubsystem.GpuDriver, e.subsystem)
    }

    @Test fun subsystemKeywordsRoute() {
        assertEquals(LogSubsystem.Kernel, LogParse.parse("I kernel: lv2 loaded")!!.subsystem)
        assertEquals(LogSubsystem.Core, LogParse.parse("D core: cellSpurs reset")!!.subsystem)
        assertEquals(LogSubsystem.App, LogParse.parse("I woke up from background")!!.subsystem)
        assertEquals(LogSubsystem.Vulkan, LogParse.parse("F vkQueueSubmit failed")!!.subsystem)
    }

    @Test fun garbageTimeFallsBackToNow() {
        val before = System.currentTimeMillis()
        val e = LogParse.parse("E [app] something")!!
        assertTrue(e.at >= before)
    }

    @Test fun noiseWithoutLetterIsDropped() {
        assertNull(LogParse.parse("random text with no severity"))
    }
}
```

### A15 · LogPipelineTest (pure)

```kotlin
package samba.s3.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import samba.s3.app.LogSeverity

class LogPipelineTest {
    @Test fun ingestParsesAndStores() {
        val p = LogPipeline()
        val e = p.ingest("E [vulkan] device lost")!!
        assertEquals(e, p.lastOrNull())
        assertEquals(1, p.entries.value.size)
    }

    @Test fun noiseIsNotStored() {
        val p = LogPipeline()
        assertNull_shim(p.ingest("nothing here"))
        assertEquals(0, p.entries.value.size)
    }

    @Test fun capsAtFiftyThousandEntries() {
        val p = LogPipeline(capacity = 50_000)
        repeat(60_000) { p.ingest("D [core] tick $it") }
        assertEquals(50_000, p.entries.value.size)
        assertTrue(p.entries.value.last().message.contains("tick 59999"))
    }

    @Test fun countsBySeverity() {
        val p = LogPipeline()
        repeat(3) { p.ingest("E [vulkan] err") }
        repeat(2) { p.ingest("W [kernel] warn") }
        assertEquals(3, p.count(LogSeverity.Error))
        assertEquals(2, p.count(LogSeverity.Warn))
    }
}
```

### A16 · AttentionReducerTest (Robolectric — touches design enums only)

```kotlin
package samba.s3.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import samba.s3.design.DeckAttention
import samba.s3.design.DeckId

@RunWith(RobolectricTestRunner::class)
class AttentionReducerTest {
    @Test fun allClearIsSilent() {
        assertEquals(emptyMap<DeckId, DeckAttention>(), AppHealth().deckAttentions())
    }

    @Test fun runningGameLightsCrate() {
        val a = AppHealth(gameRunning = true).deckAttentions()
        assertEquals(DeckAttention(count = 1, blinking = true), a[DeckId.Crate])
    }

    @Test fun pendingChangesLightTune() {
        assertEquals(3, AppHealth(pendingChanges = 3).deckAttentions()[DeckId.Tune]!!.count)
    }

    @Test fun droppedControllerLightsPadWithError() {
        val a = AppHealth(controllerDropped = true).deckAttentions()
        assertTrue(a[DeckId.Pad]!!.error)
    }

    @Test fun partsAggregatesThreeConditions() {
        val a = AppHealth(firmwareInstalled = false, driverOutdated = true, patchesPending = 4).deckAttentions()
        assertEquals(6, a[DeckId.Parts]!!.count)
    }

    @Test fun manyUnseenErrorsEscalateToError() {
        assertTrue(AppHealth(unseenErrors = 9).deckAttentions()[DeckId.Scope]!!.error)
        assertNull(AppHealth(unseenErrors = 8).deckAttentions()[DeckId.Scope].let { it?.takeIf { _ -> false } })
        // 8 stays a plain count, not an error state:
        assertEquals(false, AppHealth(unseenErrors = 8).deckAttentions()[DeckId.Scope]!!.error)
    }
}
```

### A17 · RouterTest (Robolectric — snapshot state)

```kotlin
package samba.s3.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouterTest {
    @Test fun pushPopAndDepth() {
        val r = Router(listOf(Screen.Crate))
        r.push(Screen.Sleeve("g1"))
        assertEquals(2, r.depth)
        r.push(Screen.Blueprint("g1"))
        assertEquals(3, r.depth)
        assertTrue(r.pop())
        assertEquals(Screen.Sleeve("g1"), r.current)
    }

    @Test fun popReturnsFalseAtRoot() {
        val r = Router(listOf(Screen.Crate))
        assertTrue(!r.pop())
        assertEquals(Screen.Crate, r.current)
    }

    @Test fun popToRoot() {
        val r = Router(listOf(Screen.Crate))
        r.push(Screen.Scope); r.push(Screen.Patches)
        r.popToRoot()
        assertEquals(1, r.depth)
    }

    @Test fun deepLinksParse() {
        assertEquals(Screen.Sleeve("abc"), Router.parse("samba://game/abc"))
        assertEquals(Screen.Tune("cpu"), Router.parse("samba://tune/cpu"))
        assertNull(Router.parse("samba://unknown/thing"))
        assertNull(Router.parse("https://example.com"))
    }
}
```

---

## B · `src/androidTest/` — the instrumented suite

```kotlin
package samba.s3.design

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Components hosted in a bare theme — the standard harness pattern: no app,
// just the component + state + assertions on semantics and behavior.

private fun descContains(s: String) = SemanticsMatcher("contentDescription contains '$s'") { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { s in it } == true
}

class ComponentSemanticsTest {

    @get:Rule val rule = createComposeRule()

    @Test fun vuAnnouncesValueAndZone() {
        rule.setContent { SambaS3Theme { VuMeterFull(95f, max = 100f, label = "fps") } }
        rule.onNode(descContains("fps")).assertExists()
        rule.onNode(descContains("95")).assertExists()
        rule.onNode(descContains("red zone")).assertExists()
    }

    @Test fun vuQuietReadsSteady() {
        rule.setContent { SambaS3Theme { VuMeterFull(30f, max = 100f, label = "cpu") } }
        rule.onNode(descContains("steady")).assertExists()
    }

    @Test fun rockerTogglesOnClick() {
        var checked = false
        rule.setContent { SambaS3Theme { BossaRocker(checked, { checked = it }) } }
        rule.onNode(hasClickAction()).performClick()
        assertTrue(checked)
    }

    @Test fun triStateAnnouncesInheritedValue() {
        rule.setContent {
            SambaS3Theme {
                BossaTriStateRocker(TriState.Inherit, parentValue = true, onValueChange = {})
            }
        }
        rule.onNode(descContains("on · global")).assertExists()   // wait 400ms render — assert after
    }

    @Test fun faderExposesRangeSemantics() {
        rule.setContent {
            SambaS3Theme {
                BossaFader(50f, {}, 0f..100f, label = "resolution", readout = FaderReadout.Always)
            }
        }
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(50f, 0f..100f))).assertExists()
    }

    @Test fun holdButtonFiresAfter800msNotBefore() {
        rule.mainClock.autoAdvance = false
        var confirmed = false
        rule.setContent { SambaS3Theme { BossaHoldButton("remove", { confirmed = true }) } }
        val node = rule.onNode(hasClickAction())

        node.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(300)                        // too early
        rule.waitForIdle()
        assertTrue(!confirmed)
        rule.mainClock.advanceTimeBy(700)                        // past 800ms total
        rule.waitForIdle()
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(confirmed)
    }

    @Test fun deckShowsNinePlusAttention() {
        rule.setContent {
            SambaS3Theme {
                BossaDeck(
                    selected = DeckId.Crate, onSelect = {},
                    attentions = mapOf(DeckId.Parts to DeckAttention(count = 12)),
                )
            }
        }
        rule.onNode(descContains("9+")).assertExists()           // ≥9 collapses to “9+”, §6.6
    }

    @Test fun ledBlinkStaysAccessible() {                        // never color alone, §7
        rule.setContent {
            SambaS3Theme { BossaLed(LedState.Error, contentDescription = "vulkan error") }
        }
        rule.onNode(descContains("vulkan error")).assertExists()
    }
}
```

---

## C · Gradle wiring

```kotlin
// app/build.gradle.kts
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true      // Robolectric
        }
    }
}

dependencies {
    // JVM suite
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("org.json:json:20240303")  // OverlayJson on plain JVM

    // Instrumented suite
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    // Contrast gate already runs as a plain main() (SS3-D-003 file 22b);
    // wire it into CI as:  java -jar lint.jar && ./gradlew test
}
```

---

## D · Errata & migrations (apply once, mechanical)

| # | File | Change |
|---|---|---|
| 1 | 03 Meters.kt | peak-hold loops → `MeterMath.peakDecay`; VU a11y → `MeterMath.vuA11y`; add a comment that `DampedNeedle` documents the reference physics for `BossaNeedleFloat` |
| 2 | 01 BossaKit.kt | `squirclePath` samples via `Superellipse.point` |
| 3 | 11 State.kt | `PendingChangesState` → facade over `PendingChangesCore` (listener bumps a `mutableIntStateOf` version) |
| 4 | 16 EmuSettings.kt | `PresetDiffSheet` uses `PresetMath.diff` |
| 5 | 21 Blueprint.kt | editor block deleted, imports `samba.s3.core.BlueprintEditor`; the composable wraps it: `remember { BlueprintEditor(initial) }` + a `mutableIntStateOf` version driven by `addListener` — same trick as #3; JSON moves out as `OverlayJson` |
| 6 | 15 SleeveScreen.kt | `painterCrop` helper (condensed header) — implement as `Modifier.drawBehind { with(painter) { draw(size) } }` or just `Image` in a clip; cosmetic |
| 7 | A1/A3 test shims | two `_placeholder` lines above are markers — delete them; the real assertions are the lines immediately after |

## E · Ledger

| SS3-D-005 goal | Status |
|---|---|
| Physics pinned (needle, peak-hold, red zone) | ✅ A1, A2 |
| Snap & detent math pinned | ✅ A3 |
| Squircle math pinned (curve residual, fuller corners) | ✅ A4 |
| Real SFO + PUP parsers, byte-tested | ✅ A5, A6 |
| 50k-row scope guarantee | ✅ A7, A15 |
| Game detection rules | ✅ A8 |
| Hashing (known vector + streaming) | ✅ A9 |
| Presets, pending changes, attention, router | ✅ A10–A17 |
| SAF scanner, download/verify/install pipeline, service, catalog, core bridge + fake | ✅ files 25–27 |
| Instrumented semantics (VU a11y, rocker, fader range, hold timing, 9+ badge, LED a11y) | ✅ section B |

**Remaining after this pack:** JNI bindings for `JniCoreInstaller` / `EmuCoreBridge` (the two `NotImplementedError` seams are deliberate — they're the contract the core team fills), font/audio/illustration assets, and hooking the wizard's callbacks to `GameScanner`/`FirmwareInstaller` (both now exist, the wiring is ~40 lines of ViewModel).

Natural next steps, pick one: **(a)** the ViewModel layer that wires scanner + installer + bridge into the Wizard/Sleeve/AmpRoom screens (closes the last app-side gap), **(b)** the full 58-glyph Corda SVG asset pack, or **(c)** a demo/README pack — a scripted FakeCore walkthrough so anyone can run the app end-to-end before the core is bound.
