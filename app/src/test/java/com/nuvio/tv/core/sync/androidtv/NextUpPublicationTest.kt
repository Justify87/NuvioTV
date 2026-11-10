package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.data.local.CachedNextUpItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpPublicationTest {
    private fun episode(
        aired: Boolean = true,
        contentId: String = "series-1",
        season: Int = 2,
        number: Int = 3,
    ) = CachedNextUpItem(
        contentId = contentId,
        contentType = "series",
        name = "Example series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "video-3",
        season = season,
        episode = number,
        episodeTitle = "Episode three",
        thumbnail = null,
        hasAired = aired,
        lastWatched = 123L,
        sortTimestamp = 124L,
    )

    @Test fun airedNextEpisodeIsEligibleEvenWithoutPlaybackProgress() {
        assertTrue(isVerifiedAiredNextUp(episode()))
    }

    @Test fun unairedEpisodeIsNeverPublished() {
        assertFalse(isVerifiedAiredNextUp(episode(aired = false)))
    }

    @Test fun missingContentIdentityIsNotPublished() {
        assertFalse(isVerifiedAiredNextUp(episode(contentId = "")))
    }

    @Test fun malformedSeasonOrEpisodeIsNotPublished() {
        assertFalse(isVerifiedAiredNextUp(episode(season = -1)))
        assertFalse(isVerifiedAiredNextUp(episode(number = 0)))
    }
}
