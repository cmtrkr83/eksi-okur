package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.local.ThemeMode
import com.example.data.local.UserPreferences
import com.example.data.model.DEFAULT_EKSI_CHANNELS
import com.example.notifications.MessageNotificationHelper
import com.example.notifications.MessageNotificationScheduler
import com.example.ui.components.EmptyEditorialView
import com.example.ui.screens.AuthorScreen
import com.example.ui.screens.FeedScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.MessageThreadScreen
import com.example.ui.screens.MessagesScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TopicScreen
import com.example.ui.theme.EksiOkurTheme
import com.example.ui.viewmodel.AuthorViewModel
import com.example.ui.viewmodel.FeedViewModel
import com.example.ui.viewmodel.LibraryViewModel
import com.example.ui.viewmodel.MessagesViewModel
import com.example.ui.viewmodel.SearchViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.TopicViewModel
import kotlinx.coroutines.launch

private object AppRoutes {
    const val FEED = "feed"
    const val SEARCH = "search"
    const val MESSAGES = "messages"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val TOPIC = "topic?slug={slug}&page={page}"
    const val ENTRY = "entry/{entryId}"
    const val AUTHOR = "author/{slug}"
    const val MESSAGE_THREAD = "message_thread?url={url}"

    fun topicRoute(slugOrUrl: String, page: Int = 1): String =
        "topic?slug=${Uri.encode(slugOrUrl)}&page=$page"

    fun entryRoute(entryId: Long): String = "entry/$entryId"

    fun authorRoute(authorSlug: String): String =
        "author/${Uri.encode(authorSlug)}"

    fun messageThreadRoute(threadUrl: String): String =
        "message_thread?url=${Uri.encode(threadUrl)}"
}

private enum class TopDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    FEED(AppRoutes.FEED, "Gündem", Icons.Rounded.LocalFireDepartment, Icons.Outlined.LocalFireDepartment),
    SEARCH(AppRoutes.SEARCH, "Ara", Icons.Rounded.Search, Icons.Outlined.Search),
    MESSAGES(AppRoutes.MESSAGES, "Mesajlar", Icons.Rounded.Mail, Icons.Outlined.Mail),
    LIBRARY(AppRoutes.LIBRARY, "Arşiv", Icons.Rounded.Bookmark, Icons.Outlined.BookmarkBorder),
    SETTINGS(AppRoutes.SETTINGS, "Ayarlar", Icons.Rounded.Settings, Icons.Outlined.Settings)
}

class MainActivity : ComponentActivity() {
    private var pendingMessageThreadUrl by mutableStateOf<String?>(null)
    private var pendingOpenMessagesTab by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeNotificationIntent(intent)
        val appContainer = (application as EksiApplication).container

