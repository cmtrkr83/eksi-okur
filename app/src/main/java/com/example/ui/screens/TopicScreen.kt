package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.example.data.model.TopicDetail
import com.example.data.model.TopicFilter
import com.example.data.model.UiResult
import com.example.ui.components.EmptyEditorialView
import com.example.ui.components.EntryCard
import com.example.ui.components.ErrorStateView
import com.example.ui.components.LoadingCenterView
import com.example.ui.components.OfflineCacheBanner
import com.example.ui.components.PaginationBar
import com.example.ui.components.WebAuthDialog
import com.example.ui.viewmodel.TopicViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicScreen(
    viewModel: TopicViewModel,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onNavigateToTopic: (slugOrQuery: String) -> Unit,
    onNavigateToAuthor: (authorSlug: String) -> Unit,
    onNavigateToChannel: (channelSlug: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val isTopicBookmarked by viewModel.isTopicBookmarked.collectAsStateWithLifecycle()
    val bookmarkedEntryIds by viewModel.bookmarkedEntryIds.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showOverflowMenu by remember { mutableStateOf(false) }
    var authDialogUrl by remember { mutableStateOf<String?>(null) }
    var openComposeAfterAuth by remember { mutableStateOf(false) }
    var retryAppendAfterAuth by remember { mutableStateOf(false) }

    LaunchedEffect(state.actionMessage, state.actionChallengeUrl) {
        val msg = state.actionMessage
        val cfUrl = state.actionChallengeUrl
        if (msg != null) {
            val res = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = if (cfUrl != null) "Doğrula" else null,
                withDismissAction = true
            )
            if (res == SnackbarResult.ActionPerformed && cfUrl != null) {
                openComposeAfterAuth = false
                retryAppendAfterAuth = false
                authDialogUrl = cfUrl
            }
            viewModel.clearMessage()
        }
    }

    val activeDetail: TopicDetail? = when (val r = state.result) {
        is UiResult.Success -> r.data
        is UiResult.Error -> r.cachedData
        else -> null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = activeDetail?.title ?: "Başlık",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier.testTag("topic_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Geri"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleTopicBookmark() },
                        enabled = activeDetail != null,
                        modifier = Modifier.testTag("topic_bookmark_button")
                    ) {
                        Icon(
                            imageVector = if (isTopicBookmarked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Başlığı Kaydet",
                            tint = if (isTopicBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.testTag("topic_refresh_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Yenile")
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Diğer")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Entry Yaz") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                enabled = activeDetail != null,
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.onWriteEntryClicked()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Paylaş") },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    val detail = activeDetail ?: return@DropdownMenuItem
                                    val shareUrl = "https://eksisozluk.com${detail.canonicalUrl}"
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "${detail.title}\n$shareUrl")
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Başlığı Paylaş"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Tarayıcıda Aç") },
                                leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    val url = activeDetail?.canonicalUrl ?: state.requestedSlugOrUrl
                                    val full = if (url.startsWith("http")) url else "https://eksisozluk.com$url"
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)))
                                    }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val res = state.result) {
                is UiResult.Loading -> {
                    LoadingCenterView("Entry'ler yükleniyor…")
                }
                is UiResult.Error -> {
                    if (res.cachedData != null) {
                        TopicDetailBody(
                            detail = res.cachedData,
                            isSingleEntryMode = state.singleEntryId != null,
                            isRefreshing = state.isRefreshing,
                            isAppendingNextPage = false,
                            appendError = null,
                            appendChallengeUrl = null,
                            scrollToTopTrigger = state.scrollToTopTrigger,
                            isFromCache = true,
                            cachedAtMillis = res.cachedAtMillis,
                            expandSpoilersByDefault = prefs.expandSpoilersByDefault,
                            bookmarkedEntryIds = bookmarkedEntryIds,
                            onRefresh = { viewModel.refresh() },
                            onLoadNextPage = {},
                            onRetryNextPage = {},
                            onVerifyAppendChallenge = {},
                            onSelectFilter = { viewModel.selectFilter(it) },
                            onPageSelected = { viewModel.goToPage(it) },
                            onOpenFullTopic = { viewModel.openFullTopicFromSingleEntry() },
                            onWriteEntryClick = { viewModel.onWriteEntryClicked() },
                            onBkzClick = { url, topic ->
                                onNavigateToTopic(url.ifBlank { topic })
                            },
                            onAuthorClick = onNavigateToAuthor,
                            onChannelClick = onNavigateToChannel,
                            onFavoriteClick = { viewModel.toggleFavorite(it) },
                            onBookmarkEntryClick = { viewModel.toggleEntryBookmark(it) }
                        )
                    } else {
                        ErrorStateView(
                            message = res.message,
                            isCloudflareChallenge = res.isCloudflareChallenge,
                            onRetry = { viewModel.refresh() },
                            onOpenBrowserVerify = res.challengeUrl?.let { url ->
                                {
                                    openComposeAfterAuth = false
                                    retryAppendAfterAuth = false
                                    authDialogUrl = url
                                }
                            }
                        )
                    }
                }
                is UiResult.Success -> {
                    TopicDetailBody(
                        detail = res.data,
                        isSingleEntryMode = state.singleEntryId != null,
                        isRefreshing = state.isRefreshing,
                        isAppendingNextPage = state.isAppendingNextPage,
                        appendError = state.appendError,
                        appendChallengeUrl = state.appendChallengeUrl,
                        scrollToTopTrigger = state.scrollToTopTrigger,
                        isFromCache = res.isFromCache,
                        cachedAtMillis = res.cachedAtMillis,
                        expandSpoilersByDefault = prefs.expandSpoilersByDefault,
                        bookmarkedEntryIds = bookmarkedEntryIds,
                        onRefresh = { viewModel.refresh() },
                        onLoadNextPage = { viewModel.loadNextPage() },
                        onRetryNextPage = { viewModel.loadNextPage(forceRetry = true) },
                        onVerifyAppendChallenge = { url ->
                            openComposeAfterAuth = false
                            retryAppendAfterAuth = true
                            authDialogUrl = url
                        },
                        onSelectFilter = { viewModel.selectFilter(it) },
                        onPageSelected = { viewModel.goToPage(it) },
                        onOpenFullTopic = { viewModel.openFullTopicFromSingleEntry() },
                        onWriteEntryClick = { viewModel.onWriteEntryClicked() },
                        onBkzClick = { url, topic ->
                            onNavigateToTopic(url.ifBlank { topic })
                        },
                        onAuthorClick = onNavigateToAuthor,
                        onChannelClick = onNavigateToChannel,
                        onFavoriteClick = { viewModel.toggleFavorite(it) },
                        onBookmarkEntryClick = { viewModel.toggleEntryBookmark(it) }
                    )
                }
            }
        }
    }

    state.loginPromptMessage?.let { promptMsg ->
        val shouldOpenCompose = state.pendingComposeAfterLogin
        AlertDialog(
            onDismissRequest = { viewModel.dismissLoginPrompt() },
            title = { Text("Giriş Gerekli") },
            text = { Text(promptMsg) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissLoginPrompt()
                        openComposeAfterAuth = shouldOpenCompose
                        retryAppendAfterAuth = false
                        authDialogUrl = "https://eksisozluk.com/giris"
                    }
                ) {
                    Text("Giriş Yap")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLoginPrompt() }) {
                    Text("İptal")
                }
            }
        )
    }

    if (state.isComposeOpen && activeDetail != null) {
        ComposeEntrySheet(
            topicTitle = activeDetail.title,
            authorNick = prefs.loggedInNick,
            isSubmitting = state.isSubmittingEntry,
            errorMessage = state.submitEntryError,
            onDismiss = { viewModel.closeComposeSheet() },
            onSubmit = { content -> viewModel.submitEntry(content) }
        )
    }

    authDialogUrl?.let { url ->
        WebAuthDialog(
            initialUrl = url,
            title = if (openComposeAfterAuth) "Ekşi Sözlük Girişi" else "Ekşi Sözlük Doğrulama",
            onDismiss = {
                authDialogUrl = null
                openComposeAfterAuth = false
                retryAppendAfterAuth = false
            },
            onCompleted = { detectedNick ->
                val shouldOpenCompose = openComposeAfterAuth
                val shouldRetryAppend = retryAppendAfterAuth
                authDialogUrl = null
                openComposeAfterAuth = false
                retryAppendAfterAuth = false
                viewModel.onWebAuthCompleted(
                    detectedNick = detectedNick,
                    openComposeAfter = shouldOpenCompose,
                    retryAppendAfter = shouldRetryAppend
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicDetailBody(
    detail: TopicDetail,
    isSingleEntryMode: Boolean,
    isRefreshing: Boolean,
    isAppendingNextPage: Boolean,
    appendError: String?,
    appendChallengeUrl: String?,
    scrollToTopTrigger: Int,
    isFromCache: Boolean,
    cachedAtMillis: Long?,
    expandSpoilersByDefault: Boolean,
    bookmarkedEntryIds: Set<Long>,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onRetryNextPage: () -> Unit,
    onVerifyAppendChallenge: (String) -> Unit,
    onSelectFilter: (TopicFilter) -> Unit,
    onPageSelected: (Int) -> Unit,
    onOpenFullTopic: () -> Unit,
    onWriteEntryClick: () -> Unit,
    onBkzClick: (String, String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onFavoriteClick: (com.example.data.model.EntryItem) -> Unit,
    onBookmarkEntryClick: (com.example.data.model.EntryItem) -> Unit
) {
    val showPager = !isSingleEntryMode && detail.totalPages > 1
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val triggerThresholdPx = with(density) { 84.dp.toPx() }
    val maxVisualOffsetPx = with(density) { 128.dp.toPx() }
    val swipeOffsetX = remember { Animatable(0f) }

    val latestPage by rememberUpdatedState(detail.currentPage)
    val latestTotalPages by rememberUpdatedState(detail.totalPages)
    val latestOnPageSelected by rememberUpdatedState(onPageSelected)
    val latestOnLoadNextPage by rememberUpdatedState(onLoadNextPage)

    LaunchedEffect(scrollToTopTrigger) {
        listState.scrollToItem(0)
        swipeOffsetX.snapTo(0f)
    }

    val shouldLoadNextPage by remember(
        detail.canonicalUrl,
        detail.filter,
        detail.currentPage,
        detail.totalPages,
        detail.entries.size,
        isSingleEntryMode,
        isAppendingNextPage,
        appendError
    ) {
        derivedStateOf {
            if (isSingleEntryMode || isAppendingNextPage || appendError != null) {
                false
            } else if (detail.entries.isEmpty() || detail.currentPage >= detail.totalPages) {
                false
            } else {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                totalItems > 0 && lastVisibleIndex >= (totalItems - 2).coerceAtLeast(1)
            }
        }
    }

    LaunchedEffect(shouldLoadNextPage) {
        if (shouldLoadNextPage) {
            latestOnLoadNextPage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (showPager) {
                    Modifier.pointerInput(detail.canonicalUrl) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDx = 0f
                            var totalDy = 0f
                            var isHorizontalSwipe = false
                            var canceled = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (change == null || !change.pressed) {
                                    break
                                }
                                if (change.isConsumed && !isHorizontalSwipe) {
                                    canceled = true
                                    break
                                }

                                val delta = change.positionChange()
                                totalDx += delta.x
                                totalDy += delta.y

                                if (!isHorizontalSwipe) {
                                    val absX = abs(totalDx)
                                    val absY = abs(totalDy)
                                    if (absX > touchSlop && absX > absY * 1.25f) {
                                        isHorizontalSwipe = true
                                    } else if (absY > touchSlop && absY > absX) {
                                        canceled = true
                                        break
                                    }
                                }

                                if (isHorizontalSwipe) {
                                    change.consume()
                                    val resistance = if (
                                        (totalDx > 0 && latestPage <= 1) ||
                                        (totalDx < 0 && latestPage >= latestTotalPages)
                                    ) 0.25f else 0.72f
                                    val clamped = (totalDx * resistance).coerceIn(
                                        -maxVisualOffsetPx,
                                        maxVisualOffsetPx
                                    )
                                    scope.launch {
                                        swipeOffsetX.snapTo(clamped)
                                    }
                                }
                            }

                            if (!canceled && isHorizontalSwipe) {
                                val currentOffset = swipeOffsetX.value
                                val canGoPrev = latestPage > 1
                                val canGoNext = latestPage < latestTotalPages
                                val threshold = triggerThresholdPx * 0.72f

                                if (currentOffset >= threshold && canGoPrev) {
                                    latestOnPageSelected(latestPage - 1)
                                } else if (currentOffset <= -threshold && canGoNext) {
                                    latestOnPageSelected(latestPage + 1)
                                }
                                scope.launch {
                                    swipeOffsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                    )
                                }
                            } else if (swipeOffsetX.value != 0f) {
                                scope.launch {
                                    swipeOffsetX.animateTo(0f)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = swipeOffsetX.value * 0.28f
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("topic_entries_list"),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = if (showPager) 148.dp else 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "topic_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 720.dp)
                    ) {
                        if (isFromCache) {
                            OfflineCacheBanner(
                                cachedAtMillis = cachedAtMillis,
                                modifier = Modifier.padding(horizontal = 0.dp)
                            )
                        }

                        if (detail.channels.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = if (isFromCache) 8.dp else 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                detail.channels.forEach { ch ->
                                    Surface(
                                        onClick = { onChannelClick(ch.slug) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Text(
                                            text = "#${ch.name}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (!isSingleEntryMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(
                                        top = if (detail.channels.isNotEmpty() || isFromCache) 10.dp else 2.dp
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TopicFilter.entries.forEach { filter ->
                                    val selected = detail.filter == filter
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSelectFilter(filter) },
                                        label = { Text(filter.label) },
                                        leadingIcon = if (filter == TopicFilter.SUKELA || filter == TopicFilter.DAILY_SUKELA) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Rounded.AutoAwesome,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.testTag("topic_filter_${filter.name.lowercase()}")
                                    )
                                }
                            }
                        } else {
                            if (detail.channels.isNotEmpty() || isFromCache) {
                                Spacer(Modifier.height(10.dp))
                            }
                            Button(
                                onClick = onOpenFullTopic,
                                modifier = Modifier.testTag("single_entry_full_topic_btn")
                            ) {
                                Text("Başlığın Tümünü Gör")
                            }
                        }
                    }
                }

                if (detail.entries.isEmpty()) {
                    item(key = "topic_empty") {
                        EmptyEditorialView(
                            title = if (detail.isUkde) "Bu başlık henüz ukde" else "Entry bulunamadı",
                            subtitle = if (detail.isUkde) {
                                "Bu başlık için henüz yazarlar tarafından yazılmış bir entry yok. İlk entry'yi siz girebilirsiniz."
                            } else {
                                "Seçili filtre için gösterilecek entry bulunamadı."
                            },
                            modifier = Modifier.height(340.dp)
                        )
                    }
                } else {
                    items(detail.entries, key = { it.id }) { entry ->
                        EntryCard(
                            entry = entry,
                            isBookmarked = bookmarkedEntryIds.contains(entry.id),
                            expandSpoilersByDefault = expandSpoilersByDefault,
                            onBkzClick = onBkzClick,
                            onAuthorClick = onAuthorClick,
                            onFavoriteClick = { onFavoriteClick(entry) },
                            onBookmarkClick = { onBookmarkEntryClick(entry) }
                        )
                    }

                    if (!isSingleEntryMode) {
                        when {
                            isAppendingNextPage -> {
                                item(key = "topic_append_loading") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp)
                                            .testTag("topic_append_loading"),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "Sayfa ${detail.currentPage + 1} yükleniyor…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            appendError != null -> {
                                item(key = "topic_append_error") {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("topic_append_error")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = appendError,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = onRetryNextPage,
                                                    modifier = Modifier.testTag("topic_append_retry_button")
                                                ) {
                                                    Text("Sonraki Sayfayı Dene")
                                                }
                                                appendChallengeUrl?.let { challenge ->
                                                    TextButton(
                                                        onClick = { onVerifyAppendChallenge(challenge) },
                                                        modifier = Modifier.testTag("topic_append_verify_button")
                                                    ) {
                                                        Text("Doğrula")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            detail.currentPage >= detail.totalPages && detail.totalPages > 1 -> {
                                item(key = "topic_end_footer") {
                                    Text(
                                        text = "Başlığın sonuna ulaştınız",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp)
                                            .wrapContentWidth(Alignment.CenterHorizontally)
                                            .testTag("topic_end_footer")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!isSingleEntryMode) {
            ExtendedFloatingActionButton(
                onClick = onWriteEntryClick,
                icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                text = { Text("Entry Yaz") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(
                        end = 16.dp,
                        bottom = if (showPager) 78.dp else 16.dp
                    )
                    .testTag("topic_write_entry_fab")
            )
        }

        if (showPager) {
            val offset = swipeOffsetX.value
            val triggerThreshold = triggerThresholdPx * 0.72f
            if (abs(offset) > 12f) {
                val swipingRight = offset > 0f
                val canNavigate = if (swipingRight) detail.currentPage > 1 else detail.currentPage < detail.totalPages
                val isReady = canNavigate && abs(offset) >= triggerThreshold
                val label = when {
                    swipingRight && detail.currentPage > 1 -> "Önceki • Sayfa ${detail.currentPage - 1}"
                    swipingRight -> "İlk sayfadasınız"
                    !swipingRight && detail.currentPage < detail.totalPages -> "Sonraki • Sayfa ${detail.currentPage + 1}"
                    else -> "Son sayfadasınız"
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (isReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isReady) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    shadowElevation = 6.dp,
                    border = if (isReady) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .align(if (swipingRight) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (swipingRight) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (!swipingRight) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            PaginationBar(
                currentPage = detail.currentPage,
                totalPages = detail.totalPages,
                onPageSelected = onPageSelected,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeEntrySheet(
    topicTitle: String,
    authorNick: String?,
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var textValue by remember { mutableStateOf(TextFieldValue("")) }

    fun insertSnippet(prefix: String, suffix: String = "", placeholder: String = "") {
        val current = textValue.text
        val selStart = textValue.selection.min.coerceIn(0, current.length)
        val selEnd = textValue.selection.max.coerceIn(selStart, current.length)
        val selected = current.substring(selStart, selEnd).ifEmpty { placeholder }
        val inserted = "$prefix$selected$suffix"
        val updated = current.substring(0, selStart) + inserted + current.substring(selEnd)
        val cursorPos = if (selected.isEmpty() && placeholder.isEmpty()) {
            selStart + prefix.length
        } else {
            selStart + inserted.length
        }
        textValue = TextFieldValue(
            text = updated,
            selection = TextRange(cursorPos)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = topicTitle,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!authorNick.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "@$authorNick olarak yazıyorsunuz",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(12.dp))

            // Formatting toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormatAssistChip("(bkz: )") { insertSnippet("(bkz: ", ")") }
                FormatAssistChip("`hede`") { insertSnippet("`", "`") }
                FormatAssistChip("akıllı bkz *") { insertSnippet("`*:", "`") }
                FormatAssistChip("spoiler") {
                    insertSnippet("--- spoiler ---\n", "\n--- spoiler ---")
                }
                FormatAssistChip("[link]") { insertSnippet("[https://", " metin]") }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                placeholder = { Text("Başlık hakkında düşüncelerinizi yazın…") },
                minLines = 6,
                maxLines = 12,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compose_entry_input")
            )

            if (!errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting
                ) {
                    Text("Vazgeç")
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onSubmit(textValue.text) },
                    enabled = !isSubmitting && textValue.text.isNotBlank(),
                    modifier = Modifier.testTag("compose_entry_submit_button")
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Gönderiliyor…")
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Yayınla")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FormatAssistChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
