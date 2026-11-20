package `in`.melo.presentation.components

import `in`.melo.ui.theme.MeloAlpha
import `in`.melo.ui.theme.MeloLayout
import `in`.melo.presentation.navigation.navigateToTopLevelSafely

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import `in`.melo.BottomNavItem
import `in`.melo.data.preferences.NavBarStyle
import `in`.melo.presentation.components.scoped.CustomNavigationBarItem
import `in`.melo.presentation.navigation.Screen
import kotlinx.collections.immutable.ImmutableList

internal val NavBarContentHeight = 80.dp // Altura del contenido de la barra de navegación
internal val NavBarCompactContentHeight = 64.dp
internal val NavBarContentHeightFullWidth = NavBarContentHeight // Altura del contenido de la barra de navegación en modo completo
private val MainScreenBottomGradientExtraHeight = MiniPlayerHeight + MiniPlayerBottomSpacer + 8.dp
// Some OEM freeform/floating-window modes can report a bottom inset close to the whole window height.
internal val MaxNavigationBarBottomInset = 96.dp

internal fun sanitizeNavigationBarBottomInset(systemNavBarInset: Dp): Dp {
    if (!systemNavBarInset.value.isFinite()) return 0.dp
    return systemNavBarInset.coerceIn(0.dp, MaxNavigationBarBottomInset)
}

internal fun calculatePlayerSheetCollapsedTargetY(
    containerHeightPx: Float,
    collapsedContentHeightPx: Float,
    bottomMarginPx: Float,
    bottomSpacerPx: Float
): Float {
    val safeContainerHeightPx = containerHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeCollapsedContentHeightPx = collapsedContentHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeBottomMarginPx = bottomMarginPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeBottomSpacerPx = bottomSpacerPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val maxTargetY = (safeContainerHeightPx - safeCollapsedContentHeightPx).coerceAtLeast(0f)

    return (safeContainerHeightPx - safeCollapsedContentHeightPx - safeBottomMarginPx - safeBottomSpacerPx)
        .coerceIn(0f, maxTargetY)
}

internal fun resolveNavBarContentHeight(compactMode: Boolean): Dp =
    if (compactMode) NavBarCompactContentHeight else NavBarContentHeight

internal fun resolveMainScreenBottomGradientHeight(compactMode: Boolean): Dp =
    resolveNavBarContentHeight(compactMode) + MainScreenBottomGradientExtraHeight

internal fun resolveNavBarSurfaceHeight(
    navBarStyle: String,
    systemNavBarInset: Dp,
    compactMode: Boolean
): Dp {
    val contentHeight = resolveNavBarContentHeight(compactMode)
    return if (navBarStyle == NavBarStyle.FULL_WIDTH) {
        contentHeight + systemNavBarInset
    } else {
        contentHeight
    }
}

internal fun resolveNavBarOccupiedHeight(
    systemNavBarInset: Dp,
    compactMode: Boolean
): Dp = resolveNavBarContentHeight(compactMode) + systemNavBarInset

