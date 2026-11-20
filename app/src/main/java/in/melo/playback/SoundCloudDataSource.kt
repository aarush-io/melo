package `in`.melo.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import `in`.melo.data.AudioFileRecord
import `in`.melo.data.AudioIndex
import `in`.melo.data.Music
import `in`.melo.data.SOUNDCLOUD_FILE_PREFIX
import `in`.melo.data.soundcloud.SC_TRACK_PREFIX
import `in`.melo.data.soundcloud.SoundCloud
import `in`.melo.data.soundcloud.SoundCloudAudio
import `in`.melo.data.soundcloud.SoundCloudFailure
import `in`.melo.data.soundcloud.soundCloudTrackId
import `in`.melo.data.soundcloud.soundCloudTrackIdOf
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/** Addresses of SoundCloud tracks: `scm://track/<id>`. The signed CDN addresses are resolved when reading. */
internal object SoundCloudUris {
    const val SCHEME = "scm"

    fun isSoundCloud(uri: Uri?): Boolean = uri?.scheme == SCHEME

    fun of(track: Music): Uri? = track.soundCloudTrackId?.let { "$SCHEME://track/$it".toUri() }

    /** The file name the caches know this track's audio by. */
    fun fileHex(trackId: String): String = SOUNDCLOUD_FILE_PREFIX + trackId.substringAfterLast(':')

    @androidx.annotation.OptIn(UnstableApi::class)
    fun cacheKey(trackId: String): String = AudioCache.key(fileHex(trackId))

    fun trackId(uri: Uri): String? =
        uri.lastPathSegment?.takeIf { uri.authority == "track" && it.all(Char::isDigit) }?.let { SC_TRACK_PREFIX + it }
}

/**
 * How a SoundCloud track is presented to the player: one progressive file. SoundCloud streams
 * fragmented AAC as HLS segments; those segments, preceded by the initialization section and a
 * segment index built from the segment sizes, form a regular fragmented MP4 file that can be
 * seeked, cached whole and downloaded exactly like a YouTube Music file.
 */
internal class SoundCloudLayout(
    val head: ByteArray,
    val segmentOffsets: LongArray,
    val segmentSizes: LongArray,
    @Volatile var segmentUrls: List<String>,
    @Volatile var expiresAtMs: Long,
    /** The encoding laid out: renewed addresses must be those of this encoding, whose sizes the layout holds. */
    val preset: String = "",
) {
    /** When the addresses were last renewed, on the monotonic clock. */
    @Volatile var renewedAtMs: Long = 0L

    val length: Long = head.size + segmentSizes.sum()

    /** The segment holding [position], or -1 inside the head. */
    fun segmentAt(position: Long): Int {
        if (position < head.size) return -1
        var low = 0
        var high = segmentOffsets.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (segmentOffsets[middle] <= position) low = middle else high = middle - 1
        }
        return low
    }
}

/**
 * Reads a SoundCloud track as one file (see [SoundCloudLayout]); MP3-only tracks are read directly.
 * Tracks SoundCloud only previews or protects for this client are played from their YouTube Music
 * match instead, the way Spotify tracks are played without a Spotify account.
 */
@UnstableApi
internal class SoundCloudDataSource private constructor(private val context: Context) : BaseDataSource(true) {
    class Factory(context: Context) : DataSource.Factory {
        private val app = context.applicationContext
        init { SoundCloud.init(app) }
        override fun createDataSource(): DataSource = SoundCloudDataSource(app)
    }

    private var spec: DataSpec? = null
    private var trackKey: String = ""
    private var numericId = 0L
    private var layout: SoundCloudLayout? = null
    private var direct: Direct? = null
    private var fallback: DataSource? = null
    private var position = 0L
    private var end = 0L
    private var memory: ByteArray? = null
    private var memoryOffset = 0
    private var stream: InputStream? = null
    private var response: Response? = null
    private var streamLeft = 0L
    private var streamYielded = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        trackKey = SoundCloudUris.trackId(dataSpec.uri) ?: throw IOException("Unknown SoundCloud address.")
        numericId = soundCloudTrackIdOf(trackKey) ?: throw IOException("Unknown SoundCloud address.")
        position = dataSpec.position
        val total: Long
        when (val prepared = prepare(numericId)) {
            is Prepared.Layout -> { layout = prepared.layout; total = prepared.layout.length }
            is Prepared.Single -> { direct = prepared.direct; total = prepared.direct.length }
            is Prepared.Elsewhere -> return openElsewhere(dataSpec, prepared.track)
        }
        // Before the range check: bytes cached from a longer variant must not make this one look out of range.
        dropOtherVariant(total)
        if (position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(total, position + dataSpec.length) else total
        AudioIndex.get(context).attachDirectFile(trackKey, AudioFileRecord(SoundCloudUris.fileHex(trackKey), total, ""))
        opened = true
        transferStarted(dataSpec)
        return end - position
    }

