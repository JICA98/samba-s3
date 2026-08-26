package com.zenithblue.sambas3.drivers.download

import android.content.Context
import com.zenithblue.sambas3.drivers.catalog.ChecksumAlgorithm
import com.zenithblue.sambas3.drivers.catalog.DriverArchiveFormat
import com.zenithblue.sambas3.drivers.catalog.RemoteDriverPackage
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverInstallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface DriverDownloadState {
    data object Idle : DriverDownloadState

    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long?,
    ) : DriverDownloadState

    data object Verifying : DriverDownloadState
    data object Installing : DriverDownloadState
    data object Installed : DriverDownloadState

    data class Failed(
        val message: String
    ) : DriverDownloadState
}

class DriverDownloadRegistry(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext =
        context.applicationContext

    private val states =
        ConcurrentHashMap<
            String,
            MutableStateFlow<DriverDownloadState>
        >()

    private data class ActiveJob(
        val token: Long,
        val job: Job,
    )

    private data class ActiveCall(
        val token: Long,
        val call: Call,
    )

    private val nextToken =
        AtomicLong(1L)

    private val tokens =
        ConcurrentHashMap<
            String,
            Long
        >()

    private val jobs =
        ConcurrentHashMap<
            String,
            ActiveJob
        >()

    private val calls =
        ConcurrentHashMap<
            String,
            ActiveCall
        >()

    companion object {
        private val installMutex =
            Mutex()
    }

    private fun isCurrent(
        packageId: String,
        token: Long
    ): Boolean =
        tokens[packageId] ==
            token

    private fun publish(
        packageId: String,
        token: Long,
        value: DriverDownloadState
    ) {
        if (
            !isCurrent(
                packageId,
                token
            )
        ) {
            return
        }

        states
            .getOrPut(packageId) {
                MutableStateFlow(
                    DriverDownloadState.Idle
                )
            }
            .value =
            value
    }

    private fun cacheStem(
        pkg: RemoteDriverPackage
    ): String {
        val clean =
            pkg.displayName
                .replace(
                    Regex(
                        "[^A-Za-z0-9._-]"
                    ),
                    "_"
                )
                .take(40)

        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    pkg.id.toByteArray(
                        Charsets.UTF_8
                    )
                )
                .take(8)
                .joinToString("") {
                    "%02x".format(it)
                }

        return "${clean}_$digest"
    }

    fun stateFor(
        packageId: String
    ): StateFlow<DriverDownloadState> =
        states.getOrPut(packageId) {
            MutableStateFlow(
                DriverDownloadState.Idle
            )
        }.asStateFlow()

    fun start(
        pkg: RemoteDriverPackage,
        onInstalled: suspend () -> Unit,
    ) {
        val existing = jobs[pkg.id]
        if (existing?.job?.isActive == true) {
            return
        }

        val token = nextToken.getAndIncrement()
        tokens[pkg.id] = token

        val state =
            states.getOrPut(pkg.id) {
                MutableStateFlow(
                    DriverDownloadState.Idle
                )
            }

        val job = scope.launch(Dispatchers.IO) {
            publish(
                pkg.id,
                token,
                DriverDownloadState.Downloading(
                    bytesRead = 0,
                    totalBytes = null,
                )
            )

            val stem = cacheStem(pkg)

            val ext =
                when (pkg.archiveFormat) {
                    DriverArchiveFormat.TZST ->
                        ".tzst"

                    else ->
                        ".zip"
                }

            val cacheDir =
                File(
                    appContext.cacheDir,
                    "driver_downloads"
                ).apply {
                    mkdirs()
                }

            val sourceFile =
                File(
                    cacheDir,
                    "$stem$ext"
                )

            val adaptedFile =
                File(
                    cacheDir,
                    "$stem.adapted.zip"
                )

            try {
                sourceFile.delete()
                adaptedFile.delete()

                val download =
                    DriverDownloader.download(
                        url = pkg.downloadUrl,
                        destFile = sourceFile,
                        expectedSha256 =
                            pkg.checksum
                                ?.takeIf {
                                    it.algorithm ==
                                        ChecksumAlgorithm.SHA256
                                }
                                ?.value
                                ?: pkg.sha256,
                        progress = {
                                bytes,
                                total ->
                            publish(
                                pkg.id,
                                token,
                                DriverDownloadState
                                    .Downloading(
                                        bytesRead = bytes,
                                        totalBytes = total,
                                    )
                            )
                        },
                        onCallCreated = { call ->
                            if (isCurrent(pkg.id, token)) {
                                calls[pkg.id] = ActiveCall(token, call)
                            } else {
                                call.cancel()
                            }
                        }
                    )

                when (download) {
                    is DriverDownloader.Result.Canceled -> {
                        publish(
                            pkg.id,
                            token,
                            DriverDownloadState.Idle
                        )
                        return@launch
                    }

                    is DriverDownloader.Result.Error -> {
                        publish(
                            pkg.id,
                            token,
                            DriverDownloadState.Failed(
                                download.message
                            )
                        )
                        return@launch
                    }

                    is DriverDownloader.Result.Success ->
                        Unit
                }

                publish(
                    pkg.id,
                    token,
                    DriverDownloadState.Verifying
                )

                val adapt =
                    DriverPackageAdapter.adapt(
                        sourceFile,
                        adaptedFile,
                        pkg.checksum
                    )

                val installFile =
                    when (adapt) {
                        is DriverPackageAdapter
                            .Result.Success ->
                            adapt.adaptedFile

                        is DriverPackageAdapter
                            .Result.Error -> {
                            publish(
                                pkg.id,
                                token,
                                DriverDownloadState
                                    .Failed(
                                        adapt.message
                                    )
                            )
                            return@launch
                        }
                    }

                publish(
                    pkg.id,
                    token,
                    DriverDownloadState.Installing
                )

                val installResult =
                    installMutex.withLock {
                        FileInputStream(
                            installFile
                        ).use {
                                stream ->

                            GpuDriverHelper
                                .installDriver(
                                    appContext,
                                    stream
                                )
                        }
                    }

                if (
                    installResult !=
                    GpuDriverInstallResult.Success
                ) {
                    publish(
                        pkg.id,
                        token,
                        DriverDownloadState.Failed(
                            GpuDriverHelper
                                .resolveInstallResultToString(
                                    installResult
                                )
                        )
                    )
                    return@launch
                }

                publish(
                    pkg.id,
                    token,
                    DriverDownloadState.Installed
                )

                withContext(Dispatchers.Main.immediate) {
                    onInstalled()
                }
            } catch (
                e: CancellationException
            ) {
                publish(
                    pkg.id,
                    token,
                    DriverDownloadState.Idle
                )

                throw e
            } catch (
                t: Throwable
            ) {
                publish(
                    pkg.id,
                    token,
                    DriverDownloadState
                        .Failed(
                            t.message
                                ?: "Driver installation failed"
                        )
                )
            } finally {
                val call =
                    calls[pkg.id]

                if (
                    call?.token ==
                    token
                ) {
                    calls.remove(
                        pkg.id,
                        call
                    )
                }

                val jobEntry =
                    jobs[pkg.id]

                if (
                    jobEntry?.token ==
                    token
                ) {
                    jobs.remove(
                        pkg.id,
                        jobEntry
                    )
                }

                tokens.remove(
                    pkg.id,
                    token
                )

                runCatching {
                    sourceFile.delete()
                }

                runCatching {
                    adaptedFile.delete()
                }
            }
        }

        jobs[pkg.id] = ActiveJob(token, job)
    }

    fun cancel(
        packageId: String
    ) {
        val active =
            jobs[packageId]
                ?: return

        tokens.remove(
            packageId,
            active.token
        )

        calls[packageId]
            ?.takeIf {
                it.token ==
                    active.token
            }
            ?.let {
                calls.remove(
                    packageId,
                    it
                )

                it.call.cancel()
            }

        jobs.remove(
            packageId,
            active
        )

        active.job.cancel()

        states[packageId]
            ?.value =
            DriverDownloadState.Idle
    }

    fun retry(
        pkg: RemoteDriverPackage,
        onInstalled: suspend () -> Unit,
    ) {
        cancel(pkg.id)
        start(pkg, onInstalled)
    }
}
