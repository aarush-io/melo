package `in`.melo.data.soundcloud

import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/*
 * Pure parsing of SoundCloud's public api-v2 responses and web pages. Nothing here touches the
 * network or Android, so every function can be checked on the JVM with hand-built doubles.
 */

const val SC_TRACK_PREFIX = "sc:track:"
const val SC_PLAYLIST_PREFIX = "sc:playlist:"
const val SC_USER_PREFIX = "sc:user:"

/** The public likes of the linked SoundCloud profile, shown as one playlist: `sc:likes:<userId>`. */
const val SC_LIKES_PREFIX = "sc:likes:"

val Music.isSoundCloudTrack: Boolean get() = id.startsWith(SC_TRACK_PREFIX)
val Music.soundCloudTrackId: Long? get() = soundCloudTrackIdOf(id)

fun soundCloudTrackIdOf(id: String): Long? =
    id.takeIf { it.startsWith(SC_TRACK_PREFIX) }?.removePrefix(SC_TRACK_PREFIX)?.toLongOrNull()

/** True for every SoundCloud track, playlist, album, user or likes list. */
fun isSoundCloudId(id: String): Boolean = id.startsWith("sc:")

/** The numeric part of a SoundCloud id of any kind. */
internal fun soundCloudNumericId(id: String): Long? = id.takeIf(::isSoundCloudId)?.substringAfterLast(':')?.toLongOrNull()

/** A transcoding of a track, as `media.transcodings` lists it. */
internal data class SoundCloudTranscoding(
    val url: String,
    val protocol: String,
    val mimeType: String,
    val preset: String,
    val snipped: Boolean,
) {
    /** Bits per second the preset names, such as 160 000 for `aac_160k`; 0 when it names none. */
    val bitrate: Int get() = PRESET_RATE.find(preset)?.groupValues?.get(1)?.toIntOrNull()?.times(1000) ?: 0
    val isFragmentedAac: Boolean get() = protocol == "hls" && mimeType.startsWith("audio/mp4")
    val isProgressiveMp3: Boolean get() = protocol == "progressive" && mimeType.startsWith("audio/mpeg")

    private companion object { val PRESET_RATE = Regex("""(\d+)k""") }
}

/** One media segment of an HLS playlist. */
internal data class HlsSegment(val url: String, val durationUs: Long)

/** An unencrypted fragmented-MP4 media playlist: its initialization section, then its segments. */
internal data class HlsMedia(val initUrl: String, val segments: List<HlsSegment>)

/** The script bundles of soundcloud.com, in page order. The web client id is defined in one of them. */
internal fun soundCloudScriptUrls(html: String): List<String> =
    SCRIPT_SRC.findAll(html).map { it.groupValues[1] }.filter { it.contains("sndcdn.com") }.distinct().toList()

/** The public web client id a script bundle (`client_id:"…"`) or the mobile page (`"clientId":"…"`) carries, if any. */
internal fun extractSoundCloudClientId(script: String): String? =
    CLIENT_ID.find(script)?.groupValues?.get(1)

/** Transcodings Melo can read, best first: fragmented AAC over HLS, then progressive MP3. Previews never count. */
internal fun playableTranscodings(track: JSONObject): List<SoundCloudTranscoding> {
    val policy = track.text("policy")
    if (policy == "BLOCK" || policy == "SNIP") return emptyList()
    val list = track.optJSONObject("media")?.optJSONArray("transcodings") ?: return emptyList()
    return (0 until list.length()).mapNotNull { index ->
        val item = list.optJSONObject(index) ?: return@mapNotNull null
        val format = item.optJSONObject("format") ?: return@mapNotNull null
        SoundCloudTranscoding(
            url = item.text("url").takeIf(String::isNotBlank) ?: return@mapNotNull null,
            protocol = format.text("protocol"),
            mimeType = format.text("mime_type"),
            preset = item.text("preset"),
            snipped = item.optBoolean("snipped", false),
        )
    }.filter { !it.snipped && (it.isFragmentedAac || it.isProgressiveMp3) }
        .sortedWith(compareByDescending<SoundCloudTranscoding> { it.isFragmentedAac }.thenByDescending { it.bitrate })
}

/**
 * Parses a media playlist. Null for encrypted playlists, byte-range playlists (one file cut in
 * ranges) and any layout other than whole fMP4 segment files.
 */
internal fun parseSoundCloudHls(text: String, playlistUrl: String): HlsMedia? {
    var init: String? = null
    var duration: Long? = null
    val segments = ArrayList<HlsSegment>()
    text.lineSequence().map(String::trim).forEach { line ->
        when {
            line.startsWith("#EXT-X-KEY:") -> if (!line.contains("METHOD=NONE")) return null
            line.startsWith("#EXT-X-BYTERANGE") -> return null
            line.startsWith("#EXT-X-MAP:") -> {
                if (line.contains("BYTERANGE=")) return null
                init = HLS_URI.find(line)?.groupValues?.get(1)?.let { resolveUrl(playlistUrl, it) }
            }
            line.startsWith("#EXTINF:") -> duration = line.removePrefix("#EXTINF:").substringBefore(',').trim()
                .toBigDecimalOrNull()?.movePointRight(6)?.toLong()
            line.isNotEmpty() && !line.startsWith("#") -> {
                segments += HlsSegment(resolveUrl(playlistUrl, line), duration ?: return null)
                duration = null
            }
        }
    }
    val first = init ?: return null
    return if (segments.isEmpty()) null else HlsMedia(first, segments)
}

