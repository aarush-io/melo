package `in`.melo

import android.os.SystemClock
import `in`.melo.data.Music
import `in`.melo.data.WaveSession
import `in`.melo.data.diagnostics.DiagnosticLog
import `in`.melo.data.isWaveKeep
import `in`.melo.data.isWaveSkip
import `in`.melo.playback.Playback
import `in`.melo.playback.WavePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a wave started from. */
enum class WaveOrigin { TRACK, HISTORY, LIKES, MOOD }

/** What the My Wave screen shows. */
data class WaveUi(
    val active: Boolean = false,
    val loading: Boolean = false,
    /** The track the last batch was built around. */
    val seed: Music? = null,
    val origin: WaveOrigin? = null,
    val failure: String? = null,
)

/** How a wave starts when nothing plays: its seed, and the tracks to open with when there are some. */
internal class WaveStart(val seed: Music, val origin: WaveOrigin, val opening: List<Music> = emptyList())

/** No track to start from, or no similar track found for any seed. */
internal class WaveEmpty : Exception("My Wave found nothing to play.")

/**
 * A running wave passed from one controller to the next, when the app's screen is rebuilt.
 * A wave still starting is not passed on: the new screen cannot tell yet what plays, and the
 * listener starts it again from the tab.
 */
internal class WaveHandOver(val session: WaveSession, val ui: WaveUi)

/**
 * Whether playback goes on by itself: meant to play, without an error, and playing, loading or
 * only held back by a call. An ended queue or a failed track keeps "play when ready" set, yet
 * nothing plays.
 */
internal fun wavePlaying(playback: Playback): Boolean =
    playback.playWhenReady && playback.error == null && (playback.playing || playback.buffering || playback.suppressed)

/**
 * Plays My Wave: tracks similar to what plays now, appended in small batches as playback nears
 * the end of the queue, so it never ends. It stops as soon as the listener plays something else.
 * The rules deciding which tracks join, and what the next batch is built around, are in [WaveSession].
 */
