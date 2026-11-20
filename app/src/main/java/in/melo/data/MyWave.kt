package `in`.melo.data

import java.util.Locale

/**
 * One listening session of My Wave: an endless queue of tracks similar to what the listener plays.
 *
 * The session decides which candidates may join the queue and which track the next batch is
 * built around. It holds no I/O: the caller fetches "tracks similar to a seed" from whichever
 * service the seed belongs to and passes them to [accept].
 *
 * Rules, after the curation of Yandex Music's My Wave:
 * - a track, or another version of the same song (remix, live, sped up…), plays once per session;
 * - tracks heard recently outside the wave are left out, unless nothing else is found;
 * - an artist does not come back within [ARTIST_GAP] tracks;
 * - an artist skipped twice is left out for the rest of the session;
 * - the wave follows the listener: tracks played through or liked become the next seeds.
 */
class WaveSession(
    val seed: Music,
    recentlyHeard: Collection<Music> = emptyList(),
) {
    private val served = HashSet<String>()
    private val songs = HashSet<String>()
    private val heardIds = HashSet<String>()
    private val heardSongs = HashSet<String>()
    /** Artists of the last tracks that joined the queue, newest last. */
    private val tape = ArrayDeque<Set<String>>()
    private val skips = HashMap<String, Int>()
    /** Tracks the listener skipped: the wave is never built around them. */
    private val skippedIds = HashSet<String>()
    /** Tracks the listener kept: played through, or liked (those first). */
    private val keptSeeds = ArrayDeque<Music>()
    private val usedSeeds = HashSet<String>()
    /** Every track that joined the queue, oldest first: seeds when the listener kept none. */
    private val joined = ArrayList<Music>()

    init {
        recentlyHeard.forEach { track ->
            heardIds += track.id
            heardSongs += waveSongKey(track)
        }
        remember(seed)
    }

    /** How many tracks joined the queue, the seed included. */
    val size: Int get() = served.size

    fun contains(track: Music): Boolean = track.id in served

    /**
     * Picks up to [limit] tracks of [candidates] for the queue, in their order, and records them.
     * [relaxed] lets in tracks heard recently and ignores the artist gap: used when the strict
     * rules leave nothing, so the wave never stops.
     */
    fun accept(candidates: List<Music>, limit: Int, relaxed: Boolean = false): List<Music> {
        val accepted = ArrayList<Music>()
        for (track in candidates) {
            if (accepted.size >= limit) break
            if (!admits(track, relaxed)) continue
            remember(track)
            accepted += track
        }
        return accepted
    }

    private fun admits(track: Music, relaxed: Boolean): Boolean {
        if (track.kind != MusicKind.Track || track.id in served) return false
        val song = waveSongKey(track)
        if (song in songs) return false
        val artists = waveArtistKeys(track)
        if (artists.any { (skips[it] ?: 0) >= SKIPS_TO_MUTE }) return false
        if (relaxed) return true
        if (track.id in heardIds || song in heardSongs) return false
        return tape.takeLast(ARTIST_GAP).none { recent -> recent.any(artists::contains) }
    }

    private fun remember(track: Music) {
        served += track.id
        songs += waveSongKey(track)
        tape.addLast(waveArtistKeys(track))
        while (tape.size > TAPE) tape.removeFirst()
        joined += track
    }

    /**
     * The listener skipped [track] early: it never seeds a batch, and a second skip of one of its
     * artists leaves that artist out for the rest of the session.
     */
    fun skipped(track: Music) {
        if (track.id !in served) return
        skippedIds += track.id
        waveArtistKeys(track).forEach { skips[it] = (skips[it] ?: 0) + 1 }
        keptSeeds.removeAll { it.id == track.id }
    }

    /** The listener played [track] through, or liked it ([liked] seeds come first). */
    fun kept(track: Music, liked: Boolean = false) {
        if (track.id !in served) return
        // Liked after a skip: the listener changed their mind.
        if (liked) skippedIds -= track.id
        if (track.id in skippedIds || track.id in usedSeeds || keptSeeds.any { it.id == track.id }) return
        if (liked) keptSeeds.addFirst(track) else keptSeeds.addLast(track)
        while (keptSeeds.size > KEPT_SEEDS) keptSeeds.removeLast()
    }

    /**
     * The track the next batch is built around: the newest track the listener kept, then the
     * first seed, then the tracks of the queue in turn. Each kept track seeds one batch.
     */
    fun nextSeed(): Music {
        while (keptSeeds.isNotEmpty()) {
            val kept = keptSeeds.removeFirst()
            // Its artist may have been left out since the listener kept it.
            if (canSeed(kept)) { usedSeeds += kept.id; return kept }
        }
        if (seed.id !in usedSeeds && canSeed(seed)) { usedSeeds += seed.id; return seed }
        val unused = joined.firstOrNull { it.id !in usedSeeds && canSeed(it) }
        if (unused != null) { usedSeeds += unused.id; return unused }
        // Every track has seeded a batch: start the rotation again from the most recent ones.
        usedSeeds.clear()
        return joined.lastOrNull(::canSeed) ?: seed
    }

    private fun canSeed(track: Music) = track.id !in skippedIds && !isMuted(track)

    private fun isMuted(track: Music) = waveArtistKeys(track).any { (skips[it] ?: 0) >= SKIPS_TO_MUTE }

    companion object {
        const val ARTIST_GAP = 3
        const val SKIPS_TO_MUTE = 2
        private const val TAPE = 12
        private const val KEPT_SEEDS = 8
    }
}

