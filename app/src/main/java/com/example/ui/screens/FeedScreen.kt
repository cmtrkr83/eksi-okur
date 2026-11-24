package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.DEFAULT_EKSI_CHANNELS
import com.example.data.model.FeedCategory
import com.example.data.model.TopicItem
import com.example.data.model.UiResult
import com.example.ui.components.EmptyEditorialView
import com.example.ui.components.ErrorStateView
import com.example.ui.components.LoadingCenterView
import com.example.ui.components.OfflineCacheBanner
import com.example.ui.components.TopicListItem
import com.example.ui.components.WebAuthDialog
import com.example.ui.viewmodel.FeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    selectedSlug: String? = null,
    onTopicClick: (TopicItem) -> Unit,
    onQuickSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var challengeUrl by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "ekşi okur",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = when (state.category) {
                            FeedCategory.GUNDEM -> "gündemdeki başlıklar"
                            FeedCategory.DEBE -> "dünün en beğenilenleri"
                            FeedCategory.BUGUN -> "bugün yazılanlar"
                            FeedCategory.KANAL -> "#${state.selectedChannel.slug} kanalı"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = onQuickSearchClick,
                    modifier = Modifier.testTag("feed_search_button")
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = "Ara")
                }
                IconButton(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.testTag("feed_refresh_button")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Yenile")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // Category Tabs Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeedCategory.entries.forEach { category ->
                val selected = state.category == category
                val icon = when (category) {
                    FeedCategory.GUNDEM -> Icons.Rounded.LocalFireDepartment
                    FeedCategory.DEBE -> Icons.Rounded.Star
                    FeedCategory.BUGUN -> Icons.Rounded.Today
                    FeedCategory.KANAL -> Icons.Rounded.Tag
                }
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectCategory(category) },
                    label = { Text(category.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("feed_tab_${category.name.lowercase()}")
                )
            }
        }

        // Channel Sub-Chips when Kanallar is selected
        AnimatedVisibility(visible = state.category == FeedCategory.KANAL) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(DEFAULT_EKSI_CHANNELS, key = { it.slug }) { channel ->
                    val isSelected = state.selectedChannel.slug == channel.slug
                    Surface(
                        onClick = { viewModel.selectChannel(channel) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.testTag("channel_chip_${channel.slug}")
                    ) {
                        Text(
                            text = "#${channel.name.lowercase()}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Content Area
        when (val res = state.topicsResult) {
            is UiResult.Loading -> {
                LoadingCenterView("Başlıklar getiriliyor…")
            }
            is UiResult.Error -> {
                if (!res.cachedData.isNullOrEmpty()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        OfflineCacheBanner(cachedAtMillis = res.cachedAtMillis)
                        FeedTopicList(
                            topics = res.cachedData,
                            selectedSlug = selectedSlug,
                            currentPage = state.page,
                            showPager = state.category != FeedCategory.DEBE,
                            isRefreshing = state.isRefreshing,
                            onRefresh = { viewModel.refresh() },
                            onTopicClick = onTopicClick,
                            onPageChange = { viewModel.changePage(it) }
                        )
                    }
                } else {
                    ErrorStateView(
                        message = res.message,
                        isCloudflareChallenge = res.isCloudflareChallenge,
                        onRetry = { viewModel.loadFeed() },
                        onOpenBrowserVerify = res.challengeUrl?.let { url ->
                            { challengeUrl = url }
                        }
                    )
                }
            }
            is UiResult.Success -> {
                if (res.data.isEmpty()) {
                    EmptyEditorialView(
                        title = "Başlık bulunamadı",
                        subtitle = "Bu sayfada henüz gösterilecek başlık yok. Yenilemeyi deneyebilirsiniz."
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (res.isFromCache) {
                            OfflineCacheBanner(cachedAtMillis = res.cachedAtMillis)
                        }
                        FeedTopicList(
                            topics = res.data,
                            selectedSlug = selectedSlug,
                            currentPage = state.page,
                            showPager = state.category != FeedCategory.DEBE,
                            isRefreshing = state.isRefreshing,
                            onRefresh = { viewModel.refresh() },
                            onTopicClick = onTopicClick,
                            onPageChange = { viewModel.changePage(it) }
                        )
                    }
                }
            }
        }
    }

    challengeUrl?.let { url ->
        WebAuthDialog(
            initialUrl = url,
            title = "Ekşi Sözlük Doğrulama",
            onDismiss = { challengeUrl = null },
            onCompleted = { _ ->
                challengeUrl = null
                viewModel.loadFeed()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedTopicList(
    topics: List<TopicItem>,
    selectedSlug: String?,
    currentPage: Int,
    showPager: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTopicClick: (TopicItem) -> Unit,
    onPageChange: (Int) -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("feed_topic_list"),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(topics, key = { "${it.slugUrl}_${it.title}" }) { topic ->
                TopicListItem(
                    topic = topic,
                    isSelected = selectedSlug != null && selectedSlug == topic.slugUrl,
                    onClick = { onTopicClick(topic) }
                )
            }

            if (showPager) {
                item(key = "feed_pager") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onPageChange(currentPage - 1) },
                            enabled = currentPage > 1,
                            modifier = Modifier.testTag("feed_prev_page")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Önceki")
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = "Sayfa $currentPage",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(16.dp))
                        OutlinedButton(
                            onClick = { onPageChange(currentPage + 1) },
                            modifier = Modifier.testTag("feed_next_page")
                        ) {
                            Text("Sonraki")
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
