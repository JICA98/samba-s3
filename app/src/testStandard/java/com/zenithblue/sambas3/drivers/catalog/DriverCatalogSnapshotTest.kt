package com.zenithblue.sambas3.drivers.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DriverCatalogSnapshotTest {

    @Test
    fun packages_is_cached_same_instance() {
        val pkg1 = RemoteDriverPackage(
            id = "pkg1",
            source = DriverSourceId.ARIHANY,
            displayName = "Driver One",
            version = "1.0",
            downloadUrl = "https://example.com/a.zip"
        )
        val pkg2 = RemoteDriverPackage(
            id = "pkg2",
            source = DriverSourceId.KIMCHI,
            displayName = "Driver Two",
            version = "2.0",
            downloadUrl = "https://example.com/b.zip"
        )
        val snap = DriverCatalogSnapshot(
            sources = listOf(
                DriverSourceSnapshot(DriverSourceId.ARIHANY, listOf(pkg1)),
                DriverSourceSnapshot(DriverSourceId.KIMCHI, listOf(pkg2))
            )
        )
        // Must be same list instance, not recomputed flatMap each access
        assertSame(snap.packages, snap.packages)
        assertEquals(2, snap.totalCount)
        assertEquals(2, snap.packages.size)
    }

    @Test
    fun totalCount_matches_packages_size() {
        val pkg = RemoteDriverPackage(
            id = "pkg1",
            source = DriverSourceId.BANNERHUB,
            displayName = "B1",
            version = null,
            downloadUrl = "https://example.com/c.zip"
        )
        val snap = DriverCatalogSnapshot(
            sources = listOf(DriverSourceSnapshot(DriverSourceId.BANNERHUB, listOf(pkg, pkg)))
        )
        assertSame(snap.packages, snap.packages)
        assertEquals(snap.packages.size, snap.totalCount)
    }
}
