package `in`.melo.presentation.screens

import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import `in`.melo.presentation.components.meloPanel
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.R
import `in`.melo.MeloModel
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.model.SearchFilterType
import `in`.melo.data.model.Song
import `in`.melo.data.model.toAlbum
import `in`.melo.data.model.toArtist
import `in`.melo.data.model.toPlaylist
import `in`.melo.data.model.toSong
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.MusicOptionsHost
import `in`.melo.presentation.components.SongOptionsHost
import `in`.melo.presentation.components.resolveMainScreenBottomGradientHeight
import `in`.melo.presentation.components.subcomps.EnhancedSongListItem
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely
import `in`.melo.presentation.navigation.openMusic
import kotlinx.coroutines.flow.distinctUntilChanged

private fun SearchFilterType.kind(): MusicKind? = when (this) {
    SearchFilterType.ALL -> null
    SearchFilterType.SONGS -> MusicKind.Track
    SearchFilterType.ALBUMS -> MusicKind.Album
    SearchFilterType.ARTISTS -> MusicKind.Artist
    SearchFilterType.PLAYLISTS -> MusicKind.Playlist
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    model: MeloModel,
    navController: NavHostController,
    paddingValues: PaddingValues,
    onSearchBarActiveChange: (Boolean) -> Unit,
) {
    val ui = model.ui
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val history by ui.searchHistory.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val navBarCompactMode by ui.navBarCompactMode.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf(discovery.query) }
    var filter by rememberSaveable { mutableStateOf(SearchFilterType.ALL) }
    var selected by remember { mutableStateOf<Song?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    // Opened to type: the field takes the focus, unless results of an earlier search are shown.
    LaunchedEffect(Unit) {
        if (searchQuery.isBlank()) runCatching { fieldFocus.requestFocus(); keyboard?.show() }
    }
    val statusBarTopInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val colorScheme = MaterialTheme.colorScheme
    val appSettings by ui.settings.collectAsStateWithLifecycle()
    val showSources = appSettings.showSearchSources
    // With the choice hidden, every search reads all sources.
    LaunchedEffect(showSources) {
        if (!showSources && discovery.source != `in`.melo.SearchSource.All) model.setSearchSource(`in`.melo.SearchSource.All)
    }
    val results = remember(discovery.results, filter) {
        val kind = filter.kind()
        discovery.results.filter { kind == null || it.kind == kind }
    }
    val listState = rememberLazyListState()
    val open: (Music) -> Unit = {
        ui.rememberSearchResult(it)
        navController.openMusic(ui, it)
    }
    val playFromHistory: (Music) -> Unit = { item ->
        ui.rememberSearchResult(item)
        if (item.kind == MusicKind.Track) ui.playTrack(item)
        else navController.openMusic(ui, item)
    }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val showOptions: (Music) -> Unit = {
        // The long-click handler of the card already vibrates.
        optionsFor = it
    }
    LaunchedEffect(listState, results.size, discovery.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (discovery.hasMore && last >= listState.layoutInfo.totalItemsCount - 6) model.searchNext() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = statusBarTopInset + 12.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search opens from the Home header, over the tab it was opened from.
                IconButton(
                    modifier = Modifier.padding(bottom = MeloLayout.Space1).meloPanel(CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = {
                        keyboard?.hide()
                        // A second tap during the exit animation would pop the screen below as well.
                        if (navController.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) {
                            navController.popBackStack()
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                val inputColors = SearchBarDefaults.inputFieldColors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = MeloAlpha.Secondary),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
                Box(Modifier.weight(1f).background(Color.Transparent)) {
                    DockedSearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = {
                                    searchQuery = it
                                    model.search(it)
                                },
                                onSearch = { query ->
                                    model.search(query, false)
                                    keyboard?.hide()
                                },
                                modifier = Modifier.focusRequester(fieldFocus),
                                expanded = false,
                                onExpandedChange = { onSearchBarActiveChange(false) },
                                placeholder = {
                                    Text(
                                        stringResource(R.string.search_placeholder),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = stringResource(R.string.search_cd_search_icon),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                searchQuery = ""
                                                model.search("")
                                            },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = MeloAlpha.Divider))
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = stringResource(R.string.search_cd_clear_search_query),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                colors = inputColors
                            )
                        },
                        expanded = false,
                        onExpandedChange = {},
                        modifier = Modifier.meloPanel(MeloLayout.PillShape),
                        colors = SearchBarDefaults.colors(
                            containerColor = Color.Transparent,
                            dividerColor = MaterialTheme.colorScheme.primary.copy(alpha = MeloAlpha.Divider),
                            inputFieldColors = inputColors
                        ),
                        content = {}
                    )
                }
                IconButton(
                    modifier = Modifier.padding(bottom = MeloLayout.Space1).meloPanel(CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = { navController.navigateSafely(Screen.Settings.route) }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_settings_24),
                        contentDescription = stringResource(R.string.library_cd_open_settings)
                    )
                }
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (searchQuery.isBlank()) {
                    if (history.isNotEmpty()) {
                        SearchResultHistoryList(
                            items = history,
                            onOpen = playFromHistory,
                            onDelete = ui::forgetSearch,
                            onClearAll = ui::clearSearchHistory,
                            bottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp,
                        )
                    }
                } else {
                    // Everything at once by default; one catalogue, or the device, when the choice is shown (Settings → Behavior).
                    val sources = `in`.melo.SearchSource.entries.filter { it != `in`.melo.SearchSource.Spotify || discovery.spotifyEnabled }
                    // Five services do not fit a phone's width in every language: the row scrolls when it has to.
                    if (showSources) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp)) {
                    val fits = maxWidth >= SOURCE_SEGMENT_MIN_WIDTH * sources.size
                    SingleChoiceSegmentedButtonRow(if (fits) Modifier.fillMaxWidth()
                        else Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                        sources.forEachIndexed { index, source ->
                            SegmentedButton(
                                selected = discovery.activeSource == source,
                                onClick = { model.setSearchSource(source) },
                                shape = SegmentedButtonDefaults.itemShape(index, sources.size),
                                icon = {},
                            ) {
                                Text(stringResource(when (source) {
                                    `in`.melo.SearchSource.All -> R.string.search_source_all
                                    `in`.melo.SearchSource.Spotify -> R.string.search_source_spotify
                                    `in`.melo.SearchSource.YouTubeMusic -> R.string.search_source_youtube_short
                                    `in`.melo.SearchSource.SoundCloud -> R.string.search_source_soundcloud
                                    `in`.melo.SearchSource.Local -> R.string.search_source_device
                                }), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        SearchFilterType.entries.forEach { type ->
                            SearchFilterChip(type, filter, { filter = it })
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when {
                            discovery.error != null && results.isEmpty() -> Text(
                                discovery.error.orEmpty(),
                                Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                            results.isEmpty() && discovery.loading -> Box(Modifier.fillMaxSize().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
                                ContainedLoadingIndicator()
                            }
                            results.isEmpty() -> EmptySearchResults(searchQuery = searchQuery, colorScheme = colorScheme)
                            else -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // "All": one list, most relevant first, kinds and sources mixed, as Spotify's own search.
                                // A kind filter: that kind only, in the same order.
                                items(results, key = { "${it.kind}-${it.id}" }, contentType = { it.kind }) { item ->
                                    when (item.kind) {
                                        MusicKind.Track -> {
                                            val song = item.toSong(item.id in saved)
                                            val current = stable.currentSong?.id == song.id
                                            EnhancedSongListItem(
                                                song = song,
                                                isPlaying = current && stable.isPlaying,
                                                isCurrentSong = current,
                                                onMoreOptionsClick = { selected = it },
                                                onLongPress = { selected = song },
                                                onClick = {
                                                    ui.rememberSearchResult(item)
                                                    ui.playTrack(item)
                                                },
                                                showSourceBadge = true,
                                            )
                                        }
                                        MusicKind.Album -> SearchResultAlbumItem(
                                            album = item.toAlbum(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                        MusicKind.Artist -> SearchResultArtistItem(
                                            artist = item.toArtist(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                        else -> SearchResultPlaylistItem(
                                            playlist = item.toPlaylist(),
                                            onOpenClick = { open(item) },
                                            onPlayClick = { ui.shuffleItem(item) },
                                            onLongPress = { showOptions(item) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(resolveMainScreenBottomGradientHeight(navBarCompactMode))
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.8f to MaterialTheme.colorScheme.surfaceContainerLowest,
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        )
    }
    SongOptionsHost(ui, navController, selected, emptyList(), searchQuery) { selected = null }
    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
}

/** Narrowest a search source segment can be before the row scrolls instead. */
private val SOURCE_SEGMENT_MIN_WIDTH = 92.dp
