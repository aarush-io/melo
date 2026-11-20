package `in`.melo.presentation.navigation

internal fun isMainRootRoute(route: String?): Boolean = when (route) {
    Screen.Home.route,
    Screen.Explore.route,
    Screen.MyWave.route,
    Screen.Library.route,
    Screen.Podcasts.route -> true
    else -> false
}

internal fun mainRootRouteIndex(route: String?): Int? = when (route) {
    Screen.Home.route -> 0
    Screen.Explore.route -> 1
    Screen.MyWave.route -> 2
    Screen.Library.route -> 3
    Screen.Podcasts.route -> 4
    else -> null
}