    /** Plays the YouTube Music match of a track SoundCloud does not stream in full, under the same cache key. */
    private fun openElsewhere(dataSpec: DataSpec, track: Music): Long {
        val previous = recordedLength()
        // The YouTube source records the file under this track's SoundCloud name (see YouTubeUris.fileHex).
        val source = YouTubeDataSource.Factory(context).createDataSource()
        val length = source.open(dataSpec.buildUpon().setUri(YouTubeUris.match(track)).build())
        fallback = source
        // The YouTube source records the length of the whole file when it opens. The length it returns
        // is only that of the range asked for, which the cache often limits to a gap between spans.
        val total = recordedLength()
        if (previous > 0 && total > 0 && previous != total) removeCachedAudio()
        opened = true
        transferStarted(dataSpec)
        return length
    }

    private fun recordedLength(): Long = AudioIndex.get(context).record(trackKey)?.file?.expectedBytes ?: 0L

    /**
     * One cache key holds this track whichever stream it comes from (the laid-out file, an MP3, or
     * its YouTube Music match). When SoundCloud changed what it serves, the bytes cached from the
     * other stream would be mixed with these: they are dropped, and the next read starts clean.
     */
    private fun dropOtherVariant(total: Long) {
        val previous = recordedLength()
        if (previous > 0 && previous != total) removeCachedAudio()
    }