        setContent {
            val prefs by appContainer.repository.userPreferences.collectAsStateWithLifecycle(
                initialValue = UserPreferences()
            )
            val isSystemDark = isSystemInDarkTheme()
            val darkTheme = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            EksiOkurTheme(
                darkTheme = darkTheme,
                dynamicColor = prefs.useDynamicColor,
                entryFontScale = prefs.fontScale
            ) {
                EksiOkurApp(
                    factory = EksiViewModelFactory(appContainer.repository),
                    prefs = prefs,
                    onMarkNotificationPermissionAsked = {
                        appContainer.repository.preferences.setHasAskedNotificationPermission(true)
                    },
                    pendingMessageThreadUrl = pendingMessageThreadUrl,
                    pendingOpenMessagesTab = pendingOpenMessagesTab,
                    onConsumeNotificationNav = {
                        pendingMessageThreadUrl = null
                        pendingOpenMessagesTab = false
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val threadUrl = intent.getStringExtra(MessageNotificationHelper.EXTRA_MESSAGE_THREAD_URL)
        val openMessages = intent.getBooleanExtra(MessageNotificationHelper.EXTRA_OPEN_MESSAGES_TAB, false)
        if (!threadUrl.isNullOrBlank()) {
            pendingMessageThreadUrl = threadUrl
            pendingOpenMessagesTab = true
            intent.removeExtra(MessageNotificationHelper.EXTRA_MESSAGE_THREAD_URL)
            intent.removeExtra(MessageNotificationHelper.EXTRA_OPEN_MESSAGES_TAB)
        } else if (openMessages) {
            pendingOpenMessagesTab = true
            intent.removeExtra(MessageNotificationHelper.EXTRA_OPEN_MESSAGES_TAB)
        }
    }
}

@Composable
fun EksiOkurApp(
    factory: EksiViewModelFactory,
    prefs: UserPreferences,
    onMarkNotificationPermissionAsked: suspend () -> Unit,
    pendingMessageThreadUrl: String? = null,
    pendingOpenMessagesTab: Boolean = false,
    onConsumeNotificationNav: () -> Unit = {}
) {
    val expandSpoilersByDefault = prefs.expandSpoilersByDefault
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && prefs.messageNotificationsEnabled) {
            MessageNotificationScheduler.syncPeriodicWork(context, true)
        }
    }

    LaunchedEffect(
        prefs.loggedInNick,
        prefs.messageNotificationsEnabled,
        prefs.hasAskedNotificationPermission
    ) {
        if (prefs.loggedInNick != null && prefs.messageNotificationsEnabled) {
            MessageNotificationScheduler.syncPeriodicWork(context, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !prefs.hasAskedNotificationPermission &&
                !MessageNotificationHelper.hasNotificationPermission(context)
            ) {
                coroutineScope.launch { onMarkNotificationPermissionAsked() }
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val feedViewModel: FeedViewModel = viewModel(factory = factory)
    val searchViewModel: SearchViewModel = viewModel(factory = factory)
    val messagesViewModel: MessagesViewModel = viewModel(factory = factory)
    val libraryViewModel: LibraryViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
    val detailPaneTopicViewModel: TopicViewModel = viewModel(key = "wide_detail_topic", factory = factory)

    val feedState by feedViewModel.uiState.collectAsStateWithLifecycle()

    val isTopLevelRoute = TopDestination.entries.any { it.route == currentRoute }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 840.dp

        val navigateTopLevel: (TopDestination) -> Unit = { dest ->
            navController.navigate(dest.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        LaunchedEffect(pendingMessageThreadUrl, pendingOpenMessagesTab) {
            val threadUrl = pendingMessageThreadUrl
            if (!threadUrl.isNullOrBlank()) {
                navigateTopLevel(TopDestination.MESSAGES)
                messagesViewModel.loadInbox()
                navController.navigate(AppRoutes.messageThreadRoute(threadUrl))
                onConsumeNotificationNav()
            } else if (pendingOpenMessagesTab) {
                navigateTopLevel(TopDestination.MESSAGES)
                messagesViewModel.loadInbox()
                onConsumeNotificationNav()
            }
        }

        val openTopicOrEntry: (String, Long?, Int) -> Unit = { slugUrl, entryId, page ->
            if (isExpandedWidth && currentRoute == AppRoutes.FEED) {
                if (entryId != null) {
                    feedViewModel.setActiveDetail(slugUrl, entryId)
                    detailPaneTopicViewModel.loadSingleEntry(entryId)
                } else {
                    feedViewModel.setActiveDetail(slugUrl, null)
                    detailPaneTopicViewModel.loadTopic(slugUrl, page = page)
                }
            } else {
                if (entryId != null) {
                    navController.navigate(AppRoutes.entryRoute(entryId))
                } else {
                    navController.navigate(AppRoutes.topicRoute(slugUrl, page))
                }
            }
        }

        val navigateToChannel: (String) -> Unit = { channelSlug ->
            val found = DEFAULT_EKSI_CHANNELS.firstOrNull {
                it.slug.equals(channelSlug, ignoreCase = true)
            } ?: com.example.data.model.EksiChannel(channelSlug, channelSlug)
            feedViewModel.selectChannel(found)
            navigateTopLevel(TopDestination.FEED)
        }

        val openMessageToAuthor: (String) -> Unit = { nick ->
            messagesViewModel.openCompose(prefillNick = nick)
            navigateTopLevel(TopDestination.MESSAGES)
        }

        if (isExpandedWidth) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    TopDestination.entries.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(dest) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label
                                )
                            },
                            label = { Text(dest.label) },
                            modifier = Modifier.testTag("rail_${dest.route}")
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.FEED,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(AppRoutes.FEED) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                FeedScreen(
                                    viewModel = feedViewModel,
                                    selectedSlug = feedState.activeDetailSlug,
                                    onTopicClick = { item ->
                                        openTopicOrEntry(item.slugUrl, item.entryId, 1)
                                    },
                                    onQuickSearchClick = { navigateTopLevel(TopDestination.SEARCH) },
                                    modifier = Modifier
                                        .width(380.dp)
                                        .fillMaxHeight()
                                )
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    if (feedState.activeDetailSlug == null && feedState.activeDetailEntryId == null) {
                                        EmptyEditorialView(
                                            title = "Bir başlık seçin",
                                            subtitle = "Okumak için soldaki gündem listesinden bir başlığa dokunun."
                                        )
                                    } else {
                                        TopicScreen(
                                            viewModel = detailPaneTopicViewModel,
                                            showBackButton = false,
                                            onBackClick = {},
                                            onNavigateToTopic = { target ->
                                                feedViewModel.setActiveDetail(target, null)
                                                detailPaneTopicViewModel.loadTopic(target, 1)
                                            },
                                            onNavigateToAuthor = { authorSlug ->
                                                navController.navigate(AppRoutes.authorRoute(authorSlug))
                                            },
                                            onNavigateToChannel = navigateToChannel
                                        )
                                    }
                                }
                            }
                        }

                        composable(AppRoutes.SEARCH) {
                            SearchScreen(
                                viewModel = searchViewModel,
                                onOpenTopic = { slug -> navController.navigate(AppRoutes.topicRoute(slug, 1)) },
                                onOpenAuthor = { slug -> navController.navigate(AppRoutes.authorRoute(slug)) }
                            )
                        }

                        composable(AppRoutes.MESSAGES) {
                            MessagesScreen(
                                viewModel = messagesViewModel,
                                onOpenThread = { threadUrl ->
                                    navController.navigate(AppRoutes.messageThreadRoute(threadUrl))
                                }
                            )
                        }

                        composable(AppRoutes.LIBRARY) {
                            LibraryScreen(
                                viewModel = libraryViewModel,
                                onOpenTopic = { slug, page -> navController.navigate(AppRoutes.topicRoute(slug, page)) },
                                onOpenEntry = { entryId -> navController.navigate(AppRoutes.entryRoute(entryId)) }
                            )
                        }

                        composable(AppRoutes.SETTINGS) {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onOpenAuthor = { slug -> navController.navigate(AppRoutes.authorRoute(slug)) }
                            )
                        }

