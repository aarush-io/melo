package `in`.melo.utils

import `in`.melo.presentation.screens.formatSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FormatsTest {
    @Test fun russianTrackCountsUseTheirThreeForms() {
        val expected = mapOf(
            0 to "0 треков", 1 to "1 трек", 2 to "2 трека", 4 to "4 трека", 5 to "5 треков", 11 to "11 треков",
            12 to "12 треков", 14 to "14 треков", 21 to "21 трек", 22 to "22 трека", 25 to "25 треков",
            101 to "101 трек", 111 to "111 треков", 112 to "112 треков", 1_004 to "1004 трека",
        )
        expected.forEach { (count, text) -> assertEquals(text, formatSongCount(count, "ru")) }
    }

    @Test fun englishAndFrenchTrackCountsAreUnchanged() {
        assertEquals("1 track", formatSongCount(1, "en"))
        assertEquals("3 tracks", formatSongCount(3, "en"))
        assertEquals("1 titre", formatSongCount(1, "fr"))
        assertEquals("3 titres", formatSongCount(3, "fr"))
        assertEquals("3 tracks", formatSongCount(3, "de"))
    }

    @Test fun storedCountsInAnyLanguageAreReadBack() {
        listOf("1 track", "7 tracks", "1 titre", "7 titres", "1 трек", "3 трека", "7 треков").forEach { stored ->
            assertEquals(stored.substringBefore(' ').toInt(), parseSongCount(stored))
        }
        assertNull(parseSongCount("Forss"))
        assertNull(parseSongCount("3 трековый"))
    }

    @Test fun radioTitlesFollowTheLanguage() {
        assertEquals("Forss Radio", formatRadioTitle("Forss", "en"))
        assertEquals("Radio de Forss", formatRadioTitle("Forss", "fr"))
        assertEquals("Радио: Forss", formatRadioTitle("Forss", "ru"))
    }

    @Test fun sizesUseTheUnitsOfTheLanguage() {
        val gigabytes = (1L shl 30) * 3 / 2
        assertEquals("1.5 GB", formatSize(gigabytes, Locale.ENGLISH))
        assertEquals("1,5 Go", formatSize(gigabytes, Locale.FRENCH))
        assertEquals("1,5 ГБ", formatSize(gigabytes, Locale.forLanguageTag("ru")))
        assertEquals("20 МБ", formatSize(20L shl 20, Locale.forLanguageTag("ru")))
        assertEquals("512 KB", formatSize(512L * 1024, Locale.ENGLISH))
    }
}
