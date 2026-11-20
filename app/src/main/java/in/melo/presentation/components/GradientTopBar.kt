package `in`.melo.presentation.components

import `in`.melo.ui.theme.MeloAlpha
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.SwapVert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import `in`.melo.R
import `in`.melo.ui.theme.GoogleSansRounded
import `in`.melo.ui.theme.MeloStatusBarStyle
import androidx.compose.ui.res.stringResource
import `in`.melo.ui.theme.MeloLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreGradientTopBar(
    title: String,
    startColor: Color,
    endColor: Color,
    contentColor: Color,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationIconClick: () -> Unit,
) {
    val gradientBrush = remember(startColor, endColor) {
        Brush.verticalGradient(colors = listOf(startColor, endColor))
    }

    MeloStatusBarStyle(color = startColor)

    LargeTopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
            Text(
                modifier = Modifier.padding(start = MeloLayout.Space2),
                text = title,
                color = contentColor,
                fontFamily = GoogleSansRounded
            )
        },
        expandedHeight = 160.dp,
        modifier = Modifier.background(brush = gradientBrush),
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(start = MeloLayout.Space3),
                onClick = onNavigationIconClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = contentColor
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = startColor
                )
            }
        },
        colors = topAppBarColors(
            containerColor = Color.Transparent, // Background is handled by the gradient brush
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer, // Or a color that contrasts well with your typical gradient
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer // Same as title
        )
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeGradientTopBar(
    onSettingsClick: () -> Unit,
    homeDialSettingsContent: (@Composable () -> Unit)? = null,
    onReorderPinsClick: (() -> Unit)? = null,
    onHistoryClick: () -> Unit,
    profileImageUrl: String? = null,
    profileName: String? = null,
    profileHandle: String? = null,
    spotifyConnected: Boolean = false,
    onProfileClick: (() -> Unit)? = null,
    /** Shown first in the header when set: search no longer has a tab of its own. */
    onSearchClick: (() -> Unit)? = null,
    isScrolled: Boolean = false,
    refreshing: Boolean = false,
    pullFraction: () -> Float = { 0f },
    animateLogo: Boolean = true,
    settingsButtonDirect: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }
    var dialSettingsExpanded by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    MeloStatusBarStyle(color = colors.background)
    val fontScale = LocalDensity.current.fontScale
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The actions float over the bar: on a narrow screen or with large text, the wordmark
        // gives way to them and the dotted mark stays.
        val buttons = 2 + listOfNotNull(onSearchClick, onProfileClick).size
        val actionsWidth = MeloLayout.ControlSize * buttons + MeloLayout.Space4 + MeloLayout.Space1
        val titleWidth = MeloLayout.Space4 + MeloLayout.ControlSize + MeloLayout.Space2 + WORDMARK_WIDTH * fontScale
        val showWordmark = maxWidth >= actionsWidth + titleWidth + MeloLayout.Space2
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeloRefreshLogo(refreshing, pullFraction, animateLogo)
                    if (showWordmark) MeloWordmark()
                }
            },
            // The actions are drawn in the Box overlay below so their bounds don't participate in
            // the title's layout and opening a panel can never change the app-bar measurement.
            actions = { Spacer(Modifier.width(0.dp)) },
            colors = topAppBarColors(
                containerColor = colors.background,
                scrolledContainerColor = colors.background,
            ),
        )
        Row(
            Modifier.align(Alignment.CenterEnd).offset(y = statusBarInset / 2).zIndex(1f)
                .padding(end = 16.dp)
                .background(colors.surface.copy(alpha = 1f), CircleShape)
                .border(0.75.dp, colors.outlineVariant.copy(alpha = MeloAlpha.Scrim), CircleShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onSearchClick != null) {
                IconButton(onClick = onSearchClick, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.rounded_search_24), stringResource(R.string.nav_bar_search))
                }
            }
            IconButton(onClick = onHistoryClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.History, stringResource(R.string.history_title))
            }
            Box {
                IconButton(
                    onClick = {
                        if (settingsButtonDirect) onSettingsClick()
                        else if (homeDialSettingsContent != null) dialSettingsExpanded = true
                        else menuExpanded = true
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    if (settingsButtonDirect) {
                        Icon(painterResource(R.drawable.rounded_settings_24), stringResource(R.string.common_settings))
                    } else {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.player_cd_more_options))
                    }
                }
                if (homeDialSettingsContent == null && !settingsButtonDirect) {
                    MeloMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
                        MeloMenuItem(
                            label = stringResource(R.string.common_settings),
                            icon = { Icon(painterResource(R.drawable.rounded_settings_24), contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSettingsClick()
                            },
                        )
                    }
                }
            }
            if (onProfileClick != null) {
                IconButton(onClick = { accountMenuExpanded = true }, modifier = Modifier.size(48.dp)) {
                    if (!profileImageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = profileImageUrl,
                            contentDescription = stringResource(R.string.settings_accounts_title),
                            modifier = Modifier.size(34.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = stringResource(R.string.settings_accounts_title),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
        }
    }
    if (dialSettingsExpanded && homeDialSettingsContent != null) {
        HomeDialSettingsOverlay(
            content = homeDialSettingsContent,
            onDismiss = { dialSettingsExpanded = false },
            onReorderPinsClick = onReorderPinsClick?.let { callback ->
                {
                    dialSettingsExpanded = false
                    callback()
                }
            },
        )
    }
    if (accountMenuExpanded && onProfileClick != null) {
        HomeAccountMenuOverlay(
            profileName = profileName,
            profileHandle = profileHandle,
            profileImageUrl = profileImageUrl,
            spotifyConnected = spotifyConnected,
            onDismiss = { accountMenuExpanded = false },
            onManageAccountsClick = {
                accountMenuExpanded = false
                onProfileClick()
            },
            onSettingsClick = {
                accountMenuExpanded = false
                onSettingsClick()
            },
        )
    }
}

@Composable
private fun HomeDialSettingsOverlay(
    content: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onReorderPinsClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val panelTop = statusInset + 10.dp
            val availableHeight = maxHeight - panelTop - navigationInset - 16.dp
            // Let the home-dial controls use the screen below the system bars so the whole menu
            // fits without scrolling on normal phone layouts. Keep the scroll fallback for short windows.
            val panelMaxHeight = availableHeight.coerceAtLeast(220.dp)
            val panelWidth = minOf(maxWidth * 0.72f, maxWidth - 24.dp, 288.dp)
            val shape = MeloLayout.PanelShape
            val noRipple = remember { MutableInteractionSource() }
            Box(
                Modifier.matchParentSize()
                    .background(colors.scrim.copy(alpha = MeloAlpha.Disabled))
                    .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            )
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(160)) + scaleIn(
                    animationSpec = tween(180),
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
                modifier = Modifier.align(Alignment.TopEnd).zIndex(2f),
            ) {
                Surface(
                    modifier = Modifier.padding(top = panelTop, end = 12.dp)
                        .width(panelWidth)
                        .heightIn(max = panelMaxHeight)
                        .shadow(20.dp, shape)
                        .border(
                            BorderStroke(0.75.dp, Brush.verticalGradient(listOf(
                                colors.onSurface.copy(alpha = 0.22f),
                                colors.onSurface.copy(alpha = 0.06f),
                                colors.onSurface.copy(alpha = 0.12f),
                            ))),
                            shape,
                        ),
                    shape = shape,
                    color = colors.surface,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(
                                colors.onSurface.copy(alpha = if (LocalSurfaceGradients.current) 0.025f else 0f),
                                Color.Transparent,
                            )))
                            .verticalScroll(rememberScrollState())
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        content()
                        if (onReorderPinsClick != null) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp, vertical = MeloLayout.Space1),
                                color = colors.onSurface.copy(alpha = MeloAlpha.Divider),
                            )
                            OverlayPanelAction(
                                label = stringResource(R.string.home_dial_reorder_pins),
                                icon = { Icon(Icons.Rounded.SwapVert, contentDescription = null) },
                                onClick = onReorderPinsClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeAccountMenuOverlay(
    profileName: String?,
    profileHandle: String?,
    profileImageUrl: String?,
    spotifyConnected: Boolean,
    onDismiss: () -> Unit,
    onManageAccountsClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val panelTop = statusInset + 10.dp
            val availableHeight = maxHeight - panelTop - navigationInset - 16.dp
            val panelMaxHeight = minOf(maxHeight * 0.72f, availableHeight).coerceAtLeast(250.dp)
            val panelWidth = (maxWidth - 24.dp).coerceAtLeast(0.dp)
            val shape = MeloLayout.PanelShape
            val noRipple = remember { MutableInteractionSource() }
            Box(
                Modifier.matchParentSize()
                    .background(colors.scrim.copy(alpha = MeloAlpha.Disabled))
                    .clickable(interactionSource = noRipple, indication = null, onClick = onDismiss),
            )
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(160)) + scaleIn(
                    animationSpec = tween(180),
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(1f, 0f),
                ),
                modifier = Modifier.align(Alignment.TopEnd).zIndex(2f),
            ) {
                Surface(
                    modifier = Modifier.padding(top = panelTop, end = 12.dp)
                        .width(panelWidth)
                        .heightIn(max = panelMaxHeight)
                        .shadow(20.dp, shape)
                        .border(
                            BorderStroke(0.75.dp, Brush.verticalGradient(listOf(
                                colors.onSurface.copy(alpha = 0.22f),
                                colors.onSurface.copy(alpha = 0.06f),
                                colors.onSurface.copy(alpha = 0.12f),
                            ))),
                            shape,
                        ),
                    shape = shape,
                    color = colors.surface,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(
                                colors.onSurface.copy(alpha = if (LocalSurfaceGradients.current) 0.025f else 0f),
                                Color.Transparent,
                            )))
                            .verticalScroll(rememberScrollState())
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        Text(
                            text = stringResource(R.string.home_account_menu_title),
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        HomeAccountServiceRow(
                            title = stringResource(R.string.search_source_youtube),
                            detail = profileName?.let { name ->
                                val handle = profileHandle?.takeIf(String::isNotBlank)
                                if (handle == null) name else "$name · $handle"
                            },
                            connected = profileName != null,
                            imageUrl = profileImageUrl,
                            useYouTubeIcon = true,
                        )
                        HomeAccountServiceRow(
                            title = stringResource(R.string.search_source_spotify),
                            detail = null,
                            connected = spotifyConnected,
                            useYouTubeIcon = false,
                        )
                        HorizontalDivider(
                            Modifier.padding(horizontal = 16.dp, vertical = MeloLayout.Space1),
                            color = colors.onSurface.copy(alpha = MeloAlpha.Divider),
                        )
                        OverlayPanelAction(
                            label = stringResource(R.string.home_account_manage),
                            icon = { Icon(Icons.Rounded.AccountCircle, contentDescription = null) },
                            onClick = onManageAccountsClick,
                        )
                        HorizontalDivider(
                            Modifier.padding(horizontal = 16.dp, vertical = MeloLayout.Space1),
                            color = colors.onSurface.copy(alpha = MeloAlpha.Divider),
                        )
                        OverlayPanelAction(
                            label = stringResource(R.string.common_settings),
                            icon = { Icon(painterResource(R.drawable.rounded_settings_24), contentDescription = null) },
                            onClick = onSettingsClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeAccountServiceRow(
    title: String,
    detail: String?,
    connected: Boolean,
    imageUrl: String? = null,
    useYouTubeIcon: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (useYouTubeIcon && connected && !imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (useYouTubeIcon) {
                    Icon(painterResource(R.drawable.ic_source_youtube_music), null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.secondary)
                } else {
                    Icon(painterResource(R.drawable.ic_source_spotify), null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                text = detail ?: stringResource(if (connected) R.string.home_account_connected else R.string.home_account_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun OverlayPanelAction(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(start = MeloLayout.Space3),
            textAlign = TextAlign.Start,
            maxLines = 1,
        )
    }
}

/** The MELO wordmark at 25 sp and a font scale of 1. */
private val WORDMARK_WIDTH = 70.dp
