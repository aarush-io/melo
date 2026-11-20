package `in`.melo.data.soundcloud

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import `in`.melo.data.ArtistProfile
import `in`.melo.data.CatalogDetail
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.NetworkMonitor
import `in`.melo.data.diagnostics.DiagnosticLog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A SoundCloud request failed. [status] is one of the `STATUS_*` constants or `HTTP_<code>`. */
class SoundCloudFailure(message: String, val status: String? = null) : IOException(message) {
    companion object {
        const val STATUS_NOT_FOUND = "NOT_FOUND"
        const val STATUS_UNAVAILABLE = "UNAVAILABLE"
        const val STATUS_CLIENT = "CLIENT"
        const val STATUS_RATE_LIMITED = "RATE_LIMITED"
        /** Refused, possibly because the client id is stale and could not be renewed yet: worth retrying later. */
        const val STATUS_REFUSED = "REFUSED"
        /** Refused with a client id known to be current: this resource is not served to this client. */
        const val STATUS_NO_ACCESS = "NO_ACCESS"
        const val STATUS_EMPTY = "EMPTY"

        /** Failures that retrying cannot fix. */
        val PERMANENT = setOf(STATUS_NOT_FOUND, STATUS_UNAVAILABLE, STATUS_EMPTY)
    }
}

data class SoundCloudPage(val items: List<Music>, val next: String? = null)

/** A public profile linked by name or address: no password and no token are involved. */
data class SoundCloudProfile(
    val id: Long,
    val username: String,
    val permalink: String,
    val avatarUrl: String? = null,
    val followers: Long = 0,
) {
    val likesId: String get() = SC_LIKES_PREFIX + id
}

/** What a linked profile shows in the library: its liked tracks and its own or liked playlists. */
data class SoundCloudLibrary(val likes: List<Music>, val playlists: List<Music>)

/** How a track's audio can be read. */
internal sealed interface SoundCloudAudio {
    /** Unencrypted fragmented AAC over HLS. Segment addresses are signed and expire. */
    data class Segmented(val media: HlsMedia, val preset: String, val expiresAtMs: Long) : SoundCloudAudio

    /** A single MP3 file at a signed address. */
    data class Progressive(val url: String, val preset: String, val expiresAtMs: Long) : SoundCloudAudio

    /** No full-length stream for this client (a Go+ preview, a protected or blocked track). */
    data class Elsewhere(val track: Music, val policy: String) : SoundCloudAudio
}

/**
 * SoundCloud's public catalogue through api-v2, the API soundcloud.com itself uses, with the web
 * client id read from the site's scripts. Anonymous: no account, nothing is written to SoundCloud.
 * Every call blocks: run it on an IO thread. Holds only the application context (see [init]).
 */
@SuppressLint("StaticFieldLeak")
object SoundCloud {
    private var app: Context? = null

