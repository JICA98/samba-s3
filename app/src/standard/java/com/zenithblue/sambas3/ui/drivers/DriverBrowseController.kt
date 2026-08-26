package com.zenithblue.sambas3.ui.drivers

import com.zenithblue.sambas3.drivers.catalog.DriverCatalogRepository
import com.zenithblue.sambas3.drivers.catalog.DriverCatalogSnapshot
import com.zenithblue.sambas3.drivers.catalog.DriverGpuFilter
import com.zenithblue.sambas3.drivers.catalog.DriverSourceId
import com.zenithblue.sambas3.drivers.catalog.DriverVariantFilter
import com.zenithblue.sambas3.drivers.catalog.RemoteDriverPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class DriverFilterInput(
    val snapshot: DriverCatalogSnapshot?,
    val query: String,
    val source: DriverSourceId?,
    val gpu: DriverGpuFilter,
    val variant: DriverVariantFilter,
    val hideExperimental: Boolean,
    val latestOnly: Boolean,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DriverBrowseController(
    scope: CoroutineScope,
) {
    val snapshot =
        MutableStateFlow<
            DriverCatalogSnapshot?
        >(null)

    val query =
        MutableStateFlow("")

    val source =
        MutableStateFlow<
            DriverSourceId?
        >(null)

    val gpu =
        MutableStateFlow(
            DriverGpuFilter.ALL
        )

    val variant =
        MutableStateFlow(
            DriverVariantFilter.ALL
        )

    val hideExperimental =
        MutableStateFlow(false)

    val latestOnly =
        MutableStateFlow(false)

    private val input =
        combine(
            snapshot,
            query.debounce(120),
            source,
            gpu,
            variant,
            hideExperimental,
            latestOnly,
        ) {
                values ->
            @Suppress(
                "UNCHECKED_CAST"
            )
            DriverFilterInput(
                snapshot =
                    values[0]
                        as DriverCatalogSnapshot?,
                query =
                    values[1] as String,
                source =
                    values[2]
                        as DriverSourceId?,
                gpu =
                    values[3]
                        as DriverGpuFilter,
                variant =
                    values[4]
                        as DriverVariantFilter,
                hideExperimental =
                    values[5] as Boolean,
                latestOnly =
                    values[6] as Boolean,
            )
        }

    val filtered =
        input
            .mapLatest { input ->
                withContext(
                    Dispatchers.Default
                ) {
                    val packages =
                        input.snapshot
                            ?.packages
                            .orEmpty()

                    DriverCatalogRepository
                        .filterDrivers(
                            packages =
                                packages,
                            query =
                                input.query,
                            source =
                                input.source,
                            gpu =
                                input.gpu,
                            variant =
                                input.variant,
                            hideExperimental =
                                input
                                    .hideExperimental,
                            latestOnly =
                                input.latestOnly,
                        )
                }
            }
            .stateIn(
                scope = scope,
                started =
                    SharingStarted
                        .WhileSubscribed(
                            5_000
                        ),
                initialValue =
                    emptyList<
                        RemoteDriverPackage
                    >(),
            )
}
