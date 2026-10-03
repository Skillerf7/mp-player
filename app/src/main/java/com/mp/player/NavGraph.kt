package com.mp.player

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.mpNavGraph(nav: NavController) {
    composable(Routes.LIBRARY_HOME) { LibraryHomeScreen(nav) }
    composable(Routes.PLAYER) { PlayerScreen(nav) }
    composable(Routes.SETTINGS) { SettingsHomeScreen(nav) }
    composable("settings/audio") { AudioSettingsScreen(nav) }
    composable("settings/library") { LibrarySettingsScreen(nav) }
    composable("settings/hires") { HiResSettingsScreen(nav) }
    composable(Routes.SEARCH) { SearchScreen(nav) }
    composable("eq") { EqScreen(nav) }
    composable("analysis") { AnalysisScreen(nav) }
    composable("settings/playback") { PlaybackSettingsScreen(nav) }
    composable("duplicates") { DuplicatesScreen(nav) }
    composable(Routes.QUEUE) { QueueScreen(nav) }
    composable(Routes.PRESETS) { PresetsScreen(nav) }
    composable("playlist/{name}") { entry ->
        PlaylistScreen(NavArgs.decode(entry.arguments?.getString("name")), nav)
    }
    composable("category/{kind}") { entry ->
        val kind = entry.arguments?.getString("kind") ?: "all"
        CategoryScreen(kind, nav)
    }
}
