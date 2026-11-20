package `in`.melo.data.soundcloud

import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.MusicSource
import `in`.melo.data.PlaylistCsv
import `in`.melo.data.musicSourceOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCloudParsingTest {
    private fun user(id: Long, name: String, permalink: String = name.lowercase()) = JSONObject()
        .put("kind", "user").put("id", id).put("username", name).put("permalink", permalink)
        .put("avatar_url", "https://i1.sndcdn.com/avatars-000-large.jpg")
        .put("permalink_url", "https://soundcloud.com/$permalink")

    private fun track(id: Long, title: String, artist: JSONObject = user(7, "Forss")) = JSONObject()
        .put("kind", "track").put("id", id).put("title", title).put("user", artist)
        .put("duration", 30_000).put("full_duration", 241_000).put("genre", "Electronic")
        .put("artwork_url", "https://i1.sndcdn.com/artworks-000-large.jpg")
        .put("permalink_url", "https://soundcloud.com/${artist.getString("permalink")}/${title.lowercase().replace(' ', '-')}")

    private fun transcoding(protocol: String, mime: String, preset: String, snipped: Boolean = false) = JSONObject()
        .put("url", "https://api-v2.soundcloud.com/media/soundcloud:tracks:1/$preset/$protocol")
        .put("preset", preset).put("snipped", snipped)
        .put("format", JSONObject().put("protocol", protocol).put("mime_type", mime))

    // Ids ----------------------------------------------------------------------------------------

    @Test fun idsCarryTheirKindAndNumber() {
        assertEquals(42L, soundCloudTrackIdOf("sc:track:42"))
        assertNull(soundCloudTrackIdOf("sc:playlist:42"))
        assertNull(soundCloudTrackIdOf("spotify:track:42"))
        assertEquals(9L, soundCloudNumericId("sc:likes:9"))
        assertTrue(isSoundCloudId("sc:user:1"))
        assertFalse(isSoundCloudId("spotify:artist:1"))
    }

    @Test fun soundCloudIdsBelongToTheSoundCloudSource() {
        assertEquals(MusicSource.SoundCloud, musicSourceOf("sc:track:1"))
        assertEquals(MusicSource.SoundCloud, musicSourceOf("sc:playlist:1"))
        assertEquals(MusicSource.SoundCloud, musicSourceOf("sc:user:1"))
        assertEquals(MusicSource.Spotify, musicSourceOf("spotify:track:1"))
    }

    // Client id ----------------------------------------------------------------------------------

    @Test fun clientIdIsReadFromTheWebPlayerScripts() {
        val html = """<script crossorigin src="https://a-v2.sndcdn.com/assets/0-abc.js"></script>
            <script src="https://example.com/other.js"></script>
            <script crossorigin src="https://a-v2.sndcdn.com/assets/49-def.js"></script>"""
        assertEquals(listOf("https://a-v2.sndcdn.com/assets/0-abc.js", "https://a-v2.sndcdn.com/assets/49-def.js"), soundCloudScriptUrls(html))
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYz012345", extractSoundCloudClientId("""e.exports={client_id:"AbCdEfGhIjKlMnOpQrStUvWxYz012345",env:"production"}"""))
        assertEquals("AbCdEfGhIjKlMnOpQrStUvWxYz012345", extractSoundCloudClientId("""?client_id=AbCdEfGhIjKlMnOpQrStUvWxYz012345&app_version=1"""))
        assertNull(extractSoundCloudClientId("""client_id:"short""""))
        // The mobile site names it in the page itself.
        assertEquals("KKzJxmw11tYpCs6T24P4uUYhqmjalG6M",
            extractSoundCloudClientId(""""port":"80","stagingId":"","clientId":"KKzJxmw11tYpCs6T24P4uUYhqmjalG6M","appId":"65097""""))
    }

    // Entities -----------------------------------------------------------------------------------

    @Test fun trackReadsItsArtistDurationAndLargeArtwork() {
        val music = parseSoundCloudTrack(track(101, "Flickers"))!!
        assertEquals("sc:track:101", music.id)
        assertEquals("Flickers", music.title)
        assertEquals("Forss", music.subtitle)
        assertEquals(MusicKind.Track, music.kind)
        assertEquals(241_000, music.durationMs)
        assertEquals("https://i1.sndcdn.com/artworks-000-t500x500.jpg", music.imageUrl)
        assertEquals("forss", music.ownerUsername)
    }

    @Test fun trackPrefersThePublisherArtist() {
        val json = track(5, "Song").put("publisher_metadata", JSONObject().put("artist", "Real Artist").put("album_title", "LP"))
        val music = parseSoundCloudTrack(json)!!
        assertEquals("Real Artist", music.subtitle)
        assertEquals("LP", music.album)
    }

    @Test fun jsonNullsAreNotReadAsText() {
        val json = track(6, "Song").put("artwork_url", JSONObject.NULL).put("genre", JSONObject.NULL)
        val music = parseSoundCloudTrack(json)!!
        assertEquals("", music.genre)
        // Without artwork the uploader's avatar is used.
        assertEquals("https://i1.sndcdn.com/avatars-000-t500x500.jpg", music.imageUrl)
    }

    @Test fun stubsAndOtherKindsAreSkipped() {
        assertNull(parseSoundCloudTrack(JSONObject().put("id", 3).put("kind", "track")))
        assertNull(parseSoundCloudTrack(user(1, "Someone")))
        assertNull(parseSoundCloudItem(JSONObject().put("kind", "system-playlist").put("id", 1).put("title", "Mix")))
    }

    @Test fun albumsOpenAsAlbumsWithTheirYear() {
        val album = JSONObject().put("kind", "playlist").put("id", 77).put("title", "Debut").put("user", user(7, "Forss"))
            .put("set_type", "album").put("release_date", "2019-04-01T00:00:00Z")
            .put("tracks", JSONArray().put(JSONObject().put("id", 1).put("kind", "track")))
        val music = parseSoundCloudPlaylist(album)!!
        assertEquals("sc:playlist:77", music.id)
        assertEquals(MusicKind.Album, music.kind)
        assertEquals("2019", music.note)
        val playlist = parseSoundCloudPlaylist(album.put("set_type", "").put("is_album", false))!!
        assertEquals(MusicKind.Playlist, playlist.kind)
        assertEquals("", playlist.note)
    }

    @Test fun usersAreArtists() {
        val music = parseSoundCloudUser(user(9, "Forss").put("description", "Producer"))!!
        assertEquals("sc:user:9", music.id)
        assertEquals(MusicKind.Artist, music.kind)
        assertEquals("forss", music.ownerUsername)
    }

    @Test fun collectionsAndLikesKeepOrderWithoutDuplicates() {
        val page = JSONObject().put("collection", JSONArray().put(track(1, "A")).put(user(2, "B")).put(track(1, "A again")).put(JSONObject().put("kind", "unknown")))
            .put("next_href", "https://api-v2.soundcloud.com/search?offset=20")
        assertEquals(listOf("sc:track:1", "sc:user:2"), parseSoundCloudCollection(page).map(Music::id))
        assertEquals("https://api-v2.soundcloud.com/search?offset=20", soundCloudNext(page))
        assertNull(soundCloudNext(JSONObject().put("next_href", JSONObject.NULL)))

        val playlist = JSONObject().put("kind", "playlist").put("id", 3).put("title", "Set").put("user", user(7, "Forss"))
        val likes = JSONObject().put("collection", JSONArray()
            .put(JSONObject().put("track", track(1, "A")))
            .put(JSONObject().put("playlist", playlist))
            .put(JSONObject().put("created_at", "2020")))
        assertEquals(listOf("sc:track:1", "sc:playlist:3"), parseSoundCloudLikes(likes).map(Music::id))
    }

    @Test fun playlistsListAllTrackIdsAndTheFullTracksTheyCarry() {
        val playlist = JSONObject().put("tracks", JSONArray().put(track(1, "A")).put(JSONObject().put("id", 2).put("kind", "track")).put(track(3, "C")))
        assertEquals(listOf(1L, 2L, 3L), playlistTrackIds(playlist))
        assertEquals(setOf(1L, 3L), playlistFullTracks(playlist).keys)
    }

    @Test fun permalinksAreRememberedForTracksAndTheirUploaders() {
        val found = HashMap<String, String>()
        soundCloudPermalinks(track(1, "Flickers"), found)
        assertEquals("https://soundcloud.com/forss/flickers", found["sc:track:1"])
        assertEquals("https://soundcloud.com/forss", found["sc:user:7"])
    }

    // Streams ------------------------------------------------------------------------------------

    @Test fun fragmentedAacIsPreferredAndPreviewsAreDropped() {
        val json = JSONObject().put("policy", "ALLOW").put("media", JSONObject().put("transcodings", JSONArray()
            .put(transcoding("progressive", "audio/mpeg", "mp3_1_0"))
            .put(transcoding("hls", "audio/mp4; codecs=\"mp4a.40.2\"", "aac_96k"))
            .put(transcoding("hls", "audio/mp4; codecs=\"mp4a.40.2\"", "aac_160k"))
            .put(transcoding("hls", "audio/mp4; codecs=\"mp4a.40.2\"", "aac_160k", snipped = true))
            .put(transcoding("ctr-encrypted-hls", "audio/mp4", "aac_160k"))
            .put(transcoding("hls", "audio/ogg; codecs=\"opus\"", "opus_0_0"))))
        val playable = playableTranscodings(json)
        assertEquals(listOf("aac_160k", "aac_96k", "mp3_1_0"), playable.map { it.preset })
        assertEquals(160_000, playable.first().bitrate)
        assertTrue(playable.first().isFragmentedAac)
        assertTrue(playable.last().isProgressiveMp3)
    }

    @Test fun previewAndBlockedTracksHaveNoStream() {
        val media = JSONObject().put("transcodings", JSONArray().put(transcoding("hls", "audio/mp4", "aac_160k")))
        assertTrue(playableTranscodings(JSONObject().put("policy", "SNIP").put("media", media)).isEmpty())
        assertTrue(playableTranscodings(JSONObject().put("policy", "BLOCK").put("media", media)).isEmpty())
        assertTrue(playableTranscodings(JSONObject().put("policy", "ALLOW")).isEmpty())
    }

    @Test fun hlsPlaylistGivesTheInitSectionAndTimedSegments() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:10
            #EXT-X-MAP:URI="https://cf-hls-media.sndcdn.com/init/abc.mp4?Policy=x"
            #EXTINF:9.984,
            https://cf-hls-media.sndcdn.com/media/0/9984/abc.m4s?expires=1700000000
            #EXTINF:4.5,
            media/1/abc.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = parseSoundCloudHls(text, "https://playback.media-streaming.soundcloud.cloud/x/aac_160k/playlist.m3u8")!!
        assertEquals("https://cf-hls-media.sndcdn.com/init/abc.mp4?Policy=x", media.initUrl)
        assertEquals(listOf(9_984_000L, 4_500_000L), media.segments.map { it.durationUs })
        assertEquals("https://playback.media-streaming.soundcloud.cloud/x/aac_160k/media/1/abc.m4s", media.segments[1].url)
    }

    @Test fun encryptedOrSegmentOnlyPlaylistsAreRefused() {
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:10,\na.m4s\n"
        assertNull(parseSoundCloudHls(encrypted, "https://h/p.m3u8"))
        val transportStream = "#EXTM3U\n#EXTINF:10,\na.ts\n"
        assertNull(parseSoundCloudHls(transportStream, "https://h/p.m3u8"))
        // One file cut in byte ranges cannot be laid out segment by segment.
        assertNull(parseSoundCloudHls("#EXTM3U\n#EXT-X-MAP:URI=\"a.mp4\",BYTERANGE=\"800@0\"\n#EXTINF:10,\n#EXT-X-BYTERANGE:1000@800\na.mp4\n", "https://h/p.m3u8"))
        assertNull(parseSoundCloudHls("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:10,\n#EXT-X-BYTERANGE:1000@0\na.m4s\n", "https://h/p.m3u8"))
        val unencrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:10,\na.m4s\n"
        assertEquals(1, parseSoundCloudHls(unencrypted, "https://h/p.m3u8")!!.segments.size)
    }

    @Test fun signedLinksTellWhenTheyExpire() {
        assertEquals(1_700_000_000_000L, signedUrlExpiryMs("https://cf-media.sndcdn.com/a.mp3?Policy=p&expires=1700000000&Signature=s"))
        assertEquals(1_700_000_000_000L, signedUrlExpiryMs("https://h/a?Expires=1700000000000"))
        assertNull(signedUrlExpiryMs("https://h/a.mp3"))
    }

    // Links and profile --------------------------------------------------------------------------

    @Test fun pastedLinksAreNormalized() {
        assertEquals("https://soundcloud.com/forss/flickers", soundCloudLink("Listen: https://m.soundcloud.com/forss/flickers?si=abc#t=10"))
        assertEquals("https://soundcloud.com/forss/sets/ecclesia", soundCloudLink("https://www.soundcloud.com/forss/sets/ecclesia/"))
        assertEquals("https://on.soundcloud.com/AbC12", soundCloudLink("https://on.soundcloud.com/AbC12"))
        assertNull(soundCloudLink("https://soundcloud.com/"))
        assertNull(soundCloudLink("https://open.spotify.com/track/1"))
    }

    @Test fun aProfileCanBeGivenByNameOrLink() {
        assertEquals("https://soundcloud.com/forss", soundCloudProfileLink("forss"))
        assertEquals("https://soundcloud.com/forss", soundCloudProfileLink("@forss"))
        assertEquals("https://soundcloud.com/forss", soundCloudProfileLink("https://soundcloud.com/forss"))
        assertNull(soundCloudProfileLink("two words"))
    }

    @Test fun linkedProfileSurvivesStorage() {
        val profile = SoundCloudProfile(7, "Forss", "forss", "https://i1.sndcdn.com/avatars-t500x500.jpg", 1200)
        assertEquals(profile, decodeSoundCloudProfile(encodeSoundCloudProfile(profile)))
        assertEquals("sc:likes:7", profile.likesId)
        val noAvatar = profile.copy(avatarUrl = null)
        assertEquals(noAvatar, decodeSoundCloudProfile(encodeSoundCloudProfile(noAvatar)))
        assertNull(decodeSoundCloudProfile(null))
        assertNull(decodeSoundCloudProfile("not json"))
        assertNull(decodeSoundCloudProfile("""{"id":0}"""))
    }

    // CSV ----------------------------------------------------------------------------------------

    @Test fun soundCloudTracksRoundTripThroughCsv() {
        val track = Music("sc:track:101", "Flickers", "Forss", MusicKind.Track, durationMs = 241_000,
            imageUrl = "https://i1.sndcdn.com/artworks-000-t500x500.jpg")
        val csv = PlaylistCsv.export("Mix", listOf(track))
        assertTrue(csv.contains(",soundcloud,sc:track:101,"))
        // A track never seen with its page links to the official embed player.
        assertTrue(csv.contains("https://w.soundcloud.com/player/?url=https%3A//api.soundcloud.com/tracks/101"))
        val imported = PlaylistCsv.import(csv, "fallback")
        assertEquals("Mix", imported.name)
        assertEquals(listOf("sc:track:101"), imported.tracks.map(Music::id))
        assertEquals(241_000, imported.tracks.single().durationMs)
    }
}
