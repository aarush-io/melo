package `in`.melo.data

import `in`.melo.data.preferences.AppSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MyWaveTest {
    private fun track(id: String, title: String, artist: String, kind: MusicKind = MusicKind.Track) =
        Music(id = id, title = title, subtitle = "$artist • Album", kind = kind)

    private val seed = track("seed", "Midnight City", "M83")

    @Test fun versionsOfOneSongShareASongKey() {
        val original = track("1", "Blinding Lights", "The Weeknd")
        for (version in listOf(
            "Blinding Lights (Chromatics Remix)",
            "Blinding Lights - Live at Wembley",
            "Blinding Lights [Sped Up]",
            "Blinding Lights (feat. Rosalía)",
            "BLINDING LIGHTS – Slowed + Reverb",
        )) assertEquals(version, waveSongKey(original), waveSongKey(track("2", version, "The Weeknd")))
        assertEquals(waveSongKey(track("3", "Кукла колдуна", "Король и Шут")), waveSongKey(track("4", "Кукла колдуна (Ремикс)", "Король и Шут")))
    }

    @Test fun differentSongsKeepDifferentKeys() {
        val base = waveSongKey(track("1", "Intro", "The xx"))
        assertNotEquals(base, waveSongKey(track("2", "Intro (Interlude)", "The xx")))
        assertNotEquals(base, waveSongKey(track("3", "Intro - Part 2", "The xx")))
        assertNotEquals(base, waveSongKey(track("4", "Intro", "M83")))
    }

    @Test fun artistCreditsSplitOnSeparatorsButNotInsideNames() {
        assertEquals(setOf("calvin harris", "dua lipa"), waveArtistKeys(track("1", "One Kiss", "Calvin Harris feat. Dua Lipa")))
        assertEquals(setOf("баста", "скриптонит"), waveArtistKeys(track("2", "Самый лучший день", "Баста, Скриптонит")))
        assertEquals(setOf("alex", "andy"), waveArtistKeys(track("3", "Song", "Alex & Andy")))
        assertEquals(setOf("иван"), waveArtistKeys(track("4", "Песня", "Иван")))
        assertEquals(setOf("skrillex", "diplo"), waveArtistKeys(track("5", "Song", "Skrillex x Diplo")))
    }

    @Test fun bandNamesWithAndStayWhole() {
        assertEquals(setOf("король и шут"), waveArtistKeys(track("1", "Лесник", "Король и Шут")))
        assertEquals(setOf("florence and the machine"), waveArtistKeys(track("2", "Dog Days", "Florence and the Machine")))
    }

    @Test fun eachTrackAndSongJoinsOnce() {
        val wave = WaveSession(seed)
        val accepted = wave.accept(listOf(
            seed,
            track("seed-remix", "Midnight City (Eric Prydz Remix)", "M83"),
            track("a", "Kids", "MGMT"),
            track("a", "Kids", "MGMT"),
            track("album", "Oracular Spectacular", "MGMT", MusicKind.Album),
        ), limit = 10)
        assertEquals(listOf("a"), accepted.map(Music::id))
        assertEquals(2, wave.size)
        assertTrue(wave.contains(seed))
    }

    @Test fun anArtistWaitsForTheGapBeforeComingBack() {
        val wave = WaveSession(seed)
        assertTrue(wave.accept(listOf(track("m2", "Wait", "M83")), limit = 1).isEmpty())
        wave.accept(listOf(track("b", "B", "Band B"), track("c", "C", "Band C"), track("d", "D", "Band D")), limit = 3)
        assertEquals(listOf("m2"), wave.accept(listOf(track("m2", "Wait", "M83")), limit = 1).map(Music::id))
        assertTrue(wave.accept(listOf(track("m3", "Outro", "M83")), limit = 1).isEmpty())
    }

    @Test fun recentlyHeardTracksWaitUnlessNothingElseIsFound() {
        val heard = track("h", "Heard", "Someone")
        val wave = WaveSession(seed, recentlyHeard = listOf(heard, track("h2", "Other Song", "Another")))
        assertTrue(wave.accept(listOf(heard, track("h2-live", "Other Song (Live)", "Another")), limit = 5).isEmpty())
        assertEquals(listOf("h", "h2-live"), wave.accept(listOf(heard, track("h2-live", "Other Song (Live)", "Another")), limit = 5, relaxed = true).map(Music::id))
    }

    @Test fun relaxedRulesStillNeverRepeatATrack() {
        val wave = WaveSession(seed)
        assertTrue(wave.accept(listOf(seed, track("seed-2", "Midnight City - Sped Up", "M83")), limit = 5, relaxed = true).isEmpty())
    }

    @Test fun anArtistSkippedTwiceLeavesTheSession() {
        val wave = WaveSession(seed)
        val first = track("x1", "One", "Skipped Band")
        val second = track("x2", "Two", "Skipped Band")
        wave.accept(listOf(first), limit = 1)
        wave.skipped(first)
        wave.accept(listOf(track("b", "B", "Band B"), track("c", "C", "Band C"), track("d", "D", "Band D")), limit = 3)
        assertEquals(listOf("x2"), wave.accept(listOf(second), limit = 1).map(Music::id))
        wave.skipped(second)
        assertTrue(wave.accept(listOf(track("x3", "Three", "Skipped Band")), limit = 1, relaxed = true).isEmpty())
        assertTrue(wave.accept(listOf(track("x4", "Four", "Other & Skipped Band")), limit = 1, relaxed = true).isEmpty())
    }

    @Test fun skipsOfTracksOutsideTheWaveAreIgnored() {
        val wave = WaveSession(seed)
        val outside = track("o", "Elsewhere", "Band O")
        wave.skipped(outside)
        wave.skipped(outside)
        assertEquals(listOf("o"), wave.accept(listOf(outside), limit = 1).map(Music::id))
    }

    @Test fun nextSeedFollowsLikedThenKeptTracksThenTheQueue() {
        val wave = WaveSession(seed)
        val a = track("a", "A", "Band A")
        val b = track("b", "B", "Band B")
        val c = track("c", "C", "Band C")
        wave.accept(listOf(a, b, c), limit = 3)
        assertEquals("seed", wave.nextSeed().id)
        wave.kept(b)
        wave.kept(c, liked = true)
        assertEquals("c", wave.nextSeed().id)
        assertEquals("b", wave.nextSeed().id)
        // No kept track left: the queue seeds the next batches in order.
        assertEquals("a", wave.nextSeed().id)
        // Every track has seeded once: the rotation starts again from the newest track.
        assertEquals("c", wave.nextSeed().id)
    }

    @Test fun aKeptTrackSeedsOnlyOnceAndASkipWithdrawsIt() {
        val wave = WaveSession(seed)
        val a = track("a", "A", "Band A")
        val b = track("b", "B", "Band B")
        wave.accept(listOf(a, b), limit = 2)
        wave.nextSeed()
        wave.kept(a)
        assertEquals("a", wave.nextSeed().id)
        wave.kept(a)
        wave.kept(b)
        wave.skipped(b)
        // Neither from the kept list nor from the queue: the rotation starts again without it.
        assertEquals("a", wave.nextSeed().id)
        wave.kept(track("never", "N", "Band N"))
        assertNotEquals("never", wave.nextSeed().id)
    }

    @Test fun aSkippedOrMutedFirstSeedDoesNotSeedAgain() {
        val wave = WaveSession(seed)
        wave.accept(listOf(track("a", "A", "Band A"), track("b", "B", "Band B")), limit = 2)
        wave.skipped(seed)
        assertTrue(List(6) { wave.nextSeed().id }.none { it == "seed" })
        val muted = WaveSession(seed)
        muted.accept(listOf(track("a", "A", "Band A"), track("m2", "Other", "M83")), limit = 2, relaxed = true)
        muted.skipped(seed)
        muted.kept(seed, liked = true) // Liked again, but its artist is skipped once more below.
        muted.skipped(track("m2", "Other", "M83"))
        assertTrue(List(6) { muted.nextSeed().id }.none { it == "seed" || it == "m2" })
    }

    @Test fun aSkippedTrackNeverSeedsUntilItIsLiked() {
        val wave = WaveSession(seed)
        val a = track("a", "A", "Band A")
        val b = track("b", "B", "Band B")
        val c = track("c", "C", "Band C")
        wave.accept(listOf(a, b, c), limit = 3)
        wave.skipped(a)
        wave.kept(a) // Played through on a later loop does not undo a skip.
        val seeds = List(8) { wave.nextSeed().id }
        assertFalse(seeds.toString(), "a" in seeds)
        assertTrue(seeds.containsAll(listOf("seed", "b", "c")))
        wave.kept(a, liked = true)
        assertEquals("a", wave.nextSeed().id)
    }

    @Test fun mutedArtistsDoNotSeed() {
        val wave = WaveSession(seed)
        val muted = track("m", "M", "Muted")
        wave.accept(listOf(muted), limit = 1)
        wave.skipped(muted)
        wave.skipped(muted)
        assertEquals("seed", wave.nextSeed().id)
        assertEquals("seed", wave.nextSeed().id)
    }

    @Test fun skipAndKeepFollowTrackLength() {
        assertTrue(isWaveSkip(playedMs = 10_000, durationMs = 200_000))
        assertFalse(isWaveSkip(playedMs = 31_000, durationMs = 200_000))
        assertFalse(isWaveSkip(playedMs = 21_000, durationMs = 40_000))
        assertTrue(isWaveSkip(playedMs = 10_000, durationMs = 0))
        assertFalse(isWaveKeep(playedMs = 59_000, durationMs = 200_000, reachedEnd = false))
        assertTrue(isWaveKeep(playedMs = 60_000, durationMs = 200_000, reachedEnd = false))
        assertTrue(isWaveKeep(playedMs = 25_000, durationMs = 50_000, reachedEnd = false))
        assertTrue(isWaveKeep(playedMs = 0, durationMs = 0, reachedEnd = true))
    }

    @Test fun theRemovedSearchTabOpensHome() {
        assertEquals(AppSettings.TAB_HOME, AppSettings.fromJson(JSONObject("""{"defaultTab":"search"}""")).defaultTab)
        assertEquals(AppSettings.TAB_MY_WAVE, AppSettings.fromJson(JSONObject("""{"defaultTab":"my_wave"}""")).defaultTab)
        assertEquals(AppSettings.TAB_LIBRARY, AppSettings.startTab(AppSettings.TAB_LIBRARY))
        assertEquals(AppSettings.TAB_HOME, AppSettings.startTab(""))
    }
}
