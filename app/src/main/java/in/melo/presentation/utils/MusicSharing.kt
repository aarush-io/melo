package `in`.melo.presentation.utils

import android.content.Context
import android.content.Intent
import `in`.melo.data.Music

/** Public link of a Spotify, YouTube Music or SoundCloud entity; null for local collections such as the liked songs. */
fun Music.spotifyShareUrl(): String? {
    if (id.startsWith(`in`.melo.data.soundcloud.SC_LIKES_PREFIX)) return ownerUsername?.let { "https://soundcloud.com/$it/likes" }
    if (`in`.melo.data.soundcloud.isSoundCloudId(id)) return `in`.melo.data.soundcloud.SoundCloud.pageUrl(id)
        ?: ownerUsername?.takeIf { kind == `in`.melo.data.MusicKind.Artist }?.let { "https://soundcloud.com/$it" }
    if (id.startsWith(`in`.melo.data.youtube.YTM_TRACK_PREFIX)) return "https://music.youtube.com/watch?v=" + id.removePrefix(`in`.melo.data.youtube.YTM_TRACK_PREFIX)
    if (id.startsWith(`in`.melo.data.youtube.YTM_PLAYLIST_PREFIX)) return "https://music.youtube.com/playlist?list=" + id.removePrefix(`in`.melo.data.youtube.YTM_PLAYLIST_PREFIX)
    val parts = id.split(':')
    if (parts.size != 3 || parts[0] != "spotify") return null
    return when (parts[1]) {
        "track", "album", "artist", "playlist" -> "https://open.spotify.com/${parts[1]}/${parts[2]}"
        else -> null
    }
}

fun Context.shareMusic(item: Music) {
    val url = item.spotifyShareUrl() ?: return
    startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            putExtra(Intent.EXTRA_TEXT, url)
        },
        null,
    ))
}
