package `in`.melo.presentation.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import `in`.melo.MeloModel
import `in`.melo.R
import `in`.melo.WaveOrigin
import `in`.melo.WaveUi
import `in`.melo.data.Music
import `in`.melo.data.model.toSong
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.meloPanel
import `in`.melo.presentation.components.subcomps.EnhancedSongListItem
import `in`.melo.presentation.utils.rememberReducedMotion
import `in`.melo.ui.theme.GoogleSansRounded
import `in`.melo.ui.theme.MeloLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How many upcoming wave tracks the tab lists; the queue sheet shows the rest. */
private const val UPCOMING_SHOWN = 12

/**
 * The My Wave tab: one control that starts an endless stream of tracks like the one playing,
 * the reason for the current wave, and what it plays next.
 */
@Composable
fun MyWaveScreen(model: MeloModel, navController: NavHostController, paddingValues: PaddingValues) {
    val ui = model.ui
    val wave by model.myWave.collectAsStateWithLifecycle()
    // Only the queue and the play state: the position moves several times a second.
    val playbackFlow = model.player.state
    val upNext by remember(playbackFlow) {
        playbackFlow.map { it.queue to it.index }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(emptyList<Music>() to 0)
    val playerPlaying by remember(playbackFlow) {
        playbackFlow.map { it.playing }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
    val playing = wave.active && playerPlaying
    val upcoming = remember(upNext, wave.active) {
        val (queue, index) = upNext
        if (wave.active) queue.drop(index + 1).take(UPCOMING_SHOWN) else emptyList()
    }
    val bottom = paddingValues.calculateBottomPadding() + MiniPlayerHeight + MeloLayout.Space6

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.nav_bar_my_wave),
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = MeloLayout.PageGutter + MeloLayout.Space2, end = MeloLayout.PageGutter, top = MeloLayout.Space4)
                    .semantics { heading() },
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineLarge,
                letterSpacing = (-0.8).sp,
            )
        }
        item(key = "hero") {
            WaveHero(
                wave = wave,
                playing = playing,
                onToggle = { if (wave.active) ui.playPause() else model.startMyWave() },
                onShake = model::startMyWave,
                onDismissFailure = model::dismissMyWaveFailure,
                modifier = Modifier.padding(horizontal = MeloLayout.PageGutter, vertical = MeloLayout.Space4),
            )
        }
        if (upcoming.isNotEmpty()) {
            item(key = "next-title") {
                Text(
                    text = stringResource(R.string.my_wave_next_title),
                    modifier = Modifier
                        .padding(horizontal = MeloLayout.PageGutter + MeloLayout.Space2, vertical = MeloLayout.Space2)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            itemsIndexed(upcoming, key = { position, track -> "next-$position-${track.id}" }) { _, track ->
                val song = remember(track) { track.toSong() }
                EnhancedSongListItem(
                    modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                    song = song,
                    isPlaying = false,
                    showMoreOptionsButton = false,
                    showSourceBadge = true,
                    onMoreOptionsClick = {},
                    onClick = {
                        // Looked up when tapped: the queue may have grown or moved since it was drawn.
                        val live = model.player.state.value
                        val at = live.queue.withIndex().firstOrNull { it.index > live.index && it.value.id == track.id }?.index
                        if (at != null) model.player.seekToItem(at)
                    },
                )
            }
        }
    }
}

@Composable
private fun WaveHero(
    wave: WaveUi,
    playing: Boolean,
    onToggle: () -> Unit,
    onShake: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .meloPanel(MeloLayout.PanelShape)
            .padding(MeloLayout.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            WaveRings(moving = playing || wave.loading, modifier = Modifier.fillMaxSize())
            val toggleLabel = stringResource(if (playing) R.string.my_wave_pause else R.string.my_wave_play)
            FilledIconButton(
                onClick = onToggle,
                enabled = !wave.loading,
                modifier = Modifier.size(MeloLayout.ButtonLarge + MeloLayout.Space6),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (wave.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(MeloLayout.IconLarge),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = toggleLabel,
                        modifier = Modifier.size(MeloLayout.IconLarge + MeloLayout.Space2),
                    )
                }
            }
        }
        Spacer(Modifier.height(MeloLayout.Space4))
        Text(
            text = waveReason(wave),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val failure = wave.failure
        if (failure != null) {
            Spacer(Modifier.height(MeloLayout.Space3))
            Row(
                modifier = Modifier.fillMaxWidth().meloPanel(MeloLayout.CardShape, MaterialTheme.colorScheme.errorContainer)
                    .padding(start = MeloLayout.Space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = failure,
                    modifier = Modifier.weight(1f).padding(vertical = MeloLayout.Space3),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                androidx.compose.material3.IconButton(onClick = onDismissFailure, modifier = Modifier.size(MeloLayout.ControlSize)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        if (wave.active) {
            Spacer(Modifier.height(MeloLayout.Space4))
            OutlinedButton(onClick = onShake, enabled = !wave.loading, modifier = Modifier.height(MeloLayout.ButtonMedium)) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(MeloLayout.IconMedium))
                Spacer(Modifier.size(MeloLayout.Space2))
                Text(stringResource(R.string.my_wave_shake))
            }
        }
    }
}

@Composable
private fun waveReason(wave: WaveUi): String = when {
    wave.loading -> stringResource(R.string.my_wave_loading)
    !wave.active -> stringResource(R.string.my_wave_hint)
    wave.origin == WaveOrigin.HISTORY -> stringResource(R.string.my_wave_from_history)
    wave.origin == WaveOrigin.LIKES -> stringResource(R.string.my_wave_from_likes)
    wave.origin == WaveOrigin.MOOD -> stringResource(R.string.my_wave_from_mood)
    wave.seed != null -> stringResource(R.string.my_wave_from_track, wave.seed.title)
    else -> stringResource(R.string.my_wave_subtitle)
}

/** Three rings of dots, after Melo's dotted mark; they breathe only while the wave plays. */
@Composable
private fun WaveRings(moving: Boolean, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val animate = moving && !reduced
    val ink = MaterialTheme.colorScheme.onSurface
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "wave")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 6000, easing = LinearEasing), RepeatMode.Restart),
            label = "wave-phase",
        ).value
    } else 0f
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f
        val rings = listOf(0.52f to 18, 0.74f to 26, 0.96f to 34)
        rings.forEachIndexed { ring, (fraction, dots) ->
            val turn = phase * 2f * PI.toFloat() * (if (ring % 2 == 0) 1f else -1f)
            for (dot in 0 until dots) {
                val angle = dot * 2f * PI.toFloat() / dots + turn
                val swell = if (animate) 0.5f + 0.5f * sin(angle * 3f - phase * 2f * PI.toFloat() * 2f) else 0.5f
                val radius = outer * fraction * (1f + 0.035f * swell)
                val point = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
                val alpha = (0.14f + 0.12f * (2 - ring) + 0.22f * swell).coerceIn(0f, 1f)
                drawCircle(Color(ink.red, ink.green, ink.blue, alpha), radius = outer * (0.022f - ring * 0.003f), center = point)
            }
        }
    }
}
