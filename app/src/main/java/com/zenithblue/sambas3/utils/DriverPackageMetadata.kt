package com.zenithblue.sambas3.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * ADPKG-compatible GPU driver metadata (Strato / adrenotools schema).
 *
 * @see https://github.com/strato-emu/strato (DriverPackageMetadata)
 */
data class GpuDriverMetadata(
    val name: String,
    val author: String,
    val packageVersion: String,
    val vendor: String,
    val driverVersion: String,
    val minApi: Int,
    val description: String,
    val libraryName: String,
    /** Stable bundled id when installed from Play catalog; null for user imports. */
    val bundledId: String? = null,
    val isBundled: Boolean = false,
    val experimental: Boolean = false,
    val role: String? = null,
    val displayName: String? = null,
) {
    private constructor(metadataV1: GpuDriverMetadataV1) : this(
        name = metadataV1.name,
        author = metadataV1.author,
        packageVersion = metadataV1.packageVersion,
        vendor = metadataV1.vendor,
        driverVersion = metadataV1.driverVersion,
        minApi = metadataV1.minApi,
        description = metadataV1.description,
        libraryName = metadataV1.libraryName,
    )

    /** Preference / map key: bundled drivers use stable id; others use name-v-version label. */
    val label: String
        get() = when {
            isBundled && !bundledId.isNullOrBlank() -> bundledId
            packageVersion.isEmpty() -> name
            else -> "$name-v$packageVersion"
        }

    val uiTitle: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: if (packageVersion.isEmpty()) name else "$name-v$packageVersion"

    companion object {
        private const val SCHEMA_VERSION_V1 = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun deserialize(metadataFile: File): GpuDriverMetadata {
            val metadataJson = json.parseToJsonElement(metadataFile.readText())

            val base = when (metadataJson.jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull) {
                SCHEMA_VERSION_V1 -> GpuDriverMetadata(json.decodeFromJsonElement<GpuDriverMetadataV1>(metadataJson))
                else -> throw SerializationException("Unsupported metadata version")
            }

            val marker = File(metadataFile.parentFile, BundledDriverMarker.FILE_NAME)
            return if (marker.isFile) {
                val m = BundledDriverMarker.read(marker)
                base.copy(
                    bundledId = m.id,
                    isBundled = true,
                    experimental = m.experimental,
                    role = m.role,
                    displayName = m.displayName,
                )
            } else {
                base
            }
        }
    }
}

@Serializable
private data class GpuDriverMetadataV1(
    val schemaVersion: Int,
    val name: String,
    val author: String,
    val packageVersion: String,
    val vendor: String,
    val driverVersion: String,
    val minApi: Int,
    val description: String,
    val libraryName: String,
)

@Serializable
data class BundledDriverMarker(
    val id: String,
    val sha256: String,
    val displayName: String,
    val role: String,
    val experimental: Boolean = false,
    val packageFile: String = "",
    val forceSysmem: Boolean = false,
) {
    companion object {
        const val FILE_NAME = "sambas3_bundled.json"

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun read(file: File): BundledDriverMarker =
            json.decodeFromString(serializer(), file.readText())

        fun write(file: File, marker: BundledDriverMarker) {
            file.writeText(json.encodeToString(serializer(), marker))
        }
    }
}
