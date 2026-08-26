package com.zenithblue.sambas3.drivers.download

import android.content.Context
import com.zenithblue.sambas3.drivers.catalog.ChecksumAlgorithm
import com.zenithblue.sambas3.drivers.catalog.DriverArchiveFormat
import com.zenithblue.sambas3.drivers.catalog.RemoteDriverPackage
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverInstallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

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

    private val jobs =
        ConcurrentHashMap<String, Job>()

    private val calls =
        ConcurrentHashMap<String, Call>()

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
        val oldJob = jobs[pkg.id]

        if (oldJob?.isActive == true) {
            return
        }

        val state =
            states.getOrPut(pkg.id) {
                MutableStateFlow(
                    DriverDownloadState.Idle
                )
            }

        jobs[pkg.id] =
            scope.launch(Dispatchers.IO) {
                state.value =
                    DriverDownloadState.Downloading(
                        bytesRead = 0,
                        totalBytes = null,
                    )

                val safeName =
                    pkg.displayName
                        .replace(
                            Regex(
                                "[^A-Za-z0-9._-]"
                            ),
                            "_"
                        )
                        .take(64)

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
                        "$safeName$ext"
                    )

                val adaptedFile =
                    File(
                        cacheDir,
                        "$safeName.adapted.zip"
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
                                // This is a StateFlow for ONE ROW.
                                // No giant screen Map allocation.
                                state.value =
                                    DriverDownloadState
                                        .Downloading(
                                            bytesRead = bytes,
                                            totalBytes = total,
                                        )
                            },
                            onCallCreated = { call ->
                                calls[pkg.id] = call
                            }
                        )

                    when (download) {
                        is DriverDownloader.Result.Canceled -> {
                            state.value =
                                DriverDownloadState.Idle
                            return@launch
                        }

                        is DriverDownloader.Result.Error -> {
                            state.value =
                                DriverDownloadState.Failed(
                                    download.message
                                )
                            return@launch
                        }

                        is DriverDownloader.Result.Success ->
                            Unit
                    }

                    state.value =
                        DriverDownloadState.Verifying

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
                                state.value =
                                    DriverDownloadState
                                        .Failed(
                                            adapt.message
                                        )
                                return@launch
                            }
                        }

                    state.value =
                        DriverDownloadState.Installing

                    val installResult =
                        FileInputStream(
                            installFile
                        ).use { stream ->
                            GpuDriverHelper.installDriver(
                                appContext,
                                stream
                            )
                        }

                    if (
                        installResult !=
                        GpuDriverInstallResult.Success
                    ) {
                        state.value =
                            DriverDownloadState.Failed(
                                GpuDriverHelper
                                    .resolveInstallResultToString(
                                        installResult
                                    )
                            )
                        return@launch
                    }

                    state.value =
                        DriverDownloadState.Installed

                    withContext(Dispatchers.Main.immediate) {
                        onInstalled()
                    }
                } catch (t: Throwable) {
                    state.value =
                        DriverDownloadState.Failed(
                            t.message
                                ?: "Driver installation failed"
                        )
                } finally {
                    calls.remove(pkg.id)
                    jobs.remove(pkg.id)

                    runCatching {
                        sourceFile.delete()
                    }

                    runCatching {
                        adaptedFile.delete()
                    }
                }
            }
    }

    fun cancel(
        packageId: String
    ) {
        calls.remove(packageId)?.cancel()
        jobs.remove(packageId)?.cancel()

        states[packageId]?.value =
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
