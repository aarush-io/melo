package `in`.melo.presentation.screens

import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.runtime.CompositionLocalProvider
import `in`.melo.presentation.components.LocalHomeGradients
import `in`.melo.presentation.components.LocalHomePressMotion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import `in`.melo.presentation.components.MeloBottomSheet as ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withContext
import `in`.melo.R
import `in`.melo.utils.formatSongCount
import `in`.melo.MeloModel
import `in`.melo.data.Music
import `in`.melo.data.MusicKind
import `in`.melo.data.HomeMood
import `in`.melo.data.HomeRecommendationType
import `in`.melo.data.HomeRecommendationPick
import `in`.melo.data.YouTubeHomeShelf
import `in`.melo.data.YouTubeHomeChip
import `in`.melo.data.source
import `in`.melo.data.model.toSong
import `in`.melo.data.preferences.CollagePattern
import `in`.melo.presentation.components.AlbumArtCollage
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.toImmutableList
import `in`.melo.data.preferences.HomeShelf
import `in`.melo.data.preferences.ShelfStyle
import `in`.melo.data.preferences.AppSettings
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.presentation.components.ListeningStatusCard
import `in`.melo.presentation.components.ListeningShelfPlaceholder
import `in`.melo.presentation.components.ListeningHomeHeader
import `in`.melo.presentation.components.HomeGradientTopBar
import `in`.melo.presentation.components.MiniPlayerHeight
import `in`.melo.presentation.components.MusicOptionsHost
import `in`.melo.presentation.components.SmartImage
import `in`.melo.presentation.components.SmartImageCompactListTargetSize
import `in`.melo.presentation.components.resolveMainScreenBottomGradientHeight
import `in`.melo.presentation.navigation.Screen
import `in`.melo.presentation.navigation.navigateSafely
import `in`.melo.presentation.navigation.navigateToTopLevelSafely
import `in`.melo.presentation.navigation.openMusic
import `in`.melo.presentation.viewmodel.PlayerViewModel
import sh.calvin.reorderable.ReorderableColumn

private const val COLLAGE_ITEMS = 6
private const val COLLAGE_MINIMUM = 3

/** Distributes the selected Home feed's first picks across its shelves. */
private fun categoryDialEntries(shelves: List<YouTubeHomeShelf>): List<HomeSpeedDialEntry> {
    val longest = shelves.maxOfOrNull { it.items.size } ?: return emptyList()
    val picks = ArrayList<Music>()
    for (round in 0 until longest) {
        shelves.forEach { shelf -> shelf.items.getOrNull(round)?.let(picks::add) }
    }
    return picks.map(::HomeSpeedDialEntry)
}

