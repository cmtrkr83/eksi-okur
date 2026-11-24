package com.example.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.TopicItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TopicListItem(
    topic: TopicItem,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .testTag("topic_row_${topic.slugUrl.hashCode()}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (topic.isDebe) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = "Debe",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .padding(5.dp)
                            .size(16.dp)
                    )
                }
            }

            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (topic.entryCountText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = topic.entryCountText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
fun OfflineCacheBanner(
    cachedAtMillis: Long?,
    modifier: Modifier = Modifier
) {
    val timeLabel = remember(cachedAtMillis) {
        cachedAtMillis?.let {
            SimpleDateFormat("dd MMM HH:mm", Locale("tr", "TR")).format(Date(it))
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (timeLabel != null) {
                    "Önbellekten gösteriliyor ($timeLabel)"
                } else {
                    "Çevrimdışı önbellekten gösteriliyor"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun ErrorStateView(
    message: String,
    isCloudflareChallenge: Boolean,
    onRetry: () -> Unit,
    onOpenBrowserVerify: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
                    color = if (isCloudflareChallenge) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Icon(
                        imageVector = if (isCloudflareChallenge) Icons.Rounded.Security else Icons.Rounded.CloudOff,
                        contentDescription = null,
                        tint = if (isCloudflareChallenge) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier
                            .padding(14.dp)
                            .size(32.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (isCloudflareChallenge) "Doğrulama Gerekiyor" else "Bağlantı Sorunu",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("error_retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Tekrar Dene")
                    }
                    if (isCloudflareChallenge && onOpenBrowserVerify != null) {
                        Button(
                            onClick = onOpenBrowserVerify,
                            modifier = Modifier.testTag("error_verify_button")
                        ) {
                            Text("Doğrula")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyEditorialView(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_editorial_empty),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 220.dp, height = 165.dp)
                .clip(RoundedCornerShape(20.dp))
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp)
        )
    }
}

@Composable
fun LoadingCenterView(
    label: String = "Yükleniyor…",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PaginationBar(
    currentPage: Int,
    totalPages: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showJumpDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onPageSelected(1) },
                enabled = currentPage > 1,
                modifier = Modifier.testTag("page_first_button")
            ) {
                Icon(Icons.Rounded.FirstPage, contentDescription = "İlk sayfa")
            }
            IconButton(
                onClick = { onPageSelected(currentPage - 1) },
                enabled = currentPage > 1,
                modifier = Modifier.testTag("page_prev_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Önceki sayfa")
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .minimumInteractiveComponentSize()
                    .clickable(enabled = totalPages > 1) { showJumpDialog = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("page_indicator_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$currentPage / $totalPages",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = { onPageSelected(currentPage + 1) },
                enabled = currentPage < totalPages,
                modifier = Modifier.testTag("page_next_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Sonraki sayfa")
            }
            IconButton(
                onClick = { onPageSelected(totalPages) },
                enabled = currentPage < totalPages,
                modifier = Modifier.testTag("page_last_button")
            ) {
                Icon(Icons.Rounded.LastPage, contentDescription = "Son sayfa")
            }
        }
    }

    if (showJumpDialog) {
        var input by remember { mutableStateOf(currentPage.toString()) }
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("Sayfaya Git (1 - $totalPages)") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw -> input = raw.filter { it.isDigit() } },
                    singleLine = true,
                    label = { Text("Sayfa numarası") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val page = input.toIntOrNull()?.coerceIn(1, totalPages) ?: currentPage
                            showJumpDialog = false
                            onPageSelected(page)
                        }
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val page = input.toIntOrNull()?.coerceIn(1, totalPages) ?: currentPage
                        showJumpDialog = false
                        onPageSelected(page)
                    }
                ) {
                    Text("Git")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text("İptal")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAuthDialog(
    initialUrl: String,
    title: String,
    onDismiss: () -> Unit,
    onCompleted: (detectedNick: String?) -> Unit
) {
    var progress by remember { mutableIntStateOf(0) }
    var detectedNick by remember { mutableStateOf<String?>(null) }
    var hasAutoCompleted by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            detectedNick?.let { nick ->
                                Text(
                                    text = "@$nick algılandı",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "Kapat")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                CookieManager.getInstance().flush()
                                onCompleted(detectedNick)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Done,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Tamamlandı")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                var renderCrashed by remember { mutableStateOf(false) }
                if (renderCrashed) {
                    ErrorStateView(
                        message = "Tarayıcı görünümü yeniden başlatılamadı. Lütfen tekrar deneyin.",
                        isCloudflareChallenge = false,
                        onRetry = { renderCrashed = false }
                    )
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString =
                                    com.example.data.network.EksiNetworkDataSource.USER_AGENT
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean = false

                                    override fun onRenderProcessGone(
                                        view: WebView?,
                                        detail: RenderProcessGoneDetail?
                                    ): Boolean {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            view?.destroy()
                                        }
                                        renderCrashed = true
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        cm.flush()
                                        val js = """
                                            (function() {
                                              var el = document.querySelector('a[title="ben"], a[aria-label="ben"], header a[href^="/biri/"], nav a[href^="/biri/"], #top-navigation a[href^="/biri/"]');
                                              if (!el) return "";
                                              return el.getAttribute("href") || "";
                                            })();
                                        """.trimIndent()
                                        view?.evaluateJavascript(js) { rawResult ->
                                            val cleaned = rawResult?.trim('"', ' ')
                                                ?.takeIf { it.startsWith("/biri/") }
                                                ?.removePrefix("/biri/")
                                                ?.substringBefore("?")
                                                ?.substringBefore("/")
                                            val decoded = cleaned?.let {
                                                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                                            }?.takeIf { it.isNotBlank() }

                                            if (decoded != null) {
                                                detectedNick = decoded
                                                val isLoginFlow = initialUrl.contains("/giris")
                                                val navigatedAwayFromLogin = url != null && !url.contains("/giris")
                                                if (isLoginFlow && navigatedAwayFromLogin && !hasAutoCompleted) {
                                                    hasAutoCompleted = true
                                                    cm.flush()
                                                    onCompleted(decoded)
                                                }
                                            }
                                        }
                                    }
                                }
                                loadUrl(initialUrl)
                            }
                        },
                        onRelease = { webView ->
                            webView.stopLoading()
                            webView.clearHistory()
                            webView.removeAllViews()
                            webView.destroy()
                        }
                    )
                }
            }
        }
    }
}
