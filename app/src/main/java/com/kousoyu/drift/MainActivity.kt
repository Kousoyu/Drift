package com.kousoyu.drift

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kousoyu.drift.ui.theme.DriftTheme
import com.kousoyu.drift.ui.theme.ThemeMode
import com.kousoyu.drift.ui.theme.ThemeViewModel
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel

// ─── Navigation Routes ────────────────────────────────────────────────────────
object DriftRoutes {
    const val MAIN         = "main"
    const val NOTICE       = "notice"
    const val EXPLORE      = "explore"
    const val SEARCH       = "search"
    const val PROFILE_EDIT = "profile_edit"
    const val DETAIL  = "detail?url={url}&sourceName={sourceName}"
    const val READER  = "reader?url={url}&mangaUrl={mangaUrl}&chapterName={chapterName}&sourceName={sourceName}"
    
    fun createDetailRoute(url: String, sourceName: String): String {
        return "detail?url=${java.net.URLEncoder.encode(url, "UTF-8").replace("+", "%20")}&sourceName=${java.net.URLEncoder.encode(sourceName, "UTF-8").replace("+", "%20")}"
    }
    fun createReaderRoute(chapterUrl: String, mangaUrl: String, chapterName: String, sourceName: String): String {
        return "reader?url=${java.net.URLEncoder.encode(chapterUrl, "UTF-8").replace("+", "%20")}&mangaUrl=${java.net.URLEncoder.encode(mangaUrl, "UTF-8").replace("+", "%20")}&chapterName=${java.net.URLEncoder.encode(chapterName, "UTF-8").replace("+", "%20")}&sourceName=${java.net.URLEncoder.encode(sourceName, "UTF-8").replace("+", "%20")}"
    }
}