/** A skip: the listener moved on before [SKIP_BEFORE_MS] or half of the track, whichever is shorter. */
fun isWaveSkip(playedMs: Int, durationMs: Int): Boolean {
    val limit = if (durationMs > 0) minOf(SKIP_BEFORE_MS, durationMs / 2) else SKIP_BEFORE_MS
    return playedMs < limit
}

/** Played far enough to count as a track the listener wants more of. */
fun isWaveKeep(playedMs: Int, durationMs: Int, reachedEnd: Boolean): Boolean =
    reachedEnd || playedMs >= if (durationMs > 0) minOf(KEEP_AFTER_MS, durationMs / 2) else KEEP_AFTER_MS

private const val SKIP_BEFORE_MS = 30_000
private const val KEEP_AFTER_MS = 60_000

/**
 * The song a track is a version of: its first artist and its title without the version notes
 * (remix, live, sped up, featured artists…), so different versions count as one song.
 */
fun waveSongKey(track: Music): String {
    val artist = waveArtistKeys(track).firstOrNull().orEmpty()
    return "$artist|${baseTitle(track.title)}"
}

/** The artists credited on a track, normalized for comparison. */
fun waveArtistKeys(track: Music): Set<String> {
    // Lowercased first: IGNORE_CASE alone does not fold Cyrillic on the JDK.
    val credit = track.subtitle.substringBefore(" • ").substringBefore(" · ").lowercase(Locale.ROOT)
    return credit.split(ARTIST_SEPARATORS).map(::normalize).filter(String::isNotEmpty).toSet()
}

// Word edges written out: `\b` treats Cyrillic letters differently on Android (ICU) and on the JDK.
private const val WORD_START = """(?<![\p{L}\p{N}])"""
private const val WORD_END = """(?![\p{L}\p{N}])"""

// Credits are split on collaboration marks only: "and" and "и" belong to band names
// ("Florence and the Machine", "Король и Шут").
private val ARTIST_SEPARATORS = Regex(
    """\s*(?:,|&|\+|/|${WORD_START}(?:feat\.?|ft\.?|x|vs\.?)$WORD_END)\s*""",
    RegexOption.IGNORE_CASE,
)
private val BRACKETED = Regex("""[(\[{]([^)\]}]*)[)\]}]""")
private val DASH_TAIL = Regex("""\s[-–—]\s(.*)$""")
private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

private val VERSION_WORDS = Regex(
    """$WORD_START(remix|rmx|edit|mix|version|ver|live|acoustic|slowed|reverb|sped|speed|nightcore|instrumental|karaoke|cover|remaster(ed)?|mono|stereo|demo|extended|radio|feat|ft|prod|bootleg|flip|vip|ремикс|кавер|лайв)$WORD_END""",
    RegexOption.IGNORE_CASE,
)

private fun baseTitle(title: String): String {
    var base = title.lowercase(Locale.ROOT)
    // Bracketed notes that name a version or guests: "(Robert Forss Remix)", "[Live]", "(feat. X)".
    base = BRACKETED.replace(base) { match ->
        if (VERSION_WORDS.containsMatchIn(match.groupValues[1])) " " else match.value
    }
    // Dash tails that name a version: "Song - Live at Wembley", "Song – Sped Up".
    base = DASH_TAIL.replace(base) { match ->
        if (VERSION_WORDS.containsMatchIn(match.groupValues[1])) "" else match.value
    }
    return normalize(base)
}

private fun normalize(value: String): String =
    value.lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim()