                        composable(
                            route = AppRoutes.TOPIC,
                            arguments = listOf(
                                navArgument("slug") { type = NavType.StringType; defaultValue = "" },
                                navArgument("page") { type = NavType.IntType; defaultValue = 1 }
                            )
                        ) { entry ->
                            val slug = entry.arguments?.getString("slug").orEmpty()
                            val page = entry.arguments?.getInt("page") ?: 1
                            val topicVm: TopicViewModel = viewModel(entry, factory = factory)
                            LaunchedEffect(slug, page) {
                                if (slug.isNotBlank() && topicVm.uiState.value.requestedSlugOrUrl != slug) {
                                    topicVm.loadTopic(slug, page)
                                }
                            }
                            TopicScreen(
                                viewModel = topicVm,
                                showBackButton = true,
                                onBackClick = { navController.popBackStack() },
                                onNavigateToTopic = { target ->
                                    navController.navigate(AppRoutes.topicRoute(target, 1))
                                },
                                onNavigateToAuthor = { authorSlug ->
                                    navController.navigate(AppRoutes.authorRoute(authorSlug))
                                },
                                onNavigateToChannel = navigateToChannel
                            )
                        }

                        composable(
                            route = AppRoutes.ENTRY,
                            arguments = listOf(navArgument("entryId") { type = NavType.LongType })
                        ) { entry ->
                            val entryId = entry.arguments?.getLong("entryId") ?: 0L
                            val topicVm: TopicViewModel = viewModel(entry, factory = factory)
                            LaunchedEffect(entryId) {
                                if (entryId > 0 && topicVm.uiState.value.singleEntryId != entryId) {
                                    topicVm.loadSingleEntry(entryId)
                                }
                            }
                            TopicScreen(
                                viewModel = topicVm,
                                showBackButton = true,
                                onBackClick = { navController.popBackStack() },
                                onNavigateToTopic = { target ->
                                    navController.navigate(AppRoutes.topicRoute(target, 1))
                                },
                                onNavigateToAuthor = { authorSlug ->
                                    navController.navigate(AppRoutes.authorRoute(authorSlug))
                                },
                                onNavigateToChannel = navigateToChannel
                            )
                        }

