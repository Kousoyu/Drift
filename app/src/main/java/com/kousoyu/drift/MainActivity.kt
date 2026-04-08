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
import androidx.compose.runtime.saveable.rememberSaveable
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
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import com.kousoyu.drift.data.SourceManager
import com.kousoyu.drift.data.UpdateManager
import kotlinx.coroutines.launch

// In-memory holder for chapter list (passed between detail → reader)
object NovelChapterHolder {
    var chapters: List<com.kousoyu.drift.data.NovelChapter> = emptyList()
}

// ─── Navigation Routes ────────────────────────────────────────────────────────
object DriftRoutes {
    const val MAIN         = "main"
    const val NOTICE       = "notice"
    const val EXPLORE      = "explore"
    const val SEARCH       = "search"
    const val PROFILE_EDIT = "profile_edit"
    const val DETAIL  = "detail?url={url}&sourceName={sourceName}"
    const val READER  = "reader?url={url}&mangaUrl={mangaUrl}&chapterName={chapterName}&sourceName={sourceName}"
    const val NOVEL_DETAIL = "novel_detail?url={url}"
    const val NOVEL_READER = "novel_reader?url={url}&chapterName={chapterName}"
    const val NOVEL_SEARCH = "novel_search"
    const val NOVEL_BOOKSHELF = "novel_bookshelf"
    
    fun createDetailRoute(url: String, sourceName: String): String {
        return "detail?url=${enc(url)}&sourceName=${enc(sourceName)}"
    }
    fun createReaderRoute(chapterUrl: String, mangaUrl: String, chapterName: String, sourceName: String): String {
        return "reader?url=${enc(chapterUrl)}&mangaUrl=${enc(mangaUrl)}&chapterName=${enc(chapterName)}&sourceName=${enc(sourceName)}"
    }
    fun createNovelDetailRoute(url: String): String {
        return "novel_detail?url=${enc(url)}"
    }
    fun createNovelReaderRoute(chapterUrl: String, chapterName: String): String {
        return "novel_reader?url=${enc(chapterUrl)}&chapterName=${enc(chapterName)}"
    }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

private const val NAV_ANIM_MS = 230

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ── Initialize shared HTTP client (connection pool + disk cache) ──
        com.kousoyu.drift.data.DriftHttpClient.get(this)

        // ── Preconnect to source domains (DNS + TCP + TLS warmup) ──
        Thread { com.kousoyu.drift.data.DriftHttpClient.preconnect() }.start()

        // ── Initialize source managers with app context ──
        SourceManager.init(this)

        // ── Initialize auth cache (instant profile restore) ──
        com.kousoyu.drift.data.AuthManager.initialize(applicationContext)