    private fun removeCachedAudio() {
        `in`.melo.data.diagnostics.DiagnosticLog.record("soundcloud-audio", "stream of $trackKey changed, cached audio dropped")
        runCatching { AudioCache.cache(context).removeResource(SoundCloudUris.cacheKey(trackKey)) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        fallback?.let { source ->
            val read = source.read(buffer, offset, length)
            if (read > 0) bytesTransferred(read)
            return read
        }
        if (position >= end) return C.RESULT_END_OF_INPUT
        memory?.let { bytes ->
            if (memoryOffset < bytes.size) {
                val count = minOf(length.toLong(), (bytes.size - memoryOffset).toLong(), end - position).toInt()
                System.arraycopy(bytes, memoryOffset, buffer, offset, count)
                memoryOffset += count
                return advance(count)
            }
            memory = null
        }
        val input = stream
        if (input == null || streamLeft <= 0L) {
            openAt(position)
            return read(buffer, offset, length)
        }
        val read = input.read(buffer, offset, minOf(length.toLong(), streamLeft, end - position).toInt())
        if (read < 0) {
            // The CDN closed early: continue from where reading stopped, once per opened range.
            if (!streamYielded) {
                // Nothing at all where a part should be: the sizes laid out are wrong, lay the track out again.
                forget(numericId)
                throw IOException("SoundCloud closed the audio stream.")
            }
            closeStream()
            openAt(position)
            return read(buffer, offset, length)
        }
        streamYielded = true
        streamLeft -= read
        return advance(read)
    }

    private fun advance(count: Int): Int {
        position += count
        bytesTransferred(count)
        return count
    }

    /** Positions the reader at [at]: the head, a patched segment start, or a byte range of a segment or file. */
    private fun openAt(at: Long) {
        closeStream()
        direct?.let { file ->
            stream = request(file.url, at, file.length - 1, DIRECT_FILE).also { streamLeft = file.length - at }
            return
        }
        val current = checkNotNull(layout)
        val segment = current.segmentAt(at)
        if (segment < 0) {
            memory = current.head
            memoryOffset = at.toInt()
            return
        }
        val local = at - current.segmentOffsets[segment]
        val size = current.segmentSizes[segment]
        if (local < SEGMENT_HEADER_WINDOW) {
            // Each segment starts with its own index box, which would replace the file's: rename it.
            val window = minOf(SEGMENT_HEADER_WINDOW, size)
            val start = readFully(segmentUrl(current, segment), 0, window, segment)
            neutralizeSegmentIndex(start)
            memory = start
            memoryOffset = local.toInt()
            if (memoryOffset >= start.size) { memory = null; openRange(current, segment, window, size) }
            return
        }
        openRange(current, segment, local, size)
    }

    private fun openRange(current: SoundCloudLayout, segment: Int, from: Long, size: Long) {
        stream = request(segmentUrl(current, segment), from, size - 1, segment)
        streamLeft = size - from
    }

    private fun segmentUrl(current: SoundCloudLayout, index: Int): String {
        // Renewed ahead of expiry, but at most every few minutes: with a device clock running ahead,
        // every address would look expired. A refused address is renewed anyway (see request).
        val due = System.currentTimeMillis() > current.expiresAtMs - EXPIRY_MARGIN_MS
        if (due && android.os.SystemClock.elapsedRealtime() - current.renewedAtMs > MIN_RENEWAL_INTERVAL_MS) renew(current)
        return current.segmentUrls[index]
    }

    /**
     * Signed addresses expired: resolve the track again, in the encoding laid out, whose segment
     * sizes the layout holds. Anything else means the track changed: it is laid out again on the
     * next attempt.
     */
    private fun renew(current: SoundCloudLayout) = synchronized(current) {
        // Another reader of this layout (player, preloader, download) just renewed it.
        if (android.os.SystemClock.elapsedRealtime() - current.renewedAtMs < RECENT_RENEWAL_MS) return@synchronized
        val audio = resolveAgain(current.preset)
        if (audio !is SoundCloudAudio.Segmented || audio.media.segments.size != current.segmentSizes.size) {
            forget(numericId)
            throw IOException("This track changed on SoundCloud.")
        }
        current.segmentUrls = audio.media.segments.map { it.url }
        current.expiresAtMs = audio.expiresAtMs
        current.renewedAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** A ranged GET of [part] (a segment index, or [DIRECT_FILE]); a refused (expired) address is resolved again once. */
    private fun request(url: String, from: Long, to: Long, part: Int): InputStream {
        var address = url
        repeat(2) { attempt ->
            val call = SoundCloud.http.newCall(Request.Builder().url(address).header("Range", "bytes=$from-$to").build())
            val answer = call.execute()
            if (answer.code == 206 || (answer.code == 200 && from == 0L)) {
                response = answer
                return answer.body?.byteStream() ?: throw IOException("Empty answer from SoundCloud.")
            }
            answer.close()
            // A range past the end: the sizes laid out are wrong, lay the track out again.
            if (answer.code == 416) forget(numericId)
            if (answer.code !in REFUSED || attempt > 0) throw SoundCloudFailure("SoundCloud answered ${answer.code}.", "HTTP_${answer.code}")
            address = renewedAddress(part)
        }
        throw IOException("SoundCloud refused the audio.")
    }

    private fun renewedAddress(part: Int): String {
        direct?.let { file ->
            val audio = resolveAgain(file.preset)
            if (audio !is SoundCloudAudio.Progressive) {
                forget(numericId)
                throw IOException("This track changed on SoundCloud.")
            }
            file.url = audio.url
            return audio.url
        }
        val current = checkNotNull(layout)
        renew(current)
        return current.segmentUrls[part]
    }

    /**
     * Resolves the track again in the encoding being read. When SoundCloud no longer serves it (the
     * encoding was dropped, the track was blocked or limited to a preview), the layout is forgotten,
     * so the next attempt lays the track out from scratch, or plays it from YouTube Music.
     */
    private fun resolveAgain(preset: String): SoundCloudAudio = try {
        SoundCloud.audio(numericId, preset.ifEmpty { null })
    } catch (failure: SoundCloudFailure) {
        if (failure.status in SoundCloudFailure.PERMANENT) forget(numericId)
        throw failure
    }

    private fun readFully(url: String, from: Long, count: Long, part: Int): ByteArray {
        val input = request(url, from, from + count - 1, part)
        val bytes = ByteArray(count.toInt())
        var filled = 0
        try {
            while (filled < bytes.size) {
                val read = input.read(bytes, filled, bytes.size - filled)
                if (read < 0) throw IOException("SoundCloud ended a segment early.")
                filled += read
            }
        } finally { closeStream() }
        return bytes
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        try {
            fallback?.close()
            closeStream()
        } finally {
            fallback = null
            memory = null
            layout = null
            direct = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
        streamLeft = 0L
        streamYielded = false
    }

    /** A progressive MP3 file. */
    internal class Direct(@Volatile var url: String, val length: Long, val preset: String = "")

    private sealed interface Prepared {
        class Layout(val layout: SoundCloudLayout) : Prepared
        class Single(val direct: Direct) : Prepared
        class Elsewhere(val track: Music) : Prepared
    }

    companion object {
        /** Long enough for the `styp` and `sidx` boxes that open every segment. */
        const val SEGMENT_HEADER_WINDOW = 4096L
        private const val DIRECT_FILE = -1
        private const val EXPIRY_MARGIN_MS = 60_000L
        private const val MIN_RENEWAL_INTERVAL_MS = 2 * 60_000L
        private const val RECENT_RENEWAL_MS = 10_000L
        private val REFUSED = setOf(401, 403, 404, 410)
        private const val MAX_LAYOUTS = 24
        private val layouts = object : LinkedHashMap<Long, Prepared>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Prepared>?) = size > MAX_LAYOUTS
        }
        /** One lock per track being laid out: the player, the preloader and a download share one layout. */
        private val preparing = java.util.concurrent.ConcurrentHashMap<Long, Any>()

        private fun forget(id: Long) = synchronized(layouts) { layouts.remove(id) }

        /** Builds (or reuses) the layout of a track. Layouts outlive their addresses, which are renewed on demand. */
        private fun prepare(id: Long): Prepared {
            synchronized(layouts) { layouts[id] }?.let { return it }
            val lock = preparing.getOrPut(id) { Any() }
            try {
                synchronized(lock) {
                    synchronized(layouts) { layouts[id] }?.let { return it }
                    val prepared = layOut(id)
                    synchronized(layouts) { layouts[id] = prepared }
                    return prepared
                }
            } finally {
                preparing.remove(id, lock)
            }
        }

        private fun layOut(id: Long): Prepared =
            when (val audio = SoundCloud.audio(id)) {
                is SoundCloudAudio.Elsewhere -> Prepared.Elsewhere(audio.track)
                is SoundCloudAudio.Progressive -> {
                    val length = SoundCloud.contentLengths(listOf(audio.url))[0]
                    Prepared.Single(Direct(audio.url, length, audio.preset))
                }
                is SoundCloudAudio.Segmented -> {
                    val segments = audio.media.segments
                    val init = SoundCloud.bytes(audio.media.initUrl)
                    val sizes = SoundCloud.contentLengths(segments.map { it.url })
                    val index = buildSegmentIndex(sizes, segments.map { it.durationUs }.toLongArray())
                    val head = init + index
                    val offsets = LongArray(sizes.size)
                    var offset = head.size.toLong()
                    sizes.forEachIndexed { i, size -> offsets[i] = offset; offset += size }
                    Prepared.Layout(SoundCloudLayout(head, offsets, sizes, segments.map { it.url }, audio.expiresAtMs, audio.preset)
                        .apply { renewedAtMs = android.os.SystemClock.elapsedRealtime() })
                }
            }
    }
}

/**
 * A version 1 `sidx` box indexing [sizes] consecutive subsegments of [durationsUs], starting right
 * after the box. Media3's fragmented MP4 extractor turns it into the seek map of the whole file.
 */
internal fun buildSegmentIndex(sizes: LongArray, durationsUs: LongArray): ByteArray {
    require(sizes.size == durationsUs.size && sizes.size in 1..0xFFFF)
    val boxSize = 40 + 12 * sizes.size
    val out = java.nio.ByteBuffer.allocate(boxSize)
    out.putInt(boxSize).put("sidx".toByteArray(Charsets.US_ASCII))
    out.putInt(1 shl 24) // version 1, no flags
    out.putInt(1) // reference id: the audio track
    out.putInt(1_000_000) // timescale: microseconds
    out.putLong(0) // earliest presentation time
    out.putLong(0) // first offset: the first subsegment follows this box
    out.putShort(0).putShort(sizes.size.toShort())
    var elapsed = 0L
    sizes.indices.forEach { i ->
        require(sizes[i] in 1..0x7FFFFFFF)
        val start = elapsed
        elapsed += durationsUs[i]
        out.putInt(sizes[i].toInt()) // reference type 0: media
        out.putInt((elapsed - start).toInt())
        out.putInt(0x90000000.toInt()) // starts with a stream access point of type 1
    }
    return out.array()
}

/**
 * Renames the `sidx` boxes at the start of an HLS segment to `free`, in place: a segment's own
 * one-entry index would otherwise replace the index of the whole file while it plays.
 */
internal fun neutralizeSegmentIndex(bytes: ByteArray) {
    var offset = 0
    while (offset + 8 <= bytes.size) {
        val size = ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)
        val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
        if (type == "moof" || type == "mdat" || size < 8) return
        if (type == "sidx") "free".toByteArray(Charsets.US_ASCII).copyInto(bytes, offset + 4)
        offset += size.toInt()
    }
}
