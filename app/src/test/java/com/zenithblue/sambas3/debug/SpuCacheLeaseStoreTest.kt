package com.zenithblue.sambas3.debug

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpuCacheLeaseStoreTest {
    @Test fun ppuAdmissionGateFailsFastWhileBootOrMutationOwnsTheProcessLock() {
        val executor = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val owner = executor.submit {
                SpuCacheLeaseGate.withLock {
                    locked.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            assertEquals(null, SpuCacheLeaseGate.tryWithLock { "admitted" })
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
            assertEquals("admitted", SpuCacheLeaseGate.tryWithLock { "admitted" })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun interruptionBeforeFirstRenameClearsOnlyPreparedJournal() {
        val fixture = Fixture()
        try {
            val interrupted = fixture.store { step -> if (step == "journal-prepared") error("simulated process death") }
            assertTrue(runCatching { interrupted.begin("process-a") }.isFailure)
            assertTrue(fixture.target.exists())
            assertFalse(fixture.backup.exists())

            assertEquals("restored", fixture.store().restore("process-b").state)
            assertEquals(fixture.expectedBytes.toList(), fixture.target.readBytes().toList())
            assertFalse(fixture.journal.exists())
        } finally { fixture.close() }
    }

    @Test fun interruptionsAfterOriginalMoveOrEmptyCreateRestoreOriginalAndKeepGeneratedFile() {
        for (interruptAt in listOf("original-backed-up", "empty-canonical-created")) {
            val fixture = Fixture()
            try {
                val interrupted = fixture.store { step -> if (step == interruptAt) error("simulated process death") }
                assertTrue(runCatching { interrupted.begin("process-a") }.isFailure)
                val restored = fixture.store().restore("process-b")
                assertEquals("restored", restored.state)
                assertEquals(fixture.expectedBytes.toList(), fixture.target.readBytes().toList())
                assertEquals(interruptAt == "empty-canonical-created", fixture.generatedArchives.isNotEmpty())
                assertFalse(fixture.backup.exists())
                assertFalse(fixture.journal.exists())
            } finally { fixture.close() }
        }
    }

    @Test fun interruptionAfterGeneratedArchiveIsIdempotentlyRecoverable() {
        val fixture = Fixture()
        try {
            fixture.store().begin("process-a")
            val interrupted = fixture.store { step -> if (step == "generated-cache-archived") error("simulated process death") }
            assertTrue(runCatching { interrupted.restore("process-a") }.isFailure)
            assertTrue(fixture.generatedArchives.isNotEmpty())
            assertFalse(fixture.target.exists())

            assertEquals("restored", fixture.store().restore("process-b").state)
            assertEquals(fixture.expectedBytes.toList(), fixture.target.readBytes().toList())
            assertTrue(fixture.generatedArchives.isNotEmpty())
            assertFalse(fixture.journal.exists())
        } finally { fixture.close() }
    }

    @Test fun interruptionAfterOriginalRenameRecognizesVerifiedOriginalAndFinishesJournal() {
        val fixture = Fixture()
        try {
            fixture.store().begin("process-a")
            val interrupted = fixture.store { step -> if (step == "original-restored") error("simulated process death") }
            assertTrue(runCatching { interrupted.restore("process-a") }.isFailure)
            assertEquals(fixture.expectedBytes.toList(), fixture.target.readBytes().toList())
            assertFalse(fixture.backup.exists())

            assertEquals("restored", fixture.store().restore("process-b").state)
            assertFalse(fixture.journal.exists())
            assertTrue(fixture.generatedArchives.isNotEmpty())
        } finally { fixture.close() }
    }

    @Test fun bootConsumptionAtomicallyReplacesJournalAndSecondLeaseGetsAUniqueArchive() {
        val fixture = Fixture()
        try {
            val first = fixture.store()
            first.begin("process-a")
            first.markBootConsumed("process-a")
            assertEquals("consumed", first.status("process-a").state)
            assertEquals("restored", first.restore("process-a").state)

            val second = fixture.store()
            second.begin("process-a")
            second.restore("process-a")
            assertEquals(2, fixture.cacheDirectory.listFiles()!!.count { it.name.contains(".sambas3-lease-generated-") })
        } finally { fixture.close() }
    }

    @Test fun corruptLeaseJournalFailsClosed() {
        val fixture = Fixture()
        try {
            fixture.journal.writeText("phase=EMPTY_CREATED\nprocessToken=bad\nbootConsumed=maybe\n")
            assertTrue(runCatching { fixture.store().readRecord() }.isFailure)
            assertEquals(fixture.expectedBytes.toList(), fixture.target.readBytes().toList())
        } finally { fixture.close() }
    }

    @Test fun mismatchedExpectedHashAndConflictingArchiveFailWithoutLosingOriginal() {
        val fixture = Fixture()
        try {
            val bad = SpuCacheLeaseStore(fixture.target, fixture.journal, "0".repeat(64), fixture.expectedBytes.size.toLong(), syncDirectory = {})
            assertTrue(runCatching { bad.begin("process-a") }.isFailure)
            assertEquals(fixture.expectedBytes.toList(), fixture.target.readBytes().toList())
            assertFalse(fixture.journal.exists())

            fixture.store().begin("process-a")
            val record = fixture.store().readRecord()!!
            File(fixture.cacheDirectory, record.archiveName).writeText("preexisting conflict")
            assertTrue(runCatching { fixture.store().restore("process-a") }.isFailure)
            assertTrue(fixture.backup.exists())
            assertEquals(fixture.expectedBytes.toList(), fixture.backup.readBytes().toList())
        } finally { fixture.close() }
    }

    private class Fixture : AutoCloseable {
        private val root = Files.createTempDirectory("spu-cache-lease-test").toFile()
        val cacheDirectory = File(root, "cache/cache/BCUS98111/ppu-HQfb9JM3ziXgkTpuJGuiKhWEfSFq-EBOOT.BIN")
        private val appDirectory = File(root, "app-files")
        val expectedBytes = ByteArray(257) { ((it * 73 + 19) and 0xff).toByte() }
        val target = File(cacheDirectory, "spu-mega-v1-tane.dat")
        val journal = File(appDirectory, "lease.properties")
        val backup get() = File(cacheDirectory, target.name + ".sambas3-lease-original")
        val generatedArchives get() = cacheDirectory.listFiles()!!.filter { it.name.contains(".sambas3-lease-generated-") }
        private val hash = sha256(expectedBytes)

        init {
            assertTrue(cacheDirectory.mkdirs())
            assertTrue(appDirectory.mkdirs())
            target.writeBytes(expectedBytes)
        }

        fun store(afterStep: (String) -> Unit = {}): SpuCacheLeaseStore =
            SpuCacheLeaseStore(target, journal, hash, expectedBytes.size.toLong(), afterStep, syncDirectory = {})

        override fun close() { root.deleteRecursively() }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