        // ── Coil: 256MB disk cache + Referer for image servers ──
        val coilClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                // readpai.com (bilinovel covers) requires Referer
                val newReq = if (host.contains("readpai") || host.contains("bilinovel") || host.contains("linovelib")) {
                    req.newBuilder()
                        .header("Referer", "https://www.linovelib.com/")
                        .build()
                } else req
                chain.proceed(newReq)
            }
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(coilClient)
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(256L * 1024 * 1024)
                        .build()
                }
                .crossfade(150)
                .build()
        )

        setContent {
            val themeViewModel: ThemeViewModel = viewModel(
                factory = ThemeViewModel.Factory(this)
            )
            val themeMode by themeViewModel.themeMode.collectAsState()

            // ─── Update Manager ──────────────────────────────────────────
            val updateManager = remember { UpdateManager(this) }
            val updateState by updateManager.updateState.collectAsState()
            val coroutineScope = rememberCoroutineScope()

            // Auto-check for updates on launch (silent, non-blocking)
            LaunchedEffect(Unit) {
                try { updateManager.checkUpdate() } catch (_: Exception) { }
            }

            if (themeMode == ThemeMode.UNINITIALIZED) return@setContent

            val isDark = when (themeMode) {
                ThemeMode.DARK   -> true
                ThemeMode.LIGHT  -> false
                ThemeMode.SYSTEM, ThemeMode.UNINITIALIZED -> isSystemInDarkTheme()
            }

            DriftTheme(darkTheme = isDark) {
                // ─── Show update dialog when available ────────────────────
                if (updateState is UpdateManager.UpdateState.Available) {
                    val info = (updateState as UpdateManager.UpdateState.Available).info
                    UpdateDialog(
                        info = info,
                        currentVersion = updateManager.getCurrentVersionName(),
                        onUpdate = { updateManager.downloadAndInstall(info) },
                        onDismiss = { updateManager.dismiss() }
                    )
                }

                val navController = rememberNavController()

                // Download progress bar at top
                Column {
                    if (updateState is UpdateManager.UpdateState.Downloading) {
                        UpdateDownloadingBar(
                            progress = (updateState as UpdateManager.UpdateState.Downloading).progress
                        )
                    }

                NavHost(
                    navController = navController,
                    startDestination = DriftRoutes.MAIN,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(DriftRoutes.MAIN) {
                    DriftApp(
                            navController    = navController,
                            currentTheme     = themeMode,
                            onThemeChange    = { themeViewModel.setTheme(it) },
                            updateManager    = updateManager
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

                    // ─ Novel Search screen
                    composable(
                        route = DriftRoutes.NOVEL_SEARCH,
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(NAV_ANIM_MS)) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) }
                    ) {
                        NovelSearchScreen(
                            onBack = { navController.popBackStack() },
                            onNovelClick = { url -> navController.navigate(DriftRoutes.createNovelDetailRoute(url)) }
                        )
                    }

                    // ─ Novel Bookshelf screen
                    composable(
                        route = DriftRoutes.NOVEL_BOOKSHELF,
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(NAV_ANIM_MS)) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) }
                    ) {
                        NovelBookshelfScreen(
                            onBack = { navController.popBackStack() },
                            onNovelClick = { url -> navController.navigate(DriftRoutes.createNovelDetailRoute(url)) }
                        )
                    }

                    // ─ Novel Detail screen
                    composable(
                        route = DriftRoutes.NOVEL_DETAIL,
                        arguments = listOf(
                            androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType; defaultValue = "" }
                        ),
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) },
                        exitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) },
                        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(NAV_ANIM_MS)) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) }
                    ) { backStackEntry ->
                        val url = backStackEntry.arguments?.getString("url") ?: ""
                        NovelDetailScreen(
                            detailUrl = url,
                            onBack = { navController.popBackStack() },
                            onChapterClick = { chapterUrl, chapterName, allChapters ->
                                NovelChapterHolder.chapters = allChapters
                                navController.navigate(DriftRoutes.createNovelReaderRoute(chapterUrl, chapterName))
                            }
                        )
                    }

                    // ─ Novel Reader screen
                    composable(
                        route = DriftRoutes.NOVEL_READER,
                        arguments = listOf(
                            androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType; defaultValue = "" },
                            androidx.navigation.navArgument("chapterName") { type = androidx.navigation.NavType.StringType; defaultValue = "" }
                        ),
                        enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) },
                        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(NAV_ANIM_MS)) }
                    ) { backStackEntry ->
                        val url = backStackEntry.arguments?.getString("url") ?: ""
                        val chapterName = backStackEntry.arguments?.getString("chapterName") ?: ""
                        NovelReaderScreen(
                            chapterUrl = url,
                            chapterName = chapterName,
                            allChapters = NovelChapterHolder.chapters,
                            onBack = { navController.popBackStack() },
                            onNavigateChapter = { newUrl, newName ->
                                // Replace current reader with new chapter
                                navController.navigate(DriftRoutes.createNovelReaderRoute(newUrl, newName)) {
                                    popUpTo(DriftRoutes.NOVEL_READER) { inclusive = true }
                                }
                            }
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
                } // end Column
            }
        }
    }
}

// ─── Main Shell ───────────────────────────────────────────────────────────────

@Composable
fun DriftApp(
    navController: NavHostController,
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    updateManager: UpdateManager? = null
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs  = listOf("漫画", "小说", "我")
    val icons = listOf(
        Icons.AutoMirrored.Filled.MenuBook,
        Icons.Filled.Book,
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
                1 -> NovelScreen(
                    onNavigateToDetail = { url -> navController.navigate(DriftRoutes.createNovelDetailRoute(url)) },
                    onNavigateToSearch = { navController.navigate(DriftRoutes.NOVEL_SEARCH) },
                    onNavigateToBookshelf = { navController.navigate(DriftRoutes.NOVEL_BOOKSHELF) }
                )
                2 -> ProfileScreen(
                    currentTheme  = currentTheme,
                    onThemeChange = onThemeChange,
                    onNavigateToEdit = { navController.navigate(DriftRoutes.PROFILE_EDIT) },
                    updateManager = updateManager
                )
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