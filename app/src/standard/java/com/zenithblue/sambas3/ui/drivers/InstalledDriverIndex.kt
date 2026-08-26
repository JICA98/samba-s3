package com.zenithblue.sambas3.ui.drivers

import com.zenithblue.sambas3.drivers.catalog.RemoteDriverPackage
import com.zenithblue.sambas3.utils.GpuDriverMetadata
import java.io.File
import java.util.Locale

data class InstalledDriverRef(
    val directory: File,
    val metadata: GpuDriverMetadata,
)

class InstalledDriverIndex private constructor(
    private val exact:
        Map<String, InstalledDriverRef>,
    private val entries:
        List<InstalledDriverRef>,
) {

    fun find(
        pkg: RemoteDriverPackage
    ): InstalledDriverRef? {
        val candidates =
            buildList {
                add(pkg.id)
                add(pkg.displayName)
                pkg.version?.let(::add)
            }

        for (candidate in candidates) {
            exact[normalize(candidate)]?.let {
                return it
            }
        }

        // Compatibility fallback for old packages whose remote id
        // was never persisted into meta.json.
        //
        // This is done against the small installed list, not by
        // rebuilding fuzzy comparisons repeatedly in every row.
        val remote =
            normalize(pkg.displayName)

        return entries.firstOrNull { ref ->
            val metadata = ref.metadata

            val labels =
                listOf(
                    metadata.label,
                    metadata.name,
                    metadata.uiTitle,
                    metadata.driverVersion,
                    metadata.packageVersion,
                )

            labels.any {
                normalize(it) == remote
            }
        }
    }

    companion object {
        fun from(
            drivers:
                Map<File, GpuDriverMetadata>
        ): InstalledDriverIndex {
            val entries =
                drivers.map { (file, metadata) ->
                    InstalledDriverRef(
                        directory = file,
                        metadata = metadata,
                    )
                }

            val index =
                buildMap {
                    for (ref in entries) {
                        val metadata =
                            ref.metadata

                        val keys =
                            listOfNotNull(
                                metadata.label,
                                metadata.name,
                                metadata.uiTitle,
                                metadata.bundledId,
                                metadata.driverVersion,
                            )

                        for (key in keys) {
                            put(
                                normalize(key),
                                ref
                            )
                        }
                    }
                }

            return InstalledDriverIndex(
                exact = index,
                entries = entries,
            )
        }

        private fun normalize(
            value: String
        ): String =
            value
                .trim()
                .lowercase(Locale.ROOT)
                .replace(
                    Regex("[^a-z0-9]+"),
                    ""
                )
    }
}
