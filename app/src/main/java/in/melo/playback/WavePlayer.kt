package `in`.melo.playback

import `in`.melo.data.Music
import kotlinx.coroutines.flow.StateFlow

/** What My Wave needs from the player: its state, and an endless context it can grow. */
interface WavePlayer {
    val state: StateFlow<Playback>
    /** Whether the player can play [track]: the wave only keeps tracks it will be able to add. */
    fun canPlay(track: Music): Boolean
    /** Plays [tracks] from the first one as an endless context. Returns false when nothing could start. */
    fun startEndless(tracks: List<Music>): Boolean
    /** Puts [tracks] after the current track as an endless context. Returns how many follow it. */
    fun continueWith(tracks: List<Music>): Int
    /** Adds [tracks] at the end of the context. Returns how many were added. */
    fun appendContext(tracks: List<Music>): Int
    /** How many tracks of the context follow the current one. */
    fun remainingAhead(): Int
    /** Marks the loaded context as endless, for a wave taken over from an earlier player connection. */
    fun keepEndless()
}