                        composable(
                            route = AppRoutes.AUTHOR,
                            arguments = listOf(navArgument("slug") { type = NavType.StringType })
                        ) { entry ->
                            val slug = entry.arguments?.getString("slug").orEmpty()
                            val authorVm: AuthorViewModel = viewModel(entry, factory = factory)
                            LaunchedEffect(slug) {
                                if (slug.isNotBlank() && authorVm.uiState.value.authorSlug != slug) {
                                    authorVm.loadAuthor(slug)
                                }
                            }
                            AuthorScreen(
                                viewModel = authorVm,
                                expandSpoilersByDefault = expandSpoilersByDefault,
                                onBackClick = { navController.popBackStack() },
                                onNavigateToTopic = { target ->
                                    navController.navigate(AppRoutes.topicRoute(target, 1))
                                },
                                onNavigateToAuthor = { authorSlug ->
                                    navController.navigate(AppRoutes.authorRoute(authorSlug))
                                },
                                onSendMessageClick = openMessageToAuthor
                            )
                        }

                        composable(
                            route = AppRoutes.MESSAGE_THREAD,
                            arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })
                        ) { entry ->
                            val threadUrl = entry.arguments?.getString("url").orEmpty()
                            MessageThreadScreen(
                                viewModel = messagesViewModel,
                                threadUrl = threadUrl,
                                expandSpoilersByDefault = expandSpoilersByDefault,
                                onBackClick = { navController.popBackStack() },
                                onNavigateToTopic = { target ->
                                    navController.navigate(AppRoutes.topicRoute(target, 1))
                                },
                                onNavigateToAuthor = { authorSlug ->
                                    navController.navigate(AppRoutes.authorRoute(authorSlug))
                                }
                            )
                        }
                    }
                }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (isTopLevelRoute) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            TopDestination.entries.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = dest.label,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_${dest.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = AppRoutes.FEED,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    composable(AppRoutes.FEED) {
                        FeedScreen(
                            viewModel = feedViewModel,
                            onTopicClick = { item ->
                                openTopicOrEntry(item.slugUrl, item.entryId, 1)
                            },
                            onQuickSearchClick = { navigateTopLevel(TopDestination.SEARCH) }
                        )
                    }

                    composable(AppRoutes.SEARCH) {
                        SearchScreen(
                            viewModel = searchViewModel,
                            onOpenTopic = { slug -> navController.navigate(AppRoutes.topicRoute(slug, 1)) },
                            onOpenAuthor = { slug -> navController.navigate(AppRoutes.authorRoute(slug)) }
                        )
                    }

                    composable(AppRoutes.MESSAGES) {
                        MessagesScreen(
                            viewModel = messagesViewModel,
                            onOpenThread = { threadUrl ->
                                navController.navigate(AppRoutes.messageThreadRoute(threadUrl))
                            }
                        )
                    }

                    composable(AppRoutes.LIBRARY) {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onOpenTopic = { slug, page -> navController.navigate(AppRoutes.topicRoute(slug, page)) },
                            onOpenEntry = { entryId -> navController.navigate(AppRoutes.entryRoute(entryId)) }
                        )
                    }

                    composable(AppRoutes.SETTINGS) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onOpenAuthor = { slug -> navController.navigate(AppRoutes.authorRoute(slug)) }
                        )
                    }

                    composable(
                        route = AppRoutes.TOPIC,
                        arguments = listOf(
                            navArgument("slug") { type = NavType.StringType; defaultValue = "" },
                            navArgument("page") { type = NavType.IntType; defaultValue = 1 }
                        )
                    ) { entry ->
                        val slug = entry.arguments?.getString("slug").orEmpty()
                        val page = entry.arguments?.getInt("page") ?: 1
                        val topicVm: TopicViewModel = viewModel(entry, factory = factory)
                        LaunchedEffect(slug, page) {
                            if (slug.isNotBlank() && topicVm.uiState.value.requestedSlugOrUrl != slug) {
                                topicVm.loadTopic(slug, page)
                            }
                        }
                        TopicScreen(
                            viewModel = topicVm,
                            showBackButton = true,
                            onBackClick = { navController.popBackStack() },
                            onNavigateToTopic = { target ->
                                navController.navigate(AppRoutes.topicRoute(target, 1))
                            },
                            onNavigateToAuthor = { authorSlug ->
                                navController.navigate(AppRoutes.authorRoute(authorSlug))
                            },
                            onNavigateToChannel = navigateToChannel
                        )
                    }

                    composable(
                        route = AppRoutes.ENTRY,
                        arguments = listOf(navArgument("entryId") { type = NavType.LongType })
                    ) { entry ->
                        val entryId = entry.arguments?.getLong("entryId") ?: 0L
                        val topicVm: TopicViewModel = viewModel(entry, factory = factory)
                        LaunchedEffect(entryId) {
                            if (entryId > 0 && topicVm.uiState.value.singleEntryId != entryId) {
                                topicVm.loadSingleEntry(entryId)
                            }
                        }
                        TopicScreen(
                            viewModel = topicVm,
                            showBackButton = true,
                            onBackClick = { navController.popBackStack() },
                            onNavigateToTopic = { target ->
                                navController.navigate(AppRoutes.topicRoute(target, 1))
                            },
                            onNavigateToAuthor = { authorSlug ->
                                navController.navigate(AppRoutes.authorRoute(authorSlug))
                            },
                            onNavigateToChannel = navigateToChannel
                        )
                    }

                    composable(
                        route = AppRoutes.AUTHOR,
                        arguments = listOf(navArgument("slug") { type = NavType.StringType })
                    ) { entry ->
                        val slug = entry.arguments?.getString("slug").orEmpty()
                        val authorVm: AuthorViewModel = viewModel(entry, factory = factory)
                        LaunchedEffect(slug) {
                            if (slug.isNotBlank() && authorVm.uiState.value.authorSlug != slug) {
                                authorVm.loadAuthor(slug)
                            }
                        }
                        AuthorScreen(
                            viewModel = authorVm,
                            expandSpoilersByDefault = expandSpoilersByDefault,
                            onBackClick = { navController.popBackStack() },
                            onNavigateToTopic = { target ->
                                navController.navigate(AppRoutes.topicRoute(target, 1))
                            },
                            onNavigateToAuthor = { authorSlug ->
                                navController.navigate(AppRoutes.authorRoute(authorSlug))
                            },
                            onSendMessageClick = openMessageToAuthor
                        )
                    }

                    composable(
                        route = AppRoutes.MESSAGE_THREAD,
                        arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })
                    ) { entry ->
                        val threadUrl = entry.arguments?.getString("url").orEmpty()
                        MessageThreadScreen(
                            viewModel = messagesViewModel,
                            threadUrl = threadUrl,
                            expandSpoilersByDefault = expandSpoilersByDefault,
                            onBackClick = { navController.popBackStack() },
                            onNavigateToTopic = { target ->
                                navController.navigate(AppRoutes.topicRoute(target, 1))
                            },
                            onNavigateToAuthor = { authorSlug ->
                                navController.navigate(AppRoutes.authorRoute(authorSlug))
                            }
                        )
                    }
                }
            }
        }
    }
}