/**
 * The home canvas: Speed Dial above Melo's recommendations and library shelves.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(model: MeloModel, navController: NavHostController, paddingValues: PaddingValues) {
    val ui = model.ui
    val pins by model.pins.collectAsStateWithLifecycle()
    val downloads by ui.downloadCollections.collectAsStateWithLifecycle()
    val downloadsTitle = stringResource(R.string.home_downloads)
    val settings by ui.settings.collectAsStateWithLifecycle()
    val localPlaylists by model.localPlaylists.collectAsStateWithLifecycle()
    val youTubePlaylists by model.youTubePlaylists.collectAsStateWithLifecycle()
    val soundCloudPlaylists by model.soundCloudPlaylists.collectAsStateWithLifecycle()
    val youTubeAccount by model.youTubeAccount.collectAsStateWithLifecycle()
    val localBadge = stringResource(R.string.badge_local)
    val playlistsTitle = stringResource(R.string.home_playlists)
    val albumsTitle = stringResource(R.string.home_albums)
    val artistsTitle = stringResource(R.string.home_artists)
    val recent by model.recent.collectAsStateWithLifecycle()
    val localFiles by model.localTracks.collectAsStateWithLifecycle()
    val shelfTitles = mapOf(
        HomeShelf.PINS to stringResource(R.string.home_pins),
        HomeShelf.RECENT to stringResource(R.string.home_recent),
        HomeShelf.MULTI_SERVICE to stringResource(R.string.home_multi_service),
        HomeShelf.SPOTIFY_PLAYLISTS to stringResource(R.string.home_spotify_playlists),
        HomeShelf.YOUTUBE_PLAYLISTS to stringResource(R.string.home_youtube_playlists),
        HomeShelf.LOCAL_FILES to stringResource(R.string.home_local_files),
    )
    val library by model.library.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    val homeRecommendations by model.homeRecommendations.collectAsStateWithLifecycle()
    val homeDetails by model.details.collectAsStateWithLifecycle()
    val youTubeHome by model.youTubeHome.collectAsStateWithLifecycle()
    val recordedHistory by model.listeningHistory.entries.collectAsStateWithLifecycle()
    var spotifyHistory by remember(model) { mutableStateOf<List<Music>>(emptyList()) }
    LaunchedEffect(model) {
        withContext(Dispatchers.IO) { model.listeningHistory.load() }
    }
    LaunchedEffect(model, discovery.spotifyEnabled, online) {
        spotifyHistory = if (discovery.spotifyEnabled && online) {
            try {
                model.fullListeningHistory().map { it.track }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    val hasSong by ui.stablePlayerStateHasSong()
    val navBarCompactMode by ui.navBarCompactMode.collectAsStateWithLifecycle()
    val content = library.content
    val homeRecent = remember(recent, spotifyHistory, recordedHistory, homeRecommendations.youtubeRecent) {
        (recent + spotifyHistory + recordedHistory.map { it.track } + homeRecommendations.youtubeRecent)
            .filter { it.kind == MusicKind.Track }
            .distinctBy(Music::id)
            .take(12)
    }
    LaunchedEffect(model, online, youTubeAccount) {
        model.refreshHomeRecommendations()
        model.refreshYouTubeHomeCategories(force = true)
        while (true) {
            delay(60_000)
            model.refreshHomeRecommendations()
        }
    }
    val filteredHome = youTubeHome.selected != null
    val recentConfig = settings.homeLayout.firstOrNull { it.shelf == HomeShelf.RECENT }
    val recentFeed = if (recentConfig?.visible == true) homeRecent.take(recentConfig.count) else emptyList()
    val recentTitle = shelfTitles[HomeShelf.RECENT].orEmpty()
    val personalizedHome = youTubeHome.personalizedShelves
    val activeDialSource = if (settings.homeDialSource == AppSettings.DIAL_SOURCE_SPOTIFY && !discovery.spotifyEnabled) {
        AppSettings.DIAL_SOURCE_YOUTUBE
    } else settings.homeDialSource
    var spotifyDialRefreshKey by remember(model) { mutableStateOf(0) }
    val newReleaseIds = remember(personalizedHome) {
        personalizedHome.filter { it.title.contains("release", ignoreCase = true) || it.title.contains("new music", ignoreCase = true) }
            .flatMap(YouTubeHomeShelf::items).mapTo(HashSet(), Music::id)
    }
    val youtubeMixEntries = remember(homeRecommendations.youtubeMixes, homeRecommendations.picks, personalizedHome) {
        buildList {
            homeRecommendations.youtubeMixes.forEach { add(HomeSpeedDialEntry(it)) }
            homeRecommendations.picks
                .filter { it.type in setOf(
                    HomeRecommendationType.MOOD,
                    HomeRecommendationType.PERSONAL_STATION,
                    HomeRecommendationType.NEW_RELEASE_MIX,
                    HomeRecommendationType.DISCOVERY_STATION,
                ) }
                .forEach { pick -> add(HomeSpeedDialEntry(pick.item, pick.title, pick.artworkUrls.firstOrNull(), pick)) }
            personalizedHome.filter { shelf -> shelf.title.contains("mix", ignoreCase = true) }
                .flatMap(YouTubeHomeShelf::items).forEach { add(HomeSpeedDialEntry(it)) }
        }.filter { it.item.kind == MusicKind.Playlist }
            .distinctBy { it.item.id }
            .take(SPEED_DIAL_MAX_ITEMS)
    }
    val spotifyMixEntries = remember(discovery.spotifyEnabled, content?.playlists) {
        if (discovery.spotifyEnabled) content?.playlists.orEmpty().map { HomeSpeedDialEntry(it) } else emptyList()
    }
    val mixEntries = remember(youtubeMixEntries, spotifyMixEntries) {
        buildList {
            repeat(maxOf(youtubeMixEntries.size, spotifyMixEntries.size)) { index ->
                youtubeMixEntries.getOrNull(index)?.let(::add)
                spotifyMixEntries.getOrNull(index)?.let(::add)
            }
        }.distinctBy { it.item.id }.take(SPEED_DIAL_MAX_ITEMS)
    }
    val mixItemIds = remember(youtubeMixEntries, settings.homeMixesDialEnabled) {
        if (settings.homeMixesDialEnabled) youtubeMixEntries.mapTo(HashSet()) { it.item.id } else emptySet()
    }
    val speedDialEntries = remember(
        filteredHome, youTubeHome.shelves, personalizedHome, newReleaseIds, pins, homeRecent, homeRecommendations.picks,
        homeRecommendations.youtubeMixes, mixItemIds, localPlaylists, youTubePlaylists, localFiles, downloads, content,
        ui.likedSongs, activeDialSource, settings.homeArtistDialEnabled,
        discovery.spotifyEnabled, spotifyHistory, spotifyDialRefreshKey,
    ) {
        if (filteredHome) {
            categoryDialEntries(youTubeHome.shelves).distinctBy { it.item.id }.take(SPEED_DIAL_MAX_ITEMS)
        } else {
            val sourceItems = when {
                activeDialSource == AppSettings.DIAL_SOURCE_HISTORY -> homeRecent
                activeDialSource == AppSettings.DIAL_SOURCE_SPOTIFY -> buildList {
                    addAll(spotifyHistory.filter { it.source == `in`.melo.data.MusicSource.Spotify })
                    addAll(content?.savedTracks.orEmpty())
                    addAll(content?.playlists.orEmpty())
                    addAll(content?.albums.orEmpty())
                    addAll(content?.artists.orEmpty())
                // Keep the Spotify order stable through playback-driven recompositions;
                // only the explicit refresh key should produce a new shuffle.
                }.shuffled(kotlin.random.Random(spotifyDialRefreshKey))
                else -> buildList {
                    // Recommendation picks are rebuilt and reshuffled on refresh.
                    // Put them before the long personalized feed so the Speed
                    // Dial's page cap doesn't hide every refreshed pick.
                    addAll(homeRecommendations.picks.filterNot { it.item.id in mixItemIds }.map(HomeRecommendationPick::item))
                    personalizedHome.forEach { shelf -> addAll(shelf.items.filterNot { it.id in mixItemIds }) }
                    addAll(homeRecent.filter { it.source == `in`.melo.data.MusicSource.YouTubeMusic })
                }
            }
            buildList {
                pins.forEach { add(HomeSpeedDialEntry(it)) }
                sourceItems.forEach { item ->
                    val pick = homeRecommendations.picks.firstOrNull { it.item.id == item.id }
                        ?.takeIf { activeDialSource == AppSettings.DIAL_SOURCE_YOUTUBE }
                    if (pick != null) add(HomeSpeedDialEntry(
                        item = pick.item,
                        title = pick.title,
                        artworkUrl = pick.artworkUrls.firstOrNull() ?: pick.item.imageUrl,
                        recommendation = pick,
                        isNewRelease = pick.type == HomeRecommendationType.LATEST_RELEASE,
                    )) else add(HomeSpeedDialEntry(item, isNewRelease = item.id in newReleaseIds))
                }
                homeRecommendations.picks.forEach { pick ->
                    if (activeDialSource == AppSettings.DIAL_SOURCE_YOUTUBE && pick.item.id !in mixItemIds) {
                        add(HomeSpeedDialEntry(
                            item = pick.item,
                            title = pick.title,
                            artworkUrl = pick.artworkUrls.firstOrNull() ?: pick.item.imageUrl,
                            recommendation = pick,
                            isNewRelease = pick.type == HomeRecommendationType.LATEST_RELEASE,
                        ))
                    }
                }
                if (activeDialSource == AppSettings.DIAL_SOURCE_SPOTIFY && (content?.savedTrackCount ?: 0) > 0) {
                    add(HomeSpeedDialEntry(ui.likedSongs))
                }
                if (activeDialSource == AppSettings.DIAL_SOURCE_YOUTUBE) {
                    youTubePlaylists.forEach { add(HomeSpeedDialEntry(it)) }
                }
            }.distinctBy { it.item.id }
                .let { entries ->
                    val pinnedEntries = entries.filter { entry -> pins.any { it.id == entry.item.id } }
                    pinnedEntries + entries.filterNot { entry -> pins.any { it.id == entry.item.id } }
                }
                .let { entries -> if (settings.homeArtistDialEnabled) entries.filterNot { it.item.kind == MusicKind.Artist } else entries }
                .take(SPEED_DIAL_MAX_ITEMS)
        }
    }
    val artistDialEntries = remember(pins, homeRecommendations.youtubeArtists, content?.artists, personalizedHome, settings.homeArtistDialOrder, homeDetails) {
        val artists = buildList {
            pins.filter { it.kind == MusicKind.Artist }.forEach { add(HomeSpeedDialEntry(it)) }
            homeRecommendations.youtubeArtists.forEach { add(HomeSpeedDialEntry(it)) }
            content?.artists.orEmpty().forEach { add(HomeSpeedDialEntry(it)) }
            personalizedHome.flatMap(YouTubeHomeShelf::items).filter { it.kind == MusicKind.Artist }
                .forEach { add(HomeSpeedDialEntry(it)) }
        }.distinctBy { it.item.id }
        val pinnedArtistIds = pins.filter { it.kind == MusicKind.Artist }.mapTo(HashSet(), Music::id)
        (artists.filter { it.item.id in pinnedArtistIds } +
            orderDialEntries(artists.filterNot { it.item.id in pinnedArtistIds }, settings.homeArtistDialOrder))
            .map { entry ->
                val photo = homeDetails[entry.item.id]?.content?.item?.imageUrl
                if (entry.artworkUrl.isNullOrBlank() && photo != null) entry.copy(artworkUrl = photo) else entry
            }
            .take(SPEED_DIAL_MAX_ITEMS)
    }
    val showArtistDial = !filteredHome && settings.homeSpeedDialEnabled && settings.homeArtistDialEnabled && artistDialEntries.isNotEmpty()
    var refreshRequested by remember { mutableStateOf(false) }
    val servicesBusy = library.loading || homeRecommendations.loading || youTubeHome.personalizedLoading ||
        youTubeHome.loadingCategories || youTubeHome.loading
    var displayedSpeedDialEntries by remember(model) { mutableStateOf(speedDialEntries) }
    var displayedMixEntries by remember(model) { mutableStateOf(mixEntries) }
    val hasLuckyPick = settings.homeLuckyTileEnabled && (
        homeRecommendations.picks.any { it.type != HomeRecommendationType.LATEST_RELEASE } ||
            homeRecent.isNotEmpty() || pins.any { it.kind == MusicKind.Track }
        )
    var displayedLuckyTile by remember(model) { mutableStateOf(hasLuckyPick) }
    LaunchedEffect(refreshRequested, servicesBusy, speedDialEntries, mixEntries, hasLuckyPick) {
        if (!refreshRequested && !servicesBusy) {
            // Keep the current dial intact while all refresh sources load, then
            // commit one complete snapshot so its tiles can transition together.
            displayedSpeedDialEntries = speedDialEntries
            displayedMixEntries = mixEntries
            displayedLuckyTile = hasLuckyPick
        }
    }
    LaunchedEffect(refreshRequested, servicesBusy) {
        if (refreshRequested && !servicesBusy) {
            delay(180)
            refreshRequested = false
        }
    }
    val homeRequestBusy = refreshRequested || (filteredHome && youTubeHome.loading) ||
        (!filteredHome && personalizedHome.isEmpty() && youTubeHome.personalizedLoading)
    var completingLoad by remember { mutableStateOf(false) }
    LaunchedEffect(homeRequestBusy) {
        if (homeRequestBusy) completingLoad = true
        else {
            delay(180)
            completingLoad = false
        }
    }
    val showHomeSkeleton = homeRequestBusy || completingLoad
    val showSpeedDialSkeleton = showHomeSkeleton && displayedSpeedDialEntries.isEmpty()
    val pullState = rememberPullToRefreshState()
    val hasArtistPage = showArtistDial && !showHomeSkeleton
    val homeSpeedDialVisible = settings.homeSpeedDialEnabled &&
        (displayedSpeedDialEntries.isNotEmpty() || youTubeHome.chips.isNotEmpty() || showSpeedDialSkeleton)
    val feedCategoriesIndex = (if (homeSpeedDialVisible) 1 else 0) + (if (hasArtistPage) 1 else 0)
    val speedDialItemIds = remember(displayedSpeedDialEntries) { displayedSpeedDialEntries.mapTo(HashSet()) { it.item.id } }
    val overflowPins = remember(pins, speedDialItemIds, filteredHome) {
        if (filteredHome) emptyList() else pins.filterNot { it.id in speedDialItemIds }
    }
    val listState = rememberLazyListState()
    val homeScope = rememberCoroutineScope()
    val selectHomeCategory: (YouTubeHomeChip) -> Unit = { chip ->
        model.selectYouTubeHomeCategory(chip)
        homeScope.launch { listState.animateScrollToItem(0) }
    }
    val density = LocalDensity.current
    val scrollThresholdPx = remember(density) { with(density) { 180.dp.toPx() } }
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > scrollThresholdPx }
    }
    var requestedHomePage by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(youTubeHome.selected?.params) { requestedHomePage = null }
    LaunchedEffect(listState, youTubeHome.selected?.params, youTubeHome.continuation, youTubeHome.personalizedContinuation) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val token = if (youTubeHome.selected != null) youTubeHome.continuation else youTubeHome.personalizedContinuation
            token.takeIf {
                info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 4
            }
        }.collect { token ->
            if (token != null && token != requestedHomePage) {
                requestedHomePage = token
                if (youTubeHome.selected != null) model.loadMoreYouTubeHomeCategory() else model.loadMoreYouTubeHomeFeed()
            }
        }
    }
    var reordering by rememberSaveable { mutableStateOf(false) }
    var reorderingArtists by rememberSaveable { mutableStateOf(false) }
    val open: (Music) -> Unit = { navController.openMusic(ui, it) }
    var optionsFor by remember { mutableStateOf<Music?>(null) }
    val showOptions: (Music) -> Unit = {
        // The long-click handler of the card already vibrates.
        optionsFor = it
    }
    val openLibrary: () -> Unit = { navController.navigateToTopLevelSafely(Screen.Library.route) }

    val decay = rememberSplineBasedDecay<Float>()
    val evenFling = remember(decay) { EvenFling(decay) }
    val dialFling = remember(listState, decay) {
        DialPageFling(listState, decay, restOffsetPx = { 0 }, nextOffsetPx = { 0 })
    }
    FrameBeatWhileScrolling(listState)
    CompositionLocalProvider(
        LocalHomeGradients provides settings.homeGradients,
        LocalHomePressMotion provides settings.homePressMotion,
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                HomeGradientTopBar(
                    onSettingsClick = { navController.navigateSafely(Screen.Settings.route) },
                    homeDialSettingsContent = {
                        HomeDialSettingsMenuContent(
                            settings = settings,
                            spotifyConnected = discovery.spotifyEnabled,
                            onSettingsChange = { change -> ui.updateSettings { current -> change(current) } },
                        )
                    },
                    onReorderPinsClick = if (pins.size > 1) ({ reordering = true }) else null,
                    onHistoryClick = { navController.navigateSafely(Screen.History.route) },
                    profileImageUrl = youTubeAccount?.photoUrl,
                    profileName = youTubeAccount?.name,
                    profileHandle = youTubeAccount?.handle,
                    spotifyConnected = discovery.spotifyEnabled,
                    onProfileClick = { navController.navigateSafely(Screen.SettingsCategory.createRoute("account")) },
                    isScrolled = isScrolled,
                    refreshing = showHomeSkeleton,
                    pullFraction = { pullState.distanceFraction },
                    animateLogo = settings.homeLoadingAnimations,
                )
            }
        ) { innerPadding ->
            val screenHeight = viewportHeight
            val dialPageHeight = (
                screenHeight - innerPadding.calculateTopPadding()
                ).coerceAtLeast(360.dp)
            val dialFooter = paddingValues.calculateBottomPadding() +
                (if (hasSong) MiniPlayerHeight + 8.dp else 0.dp) + 12.dp
            // Keep both swipe pages' tile grids the same size and at the same vertical position.
            val dialGridRoom = (dialPageHeight - dialFooter - HOME_DIAL_PAGER_GRID_RESERVE).coerceAtLeast(180.dp)
            val titledGridRoom = (dialPageHeight - dialFooter - 134.dp).coerceAtLeast(180.dp)
            Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshRequested,
                state = pullState,
                indicator = {},
                onRefresh = {
                    refreshRequested = true
                    if (activeDialSource == AppSettings.DIAL_SOURCE_SPOTIFY) spotifyDialRefreshKey++
                    model.refreshHome()
                },
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            ) {
                LazyColumn(
                    state = listState,
                    flingBehavior = if (homeSpeedDialVisible && !`in`.melo.presentation.utils.LocalReducedMotion.current) dialFling else evenFling,
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    contentPadding = PaddingValues(
                        top = 0.dp,
                        bottom = paddingValues.calculateBottomPadding() + 38.dp + if (hasSong) MiniPlayerHeight else 0.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(MeloLayout.SectionGap)
                ) {
                    if (homeSpeedDialVisible) {
                        item(key = "speed_dial", contentType = "speed_dial") {
                            key(youTubeHome.selected?.params) {
                                HomeDialPager(
                                    mixesAvailable = !filteredHome && settings.homeMixesDialEnabled,
                                    speedDial = {
                                        HomeSpeedDial(
                                            entries = displayedSpeedDialEntries,
                                            pinnedIds = if (filteredHome) emptySet() else pins.mapTo(HashSet(), Music::id),
                                            categoryChips = youTubeHome.chips,
                                            selectedCategory = youTubeHome.selected,
                                            onCategoryClick = selectHomeCategory,
                                            onItemClick = { entry ->
                                                val pick = entry.recommendation
                                                when {
                                                    pick != null && pick.type == HomeRecommendationType.LATEST_RELEASE -> open(pick.item)
                                                    pick != null -> model.playHomeRecommendation(pick)
                                                    entry.item.kind == MusicKind.Track -> ui.playTrack(entry.item)
                                                    else -> open(entry.item)
                                                }
                                            },
                                            onItemLongPress = { entry -> showOptions(entry.item) },
                                            hasLuckyTile = !filteredHome && displayedLuckyTile,
                                            onLuckyClick = {
                                                val picks = homeRecommendations.picks.filter { it.type != HomeRecommendationType.LATEST_RELEASE }
                                                val pick = picks.randomOrNull()
                                                if (pick != null) {
                                                    model.playHomeRecommendation(pick)
                                                } else {
                                                    (homeRecent + pins.filter { it.kind == MusicKind.Track })
                                                        .distinctBy(Music::id)
                                                        .randomOrNull()
                                                        ?.let(ui::playTrack)
                                                    null
                                                }
                                            },
                                            onHistoryClick = { navController.navigateSafely(Screen.History.route) },
                                            onReorderPins = null,
                                            onCustomize = null,
                                            sharedPagerHeader = true,
                                            maxPages = SPEED_DIAL_MAX_PAGES,
                                            loading = showSpeedDialSkeleton,
                                            rows = settings.homeDialRows,
                                            pageHeight = dialPageHeight,
                                            footerReserve = dialFooter,
                                            maxGridHeight = dialGridRoom,
                                            showLabels = settings.homeDialLabels,
                                            showPinIcon = settings.homeDialPinIcon,
                                            tileStyle = settings.homeDialTileStyle,
                                            showNewReleaseTags = settings.homeDialNewReleaseTags,
                                            showHistoryTile = !filteredHome,
                                            onScrollToFeed = {
                                                val nextPage = if (hasArtistPage) 1 else feedCategoriesIndex
                                                homeScope.launch { listState.animateScrollToItem(nextPage) }
                                            },
                                        )
                                    },
                                    mixesDial = {
                                        HomeSpeedDial(
                                            entries = displayedMixEntries,
                                            pinnedIds = emptySet(),
                                            categoryChips = emptyList(),
                                            selectedCategory = null,
                                            onCategoryClick = {},
                                            onItemClick = { entry ->
                                                entry.recommendation?.let(model::playHomeRecommendation) ?: open(entry.item)
                                            },
                                            onItemLongPress = { entry -> showOptions(entry.item) },
                                            hasLuckyTile = false,
                                            onLuckyClick = { null },
                                            onHistoryClick = {},
                                            onReorderPins = null,
                                            title = stringResource(R.string.home_dial_mixes_title),
                                            sharedPagerHeader = true,
                                            maxPages = 1,
                                            loading = showHomeSkeleton && displayedMixEntries.isEmpty(),
                                            rows = settings.homeDialRows,
                                            pageHeight = dialPageHeight,
                                            footerReserve = dialFooter,
                                            maxGridHeight = dialGridRoom,
                                            showLabels = settings.homeDialLabels,
                                            showPinIcon = false,
                                            tileStyle = settings.homeDialTileStyle,
                                            showNewReleaseTags = settings.homeDialNewReleaseTags,
                                            showHistoryTile = false,
                                            onScrollToFeed = {
                                                val nextPage = if (hasArtistPage) 1 else feedCategoriesIndex
                                                homeScope.launch { listState.animateScrollToItem(nextPage) }
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (hasArtistPage) {
                        item(key = "artist_dial", contentType = "artist_dial") {
                            HomeSpeedDial(
                                entries = artistDialEntries,
                                pinnedIds = pins.filter { it.kind == MusicKind.Artist }.mapTo(HashSet(), Music::id),
                                categoryChips = emptyList(),
                                selectedCategory = null,
                                onCategoryClick = {},
                                onItemClick = { entry -> open(entry.item) },
                                onItemLongPress = { entry -> showOptions(entry.item) },
                                hasLuckyTile = false,
                                onLuckyClick = { null },
                                onHistoryClick = {},
                                onReorderPins = if (artistDialEntries.size > 1) ({ reorderingArtists = true }) else null,
                                title = stringResource(R.string.home_dial_artists_title),
                                rows = settings.homeDialRows,
                                pageHeight = dialPageHeight,
                                footerReserve = dialFooter,
                                maxGridHeight = titledGridRoom,
                                showLabels = settings.homeDialLabels,
                                showPinIcon = settings.homeDialPinIcon,
                                tileStyle = settings.homeDialTileStyle,
                                showNewReleaseTags = false,
                                showHistoryTile = false,
                                onMissingArtwork = { artist -> model.loadDetail(artist) },
                                onScrollToFeed = {
                                    homeScope.launch { listState.animateScrollToItem(feedCategoriesIndex) }
                                },
                            )
                        }
                    }

                    if (youTubeHome.chips.isNotEmpty()) {
                        stickyHeader(key = "home_feed_categories") {
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                HomeCategoryChips(youTubeHome.chips, youTubeHome.selected, selectHomeCategory)
                            }
                        }
                    }
                    if (showHomeSkeleton) {
                        item(key = "home_feed_skeleton") { HomeFeedSkeleton() }
                    } else if (!filteredHome) {

                    if (homeRecommendations.picks.isNotEmpty() || recentFeed.isNotEmpty() || HomeMood.coreMoods.isNotEmpty()) {
                        item(key = "for_you_discovery", contentType = "for_you_discovery") {
                            ForYouShelf(
                                picks = homeRecommendations.picks,
                                moods = HomeMood.coreMoods,
                                recent = recentFeed,
                                onPickClick = { pick ->
                                    if (pick.type == HomeRecommendationType.LATEST_RELEASE) open(pick.item)
                                    else model.playHomeRecommendation(pick)
                                },
                                onLongPress = showOptions,
                                onMoodClick = model::playHomeMood,
                                onShowAllMoods = { navController.navigateToTopLevelSafely(Screen.Explore.route) },
                                onRecentClick = { selected ->
                                    ui.playSongs(homeRecent.map { it.toSong() }, selected.toSong(), recentTitle)
                                },
                                onRecentLongPress = showOptions,
                                onOpenHistory = { navController.navigateSafely(Screen.History.route) },
                            )
                        }
                    }
                    if (youTubeHome.personalizedLoading && personalizedHome.isEmpty()) {
                        item(key = "youtube_personalized_loading") { ListeningShelfPlaceholder() }
                    }
                    if (youTubeHome.personalizedError != null && personalizedHome.isEmpty()) {
                        item(key = "youtube_personalized_error") {
                            ListeningStatusCard(
                                title = stringResource(R.string.home_personalized_feed_title),
                                message = youTubeHome.personalizedError.orEmpty(),
                                action = stringResource(R.string.melo_retry),
                                onAction = { model.refreshYouTubeHomeCategories(force = true) },
                                modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                            )
                        }
                    }
                    personalizedHome.forEachIndexed { index, shelf ->
                        val style = if (shelf.items.all { it.kind == MusicKind.Track }) ShelfStyle.LIST else ShelfStyle.CAROUSEL
                        homeShelf(
                            key = "youtube_personalized_home_$index",
                            title = shelf.title,
                            items = shelf.items.take(24),
                            style = style,
                            ui = ui,
                            onClick = { selected ->
                                if (selected.kind == MusicKind.Track) model.loadRadio(selected, open) else open(selected)
                            },
                            onLongPress = showOptions,
                            openAll = null,
                        )
                    }
                    if (youTubeHome.personalizedLoadingMore) {
                        item(key = "youtube_personalized_loading_more") { ListeningShelfPlaceholder() }
                    }
                    if (content == null && library.loading) {
                        item(key = "library_loading") {
                            ListeningShelfPlaceholder()
                        }
                    }
                    library.error?.let { message ->
                        item(key = "library_error") {
                            ListeningStatusCard(
                                title = stringResource(R.string.melo_library_error_title),
                                message = message,
                                action = stringResource(R.string.melo_retry),
                                onAction = model::refreshLibrary,
                                modifier = Modifier.padding(horizontal = MeloLayout.PageGutter)
                            )
                        }
                    }
                    val hasListeningContent = pins.isNotEmpty() || recent.isNotEmpty() || localFiles.isNotEmpty() ||
                        localPlaylists.isNotEmpty() || youTubePlaylists.isNotEmpty() || soundCloudPlaylists.isNotEmpty() || downloads.isNotEmpty() ||
                        content?.let { it.savedTrackCount > 0 || it.playlists.isNotEmpty() || it.albums.isNotEmpty() || it.artists.isNotEmpty() } == true
                    if (!hasListeningContent && !library.loading && library.error == null) {
                        item(key = "empty_home") {
                            ListeningStatusCard(
                                stringResource(R.string.melo_empty_home_title),
                                stringResource(R.string.melo_empty_home_body),
                                stringResource(R.string.melo_discover),
                                { navController.navigateToTopLevelSafely(Screen.Search.route) },
                                Modifier.padding(horizontal = MeloLayout.PageGutter)
                            )
                        }
                    }
                    // Shelves in the order, layout and size chosen in Settings → Home.
                    settings.homeLayout.filter { it.visible && it.shelf != HomeShelf.RECENT }.forEach { config ->
                        val multi = localPlaylists.map { it.asMusic().copy(subtitle = localBadge + " · " + it.asMusic().subtitle) }
                        val openTab: (`in`.melo.data.model.LibraryTabId) -> () -> Unit = { tab -> { ui.requestedLibraryTab.value = tab.storageKey; openLibrary() } }
                        val title = when (config.shelf) {
                            HomeShelf.DOWNLOADS -> downloadsTitle
                            HomeShelf.PLAYLISTS -> playlistsTitle
                            HomeShelf.ALBUMS -> albumsTitle
                            HomeShelf.ARTISTS -> artistsTitle
                            else -> shelfTitles[config.shelf].orEmpty()
                        }
                        val (items, openAll) = when (config.shelf) {
                            HomeShelf.PINS -> overflowPins to null
                            HomeShelf.RECENT -> recent to null
                            HomeShelf.DOWNLOADS -> downloads.values.filter { it.isComplete }.map { it.collection.item } to openTab(`in`.melo.data.model.LibraryTabId.DOWNLOADS)
                            HomeShelf.LIKED -> emptyList<Music>() to null
                            HomeShelf.PLAYLISTS -> (multi + youTubePlaylists + soundCloudPlaylists + content?.playlists.orEmpty()) to openTab(`in`.melo.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.MULTI_SERVICE -> multi to openTab(`in`.melo.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.SPOTIFY_PLAYLISTS -> content?.playlists.orEmpty() to openTab(`in`.melo.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.YOUTUBE_PLAYLISTS -> youTubePlaylists to openTab(`in`.melo.data.model.LibraryTabId.PLAYLISTS)
                            HomeShelf.ALBUMS -> content?.albums.orEmpty() to openTab(`in`.melo.data.model.LibraryTabId.ALBUMS)
                            HomeShelf.ARTISTS -> content?.artists.orEmpty() to openTab(`in`.melo.data.model.LibraryTabId.ARTISTS)
                            HomeShelf.LOCAL_FILES -> localFiles to openTab(`in`.melo.data.model.LibraryTabId.LOCAL)
                        }
                        when {
                            config.shelf == HomeShelf.LIKED -> if (content != null && content.savedTrackCount > 0) item(key = "liked", contentType = "liked") {
                                LikedSongsCard(
                                    count = content.savedTrackCount,
                                    artworks = content.savedTracks.mapNotNull(Music::imageUrl).distinct().take(3),
                                    onOpen = { open(ui.likedSongs) },
                                    onLongPress = { showOptions(ui.likedSongs) },
                                    onPlay = {
                                        // The count is known before the tracks: without them, the list itself is opened.
                                        val first = content.savedTracks.firstOrNull()
                                        if (first == null) open(ui.likedSongs)
                                        else ui.playSongs(content.savedTracks.map { it.toSong() }, first.toSong(), ui.likedSongs.title)
                                    },
                                )
                            }
                            config.shelf == HomeShelf.PINS && config.style == ShelfStyle.COLLAGE -> if (overflowPins.isNotEmpty()) {
                                item(key = "pins_collage", contentType = "pins_collage") {
                                    PinsCollage(overflowPins.take(config.count), settings.collagePattern, settings.collageAutoRotate, ui, { open(it) }, { showOptions(it) }, { reordering = true })
                                }
                            }
                            else -> homeShelf(config.shelf.key, title.takeIf { config.showTitle }, items.take(config.count), config.style, ui,
                                onClick = { item ->
                                    val tracks = items.filter { it.kind == MusicKind.Track }
                                    if (item.kind == MusicKind.Track && tracks.size > 1) ui.playSongs(tracks.map { it.toSong() }, item.toSong(), title)
                                    else if (item.kind == MusicKind.Track) ui.playItem(item) else open(item)
                                },
                                onLongPress = showOptions, openAll = openAll)
                        }
                    }
                    } else {
                        if (youTubeHome.loading && youTubeHome.shelves.isEmpty()) {
                            item(key = "home_category_loading") { ListeningShelfPlaceholder() }
                        }
                        youTubeHome.error?.let { message ->
                            item(key = "home_category_error") {
                                ListeningStatusCard(
                                    title = youTubeHome.selected?.title.orEmpty(),
                                    message = message,
                                    action = stringResource(R.string.melo_retry),
                                    onAction = { model.refreshYouTubeHomeCategories(force = true) },
                                    modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                                )
                            }
                        }
                        if (!youTubeHome.loading && youTubeHome.error == null && youTubeHome.shelves.isEmpty()) {
                            item(key = "home_category_empty") {
                                ListeningStatusCard(
                                    title = youTubeHome.selected?.title.orEmpty(),
                                    message = stringResource(R.string.home_category_empty),
                                    action = stringResource(R.string.melo_retry),
                                    onAction = { model.refreshYouTubeHomeCategories(force = true) },
                                    modifier = Modifier.padding(horizontal = MeloLayout.PageGutter),
                                )
                            }
                        }
                        youTubeHome.shelves.forEachIndexed { index, shelf ->
                            val style = if (shelf.items.all { it.kind == MusicKind.Track }) ShelfStyle.LIST else ShelfStyle.CAROUSEL
                            homeShelf(
                                key = "youtube_home_${youTubeHome.selected?.params}_${index}",
                                title = shelf.title,
                                items = shelf.items.take(20),
                                style = style,
                                ui = ui,
                                onClick = { selected ->
                                    if (selected.kind == MusicKind.Track) model.loadRadio(selected, open) else open(selected)
                                },
                                onLongPress = showOptions,
                                openAll = null,
                            )
                        }
                        if (youTubeHome.loadingMore) {
                            item(key = "home_category_loading_more") { ListeningShelfPlaceholder() }
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

    }

    if (reordering) PinsReorderSheet(pins, ui, model, onDismiss = { reordering = false })
    if (reorderingArtists) HomeDialSettingsSheet(
        title = stringResource(R.string.home_dial_artists_title),
        items = artistDialEntries.map(HomeSpeedDialEntry::item),
        order = settings.homeArtistDialOrder,
        onOrderChange = { order -> ui.updateSettings { it.copy(homeArtistDialOrder = order) } },
        onDismiss = { reorderingArtists = false },
        pinnedIds = pins.filter { it.kind == MusicKind.Artist }.mapTo(HashSet(), Music::id),
        onTogglePin = model::togglePin,
    )
    MusicOptionsHost(ui, navController, optionsFor) { optionsFor = null }
}

@Composable
private fun PlayerViewModel.stablePlayerStateHasSong() = remember(this) {
    derivedHasSong
}.collectAsStateWithLifecycle()

private fun androidx.compose.foundation.lazy.LazyListScope.homeShelf(
    key: String,
    title: String?,
    items: List<Music>,
    style: ShelfStyle,
    ui: PlayerViewModel,
    onClick: (Music) -> Unit,
    onLongPress: (Music) -> Unit,
    openAll: (() -> Unit)?,
) {
    if (items.isEmpty()) return
    item(key = key, contentType = "shelf_${style.key}") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) SectionHeader(title, openAll)
            ShelfItems(items, style, ui, onClick, onLongPress) { item, modifier ->
                PinnedPill(item, ui, modifier, { onClick(item) }, { onLongPress(item) })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onOpen: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier,
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (onOpen != null) FilledIconButton(
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.secondary
            ),
            onClick = onOpen,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = title,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PinsCollage(
    pins: List<Music>,
    patternKey: String,
    autoRotate: Boolean,
    ui: PlayerViewModel,
    open: (Music) -> Unit,
    showOptions: (Music) -> Unit,
    onReorder: () -> Unit,
) {
    val songs = remember(pins) { pins.take(COLLAGE_ITEMS).map { it.toSong() }.toImmutableList() }
    val pattern = remember(patternKey, autoRotate) {
        if (autoRotate) CollagePattern.entries.random() else CollagePattern.fromStorageKey(patternKey)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A collage only shows artwork: it needs enough elements to read as one.
        if (songs.size >= COLLAGE_MINIMUM) AlbumArtCollage(
            modifier = Modifier.fillMaxWidth(),
            songs = songs,
            padding = 14.dp,
            height = 400.dp,
            pattern = pattern,
            onSongClick = { song -> pins.firstOrNull { it.id == song.id }?.let(open) },
        )
        pins.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    PinnedPill(item, ui, Modifier.weight(1f), { open(item) }, { showOptions(item) })
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (pins.size > 1) Box(Modifier.fillMaxWidth().padding(horizontal = MeloLayout.PageGutter), contentAlignment = Alignment.CenterEnd) {
            FilledIconButton(
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.secondary,
                ),
                onClick = onReorder,
            ) {
                Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.pins_reorder))
            }
        }
    }
}

private val PinPillHeight = 64.dp
private val PinPillArtSize = 52.dp

/** The recently played pill, tinted from the artwork. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedPill(
    item: Music,
    ui: PlayerViewModel,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val container = MaterialTheme.colorScheme.surfaceContainer
    val title = MaterialTheme.colorScheme.onSurface
    val shape = MeloLayout.CardShape
    val artPadding = (PinPillHeight - PinPillArtSize) / 2
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .height(PinPillHeight)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = artPadding, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MeloLayout.Space3)
        ) {
            if (item.id == ui.likedSongs.id) {
                Box(
                    Modifier.size(PinPillArtSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                SmartImage(
                    model = item.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    shape = CircleShape,
                    targetSize = SmartImageCompactListTargetSize,
                    modifier = Modifier.size(PinPillArtSize)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = title
                )
                Text(
                    text = item.subtitle.ifBlank { stringResource(item.kind.labelRes) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = title.copy(alpha = MeloAlpha.Secondary)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LikedSongsCard(
    count: Int,
    artworks: List<String>,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onPlay: () -> Unit,
) {
    val shape = MeloLayout.CardShape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MeloLayout.PageGutter)
            .clip(shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = shape,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(MeloLayout.ArtworkShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (artworks.isNotEmpty()) SmartImage(
                    model = artworks.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = MeloAlpha.Tertiary)))
                Icon(Icons.Rounded.Favorite, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = MeloLayout.Space4)) {
                Text(stringResource(R.string.liked_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    formatSongCount(count),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(MeloLayout.ControlSize),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Icon(Icons.Rounded.PlayArrow, stringResource(R.string.liked_play), Modifier.size(30.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinsReorderSheet(pins: List<Music>, ui: PlayerViewModel, model: MeloModel, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.reorder),
            modifier = Modifier.padding(horizontal = MeloLayout.PageGutter, vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        ReorderableColumn(
            list = pins,
            onSettle = { from, to -> model.movePin(from, to) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(MeloLayout.Space2),
        ) { _, item, _ ->
            key(item.id) {
            ReorderableItem {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MeloLayout.PanelShape,
                ) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmartImage(
                            model = item.imageUrl,
                            contentDescription = null,
                            shape = MeloLayout.CardShape,
                            contentScale = ContentScale.Crop,
                            targetSize = SmartImageCompactListTargetSize,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            item.title,
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { ui.togglePin(item) }) {
                            Icon(Icons.Outlined.PushPin, stringResource(R.string.pin_remove))
                        }
                        IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                            Icon(Icons.Rounded.DragHandle, stringResource(R.string.reorder))
                        }
                    }
                }
            }
            }
        }
    }
}
