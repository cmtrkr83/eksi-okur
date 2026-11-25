package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.example.data.model.MessageThreadSummary
import com.example.data.model.UiResult
import com.example.notifications.MessageNotificationHelper
import com.example.notifications.MessageNotificationScheduler
import com.example.ui.components.EmptyEditorialView
import com.example.ui.components.ErrorStateView
import com.example.ui.components.LoadingCenterView
import com.example.ui.components.WebAuthDialog
import com.example.ui.components.WebThreadDeleteDialog
import com.example.ui.viewmodel.MessagesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    viewModel: MessagesViewModel,
    onOpenThread: (threadUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var authDialogUrl by remember { mutableStateOf<String?>(null) }
    var revealedThreadId by remember { mutableStateOf<String?>(null) }
    var hasNotificationPermission by remember {
        mutableStateOf(MessageNotificationHelper.hasNotificationPermission(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted || MessageNotificationHelper.hasNotificationPermission(context)
        if (hasNotificationPermission) {
            MessageNotificationScheduler.syncPeriodicWork(context, true)
            MessageNotificationScheduler.triggerImmediateCheck(context)
        }
    }

    LaunchedEffect(state.showArchived) {
        revealedThreadId = null
    }

    LaunchedEffect(state.actionMessage, state.deleteChallengeUrl) {
        val msg = state.actionMessage
        val cfUrl = state.deleteChallengeUrl
        if (msg != null) {
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = if (cfUrl != null) "Doğrula" else null,
                withDismissAction = true
            )
            if (result == SnackbarResult.ActionPerformed && cfUrl != null) {
                authDialogUrl = cfUrl
            }
            viewModel.clearActionMessage()
            viewModel.clearDeleteChallenge()
        }
    }

    val inboxSuccess = (state.inboxResult as? UiResult.Success)?.data
    val canCompose = inboxSuccess?.requiresLogin == false ||
        (inboxSuccess == null && prefs.loggedInNick != null)

    LaunchedEffect(inboxSuccess) {
        if (inboxSuccess != null && !inboxSuccess.requiresLogin) {
            MessageNotificationHelper.cancelAllMessageNotifications(context)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "mesajlar",
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadInbox(isPullRefresh = false) },
                        modifier = Modifier.testTag("messages_refresh_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Yenile")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (canCompose) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.openCompose() },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Yeni mesaj yaz"
                        )
                    },
                    text = { Text("Yeni Mesaj") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("messages_compose_fab")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !state.showArchived,
                    onClick = { viewModel.setArchivedFilter(false) },
                    label = { Text("Gelen Kutusu") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Inbox,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.testTag("messages_inbox_chip")
                )
                FilterChip(
                    selected = state.showArchived,
                    onClick = { viewModel.setArchivedFilter(true) },
                    label = { Text("Arşiv") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.testTag("messages_archive_chip")
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                canCompose &&
                prefs.messageNotificationsEnabled &&
                !hasNotificationPermission
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Yeni özel mesaj geldiğinde bildirim almak için izin verin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            modifier = Modifier.testTag("messages_enable_notifications_button")
                        ) {
                            Text("Aç")
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (val res = state.inboxResult) {
                    is UiResult.Loading -> {
                        LoadingCenterView("Mesajlar yükleniyor…")
                    }

                    is UiResult.Error -> {
                        ErrorStateView(
                            message = res.message,
                            isCloudflareChallenge = res.isCloudflareChallenge,
                            onRetry = { viewModel.loadInbox() },
                            onOpenBrowserVerify = res.challengeUrl?.let { url ->
                                { authDialogUrl = url }
                            }
                        )
                    }

                    is UiResult.Success -> {
                        if (res.data.requiresLogin) {
                            MessagesLoginPrompt(
                                onLoginClick = {
                                    authDialogUrl = "https://eksisozluk.com/giris"
                                }
                            )
                        } else {
                            PullToRefreshBox(
                                isRefreshing = state.isRefreshingInbox,
                                onRefresh = { viewModel.loadInbox(isPullRefresh = true) },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (res.data.threads.isEmpty()) {
                                    EmptyEditorialView(
                                        title = if (state.showArchived) {
                                            "Arşivlenmiş mesaj yok"
                                        } else {
                                            "Gelen kutunuz boş"
                                        },
                                        subtitle = if (state.showArchived) {
                                            "Arşive taşıdığınız yazar mesajları burada listelenir."
                                        } else {
                                            "Bir yazara özel mesaj göndermek için sağ alttaki Yeni Mesaj düğmesine dokunun."
                                        }
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 96.dp)
                                    ) {
                                        items(
                                            items = res.data.threads,
                                            key = { it.threadUrl }
                                        ) { thread ->
                                            SwipeableMessageThreadRow(
                                                thread = thread,
                                                isRevealed = revealedThreadId == thread.threadId,
                                                isDeleting = thread.threadId in state.deletingThreadIds,
                                                onSwipeStart = {
                                                    if (revealedThreadId != null && revealedThreadId != thread.threadId) {
                                                        revealedThreadId = null
                                                    }
                                                },
                                                onRevealChange = { reveal ->
                                                    revealedThreadId = if (reveal) {
                                                        thread.threadId
                                                    } else if (revealedThreadId == thread.threadId) {
                                                        null
                                                    } else {
                                                        revealedThreadId
                                                    }
                                                },
                                                onClick = {
                                                    if (revealedThreadId != null) {
                                                        revealedThreadId = null
                                                    } else {
                                                        onOpenThread(thread.threadUrl)
                                                    }
                                                },
                                                onDeleteClick = {
                                                    viewModel.deleteThread(thread) {
                                                        MessageNotificationHelper.cancelThreadNotification(
                                                            context,
                                                            thread.threadUrl
                                                        )
                                                        if (revealedThreadId == thread.threadId) {
                                                            revealedThreadId = null
                                                        }
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
    }

    if (state.isComposeOpen && canCompose) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var messageBody by rememberSaveable(state.composeRecipient) { mutableStateOf("") }

        ModalBottomSheet(
            onDismissRequest = { viewModel.closeCompose() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Yeni Özel Mesaj",
                        style = MaterialTheme.typography.titleLarge
                    )
                    TextButton(
                        onClick = { viewModel.closeCompose() },
                        enabled = !state.isSendingNewMessage
                    ) {
                        Text("Vazgeç")
                    }
                }

                OutlinedTextField(
                    value = state.composeRecipient,
                    onValueChange = { viewModel.onRecipientChanged(it) },
                    label = { Text("Alıcı yazar") },
                    placeholder = { Text("ör. ssg") },
                    prefix = { Text("@") },
                    singleLine = true,
                    enabled = !state.isSendingNewMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("compose_message_recipient_input")
                )

                if (state.recipientSuggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.recipientSuggestions.forEach { nick ->
                            SuggestionChip(
                                onClick = { viewModel.selectRecipientSuggestion(nick) },
                                label = { Text("@$nick") },
                                modifier = Modifier.testTag("recipient_suggestion_$nick")
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = messageBody,
                    onValueChange = { messageBody = it },
                    label = { Text("Mesajınız") },
                    placeholder = { Text("Yazarla paylaşmak istediğiniz mesajı yazın…") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !state.isSendingNewMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("compose_message_body_input")
                )

                state.sendNewMessageError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        viewModel.sendNewMessage(messageBody) { threadRedirectUrl ->
                            onOpenThread(threadRedirectUrl)
                        }
                    },
                    enabled = !state.isSendingNewMessage &&
                        state.composeRecipient.isNotBlank() &&
                        messageBody.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("compose_message_send_button")
                ) {
                    if (state.isSendingNewMessage) {
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
                        Spacer(Modifier.width(8.dp))
                        Text("Mesajı Gönder")
                    }
                }

                Spacer(Modifier.height(6.dp))
            }
        }
    }

    authDialogUrl?.let { url ->
        WebAuthDialog(
            initialUrl = url,
            title = if (url.contains("/giris")) "Ekşi Sözlük Giriş" else "Ekşi Sözlük Doğrulama",
            onDismiss = { authDialogUrl = null },
            onCompleted = { detectedNick ->
                authDialogUrl = null
                viewModel.onWebLoginCompleted(detectedNick)
                if (prefs.messageNotificationsEnabled) {
                    MessageNotificationScheduler.syncPeriodicWork(context, true)
                }
            }
        )
    }

    state.pendingWebDelete?.let { req ->
        WebThreadDeleteDialog(
            targetUrl = req.targetUrl,
            threadId = req.threadId,
            threadPath = req.threadPath,
            participantNick = req.thread.participantNick,
            onDismiss = { viewModel.dismissWebThreadDelete() },
            onVerifyCompleted = {
                viewModel.onWebThreadDeleteCompleted(req) {
                    MessageNotificationHelper.cancelThreadNotification(
                        context,
                        req.thread.threadUrl
                    )
                    if (revealedThreadId == req.thread.threadId) {
                        revealedThreadId = null
                    }
                }
            }
        )
    }
}

@Composable
private fun SwipeableMessageThreadRow(
    thread: MessageThreadSummary,
    isRevealed: Boolean,
    isDeleting: Boolean,
    onSwipeStart: () -> Unit,
    onRevealChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val revealWidthDp = 92.dp
    val revealWidthPx = with(LocalDensity.current) { revealWidthDp.toPx() }
    val offsetX = remember(thread.threadId) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isRevealed, isDeleting) {
        val target = if (isRevealed || isDeleting) revealWidthPx else 0f
        if (offsetX.value != target) {
            offsetX.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val draggableState = rememberDraggableState { delta ->
        scope.launch {
            val next = (offsetX.value + delta).coerceIn(0f, revealWidthPx)
            offsetX.snapTo(next)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clipToBounds()
    ) {
        if (offsetX.value > 0.5f || isDeleting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .width(revealWidthDp)
                        .fillMaxHeight()
                        .minimumInteractiveComponentSize()
                        .clickable(enabled = !isDeleting, onClick = onDeleteClick)
                        .testTag("message_thread_delete_${thread.threadId}"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onError
                            )
                            Text(
                                text = "Siliniyor",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Mesajı sil",
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Sil",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                }
            }
        }

        MessageThreadRow(
            thread = thread,
            onClick = onClick,
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    enabled = !isDeleting,
                    onDragStarted = { onSwipeStart() },
                    onDragStopped = { velocity ->
                        val shouldReveal = velocity > 450f ||
                            (velocity >= -250f && offsetX.value >= revealWidthPx * 0.42f)
                        onRevealChange(shouldReveal)
                        scope.launch {
                            offsetX.animateTo(
                                targetValue = if (shouldReveal) revealWidthPx else 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.85f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                )
        )
    }
}

@Composable
private fun MessageThreadRow(
    thread: MessageThreadSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tintOverlay = if (thread.isUnread) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val initial = thread.participantNick.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .background(tintOverlay)
            .clickable(onClick = onClick)
            .testTag("message_thread_${thread.threadId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Surface(
                    shape = CircleShape,
                    color = if (thread.isUnread) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (thread.isUnread) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
                if (thread.isUnread) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = thread.participantNick,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (thread.isUnread) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (thread.dateText.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = thread.dateText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (thread.isUnread) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                if (thread.preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = thread.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (thread.isUnread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            thickness = 0.75.dp
        )
    }
}

@Composable
private fun MessagesLoginPrompt(
    onLoginClick: () -> Unit
) {
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mail,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(14.dp)
                            .size(32.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Özel Mesajlar",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Diğer yazarlarla mesajlaşmak ve gelen kutunuzu görüntülemek için Ekşi Sözlük hesabınıza giriş yapın.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onLoginClick,
                    modifier = Modifier.testTag("messages_login_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Ekşi Sözlük'e Giriş Yap")
                }
            }
        }
    }
}
