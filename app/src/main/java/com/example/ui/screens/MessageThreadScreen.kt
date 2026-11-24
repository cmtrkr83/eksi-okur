package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.DirectMessageItem
import com.example.data.model.EntrySegment
import com.example.data.model.UiResult
import com.example.ui.components.EmptyEditorialView
import com.example.ui.components.EntryRichBody
import com.example.ui.components.ErrorStateView
import com.example.ui.components.LoadingCenterView
import com.example.ui.components.WebAuthDialog
import com.example.ui.viewmodel.MessagesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(
    viewModel: MessagesViewModel,
    threadUrl: String,
    expandSpoilersByDefault: Boolean,
    onBackClick: () -> Unit,
    onNavigateToTopic: (String) -> Unit,
    onNavigateToAuthor: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var authDialogUrl by remember { mutableStateOf<String?>(null) }
    var activeSmartBkz by remember { mutableStateOf<EntrySegment.Bkz?>(null) }
    var replyText by rememberSaveable(threadUrl) { mutableStateOf("") }
    var pendingClearReply by remember { mutableStateOf(false) }

    LaunchedEffect(threadUrl) {
        if (threadUrl.isNotBlank() && state.activeThreadUrl != threadUrl) {
            viewModel.loadThread(threadUrl)
        }
    }

    LaunchedEffect(state.isSendingReply, state.replyError) {
        if (pendingClearReply && !state.isSendingReply) {
            if (state.replyError == null) {
                replyText = ""
            }
            pendingClearReply = false
        }
    }

    val threadDetail = (state.threadResult as? UiResult.Success)?.data
    val participantNick = threadDetail?.participantNick.orEmpty()

    LaunchedEffect(threadDetail?.messages?.size) {
        val count = threadDetail?.messages?.size ?: 0
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    val hasNick = participantNick.isNotBlank() && participantNick != "Mesajlaşma"
                    Text(
                        text = if (hasNick) "@$participantNick" else "Mesajlaşma",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (hasNick) {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNavigateToAuthor(participantNick) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("thread_participant_title")
                        } else {
                            Modifier
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("thread_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadThread(threadUrl, isPullRefresh = false) },
                        modifier = Modifier.testTag("thread_refresh_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Yenile")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (threadDetail != null && !threadDetail.requiresLogin) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        state.replyError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = replyText,
                                onValueChange = { replyText = it },
                                placeholder = { Text("Yanıt yaz…") },
                                maxLines = 4,
                                enabled = !state.isSendingReply,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("thread_reply_input")
                            )
                            FilledIconButton(
                                onClick = {
                                    pendingClearReply = true
                                    viewModel.sendReply(replyText)
                                },
                                enabled = !state.isSendingReply && replyText.isNotBlank(),
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("thread_reply_send_button")
                            ) {
                                if (state.isSendingReply) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Send,
                                        contentDescription = "Yanıt gönder"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val res = state.threadResult) {
                is UiResult.Loading -> {
                    LoadingCenterView("Sohbet yükleniyor…")
                }

                is UiResult.Error -> {
                    ErrorStateView(
                        message = res.message,
                        isCloudflareChallenge = res.isCloudflareChallenge,
                        onRetry = { viewModel.loadThread(threadUrl) },
                        onOpenBrowserVerify = res.challengeUrl?.let { url ->
                            { authDialogUrl = url }
                        }
                    )
                }

                is UiResult.Success -> {
                    if (res.data.requiresLogin) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                modifier = Modifier.widthIn(max = 420.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Mail,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = "Mesajı okumak için giriş yapın",
                                        style = MaterialTheme.typography.titleMedium,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Button(
                                        onClick = { authDialogUrl = "https://eksisozluk.com/giris" },
                                        modifier = Modifier.testTag("thread_login_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.Login,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Giriş Yap")
                                    }
                                }
                            }
                        }
                    } else {
                        PullToRefreshBox(
                            isRefreshing = state.isRefreshingThread,
                            onRefresh = { viewModel.loadThread(threadUrl, isPullRefresh = true) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (res.data.messages.isEmpty()) {
                                EmptyEditorialView(
                                    title = "Mesaj bulunamadı",
                                    subtitle = "Bu görüşmede henüz görüntülenecek mesaj yok."
                                )
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(
                                        items = res.data.messages,
                                        key = { it.id }
                                    ) { item ->
                                        DirectMessageBubble(
                                            message = item,
                                            expandSpoilersByDefault = expandSpoilersByDefault,
                                            onBkzClick = { bkz ->
                                                if (bkz.isSmartBkz && !bkz.tooltipText.isNullOrBlank()) {
                                                    activeSmartBkz = bkz
                                                } else {
                                                    onNavigateToTopic(bkz.targetUrl.ifBlank { bkz.targetTopic })
                                                }
                                            },
                                            onUrlClick = { url ->
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    activeSmartBkz?.let { smart ->
        AlertDialog(
            onDismissRequest = { activeSmartBkz = null },
            title = { Text("Akıllı Bkz (*)") },
            text = { Text(smart.tooltipText.orEmpty()) },
            confirmButton = {
                Button(
                    onClick = {
                        val target = smart.targetUrl.ifBlank { smart.targetTopic }
                        activeSmartBkz = null
                        onNavigateToTopic(target)
                    }
                ) {
                    Text("Başlığa Git")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeSmartBkz = null }) {
                    Text("Kapat")
                }
            }
        )
    }

    authDialogUrl?.let { url ->
        WebAuthDialog(
            initialUrl = url,
            title = if (url.contains("/giris")) "Ekşi Sözlük Giriş" else "Ekşi Sözlük Doğrulama",
            onDismiss = { authDialogUrl = null },
            onCompleted = { detectedNick ->
                authDialogUrl = null
                viewModel.onWebLoginCompleted(detectedNick)
            }
        )
    }
}

@Composable
private fun DirectMessageBubble(
    message: DirectMessageItem,
    expandSpoilersByDefault: Boolean,
    onBkzClick: (EntrySegment.Bkz) -> Unit,
    onUrlClick: (String) -> Unit
) {
    val isOutgoing = message.isOutgoing
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }
    val containerColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            border = if (isOutgoing) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .testTag("dm_bubble_${message.id}")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!isOutgoing && message.senderNick.isNotBlank()) {
                    Text(
                        text = message.senderNick,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (message.segments.isNotEmpty()) {
                    EntryRichBody(
                        segments = message.segments,
                        expandSpoilersByDefault = expandSpoilersByDefault,
                        onBkzClick = onBkzClick,
                        onUrlClick = onUrlClick,
                        textColor = contentColor,
                        linkColor = if (isOutgoing) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                } else {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor
                    )
                }

                if (message.sentAt.isNotBlank()) {
                    Text(
                        text = message.sentAt,
                        style = MaterialTheme.typography.labelSmall,
                        color = subColor,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}
