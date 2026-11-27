package `in`.melo.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contracts of the Spotify link intake, checked with doubles only (no network). */
class SpotifyDeepLinkTest {
    private val id = "0000000000000000000001"

    @Test
    fun bareSpotifyUrisAreRecognised() = runBlocking {
        assertEquals(Music("spotify:track:$id", "", "", MusicKind.Track), resolveSpotifyDeepLink("spotify:track:$id"))
        assertEquals(Music("spotify:album:$id", "", "", MusicKind.Album), resolveSpotifyDeepLink("spotify:album:$id"))
        assertEquals(Music("spotify:artist:$id", "", "", MusicKind.Artist), resolveSpotifyDeepLink("spotify:artist:$id"))
        assertEquals(Music("spotify:playlist:$id", "", "", MusicKind.Playlist), resolveSpotifyDeepLink("spotify:playlist:$id"))
    }

    @Test
    fun shareUrlsAreRecognisedWithQueryFragmentAndLocalePrefixes() = runBlocking {
        for (kind in listOf("track", "album", "artist", "playlist")) {
            val expected = when (kind) {
                "track" -> MusicKind.Track
                "album" -> MusicKind.Album
                "artist" -> MusicKind.Artist
                else -> MusicKind.Playlist
            }
            assertEquals(
                Music("spotify:$kind:$id", "", "", expected),
                resolveSpotifyDeepLink("https://open.spotify.com/$kind/$id?si=abc#share"),
            )
        }
        assertEquals(
            Music("spotify:track:$id", "", "", MusicKind.Track),
            resolveSpotifyDeepLink("https://open.spotify.com/intl-fr/track/$id"),
        )
        assertEquals(
            Music("spotify:track:$id", "", "", MusicKind.Track),
            resolveSpotifyDeepLink("https://open.spotify.com/embed/track/$id"),
        )
        assertEquals(
            Music("spotify:playlist:$id", "", "", MusicKind.Playlist),
            resolveSpotifyDeepLink("https://open.spotify.com/user/someone/playlist/$id"),
        )
    }

    @Test
    fun linksInsideSharedTextAreExtracted() = runBlocking {
        assertEquals(
            Music("spotify:track:$id", "", "", MusicKind.Track),
            resolveSpotifyDeepLink("Check this out: https://open.spotify.com/track/$id?si=abc 🎧"),
        )
        assertEquals(
            Music("spotify:album:$id", "", "", MusicKind.Album),
            resolveSpotifyDeepLink("spotify:album:$id"),
        )
    }

    @Test
    fun unsupportedAndMalformedLinksAreRejected() = runBlocking {
        assertNull(resolveSpotifyDeepLink(""))
        assertNull(resolveSpotifyDeepLink("   "))
        assertNull(resolveSpotifyDeepLink("just some text"))
        assertNull(resolveSpotifyDeepLink("http://open.spotify.com/track/$id"))
        assertNull(resolveSpotifyDeepLink("https://www.open.spotify.com/track/$id"))
        assertNull(resolveSpotifyDeepLink("https://example.com/track/$id"))
        assertNull(resolveSpotifyDeepLink("spotify:episode:$id"))
        assertNull(resolveSpotifyDeepLink("spotify:track:short"))
        assertNull(resolveSpotifyDeepLink("spotify:track:!!!!!!!!!!!!!!!!!!!!!!"))
        assertNull(resolveSpotifyDeepLink("https://open.spotify.com/track/"))
    }
}
