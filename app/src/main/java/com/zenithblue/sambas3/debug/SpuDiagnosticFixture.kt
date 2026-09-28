package com.zenithblue.sambas3.debug

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Narrow, content-locked inputs for the shell-only ARM64 SPU compiler diagnostic route. */
internal object SpuDiagnosticFixture {
    private const val ASSET_DIRECTORY = "spu-fast-llvm-fixtures"

    private data class Spec(val asset: String, val sha256: String, val diagnosticCase: Int? = null)

    private val fixtures = mapOf(
        "alias_matrix" to Spec("spu_fast_alias_matrix.elf", "07e6763d014fcdf5e8c871c01cfd9c24a2093889d222e73ab0a8a6112609cf22"),
        "branch_reloc_reuse" to Spec("spu_fast_branch_reloc_reuse.elf", "eae18ede394ae144f212ec39b810026f36fdc8a3406268f98ecfc601d4ee7d00"),
        "branch_wrap_high" to Spec("spu_fast_branch_wrap_high.elf", "b4ac13b1e9f9a68e3deeac405ce61d9aebada7b474f6e7de53f31a4957e9b431"),
        "branch_wrap_mid" to Spec("spu_fast_branch_wrap_mid.elf", "c897aef2764e65c006fc26dbcdb83113fa54b2256e18716b77bb75962d81eaf1"),
        "sequential_wrap" to Spec("spu_fast_sequential_wrap.elf", "2ec7e4d4467431bc89214806781c894f384b37b0ac91a60794a35ce5711ecf2a"),
        "hot_promotion" to Spec("spu_fast_hot_promotion.elf", "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e"),
        // These named cases intentionally share the independently validated hot-loop ELF.
        // The selected failure/publication behavior is dispatched by this closed ID map,
        // never by a caller-supplied numeric mode.
        "hot_loop_null_result" to Spec("spu_fast_hot_promotion.elf", "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e", diagnosticCase = 1),
        "hot_loop_stop_before_publish" to Spec("spu_fast_hot_promotion.elf", "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e", diagnosticCase = 2),
        "same_key_success_wait" to Spec("spu_fast_hot_promotion.elf", "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e", diagnosticCase = 3),
        "il_signed_matrix" to Spec("spu_fast_il_signed_matrix.elf", "0fb1a49aa42f0ad5c68c9f853548ff6403608f16e8d2e3f650c221518349087b"),
        "same_key_owner_abandon_retry" to Spec("spu_fast_hot_promotion.elf", "b12be6df7fa8e2fffa421797f2c340711746b2703716336809c86a8895a8240e", diagnosticCase = 4),
        "oversize_single_entry" to Spec("spu_fast_oversize_513.elf", "9bf564d4109c23c42fa8fcc89752d2d31d9f08ecae064dbc7373e29a224ec868", diagnosticCase = 5),
        "self_modify_retained_selector" to Spec("spu_fast_retained_selector_self_modify.elf", "294ad18669bd212c7e1d419b5591ee71d5e1c1647e8f4770794c19068c3b17ea", diagnosticCase = 6),
    )

    internal fun expectedSha256(id: String): String? = fixtures[id]?.sha256
    internal fun diagnosticCase(id: String): Int? = fixtures[id]?.diagnosticCase

    fun materialize(context: Context, id: String): File? {
        val spec = fixtures[id] ?: return null
        val root = File(context.filesDir, "spu-fast-llvm-fixtures")
        if (!root.exists() && !root.mkdirs()) return null
        val canonicalRoot = root.canonicalFile
        if (canonicalRoot.parentFile != context.filesDir.canonicalFile) return null
        val destination = File(root, spec.asset)
        return runCatching {
            val temp = File(root, "${spec.asset}.tmp")
            if (temp.exists() && !temp.delete()) return null
            context.assets.open("$ASSET_DIRECTORY/${spec.asset}").use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (destination.exists() && !destination.delete()) return null
            if (!temp.renameTo(destination)) return null
            destination.takeIf { validate(context, id, it.absolutePath) }
        }.getOrNull()
    }

    /** Revalidates ownership, pinned bytes and the actual ELF identity at the Activity boundary. */
    fun validate(context: Context, id: String, path: String): Boolean {
        val spec = fixtures[id] ?: return false
        val root = File(context.filesDir, "spu-fast-llvm-fixtures")
        val canonicalRoot = root.canonicalFile
        if (canonicalRoot.parentFile != context.filesDir.canonicalFile) return false
        val expected = File(canonicalRoot, spec.asset).canonicalFile
        if (expected.parentFile != canonicalRoot) return false
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        if (candidate != expected || !candidate.isFile || !candidate.canRead() || candidate.length() !in 52L..1_048_576L) return false
        val bytes = runCatching { candidate.readBytes() }.getOrNull() ?: return false
        if (sha256(bytes) != spec.sha256 || !isElf32BigEndianSpu(bytes)) return false
        return true
    }

    fun sha256Of(file: File): String = sha256(file.readBytes())

    internal fun isElf32BigEndianSpu(bytes: ByteArray): Boolean =
        bytes.size >= 52 &&
            bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[4] == 1.toByte() && // ELFCLASS32
            bytes[5] == 2.toByte() && // ELFDATA2MSB
            bytes[18] == 0.toByte() && bytes[19] == 23.toByte() // EM_SPU, big endian

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