@Composable
private fun PlayerInternalNavigationItemsRow(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    navBarStyle: String,
    compactMode: Boolean,
    bottomBarPadding: Dp,
) {
    val navBarInsetPadding = sanitizeNavigationBarBottomInset(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    )
    // Maintain invariant: bottomBarPadding + innerRowPadding = the sanitized system nav bar inset.
    // This prevents nav items from appearing behind the gesture bar during style transitions,
    // e.g. FULL_WIDTH→DEFAULT where bottomBarPadding starts at 0 and animates to systemNavBarInset.
    val innerRowPadding = (navBarInsetPadding - bottomBarPadding).coerceAtLeast(0.dp)
    val latestNavigationEnabled by rememberUpdatedState(currentRoute != null)
    // The size each label fits at, by route. Every label is drawn at the smallest of them, so the row
    // reads as one; a label that fits larger again (a wider bar) raises its own entry.
    val labelSizes = remember(navItems) { mutableStateMapOf<String, Float>() }

    val rowModifier = if (navBarStyle == NavBarStyle.FULL_WIDTH) {
        modifier
            .fillMaxWidth()
            .padding(top = 0.dp, bottom = innerRowPadding, start = 12.dp, end = 12.dp)
    } else {
        modifier
            .padding(start = MeloLayout.Space3, end = MeloLayout.Space3, bottom = innerRowPadding)
            .fillMaxWidth()
    }
    Row(
        modifier = rowModifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 1f), CircleShape)
            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = MeloAlpha.Secondary), CircleShape)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        navItems.forEach { item ->
            val isSelected = currentRoute != null && currentRoute == item.screen.route
            val selectedColor = MaterialTheme.colorScheme.primary
            val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
            val indicatorColorFromTheme = MaterialTheme.colorScheme.secondaryContainer

            val iconPainterResId = if (isSelected && item.selectedIconResId != null && item.selectedIconResId != 0) {
                item.selectedIconResId
            } else {
                item.iconResId
            }
            val localizedLabel = stringResource(id = item.labelResId)
            val iconLambda: @Composable () -> Unit = remember(iconPainterResId, localizedLabel) {
                {
                    Icon(
                        painter = painterResource(id = iconPainterResId),
                        contentDescription = localizedLabel
                    )
                }
            }
            val selectedIconLambda: @Composable () -> Unit = remember(iconPainterResId, localizedLabel) {
                {
                    Icon(
                        painter = painterResource(id = iconPainterResId),
                        contentDescription = localizedLabel
                    )
                }
            }
            val labelLambda: (@Composable () -> Unit)? = if (compactMode) {
                null
            } else {
                val route = item.screen.route
                remember(localizedLabel, labelSizes, route) {
                    {
                        // Longer translations ("Bibliothèque", "Медиатека") must not wrap mid-word on narrow
                        // screens or at large font scales: keep one line and shrink the text to fit instead.
                        // The size steps down while the laid out text overflows the tab. Text's own auto
                        // size kept the full size here and let "Моя волна" run into the next tab.
                        BoxWithConstraints(contentAlignment = Alignment.Center) {
                            // Only what changes the layout restarts the fit: the colour, which follows the
                            // selected tab, is passed to the text on its own.
                            val provided = LocalTextStyle.current
                            val base = provided.copy(color = Color.Unspecified)
                            var fitted by remember(localizedLabel, base, constraints.maxWidth) { mutableStateOf(base) }
                            var fits by remember(localizedLabel, base, constraints.maxWidth) { mutableStateOf(false) }
                            val shared = labelSizes.values.minOrNull() ?: Float.MAX_VALUE
                            val shown = if (fits) labelAt(base, minOf(shared, fitted.fontSize.value)) else fitted
                            Text(
                                localizedLabel,
                                color = provided.color,
                                style = shown,
                                maxLines = 1,
                                softWrap = false,
                                onTextLayout = { layout ->
                                    val smaller = if (layout.didOverflowWidth) smallerLabel(base, shown) else null
                                    if (smaller != null) {
                                        fitted = smaller
                                    } else {
                                        fits = true
                                        if (fitted.fontSize.isSp) labelSizes[route] = fitted.fontSize.value
                                    }
                                },
                                // An overflowing step is not drawn, so the label does not flicker while it shrinks.
                                modifier = Modifier.drawWithContent { if (fits) drawContent() },
                            )
                        }
                    }
                }
            }
            val onClickLambda: () -> Unit = remember(item.screen.route, navController) {
                click@{
                    if (!latestNavigationEnabled) return@click
                    val itemRoute = item.screen.route
                    // Read when tapped: two taps within a frame must not act on a route already left.
                    val liveRoute = navController.currentBackStackEntry?.destination?.route ?: return@click
                    if (liveRoute == itemRoute) return@click
                    if (liveRoute == Screen.Search.route && navController.previousBackStackEntry != null) {
                        // Search opened over a tab (from the Home header) is left first: otherwise the tab's
                        // stack would be saved with Search on top, and come back with it. The tab right under
                        // it is simply shown again; any other one is reached as usual, saving the one below.
                        val underSearch = navController.previousBackStackEntry?.destination?.route == itemRoute
                        navController.popBackStack()
                        if (underSearch) return@click
                    }
                    navController.navigateToTopLevelSafely(itemRoute)
                }
            }
            CustomNavigationBarItem(
                modifier = Modifier.weight(1f),
                selected = isSelected,
                onClick = onClickLambda,
                enabled = currentRoute != null,
                compactMode = compactMode,
                icon = iconLambda,
                selectedIcon = selectedIconLambda,
                label = labelLambda,
                contentDescription = localizedLabel,
                alwaysShowLabel = true,
                selectedIconColor = selectedColor,
                unselectedIconColor = unselectedColor,
                selectedTextColor = selectedColor,
                unselectedTextColor = unselectedColor,
                indicatorColor = indicatorColorFromTheme
            )
        }
    }
}

@Composable
fun PlayerInternalNavigationBar(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    navBarStyle: String,
    compactMode: Boolean,
    bottomBarPadding: Dp = 0.dp,
) {
    PlayerInternalNavigationItemsRow(
        navController = navController,
        navItems = navItems,
        currentRoute = currentRoute,
        navBarStyle = navBarStyle,
        compactMode = compactMode,
        bottomBarPadding = bottomBarPadding,
        modifier = modifier
    )
}

/**
 * The next smaller step of [base] after [current], or null once [MIN_LABEL_SP] is reached or the
 * size is not in sp. Tracking scales with the text, so a shrunk label keeps its proportions.
 */
internal fun smallerLabel(base: TextStyle, current: TextStyle): TextStyle? {
    if (!base.fontSize.isSp || !current.fontSize.isSp) return null
    val now = current.fontSize.value
    if (now <= MIN_LABEL_SP) return null
    return labelAt(base, (now - LABEL_STEP_SP).coerceAtLeast(MIN_LABEL_SP))
}

/** [base] at [size] sp, never larger than its own size, with tracking in proportion. */
internal fun labelAt(base: TextStyle, size: Float): TextStyle {
    if (!base.fontSize.isSp || size >= base.fontSize.value) return base
    val tracking = base.letterSpacing.takeIf { it.isSp }
        ?.let { (it.value * size / base.fontSize.value).sp } ?: base.letterSpacing
    return base.copy(fontSize = size.sp, letterSpacing = tracking)
}

// Low enough for the longest label ("Медиатека") on a 320 dp wide window at 130 % font scale.
private const val MIN_LABEL_SP = 6f
private const val LABEL_STEP_SP = 0.5f