/** When a signed address stops serving, in milliseconds since the epoch; null when it does not say. */
internal fun signedUrlExpiryMs(url: String): Long? =
    EXPIRES.find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }

/** The 500 px variant of an artwork or avatar address; SoundCloud names its sizes in the file name. */
internal fun largeSoundCloudImage(url: String?): String? = url?.takeIf(String::isNotBlank)
    ?.replace(Regex("""-(large|t\d+x\d+|crop|small|badge|tiny|mini)\.(jpg|png)"""), "-t500x500.$2")

/** A track of api-v2, or null for an id-only stub or another kind. */
internal fun parseSoundCloudTrack(json: JSONObject): Music? {
    if (json.text("kind") != "track") return null
    val id = json.optLong("id").takeIf { it > 0 } ?: return null
    val title = json.text("title").trim().takeIf(String::isNotEmpty) ?: return null
    val user = json.optJSONObject("user")
    val publisher = json.optJSONObject("publisher_metadata")
    val artist = publisher?.text("artist")?.trim()?.takeIf(String::isNotEmpty)
        ?: user?.text("username")?.trim().orEmpty()
    val duration = json.optInt("full_duration").takeIf { it > 0 } ?: json.optInt("duration")
    val image = json.text("artwork_url").takeIf(String::isNotBlank) ?: user?.text("avatar_url")
    return Music(
        id = SC_TRACK_PREFIX + id,
        title = title,
        subtitle = artist,
        kind = MusicKind.Track,
        imageUrl = largeSoundCloudImage(image),
        durationMs = duration,
        genre = json.text("genre").trim(),
        album = publisher?.text("album_title")?.trim().orEmpty(),
        ownerUsername = user?.text("permalink")?.takeIf(String::isNotBlank),
    )
}

/** A playlist, album, EP or single of api-v2. Albums open as albums; everything else as a playlist. */
internal fun parseSoundCloudPlaylist(json: JSONObject): Music? {
    if (json.text("kind") != "playlist") return null
    val id = json.optLong("id").takeIf { it > 0 } ?: return null
    val title = json.text("title").trim().takeIf(String::isNotEmpty) ?: return null
    val user = json.optJSONObject("user")
    val album = json.optBoolean("is_album") || json.text("set_type") in ALBUM_TYPES
    val firstArtwork = json.optJSONArray("tracks")?.let { tracks ->
        (0 until tracks.length()).firstNotNullOfOrNull { tracks.optJSONObject(it)?.text("artwork_url")?.takeIf(String::isNotBlank) }
    }
    val year = (json.text("release_date").takeIf(String::isNotBlank) ?: json.text("display_date"))
        .take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
    return Music(
        id = SC_PLAYLIST_PREFIX + id,
        title = title,
        subtitle = user?.text("username")?.trim().orEmpty(),
        kind = if (album) MusicKind.Album else MusicKind.Playlist,
        imageUrl = largeSoundCloudImage(json.text("artwork_url").takeIf(String::isNotBlank) ?: firstArtwork ?: user?.text("avatar_url")),
        note = if (album) year else "",
        ownerUsername = user?.text("permalink")?.takeIf(String::isNotBlank),
    )
}

/** A user, shown as an artist. */
internal fun parseSoundCloudUser(json: JSONObject): Music? {
    if (json.text("kind") != "user") return null
    val id = json.optLong("id").takeIf { it > 0 } ?: return null
    val name = json.text("username").trim().takeIf(String::isNotEmpty) ?: return null
    return Music(
        id = SC_USER_PREFIX + id,
        title = name,
        subtitle = "",
        kind = MusicKind.Artist,
        imageUrl = largeSoundCloudImage(json.text("avatar_url")),
        note = json.text("description").trim(),
        ownerUsername = json.text("permalink").takeIf(String::isNotBlank),
    )
}

/** Any searchable entity of api-v2. */
internal fun parseSoundCloudItem(json: JSONObject): Music? = when (json.text("kind")) {
    "track" -> parseSoundCloudTrack(json)
    "playlist" -> parseSoundCloudPlaylist(json)
    "user" -> parseSoundCloudUser(json)
    else -> null
}

/** The entities of a `collection` page, skipping stubs and unknown kinds. */
internal fun parseSoundCloudCollection(page: JSONObject): List<Music> =
    page.optJSONArray("collection").objects().mapNotNull(::parseSoundCloudItem).distinctBy(Music::id)

