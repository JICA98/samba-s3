package com.zenithblue.sambas3

import com.zenithblue.sambas3.ui.achievements.AchievementFilter
import com.zenithblue.sambas3.ui.achievements.AchievementPresentation
import com.zenithblue.sambas3.ui.achievements.AchievementSort
import com.zenithblue.sambas3.ui.achievements.TrophyEntry
import com.zenithblue.sambas3.ui.achievements.TrophyQuery
import com.zenithblue.sambas3.ui.achievements.TrophySnapshot
import com.zenithblue.sambas3.ui.achievements.TrophySnapshotState
import com.zenithblue.sambas3.ui.achievements.TrophySnapshotProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrophySnapshotProviderTest {
    private class FixtureQuery : TrophyQuery {
        var currentSnapshot = trophySnapshot(unlocked = listOf(0), generation = "0:10", user = "user-a")
        var titleSnapshot = currentSnapshot
        override fun current() = "current"
        override fun title(titleId: String) = "title"
    }

    @Test
    fun liveAndTitleUseTheSameFixtureCountsIdsAndGrades() = runBlocking {
        val query = FixtureQuery()
        val provider = TrophySnapshotProvider(query) { value -> if (value == "current") query.currentSnapshot else query.titleSnapshot }
        val live = provider.current(force = true)!!
        val title = provider.title("BLUS31584", force = true)!!
        assertEquals(33, live.total)
        assertEquals(listOf(0), live.unlockedIds)
        assertEquals(live.unlockedIds, title.unlockedIds)
        assertEquals(live.gradeCount("bronze"), title.gradeCount("bronze"))
        assertEquals("user-a", title.rpcS3UserId)
    }

    @Test
    fun cacheIdentityIncludesUserAndGenerationAndInvalidationReloads() = runBlocking {
        val query = FixtureQuery()
        val provider = TrophySnapshotProvider(query) { value -> if (value == "current") query.currentSnapshot else query.titleSnapshot }
        val first = provider.title("BLUS31584")!!
        query.titleSnapshot = trophySnapshot(unlocked = listOf(0, 1), generation = "1:20", user = "user-a")
        val generationChanged = provider.title("BLUS31584")!!
        assertEquals(2, generationChanged.unlocked)
        query.titleSnapshot = trophySnapshot(unlocked = listOf(2), generation = "1:20", user = "user-b")
        val wrongUser = provider.title("BLUS31584")!!
        assertEquals("user-b", wrongUser.rpcS3UserId)
        assertNotSame(first, wrongUser)
        provider.invalidate("BLUS31584")
        query.titleSnapshot = trophySnapshot(unlocked = listOf(3), generation = "2:30", user = "user-b")
        assertEquals(listOf(3), provider.title("BLUS31584")!!.unlockedIds)
    }

    @Test
    fun filtersAndSortsKeepHiddenAndRecentlyUnlockedExplicit() {
        val entries = listOf(
            TrophyEntry(0, "A", "", "bronze", true, false, false, null, 20),
            TrophyEntry(1, "B", "", "gold", false, true, false, null, null),
            TrophyEntry(2, "C", "", "silver", false, false, false, null, 10),
        )
        assertEquals(2, AchievementPresentation.filter(entries, AchievementFilter.ALL, false).size)
        assertEquals(1, AchievementPresentation.filter(entries, AchievementFilter.UNLOCKED, true).size)
        assertEquals(listOf(0, 2, 1), AchievementPresentation.sort(entries, AchievementSort.RECENT).map { it.id })
        assertTrue(AchievementPresentation.filter(entries, AchievementFilter.GOLD, true).single().hidden)
    }

}

private fun trophySnapshot(unlocked: List<Int>, generation: String, user: String): TrophySnapshot = TrophySnapshot(
    state = TrophySnapshotState.READY,
    titleId = "BLUS31584",
    trophySetId = "NPWR10029_00",
    gameName = "Grand Theft Auto",
    trophies = (0 until 33).map { id -> TrophyEntry(
        id = id,
        name = "Trophy $id",
        description = "Description $id",
        grade = when { id == 32 -> "platinum"; id % 3 == 0 -> "gold"; id % 2 == 0 -> "silver"; else -> "bronze" },
        unlocked = id in unlocked,
        hidden = id == 1,
        platinumRelevant = id == 0,
        iconPath = null,
        unlockTimestamp = if (id in unlocked) (id + 1) * 100L else null,
    ) },
    rpcS3UserId = user,
    tropusrPath = "/fixture/TROPUSR.DAT",
    tropusrExists = true,
    tropusrSize = 100L,
    tropusrMtime = 10L,
    generation = generation,
    querySource = "fixture",
    queryDurationMs = 1L,
    status = "ready",
)
