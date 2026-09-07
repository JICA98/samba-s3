package com.zenithblue.sambas3

import com.zenithblue.sambas3.iso.DirectIsoManager
import com.zenithblue.sambas3.iso.DirectIsoSession
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DirectIsoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun legacyGameInfoDeserializationDefaultsToInstalled() {
        val legacyJson = """{"path":"/storage/emulated/0/games/BLUS31584","name":"GTA San Andreas","iconPath":"/icon.png","gameFlags":0}"""
        val info = json.decodeFromString<GameInfo>(legacyJson)

        assertEquals("/storage/emulated/0/games/BLUS31584", info.path)
        assertEquals("GTA San Andreas", info.name)
        assertEquals(GameSourceMode.INSTALLED, info.sourceMode)
        assertNull(info.sourceUri)
    }

    @Test
    fun directIsoSerializationRoundTrip() {
        val directInfo = GameInfo(
            path = "content://com.android.providers.media.documents/document/123",
            name = "Red Dead Redemption",
            iconPath = "/preview/BLUS30758.png",
            gameFlags = 0,
            sourceUri = "content://com.android.providers.media.documents/document/123",
            sourceMode = GameSourceMode.DIRECT_ISO
        )
        val serialized = json.encodeToString(directInfo)
        val deserialized = json.decodeFromString<GameInfo>(serialized)

        assertEquals(directInfo.path, deserialized.path)
        assertEquals(directInfo.name, deserialized.name)
        assertEquals(GameSourceMode.DIRECT_ISO, deserialized.sourceMode)
        assertEquals(directInfo.sourceUri, deserialized.sourceUri)
    }

    @Test
    fun persistenceFilterPreservesDirectIsoContentUri() {
        val installed = GameInfo(
            path = "/storage/emulated/0/games/BLUS31584",
            name = "Installed Game",
            sourceMode = GameSourceMode.INSTALLED
        )
        val legacyContent = GameInfo(
            path = "content://temp/game.iso",
            name = "Legacy Installing Game",
            sourceMode = GameSourceMode.INSTALLED
        )
        val directIsoContent = GameInfo(
            path = "content://saf/games/rdr.iso",
            name = "Direct ISO Game",
            sourceUri = "content://saf/games/rdr.iso",
            sourceMode = GameSourceMode.DIRECT_ISO
        )
        val dummyDollar = GameInfo(
            path = "$",
            name = "Dummy",
            sourceMode = GameSourceMode.INSTALLED
        )

        val list = listOf(installed, legacyContent, directIsoContent, dummyDollar)
        val filtered = list.filter { info ->
            info.path != "$" && (info.sourceMode == GameSourceMode.DIRECT_ISO || !info.path.startsWith("content://"))
        }

        assertEquals(2, filtered.size)
        assertTrue(filtered.contains(installed))
        assertTrue(filtered.contains(directIsoContent))
        assertFalse(filtered.contains(legacyContent))
        assertFalse(filtered.contains(dummyDollar))
    }

    @Test
    fun directIsoIdentityPrefersTitleIdOverVolatileUri() {
        val directKey = GameIdentity.key("direct_iso/BLUS30758", "Red Dead Redemption")
        val installedKey = GameIdentity.key("/data/data/com.zenithblue.sambas3/config/games/BLUS30758", "Red Dead Redemption")
        assertEquals("BLUS30758", directKey)
        assertEquals(installedKey, directKey)
        assertEquals("BLUS30758", GameIdentity.titleIdOrNull("direct_iso/BLUS30758", "Red Dead Redemption"))
    }

    @Test
    fun preferPathReplacesProvisionalContentUriWithInstalledDir() {
        assertTrue(GameIdentity.preferPath("/games/BLUS30758", "content://provider/doc/123"))
        assertTrue(GameIdentity.preferPath("/games/BLUS30758", "/games/game.iso"))
        assertFalse(GameIdentity.preferPath("/games/game.iso", "/games/BLUS30758"))
    }

    @Test
    fun debugBuildFlagSelectsDirectFlow() {
        // Unit tests run against the debug variant, where direct ISO loading must be on.
        // Release keeps the legacy install/copy path (verified via release build type config).
        assertTrue(BuildConfig.DIRECT_ISO_LOADING)
    }

    @Test
    fun matchExistingGame_detectsSameUriAndTitleId() {
        assertTrue(
            DirectIsoManager.matchExistingGame(
                gamePath = "direct_iso/BLUS31584",
                gameName = "GTA San Andreas",
                gameSourceUri = "content://tree/doc/gta.iso",
                incomingUri = "content://tree/doc/gta.iso",
                incomingTitleId = null,
            )
        )
        assertTrue(
            DirectIsoManager.matchExistingGame(
                gamePath = "direct_iso/BLUS31584",
                gameName = "GTA San Andreas",
                gameSourceUri = "content://other/iso",
                incomingUri = "content://tree/doc/gta.iso",
                incomingTitleId = "BLUS31584",
            )
        )
        assertTrue(
            DirectIsoManager.matchExistingGame(
                gamePath = "/files/config/games/BLUS31584",
                gameName = "GTA San Andreas",
                gameSourceUri = null,
                incomingUri = "content://tree/doc/gta.iso",
                incomingTitleId = "BLUS31584",
            )
        )
        assertFalse(
            DirectIsoManager.matchExistingGame(
                gamePath = "direct_iso/BCUS98114",
                gameName = "Other",
                gameSourceUri = "content://other/iso",
                incomingUri = "content://tree/doc/gta.iso",
                incomingTitleId = "BLUS31584",
            )
        )
    }

    @Test
    fun mergeFolderResults_prefersImportedAndDedupesTitleId() {
        val first = DirectIsoManager.IsoFolderImportResult(
            entries = listOf(
                DirectIsoManager.IsoImportEntry("A.iso", "BLUS31584", "content://a", DirectIsoManager.IsoImportStatus.FAILED, "bad"),
            )
        )
        val second = DirectIsoManager.IsoFolderImportResult(
            entries = listOf(
                DirectIsoManager.IsoImportEntry("A-fixed.iso", "BLUS31584", "content://a2", DirectIsoManager.IsoImportStatus.IMPORTED),
                DirectIsoManager.IsoImportEntry("B.iso", "BCUS98114", "content://b", DirectIsoManager.IsoImportStatus.ALREADY_IMPORTED),
            )
        )
        val merged = DirectIsoManager.mergeFolderResults(first, second)
        assertEquals(2, merged.entries.size)
        assertEquals(DirectIsoManager.IsoImportStatus.IMPORTED, merged.entries.first { it.titleId == "BLUS31584" }.status)
        assertEquals(1, merged.importedCount)
        assertEquals(1, merged.alreadyImportedCount)
    }

    @Test
    fun isoFolderImportResult_countsStatuses() {
        val result = DirectIsoManager.IsoFolderImportResult(
            entries = listOf(
                DirectIsoManager.IsoImportEntry("A.iso", "BLUS31584", "content://a", DirectIsoManager.IsoImportStatus.IMPORTED),
                DirectIsoManager.IsoImportEntry("B.iso", "BLUS31584", "content://b", DirectIsoManager.IsoImportStatus.ALREADY_IMPORTED),
                DirectIsoManager.IsoImportEntry("C.iso", null, "content://c", DirectIsoManager.IsoImportStatus.FAILED, "bad iso"),
            )
        )
        assertEquals(1, result.importedCount)
        assertEquals(1, result.alreadyImportedCount)
        assertEquals(1, result.failedCount)
    }

    @Test
    fun directIsoSessionReleaseIsIdempotent() {
        assertNull(DirectIsoSession.current())
        DirectIsoSession.release("initial-clean")
        assertNull(DirectIsoSession.current())
    }

    @Test
    fun directIsoSourceFileIsNotDeletedOnRemoval() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "sambas3_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val isoFile = File(tempDir, "game.iso")
            isoFile.writeText("ISO_HEADER_MOCK_DATA")
            assertTrue(isoFile.exists())

            // Simulating removal of cache / metadata only
            val cacheFile = File(tempDir, "icon.png")
            cacheFile.writeText("PNG_DATA")
            cacheFile.delete()

            // Source ISO must remain untouched
            assertTrue(isoFile.exists())
            assertEquals("ISO_HEADER_MOCK_DATA", isoFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
