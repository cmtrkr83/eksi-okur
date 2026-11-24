package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.data.model.AuthorTab
import com.example.data.model.UiResult
import com.example.ui.components.EmptyEditorialView
import com.example.ui.components.EntryCard
import com.example.ui.components.ErrorStateView
import com.example.ui.components.LoadingCenterView
import com.example.ui.components.PaginationBar
import com.example.ui.components.WebAuthDialog
import com.example.ui.viewmodel.AuthorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorScreen(
    viewModel: AuthorViewModel,
    expandSpoilersByDefault: Boolean,
    onBackClick: () -> Unit,
    onNavigateToTopic: (String) -> Unit,
    onNavigateToAuthor: (String) -> Unit,
    onSendMessageClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var challengeUrl by remember { mutableStateOf<String?>(null) }

    val profile = (state.result as? UiResult.Success)?.data

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = profile?.nick?.let { "@$it" } ?: "@${state.authorSlug}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("author_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Yenile")
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
                is UiResult.Loading -> LoadingCenterView("Yazar profili yükleniyor…")
                is UiResult.Error -> ErrorStateView(
                    message = res.message,
                    isCloudflareChallenge = res.isCloudflareChallenge,
                    onRetry = { viewModel.refresh() },
                    onOpenBrowserVerify = res.challengeUrl?.let { url ->
                        { challengeUrl = url }
                    }
                )
                is UiResult.Success -> {
                    val data = res.data
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = if (data.totalPages > 1) 88.dp else 28.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "author_header") {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                            modifier = Modifier.size(64.dp)
                                        ) {
                                            if (!data.avatarUrl.isNullOrBlank()) {
                                                SubcomposeAsyncImage(
                                                    model = data.avatarUrl,
                                                    contentDescription = "${data.nick} profil fotoğrafı",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(CircleShape),
                                                    loading = {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Person,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    },
                                                    error = {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Person,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                    }
                                                )
                                            } else {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Person,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(30.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column {
                                            Text(
                                                text = data.nick,
                                                style = MaterialTheme.typography.headlineSmall
                                            )
                                            if (!data.bio.isNullOrBlank()) {
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = data.bio,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    if (data.stats.isNotEmpty()) {
                                        Spacer(Modifier.height(12.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            data.stats.forEach { (label, value) ->
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.surfaceContainer
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(
                                                            horizontal = 10.dp,
                                                            vertical = 6.dp
                                                        ),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Text(
                                                            text = value,
                                                            style = MaterialTheme.typography.labelLarge,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Text(
                                                            text = label,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = { onSendMessageClick(data.nick) },
                                        modifier = Modifier.testTag("author_send_message_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Mail,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Mesaj At")
                                    }
                                }
                            }
                        }

                        item(key = "author_tabs") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AuthorTab.entries.forEach { tab ->
                                    FilterChip(
                                        selected = state.activeTab == tab,
                                        onClick = { viewModel.selectTab(tab) },
                                        label = { Text(tab.label) }
                                    )
                                }
                            }
                        }

                        if (data.entries.isEmpty()) {
                            item(key = "author_empty") {
                                EmptyEditorialView(
                                    title = "Entry bulunamadı",
                                    subtitle = "Bu sekmede görüntülenecek entry bulunmuyor.",
                                    modifier = Modifier.height(300.dp)
                                )
                            }
                        } else {
                            items(data.entries, key = { it.id }) { entry ->
                                EntryCard(
                                    entry = entry,
                                    isBookmarked = false,
                                    expandSpoilersByDefault = expandSpoilersByDefault,
                                    onBkzClick = { url, topic ->
                                        onNavigateToTopic(url.ifBlank { topic })
                                    },
                                    onAuthorClick = { slug ->
                                        if (slug != state.authorSlug) onNavigateToAuthor(slug)
                                    },
                                    onFavoriteClick = {},
                                    onBookmarkClick = {},
                                    onTopicHeaderClick = { url -> onNavigateToTopic(url) }
                                )
                            }
                        }
                    }

                    if (data.totalPages > 1) {
                        PaginationBar(
                            currentPage = data.currentPage,
                            totalPages = data.totalPages,
                            onPageSelected = { viewModel.goToPage(it) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 14.dp)
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
                viewModel.refresh()
            }
        )
    }
}
