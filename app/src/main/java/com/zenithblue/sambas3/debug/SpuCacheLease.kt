package com.zenithblue.sambas3.debug

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.RPCSX
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Process-wide serialization shared by cache leasing, PPU admission, and native boot entry. */
object SpuCacheLeaseGate {
    private val lock = ReentrantLock()
    val processToken: String = UUID.randomUUID().toString()

    fun <T> withLock(block: () -> T): T = lock.withLock(block)

    /** PPU admission must never block the main thread behind a native boot. */
    fun <T> tryWithLock(block: () -> T): T? {
        if (!lock.tryLock()) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/** Narrow, recoverable lease for the one recorded BCUS98111 Mega SPU cache file. */
object SpuCacheLease {
    const val TARGET_TITLE_ID = "BCUS98111"
    const val EXPECTED_SHA256 = "e93c76f0b477ce7debe2066b120d7731c5c4e6e0331b5808c5fa26c10d823d06"
    const val EXPECTED_SIZE = 1_014_808L

    private const val TAG = "S3SPUCACHE"
    private const val CACHE_RELATIVE = "cache/cache/BCUS98111/ppu-HQfb9JM3ziXgkTpuJGuiKhWEfSFq-EBOOT.BIN/spu-mega-v1-tane.dat"
    private const val JOURNAL_NAME = "spu-mega-cache-lease.properties"
    private val startupChecked = java.util.concurrent.atomic.AtomicBoolean(false)

    enum class Operation { BEGIN, STATUS, RESTORE }

    data class Result(val ok: Boolean, val state: String, val detail: String)

    private fun store(context: Context): SpuCacheLeaseStore {
        val externalRoot = context.applicationContext.getExternalFilesDir(null)
            ?: error("app external files directory unavailable")
        val target = File(externalRoot.canonicalFile, CACHE_RELATIVE)
        val journal = File(context.applicationContext.filesDir.canonicalFile, JOURNAL_NAME)
        return SpuCacheLeaseStore(target, journal, EXPECTED_SHA256, EXPECTED_SIZE)
    }

    private fun journalFile(context: Context): File =
        File(context.applicationContext.filesDir.canonicalFile, JOURNAL_NAME)

    private fun hasLeaseJournal(context: Context): Boolean {
        val file = journalFile(context)
        return file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())
    }

    fun begin(context: Context, expectedSha256: String): Result = SpuCacheLeaseGate.withLock {
        mutationBoundaryResult()?.let { return@withLock it }
        if (expectedSha256 != EXPECTED_SHA256) return@withLock Result(false, "rejected", "expected hash does not match approved cache")
        result("begin", runCatching {
            store(context).begin(SpuCacheLeaseGate.processToken)
        })
    }

    fun status(context: Context): Result = SpuCacheLeaseGate.withLock {
        if (!hasLeaseJournal(context)) return@withLock Result(true, "none", "no cache lease")
        result("status", runCatching { store(context).status(SpuCacheLeaseGate.processToken) })
    }

    fun restore(context: Context): Result = SpuCacheLeaseGate.withLock {
        if (!hasLeaseJournal(context)) return@withLock Result(true, "none", "no cache lease")
        mutationBoundaryResult()?.let { return@withLock it }
        result("restore", runCatching { store(context).restore(SpuCacheLeaseGate.processToken) })
    }

    /** Called once per process before any launch. A same-process armed arm is retained. */
    fun recoverOnProcessStart(context: Context): Result? {
        if (!startupChecked.compareAndSet(false, true)) return null
        return SpuCacheLeaseGate.withLock {
            if (!hasLeaseJournal(context)) return@withLock null
            val leaseStore = runCatching { store(context) }.getOrElse {
                return@withLock Result(false, "blocked", it.message ?: "cache location unavailable")
            }
            val current = runCatching { leaseStore.readRecord() }.getOrElse {
                return@withLock Result(false, "blocked", it.message ?: "lease journal unreadable")
            } ?: return@withLock null
            if (current.processToken == SpuCacheLeaseGate.processToken && !current.bootConsumed) {
                return@withLock Result(true, "armed", "same-process lease awaits its one saved-state restore")
            }
            if (!nativeStopped()) return@withLock Result(false, "blocked", "native engine is not stopped")
            if (com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.activeOwnerSnapshot() != null) {
                return@withLock Result(false, "blocked", "PPU owner is active")
            }
            if (com.zenithblue.sambas3.session.EmulationHostRegistry.current() != null || RPCSX.activeGame.value != null) {
                return@withLock Result(false, "blocked", "emulator host or active game is still present")
            }
            val recovered = runCatching { leaseStore.restore(SpuCacheLeaseGate.processToken) }
            result("startup-recovery", recovered)
        }.also { outcome ->
            if (outcome?.ok == false) startupChecked.set(false)
            if (outcome != null) Log.i(TAG, "startup-recovery ok=${outcome.ok} state=${outcome.state} detail=${outcome.detail}")
        }
    }

    /** Consume the armed lease before JNI entry. Only the preserved slot-0 target restore qualifies. */
    fun consumeSavedStateRestore(
        context: Context,
        titleId: String?,
        slot: Int,
        isRestore: Boolean,
    ): Boolean = SpuCacheLeaseGate.withLock {
        if (!hasLeaseJournal(context)) return@withLock true
        val recovery = recoverOnProcessStart(context)
        if (recovery != null && !recovery.ok) return@withLock false
        val leaseStore = runCatching { store(context) }.getOrElse { return@withLock false }
        val record = try { leaseStore.readRecord() } catch (failure: Throwable) {
            Log.e(TAG, "native-boot-refused reason=lease-journal-unreadable", failure)
            return@withLock false
        } ?: return@withLock true
        val sameProcessArmed = record.processToken == SpuCacheLeaseGate.processToken && !record.bootConsumed
        if (!sameProcessArmed || titleId != TARGET_TITLE_ID || slot != 0 || !isRestore) return@withLock false
        runCatching { leaseStore.markBootConsumed(SpuCacheLeaseGate.processToken) }.isSuccess
    }

    /** Ordinary launches and later restores are refused while the lease needs recovery. */
    fun allowsUnleasedBoot(context: Context): Boolean = SpuCacheLeaseGate.withLock {
        if (!hasLeaseJournal(context)) return@withLock true
        val recovery = recoverOnProcessStart(context)
        if (recovery != null && !recovery.ok) return@withLock false
        runCatching { store(context).readRecord() }.getOrElse { return@withLock false } == null
    }

    private fun nativeStopped(): Boolean = !RPCSX.initialized || runCatching {
        RPCSX.getState() == EmulatorState.Stopped
    }.getOrDefault(false)

    private fun mutationBoundaryResult(): Result? {
        if (!nativeStopped()) return Result(false, "blocked", "native engine must be stopped")
        if (com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.activeOwnerSnapshot() != null) {
            return Result(false, "blocked", "PPU owner is active")
        }
        if (com.zenithblue.sambas3.session.EmulationHostRegistry.current() != null || RPCSX.activeGame.value != null) {
            return Result(false, "blocked", "emulator host or active game is still present")
        }
        return null
    }

    private fun result(action: String, result: kotlin.Result<SpuCacheLeaseStore.Snapshot>): Result =
        result.fold(
            onSuccess = { Result(true, it.state, it.detail).also { value -> Log.i(TAG, "$action ok=true state=${value.state} detail=${value.detail}") } },
            onFailure = { Result(false, "error", (it.message ?: it.javaClass.simpleName).take(240)).also { value -> Log.e(TAG, "$action ok=false detail=${value.detail}") } },
        )
}

/** Pure file state machine. Paths are fixed by the caller; no caller-supplied path is accepted. */
internal class SpuCacheLeaseStore(
    private val target: File,
    private val journal: File,
    private val expectedHash: String,
    private val expectedSize: Long,
    private val afterStep: (String) -> Unit = {},
    private val syncDirectory: (File) -> Unit = ::syncLeaseDirectory,
) {
    private val parent = target.parentFile ?: error("cache target has no parent")
    private val backup = File(parent, target.name + ".sambas3-lease-original")
    data class Snapshot(val state: String, val detail: String)
    data class Record(val phase: String, val processToken: String, val bootConsumed: Boolean, val archiveName: String)

    fun begin(processToken: String): Snapshot {
        validatePaths()
        check(!journal.exists()) { "a cache lease record already exists" }
        check(!backup.exists()) { "original-backup sibling already exists" }
        check(target.isFile && !target.isSymbolicLink()) { "canonical Mega cache is missing or not a regular file" }
        check(target.length() == expectedSize && sha256(target) == expectedHash) { "canonical Mega cache does not match pinned size and hash" }
        val record = Record(
            "PREPARED", processToken, false,
            target.name + ".sambas3-lease-generated-" + UUID.randomUUID().toString(),
        )
        persist(record)
        afterStep("journal-prepared")
        atomicRename(target, backup)
        syncDirectory(parent)
        afterStep("original-backed-up")
        persist(record.copy(phase = "BACKED_UP"))
        createEmptyCanonical()
        syncDirectory(parent)
        afterStep("empty-canonical-created")
        persist(record.copy(phase = "EMPTY_CREATED"))
        return Snapshot("armed", "phase=EMPTY_CREATED original_sha256=$expectedHash boot=armed original=${backup.name} canonical_size=0")
    }

    fun status(currentProcessToken: String): Snapshot {
        validatePaths()
        val record = readRecord() ?: return Snapshot("none", "no cache lease")
        val archive = archiveFor(record)
        val backupValid = backup.isFile && !backup.isSymbolicLink() && backup.length() == expectedSize && sha256(backup) == expectedHash
        val sameProcess = record.processToken == currentProcessToken
        val detail = "phase=${record.phase} process=${if (sameProcess) "current" else "previous"} " +
            "boot=${if (record.bootConsumed) "consumed" else "armed"} original_sha256=${if (backupValid) expectedHash else "invalid"} " +
            "canonical_size=${target.takeIf { it.exists() && it.isFile && !it.isSymbolicLink() }?.length() ?: -1} " +
            "archive=${archive.exists()}"
        return Snapshot(if (backupValid) if (record.bootConsumed) "consumed" else "armed" else "recovery_required", detail)
    }

    fun readRecord(): Record? {
        check(!journal.isSymbolicLink()) { "lease journal is a symbolic link" }
        if (!journal.exists()) return null
        check(journal.isFile && !journal.isSymbolicLink()) { "lease journal is not a regular file" }
        val p = Properties().also { FileInputStream(journal).use(it::load) }
        val phase = p.getProperty("phase") ?: error("lease journal has no phase")
        check(phase in setOf("PREPARED", "BACKED_UP", "EMPTY_CREATED", "ARCHIVING_GENERATED", "RESTORING_ORIGINAL", "RESTORED")) {
            "lease journal phase is invalid"
        }
        val token = p.getProperty("processToken") ?: error("lease journal has no process token")
        val consumed = p.getProperty("bootConsumed")?.toBooleanStrictOrNull() ?: error("lease journal has invalid consumed flag")
        check(p.getProperty("expectedSha256") == expectedHash && p.getProperty("expectedSize") == expectedSize.toString()) {
            "lease journal does not match pinned cache identity"
        }
        val archiveName = p.getProperty("archiveName") ?: error("lease journal has no generated archive name")
        check(archiveName.startsWith(target.name + ".sambas3-lease-generated-") &&
            archiveName.none { it == '/' || it == '\\' || it == '\u0000' }
        ) { "lease journal generated archive name is invalid" }
        val record = Record(phase, token, consumed, archiveName)
        val archive = archiveFor(record)
        check(!archive.isSymbolicLink() && archive.canonicalFile == File(parent.canonicalFile, archive.name)) {
            "generated-cache archive resolves through a symlink"
        }
        return record
    }

    fun markBootConsumed(processToken: String) {
        val record = readRecord() ?: error("no armed cache lease")
        check(record.processToken == processToken && !record.bootConsumed) { "lease is not armed in this process" }
        check(record.phase == "EMPTY_CREATED") { "lease is not ready for boot" }
        check(backup.isFile && sha256(backup) == expectedHash) { "pinned original cache is not preserved" }
        check(target.isFile && !target.isSymbolicLink() && target.length() == 0L) { "empty canonical cache changed before boot" }
        persist(record.copy(bootConsumed = true))
    }

    fun restore(currentProcessToken: String): Snapshot {
        validatePaths()
        val record = readRecord() ?: return Snapshot("none", "no cache lease")
        val archive = archiveFor(record)
        if (!backup.exists() && target.isFile && !target.isSymbolicLink() &&
            target.length() == expectedSize && sha256(target) == expectedHash
        ) {
            // PREPARED can be durable before the first rename, and RESTORING can
            // be durable before a crash immediately after the reverse rename.
            check(archive.exists().not() || record.phase != "PREPARED") {
                "unexpected generated archive while original remains canonical"
            }
            check(journal.delete()) { "lease journal could not be cleared" }
            syncDirectory(journal.parentFile ?: error("journal has no parent"))
            return Snapshot("restored", "pinned original already canonical; lease metadata cleared")
        }
        check(backup.isFile && !backup.isSymbolicLink() && backup.length() == expectedSize && sha256(backup) == expectedHash) {
            "pinned original is missing or damaged; refusing to overwrite any file"
        }
        if (target.exists()) {
            check(target.isFile && !target.isSymbolicLink()) { "canonical path is not a regular file" }
            check(!archive.exists()) { "generated-cache archive already exists; refusing overwrite" }
            persist(record.copy(phase = "ARCHIVING_GENERATED"))
            afterStep("restore-intent-persisted")
            atomicRename(target, archive)
            syncDirectory(parent)
            afterStep("generated-cache-archived")
        }
        if (!target.exists()) {
            check(backup.exists()) { "pinned original disappeared; refusing to clear lease" }
            persist(record.copy(phase = "RESTORING_ORIGINAL"))
            afterStep("restore-original-intent-persisted")
            atomicRename(backup, target)
            syncDirectory(parent)
            afterStep("original-restored")
        }
        check(target.isFile && !target.isSymbolicLink() && target.length() == expectedSize && sha256(target) == expectedHash) {
            "restored canonical cache failed pinned verification; original remains at ${target.absolutePath}"
        }
        persist(record.copy(phase = "RESTORED"))
        afterStep("restore-verified")
        check(journal.delete()) { "lease journal could not be cleared" }
        syncDirectory(journal.parentFile ?: error("journal has no parent"))
        return Snapshot("restored", "phase=RESTORED original_sha256=$expectedHash original_hash_verified=true " +
            "boot=${if (record.bootConsumed) "consumed" else "armed"} generated_archive=${archive.exists()} " +
            "previous_process=${record.processToken != currentProcessToken}")
    }

    private fun createEmptyCanonical() {
        check(!target.exists()) { "canonical cache path unexpectedly exists" }
        check(target.createNewFile()) { "could not create empty canonical cache" }
        FileOutputStream(target, true).use { it.fd.sync() }
    }

    private fun persist(record: Record) {
        val tmp = File(journal.parentFile ?: error("journal has no parent"), journal.name + ".tmp")
        if (tmp.exists()) check(tmp.delete()) { "stale journal temporary cannot be removed" }
        check(tmp.createNewFile()) { "could not create journal temporary" }
        try {
            val p = Properties().apply {
                setProperty("phase", record.phase)
                setProperty("processToken", record.processToken)
                setProperty("bootConsumed", record.bootConsumed.toString())
                setProperty("archiveName", record.archiveName)
                setProperty("expectedSha256", expectedHash)
                setProperty("expectedSize", expectedSize.toString())
            }
            FileOutputStream(tmp).use { out -> p.store(out, "SambaS3 fixed SPU cache lease"); out.fd.sync() }
            atomicReplaceJournal(tmp, journal)
            syncDirectory(journal.parentFile ?: error("journal has no parent"))
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun validatePaths() {
        val canonicalParent = parent.canonicalFile
        check(canonicalParent == parent.absoluteFile.normalizeFile()) { "cache parent resolves through a symlink" }
        check(target.canonicalFile == File(canonicalParent, target.name)) { "cache target resolves outside its fixed directory" }
        check(journal.canonicalFile == journal.absoluteFile.normalizeFile()) { "lease journal resolves through a symlink" }
        for (artifact in listOf(target, backup, journal)) {
            check(!artifact.isSymbolicLink()) { "lease path is a symbolic link: ${artifact.name}" }
        }
    }

    private fun atomicRename(from: File, to: File) {
        check(from.exists()) { "rename source is missing: ${from.name}" }
        check(!to.exists()) { "rename destination exists: ${to.name}" }
        check(from.renameTo(to)) { "same-directory atomic rename failed: ${from.name}" }
    }

    private fun atomicReplaceJournal(from: File, to: File) {
        java.nio.file.Files.move(
            from.toPath(), to.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun archiveFor(record: Record): File = File(parent, record.archiveName)

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.isSymbolicLink(): Boolean = java.nio.file.Files.isSymbolicLink(toPath())
    private fun File.normalizeFile(): File = java.nio.file.Paths.get(absolutePath).normalize().toFile()

}

private fun syncLeaseDirectory(dir: File) {
    val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