/** The tracks and playlists of a likes page: each like wraps either a `track` or a `playlist`. */
internal fun parseSoundCloudLikes(page: JSONObject): List<Music> =
    page.optJSONArray("collection").objects().mapNotNull { like ->
        like.optJSONObject("track")?.let(::parseSoundCloudTrack) ?: like.optJSONObject("playlist")?.let(::parseSoundCloudPlaylist)
    }.distinctBy(Music::id)

/** The next page address, or null at the end. */
internal fun soundCloudNext(page: JSONObject): String? =
    page.text("next_href").takeIf { it.startsWith("https://") }

/** Ids of a playlist's tracks in order: api-v2 sends only the first few in full, the rest as stubs. */
internal fun playlistTrackIds(playlist: JSONObject): List<Long> =
    playlist.optJSONArray("tracks").objects().mapNotNull { it.optLong("id").takeIf { id -> id > 0 } }

/** The full tracks a playlist response already carries, by id. */
internal fun playlistFullTracks(playlist: JSONObject): Map<Long, Music> =
    playlist.optJSONArray("tracks").objects().mapNotNull { json ->
        parseSoundCloudTrack(json)?.let { json.optLong("id") to it }
    }.toMap()

/** Public page addresses kept by id, so a track can be shared with its real link. */
internal fun soundCloudPermalinks(json: JSONObject, into: MutableMap<String, String>) {
    val link = json.text("permalink_url").takeIf { it.startsWith("https://soundcloud.com/") }
    val prefix = when (json.text("kind")) {
        "track" -> SC_TRACK_PREFIX; "playlist" -> SC_PLAYLIST_PREFIX; "user" -> SC_USER_PREFIX; else -> null
    }
    if (link != null && prefix != null && json.optLong("id") > 0) into[prefix + json.optLong("id")] = link
    json.optJSONObject("user")?.let { soundCloudPermalinks(it, into) }
    json.optJSONArray("tracks")?.objects()?.forEach { soundCloudPermalinks(it, into) }
}

/** What a pasted text points to on SoundCloud: a normalized `https://soundcloud.com/...` address, or null. */
fun soundCloudLink(input: String): String? {
    val text = input.trim()
    val match = SOUNDCLOUD_URL.find(text) ?: return null
    val host = match.groupValues[1].lowercase()
    val path = match.groupValues[2].substringBefore('?').substringBefore('#').trimEnd('/')
    if (path.isEmpty()) return null
    return when (host) {
        "on.soundcloud.com", "snd.sc" -> "https://$host$path"
        else -> "https://soundcloud.com$path"
    }
}

/** A bare SoundCloud profile name, such as `forss`, as a profile address. */
internal fun soundCloudProfileLink(input: String): String? {
    soundCloudLink(input)?.let { return it }
    val name = input.trim().removePrefix("@")
    return if (PROFILE_NAME.matches(name)) "https://soundcloud.com/$name" else null
}

/** How a linked profile is kept on the device. */
internal fun encodeSoundCloudProfile(profile: SoundCloudProfile): String = JSONObject()
    .put("id", profile.id).put("username", profile.username).put("permalink", profile.permalink)
    .put("avatarUrl", profile.avatarUrl ?: "").put("followers", profile.followers).toString()

internal fun decodeSoundCloudProfile(text: String?): SoundCloudProfile? = runCatching {
    val json = JSONObject(text ?: return null)
    SoundCloudProfile(
        id = json.getLong("id").takeIf { it > 0 } ?: return null,
        username = json.text("username"), permalink = json.text("permalink"),
        avatarUrl = json.text("avatarUrl").takeIf(String::isNotEmpty), followers = json.optLong("followers"),
    )
}.getOrNull()

/** A string field, empty when it is missing or JSON null (org.json would otherwise return "null"). */
internal fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name).trim()

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

private fun resolveUrl(base: String, reference: String): String =
    if (reference.startsWith("http://") || reference.startsWith("https://")) reference
    else runCatching { URI(base).resolve(reference).toString() }.getOrDefault(reference)

private val ALBUM_TYPES = setOf("album", "ep", "single", "compilation")
private val SCRIPT_SRC = Regex("""<script[^>]+src="([^"]+\.js)"""")
private val CLIENT_ID = Regex("""(?:client_id\s*[:=]\s*"?|"clientId"\s*:\s*")([A-Za-z0-9]{32})\b""")
private val HLS_URI = Regex("""URI="([^"]+)"""")
private val EXPIRES = Regex("""[?&](?:expires|Expires)=(\d+)""")
private val SOUNDCLOUD_URL = Regex("""https?://((?:www\.|m\.)?soundcloud\.com|on\.soundcloud\.com|snd\.sc)(/[^\s]*)?""", RegexOption.IGNORE_CASE)
private val PROFILE_NAME = Regex("""[A-Za-z0-9_-]{2,64}""")
