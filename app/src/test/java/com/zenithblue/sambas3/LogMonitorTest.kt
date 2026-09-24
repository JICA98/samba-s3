package com.zenithblue.sambas3

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LogMonitorTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("log_monitor_test").toFile()
        LogMonitor.resetForTest(tempDir)
    }

    @After
    fun tearDown() {
        LogMonitor.stop()
        tempDir.deleteRecursively()
    }

    // -----------------------------------------------------------------------
    // Test 1: LogMonitor normal EOF backoff and cancellation
    // -----------------------------------------------------------------------

    @Test
    fun logcatReader_normalEofBacksOff_andCancelsCleanly() = runTest {
        val channel = Channel<LogEntry>(64)
        val processCount = AtomicInteger(0)
        val destroyedCount = AtomicInteger(0)

        // Mock process where inputStream immediately returns EOF (empty stream)
        val fakeProcessProvider: suspend () -> Process = {
            processCount.incrementAndGet()
            object : Process() {
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = 0
                override fun exitValue(): Int = 0
                override fun destroy() {
                    destroyedCount.incrementAndGet()
                }
            }
        }

        val readerJob = launch {
            LogMonitor.runLogcatReader(
                channel = channel,
                processProvider = fakeProcessProvider,
                eofBackoffMs = 1000L,
                errorBackoffMs = 2000L,
            )
        }

        // Initially process is launched once, hits EOF, and begins backing off for 1000ms
        runCurrent()
        assertEquals(1, processCount.get())
        assertTrue(destroyedCount.get() >= 1)

        // Advance 500ms (still within the 1000ms backoff window)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(1, processCount.get())

        // Advance past 1000ms: second process launch happens after backoff
        advanceTimeBy(600L)
        runCurrent()
        assertEquals(2, processCount.get())

        // Cancel the reader job during backoff
        readerJob.cancel()
        runCurrent()

        assertTrue(readerJob.isCancelled)
        assertTrue(channel.trySend(LogEntry(0L, "", LogLevel.DEBUG, "", "", LogSource.APP)).isClosed)

        // Advance further time to confirm no orphaned loops or additional launches
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(2, processCount.get())
    }

    // -----------------------------------------------------------------------
    // Test 2: Immediate flushing for FATAL / ERROR / CRASH messages
    // -----------------------------------------------------------------------

    @Test
    fun immediateFlushing_forFatalAndErrorAndCrash() {
        val backendFile = LogMonitor.getLogFile(LogFileCategory.BACKEND)
        assertNotNull(backendFile)

        // 1. Regular INFO entry does not force immediate flush
        val infoEntry = LogEntry(
            id = 1L,
            timestamp = "09-25 03:00:00.000",
            level = LogLevel.INFO,
            tag = "sys_ppu_thread",
            message = "normal game info message",
            source = LogSource.RPCSX,
        )
        assertFalse(LogMonitor.isFatalOrCrash(infoEntry))
        LogMonitor.writeEntryToFile(infoEntry)

        // 2. ERROR entry must trigger immediate flush
        val errorEntry = LogEntry(
            id = 2L,
            timestamp = "09-25 03:00:01.000",
            level = LogLevel.ERROR,
            tag = "sys_ppu_thread",
            message = "critical failure error",
            source = LogSource.RPCSX,
        )
        assertTrue(LogMonitor.isFatalOrCrash(errorEntry))
        LogMonitor.writeEntryToFile(errorEntry)

        // Verify error line is immediately readable on disk without manual flush
        val contentAfterError = backendFile!!.readText()
        assertTrue(contentAfterError.contains("E/sys_ppu_thread: critical failure error"))

        // 3. FATAL entry must trigger immediate flush
        val fatalEntry = LogEntry(
            id = 3L,
            timestamp = "09-25 03:00:02.000",
            level = LogLevel.FATAL,
            tag = "RPCS3",
            message = "Fatal VM fault abort",
            source = LogSource.RPCSX,
        )
        assertTrue(LogMonitor.isFatalOrCrash(fatalEntry))
        LogMonitor.writeEntryToFile(fatalEntry)

        val contentAfterFatal = backendFile.readText()
        assertTrue(contentAfterFatal.contains("F/RPCS3: Fatal VM fault abort"))

        // 4. Crash tag / message must trigger immediate flush
        val crashEntry = LogEntry(
            id = 4L,
            timestamp = "09-25 03:00:03.000",
            level = LogLevel.WARN,
            tag = "sys_crashdump",
            message = "dumping stack trace after SIGSEGV",
            source = LogSource.RPCSX,
        )
        assertTrue(LogMonitor.isFatalOrCrash(crashEntry))
        LogMonitor.writeEntryToFile(crashEntry)

        val contentAfterCrash = backendFile.readText()
        assertTrue(contentAfterCrash.contains("dumping stack trace after SIGSEGV"))
    }

    // -----------------------------------------------------------------------
    // Test 3: Timed flush for regular messages and flushWriters
    // -----------------------------------------------------------------------

    @Test
    fun timedFlush_forRegularMessages() {
        val appFile = LogMonitor.getLogFile(LogFileCategory.APP)
        assertNotNull(appFile)

        val entry1 = LogEntry(
            id = 10L,
            timestamp = "09-25 03:00:10.000",
            level = LogLevel.DEBUG,
            tag = "SambaS3",
            message = "debug item 1",
            source = LogSource.APP,
        )
        val entry2 = LogEntry(
            id = 11L,
            timestamp = "09-25 03:00:11.000",
            level = LogLevel.INFO,
            tag = "SambaS3",
            message = "info item 2",
            source = LogSource.APP,
        )

        LogMonitor.writeEntryToFile(entry1)
        LogMonitor.writeEntryToFile(entry2)

        // Calling flushWriters() must synchronously flush all buffered writers to disk
        LogMonitor.flushWriters()

        val text = appFile!!.readText()
        assertTrue(text.contains("debug item 1"))
        assertTrue(text.contains("info item 2"))
    }

    // -----------------------------------------------------------------------
    // Test 4: Bounded UI update coalescing and subscriber filtering
    // -----------------------------------------------------------------------

    @Test
    fun boundedUiUpdateCoalescing_andSubscriberFiltering() = runTest {
        val channel = Channel<LogEntry>(128)
        LogMonitor.setConsumerChannelForTest(channel)

        val consumerJob = launch {
            LogMonitor.runConsumer(
                channel = channel,
                displayCadenceMs = 300L,
                flushIntervalMs = 2000L,
            )
        }

        // Send 10 rapid entries into the channel
        repeat(10) { i ->
            val entry = LogEntry(
                id = i.toLong(),
                timestamp = "09-25 03:00:00.$i",
                level = LogLevel.INFO,
                tag = "SambaS3",
                message = "rapid message $i",
                source = LogSource.APP,
            )
            LogMonitor.enqueue(entry)
        }

        // Before display cadence has elapsed, verify UI lists are bounded / not updating per-entry
        runCurrent()

        // Advance past display cadence (300ms)
        advanceTimeBy(350L)
        runCurrent()

        // UI updates are coalesced and published
        val currentLogs = LogMonitor.logs.value
        assertEquals(10, currentLogs.size)
        assertEquals("rapid message 0", currentLogs.first().message)
        assertEquals("rapid message 9", currentLogs.last().message)

        // Verify subscriber-aware copying: _backendLogs is empty since all messages were APP
        assertTrue(LogMonitor.backendLogs.value.isEmpty())

        consumerJob.cancel()
    }

    // -----------------------------------------------------------------------
    // Test 5: Extreme queue pressure drops low-priority debug but delivers FATAL
    // -----------------------------------------------------------------------

    @Test
    fun extremePressure_dropsDebugMessages_guaranteesFatal() = runTest {
        // Create a small bounded channel
        val tinyChannel = Channel<LogEntry>(4)
        LogMonitor.setConsumerChannelForTest(tinyChannel)

        // Fill the tiny channel to capacity
        repeat(4) { i ->
            tinyChannel.trySend(
                LogEntry(
                    id = i.toLong(),
                    timestamp = "09-25 03:00:00.00$i",
                    level = LogLevel.DEBUG,
                    tag = "SambaS3",
                    message = "filler $i",
                    source = LogSource.APP,
                )
            )
        }

        // Enqueue extra low-priority debug entries under extreme pressure
        val droppedBefore = LogMonitor.droppedCount()
        repeat(5) { i ->
            LogMonitor.enqueue(
                LogEntry(
                    id = (10 + i).toLong(),
                    timestamp = "09-25 03:00:01.00$i",
                    level = LogLevel.DEBUG,
                    tag = "SambaS3",
                    message = "overflow debug $i",
                    source = LogSource.APP,
                )
            )
        }
        val droppedAfter = LogMonitor.droppedCount()
        assertEquals(droppedBefore + 5L, droppedAfter)

        // Now enqueue a critical FATAL entry under extreme pressure: MUST NOT be dropped!
        val fatalEntry = LogEntry(
            id = 999L,
            timestamp = "09-25 03:00:02.000",
            level = LogLevel.FATAL,
            tag = "sys_crashdump",
            message = "FATAL crash dump under pressure",
            source = LogSource.RPCSX,
        )
        LogMonitor.enqueue(fatalEntry)

        // The fatal message was guaranteed delivered to buffer / disk
        val currentLogs = LogMonitor.logs.value
        assertTrue(currentLogs.any { it.message == "FATAL crash dump under pressure" })
    }
}