    internal val http: OkHttpClient = OkHttpClient.Builder()
        // Offline mode and lost connections stop SoundCloud requests as they stop the other services'.
        .addInterceptor { chain ->
            if (!NetworkMonitor.online.value) throw IOException("No connection")
            chain.proceed(chain.request())
        }
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_PARALLEL })
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()

    private val preferences get() = app?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile private var clientId: String? = null
    private val clientLock = Any()
    private val permalinks = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_PERMALINKS
    }
    @Volatile private var permalinksLoaded = false
    @Volatile private var permalinksDirty = false

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        // Remembered page addresses are read off the main thread, before sharing needs them.
        Thread(::loadPermalinks, "soundcloud-permalinks").start()
    }

    // Client id -----------------------------------------------------------------------------------

    private fun clientId(): String = clientId
        ?: preferences?.getString(KEY_CLIENT_ID, null)?.takeIf { it.length == 32 }?.also { clientId = it }
        ?: renewClientId(null)

    // On the monotonic clock, so that a device clock set back does not block renewals.
    @Volatile private var lastRenewalMs = -MIN_RENEWAL_INTERVAL_MS
    @Volatile private var lastFailureMs = -MIN_RENEWAL_INTERVAL_MS

    /**
     * Reads the current web client id from soundcloud.com, unless another thread already replaced
     * [stale]. When the site still gives [stale], the refusal had another cause: it is not retried.
     * A site that gave no id is not read again for a minute, however many requests are waiting.
     */
    private fun renewClientId(stale: String?): String = synchronized(clientLock) {
        clientId?.takeIf { it != stale }?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (stale != null && now - lastRenewalMs < MIN_RENEWAL_INTERVAL_MS) {
            throw SoundCloudFailure("SoundCloud refused the request.", SoundCloudFailure.STATUS_REFUSED)
        }
        if (now - lastFailureMs < MIN_RENEWAL_INTERVAL_MS) {
            throw SoundCloudFailure("SoundCloud could not be reached.", SoundCloudFailure.STATUS_CLIENT)
        }
        lastRenewalMs = now
        // The mobile page may keep giving the refused id while the desktop scripts have the new one.
        val found = findClientId(AGENT)?.takeIf { it != stale } ?: findClientId(DESKTOP_AGENT)
        if (found == null) {
            lastFailureMs = now
            DiagnosticLog.record("soundcloud", "client id not found")
            throw SoundCloudFailure("SoundCloud could not be reached.", SoundCloudFailure.STATUS_CLIENT)
        }
        // The site still gives the refused id: it is current, and the request itself is refused.
        if (found == stale) throw SoundCloudFailure("SoundCloud refused the request.", SoundCloudFailure.STATUS_NO_ACCESS)
        clientId = found
        preferences?.edit { putString(KEY_CLIENT_ID, found) }
        return found
    }

    /**
     * The mobile site names the id in the page itself; the desktop site defines it in one of its
     * last script bundles, so reading them from the end finds it in one or two requests.
     */
    private fun findClientId(agent: String): String? {
        val page = runCatching { text("https://soundcloud.com/", agent) }.getOrNull() ?: return null
        extractSoundCloudClientId(page)?.let { return it }
        val scripts = soundCloudScriptUrls(page)
        for (script in scripts.asReversed().take(MAX_CLIENT_SCRIPTS)) {
            runCatching { text(script, agent) }.getOrNull()?.let(::extractSoundCloudClientId)?.let { return it }
        }
        DiagnosticLog.record("soundcloud", "no client id in the page or its ${scripts.size} scripts")
        return null
    }

    // Requests ------------------------------------------------------------------------------------

    private fun text(url: String, agent: String = AGENT): String = http.newCall(Request.Builder().url(url).header("User-Agent", agent).build()).execute().use { response ->
        if (!response.isSuccessful) throw SoundCloudFailure("SoundCloud answered ${response.code}.", "HTTP_${response.code}")
        response.body?.string() ?: ""
    }

    private fun apiUrl(pathOrUrl: String, parameters: Map<String, String>, id: String): HttpUrl {
        val base = if (pathOrUrl.startsWith("https://")) pathOrUrl.toHttpUrl() else "$API/$pathOrUrl".toHttpUrl()
        return base.newBuilder().removeAllQueryParameters("client_id").apply {
            parameters.forEach { (name, value) -> setQueryParameter(name, value) }
            setQueryParameter("client_id", id)
        }.build()
    }

    /**
     * One api-v2 request. A refused client id is renewed once: the web client rotates its id, and
     * every request made with the old one answers 401 or 403 until it is replaced.
     */
    internal fun api(pathOrUrl: String, parameters: Map<String, String> = emptyMap()): String {
        var id = clientId()
        repeat(2) { attempt ->
            val request = Request.Builder().url(apiUrl(pathOrUrl, parameters, id))
                .header("User-Agent", AGENT).header("Accept", "application/json").build()
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> return response.body?.string() ?: ""
                    response.code == 404 -> throw SoundCloudFailure("This is not available on SoundCloud.", SoundCloudFailure.STATUS_NOT_FOUND)
                    response.code == 429 -> throw SoundCloudFailure("SoundCloud is limiting requests. Try again in a moment.", SoundCloudFailure.STATUS_RATE_LIMITED)
                    (response.code == 401 || response.code == 403) && attempt == 0 -> {
                        DiagnosticLog.record("soundcloud", "http=${response.code}, renewing client id")
                        id = renewClientId(id)
                    }
                    // Refused again with the id just renewed: the request itself is refused.
                    response.code == 401 || response.code == 403 ->
                        throw SoundCloudFailure("SoundCloud refused the request.", SoundCloudFailure.STATUS_NO_ACCESS)
                    else -> throw SoundCloudFailure("SoundCloud answered ${response.code}.", "HTTP_${response.code}")
                }
            }
        }
        throw SoundCloudFailure("SoundCloud refused the request.", SoundCloudFailure.STATUS_REFUSED)
    }

    private fun apiObject(pathOrUrl: String, parameters: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(api(pathOrUrl, parameters)).also(::learn)

    private fun apiArray(pathOrUrl: String, parameters: Map<String, String> = emptyMap()): JSONArray =
        JSONArray(api(pathOrUrl, parameters)).also { array -> array.objects().forEach(::learn) }

    // Catalogue -----------------------------------------------------------------------------------

    /** Tracks, playlists, albums and artists for a query, as the site's own search mixes them. */
    fun search(query: String): SoundCloudPage {
        val page = apiObject("search", mapOf("q" to query, "limit" to "$SEARCH_LIMIT", "linked_partitioning" to "1"))
        return SoundCloudPage(parseSoundCloudCollection(page), soundCloudNext(page))
    }

    /** Tracks only, for adding to a playlist or matching. */
    fun searchTracks(query: String, limit: Int = SEARCH_LIMIT): List<Music> =
        parseSoundCloudCollection(apiObject("search/tracks", mapOf("q" to query, "limit" to "$limit")))
            .filter { it.kind == MusicKind.Track }

    /** The next page of a search or a list. */
    fun more(next: String): SoundCloudPage {
        val page = apiObject(next)
        return SoundCloudPage(parseSoundCloudCollection(page), soundCloudNext(page))
    }

    /** Full tracks for ids, in the given order; ids SoundCloud no longer serves are left out. */
    fun tracks(ids: List<Long>): List<Music> {
        val found = HashMap<Long, Music>()
        ids.distinct().chunked(TRACK_BATCH).forEach { batch ->
            apiArray("tracks", mapOf("ids" to batch.joinToString(","))).objects().forEach { json ->
                parseSoundCloudTrack(json)?.let { found[json.optLong("id")] = it }
            }
        }
        return ids.mapNotNull(found::get)
    }

    /** A playlist or album with all its tracks. */
    fun playlist(id: Long): CatalogDetail {
        val json = apiObject("playlists/$id", mapOf("representation" to "full"))
        val item = parseSoundCloudPlaylist(json) ?: throw SoundCloudFailure("This playlist is unavailable.", SoundCloudFailure.STATUS_NOT_FOUND)
        val order = playlistTrackIds(json)
        val known = playlistFullTracks(json)
        val missing = order.filter { it !in known }
        val fetched = if (missing.isEmpty()) emptyMap() else tracks(missing).associateBy { soundCloudTrackIdOf(it.id) ?: 0L }
        val tracks = order.mapNotNull { known[it] ?: fetched[it] }
        val owner = json.optJSONObject("user")?.let(::parseSoundCloudUser)
        val album = if (item.kind == MusicKind.Album) item.title else null
        val shown = tracks.map { track -> if (album != null && track.album.isEmpty()) track.copy(album = album) else track }
        return CatalogDetail(item.copy(tracks = shown.map(Music::id)), shown, listOfNotNull(owner), total = shown.size)
    }

    /** An artist page: popular tracks with their play counts, albums, similar artists and the description. */
    fun user(id: Long): CatalogDetail {
        val json = apiObject("users/$id")
        val artist = parseSoundCloudUser(json) ?: throw SoundCloudFailure("This artist is unavailable.", SoundCloudFailure.STATUS_NOT_FOUND)
        val plays = HashMap<String, Long>()
        val top = runCatching { apiObject("users/$id/toptracks", mapOf("limit" to "$USER_TRACKS")) }.getOrNull()
        val tracks = (top?.takeIf { (it.optJSONArray("collection")?.length() ?: 0) > 0 }
            ?: apiObject("users/$id/tracks", mapOf("limit" to "$USER_TRACKS")))
            .also { page -> page.optJSONArray("collection").objects().forEach { json -> plays[SC_TRACK_PREFIX + json.optLong("id")] = json.optLong("playback_count") } }
            .let(::parseSoundCloudCollection).filter { it.kind == MusicKind.Track }
        val albums = runCatching { parseSoundCloudCollection(apiObject("users/$id/albums", mapOf("limit" to "$USER_RELEASES"))) }
            .getOrDefault(emptyList()).map { it.copy(kind = MusicKind.Album) }
        val similar = runCatching { parseSoundCloudCollection(apiObject("users/$id/relatedartists", mapOf("limit" to "$USER_RELATED"))) }
            .getOrDefault(emptyList()).filter { it.kind == MusicKind.Artist }
        val banner = json.optJSONObject("visuals")?.optJSONArray("visuals").objects().firstOrNull()?.text("visual_url")?.takeIf(String::isNotBlank)
        val profile = ArtistProfile(
            playCounts = plays.filterValues { it > 0 }, albums = albums.map(Music::id),
            biography = artist.note, headerImageUrl = banner,
        )
        return CatalogDetail(artist.copy(tracks = tracks.map(Music::id)), tracks, albums + similar, artist = profile)
    }

    /** The station SoundCloud builds from a track, with the track first; related tracks when there is no station. */
    fun station(seed: Music, limit: Int = STATION_LIMIT): List<Music> {
        val id = seed.soundCloudTrackId ?: throw SoundCloudFailure("This track has no SoundCloud station.", SoundCloudFailure.STATUS_NOT_FOUND)
        val station = runCatching {
            parseSoundCloudCollection(apiObject("stations/soundcloud:track-stations:$id/tracks", mapOf("limit" to "$limit")))
        }.getOrDefault(emptyList()).filter { it.kind == MusicKind.Track }
        val tracks = station.ifEmpty {
            parseSoundCloudCollection(apiObject("tracks/$id/related", mapOf("limit" to "$limit"))).filter { it.kind == MusicKind.Track }
        }
        if (tracks.isEmpty()) throw SoundCloudFailure("SoundCloud found nothing similar to this track.", SoundCloudFailure.STATUS_EMPTY)
        return (listOf(seed) + tracks.filter { it.id != seed.id }).take(limit)
    }

    /** The account that uploaded a track, as an artist. */
    fun uploader(trackId: Long): Music? = apiObject("tracks/$trackId").optJSONObject("user")?.let(::parseSoundCloudUser)

    /** Opens a soundcloud.com address: a track, a playlist or album, or a profile. */
    fun resolve(link: String): Music {
        val address = followShortLink(link)
        val json = apiObject("resolve", mapOf("url" to address))
        return parseSoundCloudItem(json) ?: throw SoundCloudFailure("This SoundCloud link is not supported.", SoundCloudFailure.STATUS_NOT_FOUND)
    }

    /** `on.soundcloud.com` short links redirect to the real page. */
    private fun followShortLink(link: String): String {
        if (!link.contains("on.soundcloud.com") && !link.contains("snd.sc")) return link
        return http.newCall(Request.Builder().url(link).head().header("User-Agent", AGENT).build()).execute().use { it.request.url.toString() }
    }

    /** Finds a public profile from a name or a profile address. */
    fun profile(input: String): SoundCloudProfile {
        val link = soundCloudProfileLink(input) ?: throw SoundCloudFailure("Enter a SoundCloud profile name or link.", SoundCloudFailure.STATUS_NOT_FOUND)
        val json = apiObject("resolve", mapOf("url" to followShortLink(link)))
        if (json.text("kind") != "user") throw SoundCloudFailure("This link is not a SoundCloud profile.", SoundCloudFailure.STATUS_NOT_FOUND)
        return SoundCloudProfile(
            id = json.optLong("id"), username = json.text("username").ifEmpty { json.text("permalink") },
            permalink = json.text("permalink"), avatarUrl = largeSoundCloudImage(json.text("avatar_url")),
            followers = json.optLong("followers_count"),
        )
    }

    /**
     * The public likes and playlists of a profile. Any page that cannot be read fails the whole
     * read, so a partial library never replaces the one already shown.
     */
    fun library(profile: SoundCloudProfile): SoundCloudLibrary {
        val liked = ArrayList<Music>()
        var page: JSONObject? = apiObject("users/${profile.id}/likes", mapOf("limit" to "$LIKES_PAGE", "linked_partitioning" to "1"))
        var pages = 0
        while (page != null && pages++ < MAX_LIKE_PAGES) {
            liked += parseSoundCloudLikes(page)
            page = soundCloudNext(page)?.let(::apiObject)
        }
        val own = listOf("users/${profile.id}/playlists_without_albums", "users/${profile.id}/albums").flatMap { path ->
            try {
                parseSoundCloudCollection(apiObject(path, mapOf("limit" to "$USER_RELEASES")))
            } catch (missing: SoundCloudFailure) {
                // Some profiles have no such list at all: that is an empty list, not a failed read.
                if (missing.status != SoundCloudFailure.STATUS_NOT_FOUND) throw missing
                emptyList()
            }
        }
        val likedTracks = liked.filter { it.kind == MusicKind.Track }.distinctBy(Music::id)
        val playlists = (own + liked.filter { it.kind != MusicKind.Track }).distinctBy(Music::id)
        flushPermalinks()
        return SoundCloudLibrary(likedTracks, playlists)
    }

    // Audio ---------------------------------------------------------------------------------------

    /**
     * Where a track's audio is now. Addresses are signed for a limited time: resolve again when they
     * expire, with the [preset] already being read, so the bytes stay those of the same encoding.
     *
     * [SoundCloudAudio.Elsewhere] only means SoundCloud has no full stream Melo can read for this
     * track: none is offered, or every one offered is refused or missing (regional limits). Any
     * other failure is thrown, so a passing network error never sends the track to another source.
     *
     * When [preset] is no longer offered, a [SoundCloudFailure] with
     * [SoundCloudFailure.STATUS_UNAVAILABLE] is thrown: what was laid out for it is obsolete.
     */
    internal fun audio(trackId: Long, preset: String? = null): SoundCloudAudio {
        val json = apiObject("tracks/$trackId")
        val track = parseSoundCloudTrack(json) ?: throw SoundCloudFailure("This track is unavailable.", SoundCloudFailure.STATUS_NOT_FOUND)
        val authorization = json.text("track_authorization")
        val parameters = if (authorization.isEmpty()) emptyMap() else mapOf("track_authorization" to authorization)
        val choices = playableTranscodings(json).filter { preset == null || it.preset == preset }
        var failure: IOException? = null
        var transient = false
        for (choice in choices) {
            try {
                val located = JSONObject(api(choice.url, parameters)).text("url").takeIf { it.startsWith("https://") } ?: continue
                val expires = signedUrlExpiryMs(located) ?: (System.currentTimeMillis() + DEFAULT_LIFETIME_MS)
                if (choice.isProgressiveMp3) return SoundCloudAudio.Progressive(located, choice.preset, expires)
                // A playlist Melo cannot lay out (encrypted, byte ranges) moves on to the next encoding.
                val media = parseSoundCloudHls(text(located), located) ?: continue
                return SoundCloudAudio.Segmented(media, choice.preset, expires)
            } catch (error: IOException) {
                failure = failure ?: error
                if (!meansNoStream(error)) transient = true
            } catch (error: org.json.JSONException) {
                failure = failure ?: IOException("SoundCloud sent an unreadable answer.", error)
                transient = true
            }
        }
        if (transient) failure?.let { throw it }
        if (preset != null) {
            throw SoundCloudFailure("SoundCloud no longer offers this encoding of the track.", SoundCloudFailure.STATUS_UNAVAILABLE)
        }
        return SoundCloudAudio.Elsewhere(track, json.text("policy"))
    }

    /** A stream refused or missing for good, as opposed to a network or service failure worth retrying. */
    private fun meansNoStream(error: IOException): Boolean =
        error is SoundCloudFailure && error.status in NO_STREAM

    /** Sizes of files at signed addresses, read in parallel with HEAD requests. */
    internal fun contentLengths(urls: List<String>): LongArray {
        val sizes = LongArray(urls.size) { -1 }
        if (urls.isEmpty()) return sizes
        val done = CountDownLatch(urls.size)
        val failure = AtomicReference<IOException?>(null)
        val calls = urls.mapIndexed { index, url ->
            http.newCall(Request.Builder().url(url).head().header("User-Agent", AGENT).build()).also { call ->
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { failure.compareAndSet(null, e); done.countDown() }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val length = it.header("Content-Length")?.toLongOrNull()
                            if (it.isSuccessful && length != null && length > 0) sizes[index] = length
                            else failure.compareAndSet(null, SoundCloudFailure("A part of this track could not be read.", "HTTP_${it.code}"))
                        }
                        done.countDown()
                    }
                })
            }
        }
        try {
            // Sixteen requests run at once: long mixes with hundreds of segments get more time.
            val budget = HEAD_TIMEOUT_S + urls.size / HEADS_PER_EXTRA_SECOND
            if (!done.await(budget, TimeUnit.SECONDS)) {
                calls.forEach(Call::cancel)
                throw IOException("SoundCloud did not answer in time.")
            }
        } catch (interrupted: InterruptedException) {
            calls.forEach(Call::cancel)
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Interrupted")
        }
        failure.get()?.let { calls.forEach(Call::cancel); throw it }
        return sizes
    }

    /** Fetches a whole small file, such as an initialization section. */
    internal fun bytes(url: String): ByteArray =
        http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) throw SoundCloudFailure("A part of this track could not be read.", "HTTP_${response.code}")
            response.body?.bytes() ?: ByteArray(0)
        }

    // Links ---------------------------------------------------------------------------------------

    /** The public page of a SoundCloud item, when it has been seen. */
    fun pageUrl(id: String): String? {
        loadPermalinks()
        synchronized(permalinks) { permalinks[id] }?.let { return it }
        // A track page can always be reached through the official embed player.
        return soundCloudTrackIdOf(id)?.let { "https://w.soundcloud.com/player/?url=https%3A//api.soundcloud.com/tracks/$it" }
    }

    private fun learn(json: JSONObject) {
        val found = HashMap<String, String>()
        soundCloudPermalinks(json, found)
        json.optJSONArray("collection").objects().forEach { item ->
            soundCloudPermalinks(item, found)
            item.optJSONObject("track")?.let { soundCloudPermalinks(it, found) }
            item.optJSONObject("playlist")?.let { soundCloudPermalinks(it, found) }
        }
        if (found.isEmpty()) return
        loadPermalinks()
        synchronized(permalinks) { permalinks.putAll(found) }
        permalinksDirty = true
    }

    private fun loadPermalinks() {
        if (permalinksLoaded) return
        val preferences = preferences ?: return
        synchronized(permalinks) {
            if (permalinksLoaded) return
            preferences.getString(KEY_PERMALINKS, null)?.let { stored ->
                runCatching {
                    val json = JSONObject(stored)
                    json.keys().forEach { key -> permalinks.putIfAbsent(key, json.getString(key)) }
                }
            }
            // Set once the map is filled, so a concurrent reader never sees an empty map as loaded.
            permalinksLoaded = true
        }
    }

    /** Saves the remembered page addresses; called after larger reads and from the model when it pauses. */
    fun flushPermalinks() {
        if (!permalinksDirty) return
        permalinksDirty = false
        val json = JSONObject(synchronized(permalinks) { permalinks.toMap() })
        preferences?.edit { putString(KEY_PERMALINKS, json.toString()) }
    }

    private const val API = "https://api-v2.soundcloud.com"
    private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    private const val DESKTOP_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    private const val MAX_CLIENT_SCRIPTS = 12
    private const val MIN_RENEWAL_INTERVAL_MS = 60_000L
    /**
     * Answers that mean a stream is not served to this client, whatever the retry. NO_ACCESS is only
     * thrown once the client id was renewed or found current (see [api]); a REFUSED that may come
     * from a stale id is not one of them. HTTP_* come from the CDN that serves the playlist.
     */
    private val NO_STREAM = setOf(SoundCloudFailure.STATUS_NOT_FOUND, SoundCloudFailure.STATUS_NO_ACCESS, "HTTP_403", "HTTP_404", "HTTP_410")
    private const val HEADS_PER_EXTRA_SECOND = 8
    private const val PREFERENCES = "soundcloud"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_PERMALINKS = "permalinks"
    private const val MAX_PERMALINKS = 3000
    private const val MAX_PARALLEL = 16
    private const val SEARCH_LIMIT = 30
    private const val TRACK_BATCH = 50
    private const val USER_TRACKS = 50
    private const val USER_RELEASES = 50
    private const val USER_RELATED = 20
    private const val STATION_LIMIT = 100
    private const val LIKES_PAGE = 200
    private const val MAX_LIKE_PAGES = 10
    private const val HEAD_TIMEOUT_S = 30L
    private const val DEFAULT_LIFETIME_MS = 20 * 60_000L
}
