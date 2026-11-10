package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.torrent.TorrentMediaProfile
import org.junit.Assert.*
import org.junit.Test

class PlayerMediaInfoProfileTest {
    @Test fun media3UsesSelectedTrackInsteadOfTitleGuess() {
        val video = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280).setHeight(720)
            .setBitrate(4_500_000)
            .build()
        val audio = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2).build()
        val observed = exoMediaInfo(video, audio).asTorrentMediaProfile()
        assertEquals(1280, observed.width)
        assertEquals(720, observed.height)
        assertEquals(4_500_000L, observed.videoBitrateBitsPerSecond)
        assertEquals(2, observed.audioChannels)
    }

    @Test fun absentTrackDoesNotBecomeFakeZeroOrFake4k() {
        val observed = exoMediaInfo(null, null).asTorrentMediaProfile()
        assertNull(observed.resolution)
        assertNull(observed.videoBitrateBitsPerSecond)
        assertTrue(observed.hdr.isEmpty())
    }

    @Test fun mpvMissingPropertiesRemainUnknownEvenWhenContainerHintIs4k() {
        val observed = mpvMediaInfo { null }.asTorrentMediaProfile()
        assertNull(observed.resolution)
        assertNull(observed.videoBitrateBitsPerSecond)
        assertNull(observed.videoCodec)
    }

    @Test fun invalidPlayerFormatsAreDiscarded() {
        val profile = TorrentMediaProfile(
            width = -1, height = 200_000, videoCodec = "",
            hdr = listOf("DV", "fake", "DV"), audioChannels = 0, videoBitrateBitsPerSecond = -1
        ).sanitized()
        assertNull(profile.width)
        assertNull(profile.height)
        assertNull(profile.videoCodec)
        assertEquals(listOf("DV"), profile.hdr)
        assertNull(profile.audioChannels)
        assertNull(profile.videoBitrateBitsPerSecond)
    }
}
