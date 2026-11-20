package `in`.melo

import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.playback.Playback
import `in`.melo.playback.WavePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MyWaveControllerTest {
    private fun track(id: String, artist: String = "Artist $id") =
        Music(id = id, title = "Song $id", subtitle = "$artist • Album", kind = MusicKind.Track, durationMs = 200_000)

    /** A player that holds the queue as a list and reports it like SpotifyPlayer does. */
    private class FakePlayer : WavePlayer {
        override val state = MutableStateFlow(Playback())
        val queue = mutableListOf<Music>()
        var index = 0
        var appended = 0

        override fun canPlay(track: Music) = track.kind == MusicKind.Track && !track.id.startsWith("unplayable")
        override fun startEndless(tracks: List<Music>): Boolean {
            queue.clear(); queue += tracks; index = 0; emit(); return true
        }
        override fun continueWith(tracks: List<Music>): Int {
            while (queue.size > index + 1) queue.removeAt(queue.lastIndex)
            queue += tracks; emit(); return tracks.size
        }
        override fun appendContext(tracks: List<Music>): Int {
            appended++; queue += tracks; emit(); return tracks.size
        }
        override fun remainingAhead() = (queue.size - 1 - index).coerceAtLeast(0)
        var endlessMarks = 0
        override fun keepEndless() { endlessMarks++ }

        fun emit(positionMs: Int = 0, error: String? = null, playWhenReady: Boolean = true, playing: Boolean? = null) {
            state.value = Playback(queue.toList(), index, playing = playing ?: (playWhenReady && error == null), positionMs = positionMs,
                durationMs = 200_000, error = error, playWhenReady = playWhenReady)
        }
        /** Plays the current track from [fromMs] to [toMs] in steps, as position updates arrive. */
        fun listen(fromMs: Int, toMs: Int, stepMs: Int = 1_000) {
            var at = fromMs
            while (at <= toMs) { emit(at); at += stepMs }
        }
        fun moveTo(target: Int) { index = target; emit() }
    }

    private class Wave(
        val player: FakePlayer = FakePlayer(),
        var coldSeed: Music? = Music("seed", "Seed song", "Seed artist • Album", MusicKind.Track),
        var opening: List<Music> = emptyList(),
        val liked: MutableSet<String> = mutableSetOf(),
    ) {
        val seeds = mutableListOf<Music>()
        var failing = false
        /** How many of the next requests fail. */
        var failNext = 0
        /** Holds requests until completed, as a slow network would. */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var batchSize = 12
        /** Every n-th candidate is a track the player cannot play; 0 for none. */
        var unplayableEvery = 0
        var clock = 0L
        private var counter = 0
        val online = MutableStateFlow(true)
        val controller = controller()

        fun controller() = MyWaveController(
            CoroutineScope(Dispatchers.Unconfined), player, online,
            similar = { seed ->
                seeds += seed
                gate?.await()
                if (failing) throw IOException("offline")
                if (failNext > 0) { failNext--; throw IOException("offline") }
                List(batchSize) {
                    counter++
                    val id = if (unplayableEvery > 0 && counter % unplayableEvery == 0) "unplayable$counter" else "t$counter"
                    Music(id, "Track $counter", "Band $counter • Album", MusicKind.Track, durationMs = 200_000)
                }
            },
            coldStart = { coldSeed?.let { WaveStart(it, if (opening.isEmpty()) WaveOrigin.HISTORY else WaveOrigin.MOOD, opening) } },
            recentlyHeard = { emptyList() },
            isLiked = { it.id in liked },
            describe = { "failed: ${it.message}" },
            now = { clock },
            log = {},
            idleReleaseMs = 50,
        )
    }

    @Test fun aColdStartPlaysTheFirstBatchAndTheQueueNeverRunsDry() {
        val wave = Wave()
        wave.controller.start()
        val ui = wave.controller.state.value
        assertTrue(ui.active)
        assertEquals(WaveOrigin.HISTORY, ui.origin)
        assertEquals(MyWaveController.FIRST_BATCH, wave.player.queue.size)
        assertEquals("seed", wave.seeds.single().id)
        repeat(30) { wave.player.moveTo(wave.player.queue.size - 2) }
        assertTrue("the wave grew to ${wave.player.queue.size}", wave.player.queue.size >= MyWaveController.FIRST_BATCH + 30 * MyWaveController.BATCH)
        assertEquals(wave.player.queue.size, wave.player.queue.map(Music::id).toSet().size)
    }

    @Test fun noBatchIsFetchedWhileEnoughTracksAreAhead() {
        val wave = Wave()
        wave.controller.start()
        wave.player.moveTo(MyWaveController.FIRST_BATCH - 2 - MyWaveController.REFILL_AT)
        assertEquals(0, wave.player.appended)
        wave.player.moveTo(MyWaveController.FIRST_BATCH - 1 - MyWaveController.REFILL_AT)
        assertEquals(1, wave.player.appended)
    }

    @Test fun aWaveFromThePlayingTrackKeepsItAndReplacesWhatFollows() {
        val wave = Wave(coldSeed = null)
        val playing = track("now")
        wave.player.queue += listOf(playing, track("album-2"), track("album-3"))
        wave.player.emit()
        wave.controller.start()
        assertEquals(WaveOrigin.TRACK, wave.controller.state.value.origin)
        assertEquals("now", wave.controller.state.value.seed?.id)
        assertEquals("now", wave.player.queue.first().id)
        assertTrue(wave.player.queue.drop(1).none { it.id.startsWith("album") })
    }

    @Test fun theMoodOpeningPlaysItsFirstTrackFirst() {
        val opening = List(20) { track("mood$it") }
        val wave = Wave(coldSeed = opening.first(), opening = opening)
        wave.controller.start()
        assertEquals("mood0", wave.player.queue.first().id)
        assertEquals(MyWaveController.FIRST_BATCH, wave.player.queue.size)
        assertTrue(wave.seeds.isEmpty())
    }

    @Test fun nothingToStartFromShowsAFailure() {
        val wave = Wave(coldSeed = null)
        wave.controller.start()
        assertFalse(wave.controller.state.value.active)
        assertFalse(wave.controller.running)
        assertNotNull(wave.controller.state.value.failure)
    }

    @Test fun aFailedRestartLeavesTheRunningWavePlaying() {
        val wave = Wave()
        wave.controller.start()
        wave.failing = true
        wave.controller.start()
        val ui = wave.controller.state.value
        assertTrue(ui.active)
        assertFalse(ui.loading)
        assertEquals("failed: offline", ui.failure)
        assertTrue(wave.controller.running)
        // Once the service answers again, the same wave goes on growing.
        wave.failing = false
        wave.player.moveTo(wave.player.queue.size - 2)
        assertEquals(1, wave.player.appended)
    }

    @Test fun aFailedBatchWaitsBeforeTryingAgain() {
        val wave = Wave()
        wave.controller.start()
        wave.failing = true
        val asked = wave.seeds.size
        wave.player.moveTo(wave.player.queue.size - 2)
        val afterFailure = wave.seeds.size
        assertTrue(afterFailure > asked)
        wave.player.emit(1_000); wave.player.emit(2_000)
        assertEquals(afterFailure, wave.seeds.size)
        wave.failing = false
        wave.clock += MyWaveController.RETRY_MS
        wave.player.emit(3_000)
        assertEquals(1, wave.player.appended)
    }

    @Test fun offlineTheWaveWaitsForTheConnection() {
        val wave = Wave()
        wave.controller.start()
        wave.online.value = false
        wave.player.moveTo(wave.player.queue.size - 1)
        assertEquals(0, wave.player.appended)
        // The queue ran out: the player sends nothing more, the connection alone wakes the wave.
        wave.online.value = true
        assertEquals(1, wave.player.appended)
    }

    @Test fun aTrackHeardThroughSeedsTheNextBatch() {
        val wave = Wave()
        wave.controller.start()
        val first = wave.player.queue[0]
        val second = wave.player.queue[1]
        wave.player.listen(0, 4_000) // Skipped after a few seconds.
        wave.player.moveTo(1)
        wave.player.listen(0, 70_000) // Kept.
        wave.player.moveTo(2)
        wave.player.moveTo(wave.player.queue.size - 2)
        assertEquals(second.id, wave.seeds.last().id)
        assertNotEquals(first.id, wave.seeds.last().id)
    }

    @Test fun aSeekDoesNotCountAsListening() {
        val wave = Wave()
        wave.controller.start()
        val queue = wave.player.queue
        wave.player.moveTo(1)
        wave.player.listen(0, 1_000)
        wave.player.emit(150_000) // A seek far into the track, not listening.
        wave.player.moveTo(2)
        // Kept, the second track would seed the next batch; skipped, it never does.
        wave.player.moveTo(queue.size - 2)
        assertEquals(queue[0].id, wave.seeds.last().id)
        wave.player.moveTo(wave.player.queue.size - 2)
        assertTrue(wave.seeds.none { it.id == queue[1].id })
    }

    @Test fun aTrackThatFailedOrNeverPlayedIsNotASkip() {
        val wave = Wave()
        wave.controller.start()
        val queue = wave.player.queue
        wave.player.listen(0, 2_000)
        wave.player.emit(2_000, error = "This track cannot be played.")
        wave.player.moveTo(1) // The player moves past the failed track.
        wave.player.moveTo(2) // Never started: no position progress at all.
        wave.player.moveTo(queue.size - 2)
        // Neither counted as skipped: the first of them seeds the next batch.
        assertEquals(queue[0].id, wave.seeds.last().id)
    }

    @Test fun aKeptWaveLetsGoOnceThePlayerStaysPaused() {
        val wave = Wave()
        wave.controller.start()
        val idle = java.util.concurrent.CountDownLatch(1)
        wave.controller.onIdle = { idle.countDown() }
        assertFalse(idle.await(150, java.util.concurrent.TimeUnit.MILLISECONDS))
        wave.player.emit(1_000, playWhenReady = false)
        assertTrue(idle.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun aPauseShorterThanTheGraceKeepsTheWave() {
        val wave = Wave()
        wave.controller.start()
        val idle = java.util.concurrent.CountDownLatch(1)
        wave.controller.onIdle = { idle.countDown() }
        wave.player.emit(1_000, playWhenReady = false)
        wave.player.emit(1_000, playWhenReady = true)
        assertFalse(idle.await(300, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test fun aKeptWaveLetsGoOnceItsQueueEnded() {
        val wave = Wave()
        wave.controller.start()
        val idle = java.util.concurrent.CountDownLatch(1)
        wave.controller.onIdle = { idle.countDown() }
        // Ended or failed, the player stays "play when ready" yet nothing plays.
        wave.player.emit(200_000, playWhenReady = true, playing = false)
        assertTrue(idle.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun aFailedRestartAsksAgainForTheWaveThatGoesOn() {
        val wave = Wave()
        wave.controller.start()
        wave.failNext = MyWaveController.SEED_ATTEMPTS
        wave.player.moveTo(wave.player.queue.size - 1) // The batch fails: a retry waits.
        assertEquals(0, wave.player.appended)
        wave.failNext = MyWaveController.SEED_ATTEMPTS
        wave.controller.start() // Fails too; the queue already ended and sends nothing more.
        assertTrue(wave.controller.state.value.active)
        assertEquals(1, wave.player.appended)
    }

    @Test fun aTrackThatRecoveredFromAnErrorCanBeSkipped() {
        val wave = Wave()
        wave.controller.start()
        val queue = wave.player.queue
        wave.player.listen(0, 2_000)
        wave.player.emit(2_000, error = "The connection was interrupted.")
        wave.player.emit(3_000) // Playing on.
        wave.player.moveTo(1) // Skipped by the listener.
        wave.player.moveTo(queue.size - 2)
        assertEquals(queue[1].id, wave.seeds.last().id)
    }

    @Test fun anAdoptedWaveWaitsForItsPlayerBeforeTheNextBatch() {
        val wave = Wave()
        wave.controller.start()
        wave.online.value = false
        wave.player.moveTo(wave.player.queue.size - 1)
        val handOver = wave.controller.handOver()!!
        val next = wave.controller()
        next.adopt(handOver)
        assertEquals(0, wave.player.appended)
        wave.online.value = true
        assertEquals(1, wave.player.appended)
    }

    @Test fun aCallHoldingPlaybackBackIsNotIdle() {
        val wave = Wave()
        wave.controller.start()
        val idle = java.util.concurrent.CountDownLatch(1)
        wave.controller.onIdle = { idle.countDown() }
        wave.player.state.value = wave.player.state.value.copy(playing = false, suppressed = true)
        assertFalse(idle.await(300, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test fun clearingTheQueueWhileTheWaveStartsFromItCancelsTheStart() {
        val wave = Wave(coldSeed = null)
        wave.player.queue += track("now")
        wave.player.emit()
        wave.gate = kotlinx.coroutines.CompletableDeferred()
        wave.controller.start()
        assertTrue(wave.controller.state.value.loading)
        wave.player.queue.clear()
        wave.player.state.value = Playback()
        wave.gate!!.complete(Unit)
        assertTrue(wave.player.queue.isEmpty())
        assertFalse(wave.controller.state.value.active)
        assertFalse(wave.controller.running)
    }

    @Test fun aColdStartIsNotCancelledByTheEmptyQueueItStartsFrom() {
        val wave = Wave()
        wave.gate = kotlinx.coroutines.CompletableDeferred()
        wave.controller.start()
        wave.player.state.value = Playback(shuffle = true) // The player reports its empty queue.
        wave.gate!!.complete(Unit)
        assertTrue(wave.controller.state.value.active)
        assertEquals(MyWaveController.FIRST_BATCH, wave.player.queue.size)
    }

    @Test fun aWaveThatFailsToStartLetsGoOfItsKeeper() {
        val wave = Wave(coldSeed = null)
        var stopped = 0
        wave.controller.onStopped = { stopped++ }
        wave.controller.start()
        assertEquals(1, stopped)
    }

    @Test fun aLikedTrackSeedsTheNextBatchFirst() {
        val wave = Wave()
        wave.controller.start()
        val liked = wave.player.queue[3]
        wave.liked += liked.id
        wave.player.moveTo(3)
        wave.player.moveTo(4)
        wave.player.moveTo(wave.player.queue.size - 2)
        assertEquals(liked.id, wave.seeds.last().id)
    }

    @Test fun clearingTheQueueEndsTheWave() {
        val wave = Wave()
        wave.controller.start()
        wave.player.queue.clear()
        wave.player.state.value = Playback()
        assertFalse(wave.controller.running)
        assertFalse(wave.controller.state.value.active)
    }

    @Test fun stoppingTellsWhoeverKeepsTheWave() {
        val wave = Wave()
        var stopped = 0
        wave.controller.onStopped = { stopped++ }
        wave.controller.start()
        wave.controller.stop()
        wave.controller.stop()
        assertEquals(1, stopped)
        assertFalse(wave.controller.state.value.active)
    }

    @Test fun aHandedOverWaveGoesOnInTheNextControllerOnly() {
        val wave = Wave()
        wave.controller.start()
        val handOver = wave.controller.handOver()
        assertNotNull(handOver)
        assertFalse(wave.controller.running)
        val next = wave.controller()
        next.adopt(handOver!!)
        assertTrue(next.state.value.active)
        assertEquals(1, wave.player.endlessMarks)
        wave.player.moveTo(wave.player.queue.size - 2)
        assertEquals(1, wave.player.appended)
        val ids = wave.player.queue.map(Music::id)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun unplayableCandidatesNeverJoin() {
        val wave = Wave()
        wave.unplayableEvery = 3
        wave.controller.start()
        repeat(5) { wave.player.moveTo(wave.player.queue.size - 2) }
        assertTrue(wave.player.queue.size > MyWaveController.FIRST_BATCH)
        assertTrue(wave.player.queue.none { it.id.startsWith("unplayable") })
    }
}