internal class MyWaveController(
    private val scope: CoroutineScope,
    private val player: WavePlayer,
    private val online: StateFlow<Boolean>,
    /** Tracks similar to a seed, from the service the seed belongs to. */
    private val similar: suspend (Music) -> List<Music>,
    /** A start when nothing is loaded in the player. */
    private val coldStart: suspend () -> WaveStart?,
    /** Tracks heard lately, kept out of the wave while there is anything else to play. */
    private val recentlyHeard: suspend () -> List<Music>,
    private val isLiked: (Music) -> Boolean,
    private val describe: (Exception) -> String,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { DiagnosticLog.record("my-wave", it) },
    private val idleReleaseMs: Long = IDLE_RELEASE_MS,
) {
    private val mutableState = MutableStateFlow(WaveUi())
    val state: StateFlow<WaveUi> = mutableState.asStateFlow()

    /** Called once the wave stops, for whoever keeps it running in the background. */
    var onStopped: (() -> Unit)? = null

    /**
     * Set while the wave is kept in the background: called once playback has stayed paused for
     * [idleReleaseMs], so the playback service is free to stop.
     */
    var onIdle: (() -> Unit)? = null
        set(value) { field = value; idleJob?.cancel(); idleJob = null; if (value != null) checkIdle(player.state.value) }

    private var session: WaveSession? = null
    private var generation = 0
    private var startJob: Job? = null
    private var refillJob: Job? = null
    private var retryJob: Job? = null
    private var idleJob: Job? = null
    /** A batch is on its way; set before the job starts, which may run at once. */
    private var refilling = false
    private var retryAtMs = 0L

    // The last state seen and the time the current track was really heard, to tell a skip from a
    // track the listener kept: positions alone mislead after a seek.
    private var lastTrack: Music? = null
    private var lastNextId: String? = null
    private var lastPositionMs = 0
    private var lastDurationMs = 0
    private var listenedMs = 0
    /** The current track failed: moving on from it is the player's doing, not a skip. */
    private var lastFailed = false
    /** The track a start in progress continues from; null for a start from nothing. */
    private var startingFrom: Music? = null

    private val watcher = scope.launch {
        launch { player.state.collect(::observe) }
        // A queue that ran out sends no more updates: the connection coming back must wake the wave.
        launch { online.collect { if (it) session?.let(::maybeRefill) } }
    }

    /** Whether a wave plays, or is starting. */
    val running: Boolean get() = session != null || mutableState.value.loading

    /**
     * Starts a new wave: from the track loaded in the player, which keeps playing, or, when there
     * is none, from the listening history, the liked tracks, then music for the time of day.
     * A wave that fails to start again keeps playing as it was.
     */
    fun start() {
        val current = ++generation
        cancelWork()
        val previous = session
        val previousUi = mutableState.value
        val playing = player.state.value.track?.takeIf(player::canPlay)
        startingFrom = playing
        mutableState.update { it.copy(loading = true, failure = null) }
        startJob = scope.launch {
            try {
                val heard = recentlyHeard()
                val start = playing?.let { WaveStart(it, WaveOrigin.TRACK) } ?: coldStart() ?: throw WaveEmpty()
                val wave = WaveSession(start.seed, heard)
                val first = if (start.opening.isNotEmpty()) opening(wave, start) else batch(wave, FIRST_BATCH)
                if (first.isEmpty()) throw WaveEmpty()
                if (current != generation) return@launch
                session = wave
                forgetObservation()
                retryAtMs = 0L
                val started = if (playing != null) player.continueWith(first) > 0 else player.startEndless(first)
                if (!started) { session = previous; throw WaveEmpty() }
                mutableState.value = WaveUi(active = true, seed = start.seed, origin = start.origin)
                log("started from ${start.origin}, ${first.size} tracks")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current != generation) return@launch
                log("start failed: ${failure.javaClass.simpleName} \"${failure.message}\"")
                val ui = if (previous != null) previousUi.copy(loading = false) else WaveUi()
                mutableState.value = ui.copy(failure = describe(failure))
                val kept = session
                // Nothing runs any more: whoever keeps this wave can let it go.
                if (kept == null) onStopped?.invoke()
                // The wave that goes on lost its pending batch to this start: ask again, even if
                // its queue already ended and the player sends no more updates.
                else { retryAtMs = 0L; maybeRefill(kept) }
            }
        }
    }

    /** The mood opening: its first track, then the others shuffled, as a playlist would play. */
    private fun opening(wave: WaveSession, start: WaveStart): List<Music> {
        val seed = listOf(start.seed).filter(player::canPlay)
        val rest = start.opening.filter { it.id != start.seed.id && player.canPlay(it) }.shuffled()
        return seed + wave.accept(rest, FIRST_BATCH - seed.size, relaxed = true)
    }

    /** Ends the wave: the queue stays as it is and stops growing. */
    fun stop() {
        if (!running) return
        generation++
        cancelWork()
        session = null
        mutableState.value = WaveUi()
        onStopped?.invoke()
    }

    fun dismissFailure() = mutableState.update { it.copy(failure = null) }

    /** Stops watching the player and returns the running wave, for the controller that replaces this one. */
    fun handOver(): WaveHandOver? {
        watcher.cancel()
        generation++
        cancelWork()
        idleJob?.cancel(); idleJob = null
        val kept = session
        session = null
        if (kept == null) {
            if (mutableState.value.loading) log("start dropped: the app closed before the first batch")
            return null
        }
        return WaveHandOver(kept, mutableState.value.copy(loading = false))
    }

    /**
     * Goes on with a wave another controller ran, in this controller's player. Its next batch
     * comes with the first update of the player, once its connection is up.
     */
    fun adopt(handOver: WaveHandOver) {
        val kept = handOver.session
        generation++
        cancelWork()
        session = kept
        // The player connection is new: it must know again that its context only grows.
        player.keepEndless()
        forgetObservation()
        retryAtMs = 0L
        mutableState.value = handOver.ui
        log("resumed, ${kept.size} tracks so far")
    }

    private fun cancelWork() {
        startJob?.cancel(); refillJob?.cancel(); retryJob?.cancel()
        refilling = false
    }

    private fun forgetObservation() {
        lastTrack = null; lastNextId = null; lastPositionMs = 0; lastDurationMs = 0; listenedMs = 0; lastFailed = false
    }

    private fun observe(playback: Playback) {
        if (onIdle != null) checkIdle(playback)
        val track = playback.track
        if (track == null && playback.queue.isEmpty() && !playback.buffering) {
            // The queue was cleared: there is nothing left to continue, nor to start from.
            if (session != null || (mutableState.value.loading && startingFrom != null)) stop()
            return
        }
        val wave = session ?: return
        if (track == null) return
        val previous = lastTrack
        if (previous != null && previous.id != track.id) {
            if (wave.contains(previous)) judge(wave, previous, forward = lastNextId == track.id)
            listenedMs = 0
            lastFailed = false
        } else if (previous != null) {
            // Only steady progress counts as listening: a jump is a seek.
            val step = playback.positionMs - lastPositionMs
            if (step in 1..LISTEN_STEP_MS) {
                listenedMs += step
                // Playing on after an error it recovered from: leaving it is the listener's choice again.
                if (playback.error == null) lastFailed = false
            }
        }
        if (playback.error != null) lastFailed = true
        lastTrack = track
        lastNextId = playback.queue.getOrNull(playback.index + 1)?.id
        lastPositionMs = playback.positionMs
        lastDurationMs = playback.durationMs
        maybeRefill(wave)
    }

    /** What leaving [previous] says about it: liked, kept, skipped, or nothing. */
    private fun judge(wave: WaveSession, previous: Music, forward: Boolean) {
        val endedNaturally = forward && lastDurationMs > 0 && lastPositionMs >= lastDurationMs - END_MARGIN_MS
        val heardThrough = endedNaturally && !isWaveSkip(listenedMs, lastDurationMs)
        // A track that never played, or failed, was moved past by the player, not skipped by the listener.
        val skippable = forward && !endedNaturally && !lastFailed && listenedMs > 0
        when {
            isLiked(previous) -> wave.kept(previous, liked = true)
            skippable && isWaveSkip(listenedMs, lastDurationMs) -> wave.skipped(previous)
            isWaveKeep(listenedMs, lastDurationMs, heardThrough) -> wave.kept(previous)
        }
    }

    /** Fetches the next batch when the queue runs low, unless one is on its way or a retry waits. */
    private fun maybeRefill(wave: WaveSession) {
        if (session !== wave || refilling || mutableState.value.loading) return
        if (player.remainingAhead() > REFILL_AT || !online.value) return
        if (now() < retryAtMs) return
        refill(wave)
    }

    private fun refill(wave: WaveSession) {
        val current = generation
        refilling = true
        refillJob = scope.launch {
            try {
                val tracks = batch(wave, BATCH)
                if (current != generation || session !== wave) return@launch
                val added = if (tracks.isEmpty()) 0 else player.appendContext(tracks)
                if (added == 0) { retryLater(wave); return@launch }
                log("added $added tracks, ${wave.size} in the wave")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                log("refill failed: ${failure.javaClass.simpleName} \"${failure.message}\"")
                if (current == generation) retryLater(wave)
            } finally {
                if (current == generation) refilling = false
            }
        }
    }

    /** Tries again after [RETRY_MS], even if the player sends no update by then (its queue may have ended). */
    private fun retryLater(wave: WaveSession) {
        retryAtMs = now() + RETRY_MS
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RETRY_MS)
            maybeRefill(wave)
        }
    }

    /** While kept in the background, lets the wave go after playback has stayed stopped for a while. */
    private fun checkIdle(playback: Playback) {
        if (wavePlaying(playback)) { idleJob?.cancel(); idleJob = null; return }
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            delay(idleReleaseMs)
            onIdle?.invoke()
        }
    }

    /**
     * Up to [limit] new tracks: similar to the next seeds in turn, under the strict rules first;
     * then whatever was found under relaxed ones, so the wave keeps going. A failure is thrown
     * only when every seed failed and nothing was found.
     */
    private suspend fun batch(wave: WaveSession, limit: Int): List<Music> {
        val found = ArrayList<Music>()
        var failure: Exception? = null
        repeat(SEED_ATTEMPTS) {
            val seed = wave.nextSeed()
            val candidates = try { similar(seed).filter(player::canPlay) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure = failure ?: error; emptyList() }
            found += candidates
            val accepted = wave.accept(candidates, limit)
            if (accepted.isNotEmpty()) {
                // Once the wave runs, it follows the tracks the listener keeps: name the one it follows now.
                mutableState.update { if (it.active) it.copy(seed = seed, origin = WaveOrigin.TRACK) else it }
                return accepted
            }
        }
        val relaxed = wave.accept(found, limit, relaxed = true)
        if (relaxed.isEmpty()) failure?.let { throw it }
        return relaxed
    }

    companion object {
        const val FIRST_BATCH = 12
        const val BATCH = 10
        /** A batch is fetched when this many tracks, or fewer, are left after the current one. */
        const val REFILL_AT = 5
        const val SEED_ATTEMPTS = 3
        const val RETRY_MS = 30_000L
        const val END_MARGIN_MS = 2_000
        /** The longest step between two position updates that still counts as listening. */
        const val LISTEN_STEP_MS = 1_500
        /** How long a wave kept in the background waits on a paused player before letting go. */
        const val IDLE_RELEASE_MS = 120_000L
    }
}
