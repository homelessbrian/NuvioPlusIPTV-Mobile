package com.nuvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.addons.AddonsScreen
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.home.HomeScreen
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsScreen
import kotlinx.serialization.Serializable

@Serializable
object TabsRoute

@Serializable
data class DetailRoute(val type: String, val id: String)

@Serializable
data class StreamRoute(
    val type: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
)

enum class AppScreenTab {
    Home,
    Addons,
}

@Composable
fun AppScreen(
    tab: AppScreenTab,
    modifier: Modifier = Modifier,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
) {
    when (tab) {
        AppScreenTab.Home -> HomeScreen(
            modifier = modifier,
            onPosterClick = onPosterClick,
        )
        AppScreenTab.Addons -> AddonsScreen(modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .build()
    }
    NuvioTheme {
        val navController = rememberNavController()
        var selectedTab by rememberSaveable { mutableStateOf(AppScreenTab.Home) }

        val onPlay: (String, String, String, String?, String?, String?, Int?, Int?, String?, String?) -> Unit =
            { type, videoId, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail ->
                navController.navigate(
                    StreamRoute(
                        type = type,
                        videoId = videoId,
                        title = title,
                        logo = logo,
                        poster = poster,
                        background = background,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        episodeThumbnail = episodeThumbnail,
                    )
                )
            }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets(0),
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == AppScreenTab.Home,
                            onClick = { selectedTab = AppScreenTab.Home },
                            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                            label = { Text("Home") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == AppScreenTab.Addons,
                            onClick = { selectedTab = AppScreenTab.Addons },
                            icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                            label = { Text("Addons") },
                        )
                    }
                },
            ) { innerPadding ->
                AppScreen(
                    tab = selectedTab,
                    modifier = Modifier.padding(innerPadding),
                    onPosterClick = { meta ->
                        navController.navigate(DetailRoute(type = meta.type, id = meta.id))
                    },
                )
            }

            NavHost(
                navController = navController,
                startDestination = TabsRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable<TabsRoute> {
                    Unit
                }
                composable<DetailRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<DetailRoute>()
                    MetaDetailsScreen(
                        type = route.type,
                        id = route.id,
                        onBack = {
                            MetaDetailsRepository.clear()
                            navController.popBackStack()
                        },
                        onPlay = onPlay,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<StreamRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<StreamRoute>()
                    StreamsScreen(
                        type = route.type,
                        videoId = route.videoId,
                        title = route.title,
                        logo = route.logo,
                        poster = route.poster,
                        background = route.background,
                        seasonNumber = route.seasonNumber,
                        episodeNumber = route.episodeNumber,
                        episodeTitle = route.episodeTitle,
                        episodeThumbnail = route.episodeThumbnail,
                        onBack = {
                            StreamsRepository.clear()
                            navController.popBackStack()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
