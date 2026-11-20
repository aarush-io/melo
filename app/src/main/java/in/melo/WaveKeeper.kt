package `in`.melo

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread

/**
 * Keeps My Wave to one per process, and growing while its screen is gone and the playback
 * service plays on: swiping the app away from Recents clears [MeloModel], but the music goes on
 * and the wave must not run dry.
 *
 * The cleared model leaves its wave here with the player connection it uses. The next model
 * takes the wave back as soon as it starts. A kept wave lets go of the connection, so the service
 * can stop, when its queue is cleared or playback stays paused.
 */
@MainThread
internal object WaveKeeper {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** The waves of the models alive now; the player and its queue are shared by all of them. */
    private val live = LinkedHashSet<MyWaveController>()
    private var kept: MyWaveController? = null
    private var release: (() -> Unit)? = null

    val holds: Boolean get() = kept != null

    fun register(wave: MyWaveController) { live += wave }
    fun unregister(wave: MyWaveController) { live -= wave }

    /** One wave plays at a time: stops every wave but [except], the kept one included. */
    fun stopAll(except: MyWaveController? = null) {
        live.filter { it !== except }.forEach(MyWaveController::stop)
        if (kept != null && kept !== except) drop()
    }

    /** Keeps [wave] running; [release] closes what it uses once it is taken back or lets go. */
    fun keep(wave: MyWaveController, release: () -> Unit) {
        drop()
        live -= wave
        kept = wave
        this.release = release
        // Posted: the wave stops from inside a player update, where closing the player must wait.
        val letGo = { main.post { if (kept === wave) drop() }; Unit }
        wave.onStopped = letGo
        wave.onIdle = letGo
    }

    /** The kept wave, for a new controller to go on with; the old one stops watching the player. */
    fun takeBack(): WaveHandOver? {
        val wave = kept ?: return null
        val handOver = detach(wave)
        return handOver
    }

    private fun drop() {
        kept?.let(::detach)
    }

    private fun detach(wave: MyWaveController): WaveHandOver? {
        wave.onStopped = null
        wave.onIdle = null
        val handOver = wave.handOver()
        kept = null
        release?.invoke(); release = null
        return handOver
    }
}