private const val NAV_ANIM_MS = 230

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeViewModel: ThemeViewModel = viewModel(
                factory = ThemeViewModel.Factory(this)
            )
            val themeMode by themeViewModel.themeMode.collectAsState()

            LaunchedEffect(Unit) {
                com.kousoyu.drift.data.SourceManager.initialize(this@MainActivity)
            }

            if (themeMode == ThemeMode.UNINITIALIZED) return@setContent

            val isDark = when (themeMode) {
                ThemeMode.DARK   -> true
                ThemeMode.LIGHT  -> false
                ThemeMode.SYSTEM, ThemeMode.UNINITIALIZED -> isSystemInDarkTheme()
            }

            DriftTheme(darkTheme = isDark) {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = DriftRoutes.MAIN
                ) {
                    composable(DriftRoutes.MAIN) {
                    DriftApp(
                            navController    = navController,
                            currentTheme     = themeMode,
                            onThemeChange    = { themeViewModel.setTheme(it) }
                        )
                    }

                    // ─ Notice screen — slides in from the right like a native sub-page
                    composable(
                        route = DriftRoutes.NOTICE,
                        enterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { fullWidth -> fullWidth },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        exitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> fullWidth },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        popEnterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { fullWidth -> -fullWidth },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        popExitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> fullWidth },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        }
                    ) {
                        NoticeScreen(onBack = { navController.popBackStack() })
                    }

                    // ─ Search screen
                    composable(
                        route = DriftRoutes.SEARCH,
                        enterTransition = {
                            androidx.compose.animation.fadeIn(animationSpec = tween(NAV_ANIM_MS)) + androidx.compose.animation.slideInVertically(initialOffsetY = { -it/4 })
                        },
                        exitTransition = {
                            androidx.compose.animation.fadeOut(animationSpec = tween(NAV_ANIM_MS)) + androidx.compose.animation.slideOutVertically(targetOffsetY = { -it/4 })
                        }
                    ) {
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToDetail = { url, src -> navController.navigate(DriftRoutes.createDetailRoute(url, src)) }
                        )
                    }

                    // ─ Explore screen — slides in from the right
                    composable(
                        route = DriftRoutes.EXPLORE,
                        enterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        exitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        popEnterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        popExitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        }
                    ) {
                        ExploreScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToDetail = { url, src -> navController.navigate(DriftRoutes.createDetailRoute(url, src)) }
                        )
                    }
                    
                    // ─ Detail screen
                    composable(
                        route = DriftRoutes.DETAIL,
                        arguments = listOf(
                            androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType; defaultValue = "" },
                            androidx.navigation.navArgument("sourceName") { type = androidx.navigation.NavType.StringType; defaultValue = "" }
                        )
                    ) { backStackEntry ->
                        val url = backStackEntry.arguments?.getString("url") ?: ""
                        val sourceName = backStackEntry.arguments?.getString("sourceName") ?: ""
                        DetailScreen(
                            urlEncoded = url,
                            sourceNameEncoded = sourceName,
                            onBack = { navController.popBackStack() },
                            onChapterClick = { chapterUrl, chapterName ->
                                navController.navigate(DriftRoutes.createReaderRoute(chapterUrl, url, chapterName, sourceName))
                            }
                        )
                    }
                    
                    // ─ Reader screen
                    composable(
                        route = DriftRoutes.READER,
                        arguments = listOf(
                            androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType; defaultValue = "" },
                            androidx.navigation.navArgument("mangaUrl") { type = androidx.navigation.NavType.StringType; defaultValue = "" },
                            androidx.navigation.navArgument("chapterName") { type = androidx.navigation.NavType.StringType; defaultValue = "" },
                            androidx.navigation.navArgument("sourceName") { type = androidx.navigation.NavType.StringType; defaultValue = "" }
                        )
                    ) { backStackEntry ->
                        val url = java.net.URLEncoder.encode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8")
                        val mangaUrl = java.net.URLEncoder.encode(backStackEntry.arguments?.getString("mangaUrl") ?: "", "UTF-8")
                        val chapterName = java.net.URLEncoder.encode(backStackEntry.arguments?.getString("chapterName") ?: "", "UTF-8")
                        val sourceName = java.net.URLEncoder.encode(backStackEntry.arguments?.getString("sourceName") ?: "", "UTF-8")
                        ReaderScreen(
                            urlEncoded = url,
                            mangaUrlEncoded = mangaUrl,
                            chapterNameEncoded = chapterName,
                            sourceNameEncoded = sourceName,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // ─ Profile Edit screen
                    composable(
                        route = DriftRoutes.PROFILE_EDIT,
                        enterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        exitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        popEnterTransition = {
                            slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        },
                        popExitTransition = {
                            slideOutHorizontally(
                                targetOffsetX = { it },
                                animationSpec = tween(NAV_ANIM_MS)
                            )
                        }
                    ) {
                        val authVm: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                        ProfileEditScreen(
                            vm = authVm,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

// ─── Main Shell ───────────────────────────────────────────────────────────────

@Composable
fun DriftApp(
    navController: NavHostController,
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val tabs  = listOf("漫画", "小说", "追番", "AI 助手", "我")
    val icons = listOf(
        Icons.AutoMirrored.Filled.MenuBook,
        Icons.Filled.Book,
        Icons.Filled.SlowMotionVideo,
        Icons.Filled.AutoAwesome,
        Icons.Filled.Person
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = { Icon(imageVector = icons[index], contentDescription = title) },
                        label = {
                            if (selectedIndex == index) {
                                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                        },
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                            unselectedIconColor = Color.Gray.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedIndex) {
                0 -> MangaScreen(
                    onNavigateToNotice   = { navController.navigate(DriftRoutes.NOTICE) },
                    onNavigateToExplore  = { navController.navigate(DriftRoutes.EXPLORE) },
                    onNavigateToSearch   = { navController.navigate(DriftRoutes.SEARCH) },
                    onNavigateToDetail   = { url, src -> navController.navigate(DriftRoutes.createDetailRoute(url, src)) }
                )
                4 -> ProfileScreen(
                    currentTheme  = currentTheme,
                    onThemeChange = onThemeChange,
                    onNavigateToEdit = { navController.navigate(DriftRoutes.PROFILE_EDIT) }
                )

                else -> ComingSoonScreen(label = tabs[selectedIndex])
            }
        }
    }
}



@Composable
fun ComingSoonScreen(label: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "即将呈现: $label",
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DriftAppPreview() {
    DriftTheme {
        val navController = rememberNavController()
        DriftApp(
            navController = navController,
            currentTheme  = com.kousoyu.drift.ui.theme.ThemeMode.SYSTEM,
            onThemeChange = {}
        )
    }
}