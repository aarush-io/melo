package `in`.melo.utils


fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "00:00"

    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

fun formatSongCount(count: Int): String = formatSongCount(count, java.util.Locale.getDefault().language)

/** A track count in [language]: French and Russian have their own words, every other language reads English. */
internal fun formatSongCount(count: Int, language: String): String = when (language) {
    "fr" -> if (count > 1) "$count titres" else "$count titre"
    "ru" -> "$count " + russianPlural(count, "трек", "трека", "треков")
    else -> if (count == 1) "1 track" else "$count tracks"
}

/** The Russian word form for [count]: 1, 21 трек; 2–4, 22 трека; 0, 5–20, 25 треков. */
internal fun russianPlural(count: Int, one: String, few: String, many: String): String {
    val last = Math.floorMod(count, 10)
    val lastTwo = Math.floorMod(count, 100)
    return when {
        last == 1 && lastTwo != 11 -> one
        last in 2..4 && lastTwo !in 12..14 -> few
        else -> many
    }
}

fun formatRadioTitle(name: String): String = formatRadioTitle(name, java.util.Locale.getDefault().language)

internal fun formatRadioTitle(name: String, language: String): String = when (language) {
    "fr" -> "Radio de $name"
    "ru" -> "Радио: $name"
    else -> "$name Radio"
}

private val storedTrackCount = Regex("""^(\d+) (titres?|tracks?|трек|трека|треков)$""")

/** Parses a track count stored as the localized display subtitle. */
fun parseSongCount(subtitle: String): Int? =
    storedTrackCount.matchEntire(subtitle)?.groupValues?.get(1)?.toIntOrNull()

/** Track counts are stored as display text; show them in the current language. */
fun localizeTrackCount(subtitle: String): String =
    parseSongCount(subtitle)?.let(::formatSongCount) ?: subtitle
