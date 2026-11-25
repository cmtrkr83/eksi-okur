package com.example.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
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
                var useSoftwareLayer by remember { mutableStateOf(true) }
                if (renderCrashed) {
                    ErrorStateView(
                        message = "Tarayıcı görünümü yeniden başlatılamadı. Lütfen tekrar deneyin.",
                        isCloudflareChallenge = false,
                        onRetry = {
                            useSoftwareLayer = true
                            renderCrashed = false
                        }
                    )
                } else {
                    val webViewDestroyed = remember(useSoftwareLayer) { AtomicBoolean(false) }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                if (useSoftwareLayer) {
                                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                                }
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
                                        if (webViewDestroyed.compareAndSet(false, true)) {
                                            runCatching {
                                                view?.stopLoading()
                                                view?.webViewClient = WebViewClient()
                                                view?.webChromeClient = null
                                                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                                                view?.removeAllViews()
                                                view?.destroy()
                                            }
                                        }
                                        useSoftwareLayer = true
                                        renderCrashed = true
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        if (webViewDestroyed.get()) return
                                        cm.flush()
                                        val isLoginFlow = initialUrl.contains("/giris")
                                        val js = """
                                            (function() {
                                              var el = document.querySelector('a[title="ben"], a[aria-label="ben"], header a[href^="/biri/"], nav a[href^="/biri/"], #top-navigation a[href^="/biri/"]');
                                              var href = el ? (el.getAttribute("href") || "") : "";
                                              var hasContent = !!document.querySelector('ul.topic-list, #title, ul#entry-item-list, #user-profile-title, #topic-research-menu');
                                              return JSON.stringify({ href: href, hasContent: hasContent });
                                            })();
                                        """.trimIndent()
                                        view?.evaluateJavascript(js) { rawResult ->
                                            if (webViewDestroyed.get()) return@evaluateJavascript
                                            val parsed = runCatching {
                                                val unquoted = if (rawResult != null && rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                                                    org.json.JSONTokener(rawResult).nextValue() as? String ?: rawResult
                                                } else {
                                                    rawResult.orEmpty()
                                                }
                                                org.json.JSONObject(unquoted)
                                            }.getOrNull()

                                            val rawHref = parsed?.optString("href").orEmpty()
                                            val hasContent = parsed?.optBoolean("hasContent", false) == true
                                            val cleaned = rawHref.trim('"', ' ')
                                                .takeIf { it.startsWith("/biri/") }
                                                ?.removePrefix("/biri/")
                                                ?.substringBefore("?")
                                                ?.substringBefore("/")
                                            val decoded = cleaned?.let {
                                                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                                            }?.takeIf { it.isNotBlank() }

                                            if (decoded != null) {
                                                detectedNick = decoded
                                            }

                                            val navigatedAwayFromLogin = url != null && !url.contains("/giris")
                                            if (!hasAutoCompleted) {
                                                if (isLoginFlow && decoded != null && navigatedAwayFromLogin) {
                                                    hasAutoCompleted = true
                                                    cm.flush()
                                                    onCompleted(decoded)
                                                } else if (!isLoginFlow && hasContent) {
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
                            if (webViewDestroyed.compareAndSet(false, true)) {
                                runCatching {
                                    webView.stopLoading()
                                    webView.clearHistory()
                                    webView.webViewClient = WebViewClient()
                                    webView.webChromeClient = null
                                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                                    webView.removeAllViews()
                                    webView.destroy()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebThreadDeleteDialog(
    targetUrl: String,
    threadId: String,
    threadPath: String,
    participantNick: String,
    onDismiss: () -> Unit,
    onVerifyCompleted: () -> Unit
) {
    var progress by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("Ekşi Sözlük sayfasında silme işlemi deneniyor…") }
    var renderCrashed by remember { mutableStateOf(false) }
    var retryNonce by remember { mutableIntStateOf(0) }

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
                                text = "Mesajı Sil",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "@$participantNick",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("web_delete_close_button")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Kapat")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                runCatching { CookieManager.getInstance().flush() }
                                onVerifyCompleted()
                            },
                            modifier = Modifier.testTag("web_delete_done_button")
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
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (renderCrashed) {
                    ErrorStateView(
                        message = "Tarayıcı görünümü yeniden başlatılamadı. Lütfen tekrar deneyin.",
                        isCloudflareChallenge = false,
                        onRetry = {
                            renderCrashed = false
                            retryNonce++
                        }
                    )
                } else {
                    val webViewDestroyed = remember(retryNonce) { AtomicBoolean(false) }
                    val mainHandler = remember(retryNonce) { Handler(Looper.getMainLooper()) }

                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            var autoTriggered = false
                            var triedInboxFallback = targetUrl.trimEnd('/').endsWith("/mesaj")
                            var autoCompleted = false
                            var pollAttempts = 0

                            val safeId = org.json.JSONObject.quote(threadId.trim())
                            val safePath = org.json.JSONObject.quote(threadPath.trim())
                            val safeNick = org.json.JSONObject.quote(participantNick.trim().lowercase())

                            val triggerDeleteJs = """
                                (function() {
                                  var href = (window.location.href || '').toLowerCase();
                                  if (href.indexOf('/giris') !== -1) {
                                    return JSON.stringify({ status: 'login_required' });
                                  }
                                  var title = document.title || '';
                                  var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
                                  var isCf = title.indexOf('Just a moment') !== -1 ||
                                             title.indexOf('Attention Required') !== -1 ||
                                             bodyText.indexOf('Checking your browser') !== -1 ||
                                             !!document.querySelector('#challenge-form, #challenge-running, .cf-browser-verification');
                                  if (isCf) {
                                    return JSON.stringify({ status: 'cloudflare' });
                                  }
                                  var targetId = $safeId;
                                  var targetPath = $safePath;
                                  var targetNick = $safeNick;
                                  var isInbox = /\/mesaj\/?(\?|$)/i.test(window.location.pathname || '');
                                  var scope = document;
                                  var matchedRow = false;
                                  if (isInbox) {
                                    var rows = document.querySelectorAll('ul#message-thread-list > li, ul#threads > li, .message-thread-list > li, table tr, div.thread-item, li[data-id], li[data-thread-id]');
                                    for (var i = 0; i < rows.length; i++) {
                                      var r = rows[i];
                                      var rId = (r.getAttribute('data-id') || r.getAttribute('data-thread-id') || '').trim();
                                      var link = r.querySelector('a[href*="/mesaj/"]');
                                      var linkHref = link ? (link.getAttribute('href') || '').toLowerCase() : '';
                                      var rTxt = (r.innerText || '').trim().toLowerCase();
                                      if ((targetId && rId === targetId) ||
                                          (targetPath && linkHref.indexOf(targetPath.toLowerCase()) !== -1) ||
                                          (targetNick && targetNick.length > 1 && rTxt.indexOf(targetNick) !== -1)) {
                                        scope = r;
                                        matchedRow = true;
                                        break;
                                      }
                                    }
                                    if (!matchedRow) {
                                      return JSON.stringify({ status: 'row_missing_on_inbox' });
                                    }
                                  }
                                  var delForms = scope.querySelectorAll('form');
                                  for (var f = 0; f < delForms.length; f++) {
                                    var form = delForms[f];
                                    if (form.querySelector('textarea')) continue;
                                    var act = (form.getAttribute('action') || '').toLowerCase();
                                    if (act.indexOf('arsiv') !== -1 || act.indexOf('engelle') !== -1 || act.indexOf('okundu') !== -1 || act.indexOf('giris') !== -1) continue;
                                    var btn = form.querySelector('button[type="submit"], input[type="submit"], button');
                                    var btnText = btn ? ((btn.innerText || btn.value || btn.getAttribute('title') || btn.getAttribute('aria-label') || '').trim().toLowerCase()) : '';
                                    if ((act.indexOf('/mesaj') !== -1 && (act.indexOf('sil') !== -1 || act.indexOf('delete') !== -1 || act.indexOf('kaldir') !== -1 || act.indexOf('temizle') !== -1)) ||
                                        btnText === 'sil' || btnText.indexOf('konuşmayı sil') !== -1 || btnText.indexOf('sohbeti sil') !== -1 || btnText.indexOf('mesajı sil') !== -1) {
                                      if (btn) { btn.click(); } else if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
                                      return JSON.stringify({ status: 'triggered' });
                                    }
                                  }
                                  var triggers = scope.querySelectorAll('a, button, input[type="button"], input[type="submit"]');
                                  for (var t = 0; t < triggers.length; t++) {
                                    var el = triggers[t];
                                    var h = (el.getAttribute('href') || '').toLowerCase();
                                    var dUrl = (el.getAttribute('data-url') || el.getAttribute('data-delete-url') || el.getAttribute('data-action') || '').toLowerCase();
                                    var cls = (el.className || '').toLowerCase();
                                    var txt = (el.innerText || el.value || '').trim().toLowerCase();
                                    var lbl = (el.getAttribute('title') || el.getAttribute('aria-label') || el.getAttribute('data-original-title') || '').trim().toLowerCase();
                                    if (h.indexOf('arsiv') !== -1 || h.indexOf('engelle') !== -1 || h.indexOf('bildir') !== -1) continue;
                                    var matchesAttr = (h.indexOf('/mesaj/') !== -1 && (h.indexOf('sil') !== -1 || h.indexOf('delete') !== -1)) ||
                                                      dUrl.indexOf('sil') !== -1 || dUrl.indexOf('delete') !== -1 ||
                                                      cls.indexOf('delete-message') !== -1 || cls.indexOf('delete-thread') !== -1 || cls.indexOf('delete') !== -1;
                                    var matchesTxt = txt === 'sil' || txt === 'konuşmayı sil' || txt === 'sohbeti sil' || txt === 'mesajı sil' ||
                                                     lbl === 'sil' || lbl.indexOf('konuşmayı sil') !== -1 || lbl.indexOf('sohbeti sil') !== -1 || lbl.indexOf('mesajı sil') !== -1;
                                    if (matchesAttr || matchesTxt) {
                                      el.click();
                                      return JSON.stringify({ status: 'triggered' });
                                    }
                                  }
                                  var dropdownToggle = scope.querySelector('.dropdown-toggle, button[aria-haspopup], .other.dropdown > a, [title*="diğer" i], [aria-label*="diğer" i]');
                                  if (dropdownToggle && !dropdownToggle.getAttribute('data-opened-by-eksiokur')) {
                                    dropdownToggle.setAttribute('data-opened-by-eksiokur', '1');
                                    dropdownToggle.click();
                                    return JSON.stringify({ status: 'waiting_dropdown' });
                                  }
                                  var cb = scope.querySelector('input[type="checkbox"]');
                                  if (cb && isInbox && matchedRow) {
                                    cb.checked = true;
                                    var bulkBtn = document.querySelector('form[action*="sil"] button[type="submit"], form[action*="sil"] input[type="submit"], button.delete, a.delete');
                                    if (bulkBtn) {
                                      bulkBtn.click();
                                      return JSON.stringify({ status: 'triggered' });
                                    }
                                  }
                                  return JSON.stringify({ status: 'not_found', isInbox: isInbox });
                                })();
                            """.trimIndent()

                            val confirmModalJs = """
                                (function() {
                                  var modalBtns = document.querySelectorAll('#delete-self-form button[type="submit"], .modal button, .modal a, [role="dialog"] button, .swal2-confirm, .confirm-dialog button');
                                  for (var i = 0; i < modalBtns.length; i++) {
                                    var b = modalBtns[i];
                                    var txt = (b.innerText || b.value || '').trim().toLowerCase();
                                    if (txt === 'evet' || txt === 'sil' || txt === 'onayla' || txt === 'tamam' || txt === 'ok' || txt === 'yes') {
                                      b.click();
                                      return 'confirmed';
                                    }
                                  }
                                  return 'none';
                                })();
                            """.trimIndent()

                            fun runAutomation(view: WebView?) {
                                if (view == null || webViewDestroyed.get() || autoCompleted) return
                                pollAttempts++
                                view.evaluateJavascript(triggerDeleteJs) { raw ->
                                    if (webViewDestroyed.get() || autoCompleted) return@evaluateJavascript
                                    val json = runCatching {
                                        val unquoted = if (raw != null && raw.startsWith("\"") && raw.endsWith("\"")) {
                                            org.json.JSONTokener(raw).nextValue() as? String ?: raw
                                        } else {
                                            raw.orEmpty()
                                        }
                                        org.json.JSONObject(unquoted)
                                    }.getOrNull()
                                    val status = json?.optString("status").orEmpty()
                                    when (status) {
                                        "login_required" -> {
                                            statusText = "Devam etmek için Ekşi Sözlük hesabınıza giriş yapın."
                                        }
                                        "cloudflare" -> {
                                            statusText = "Güvenlik doğrulamasını tamamlayın; işlem otomatik devam edecektir."
                                        }
                                        "row_missing_on_inbox" -> {
                                            if (autoTriggered && !autoCompleted) {
                                                autoCompleted = true
                                                statusText = "Mesaj silindi, liste güncelleniyor…"
                                                runCatching { CookieManager.getInstance().flush() }
                                                onVerifyCompleted()
                                            } else {
                                                statusText = "Sayfadan 'Sil'e dokunup Tamamlandı'ya basabilirsiniz."
                                            }
                                        }
                                        "triggered" -> {
                                            autoTriggered = true
                                            statusText = "Silme komutu gönderildi, onaylanıyor…"
                                            mainHandler.postDelayed({
                                                if (!webViewDestroyed.get()) {
                                                    view.evaluateJavascript(confirmModalJs, null)
                                                }
                                            }, 450L)
                                            mainHandler.postDelayed({
                                                if (!webViewDestroyed.get() && !autoCompleted) {
                                                    autoCompleted = true
                                                    runCatching { CookieManager.getInstance().flush() }
                                                    onVerifyCompleted()
                                                }
                                            }, 2200L)
                                        }
                                        "waiting_dropdown" -> {
                                            mainHandler.postDelayed({ runAutomation(view) }, 450L)
                                        }
                                        "not_found" -> {
                                            val onInbox = json?.optBoolean("isInbox", false) == true
                                            if (!onInbox && !triedInboxFallback) {
                                                triedInboxFallback = true
                                                statusText = "Mesaj kutusu kontrol ediliyor…"
                                                view.loadUrl("https://eksisozluk.com/mesaj")
                                            } else if (pollAttempts < 4) {
                                                mainHandler.postDelayed({ runAutomation(view) }, 500L)
                                            } else {
                                                statusText = "Otomatik bulunamadı — aşağıdaki sayfadan 'Sil'e dokunup Tamamlandı'ya basın."
                                            }
                                        }
                                    }
                                }
                            }

                            WebView(ctx).apply {
                                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                                }
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

                                    override fun onJsConfirm(
                                        view: WebView?,
                                        url: String?,
                                        message: String?,
                                        result: JsResult?
                                    ): Boolean {
                                        result?.confirm()
                                        return true
                                    }

                                    override fun onJsAlert(
                                        view: WebView?,
                                        url: String?,
                                        message: String?,
                                        result: JsResult?
                                    ): Boolean {
                                        result?.confirm()
                                        return true
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
                                        mainHandler.removeCallbacksAndMessages(null)
                                        if (webViewDestroyed.compareAndSet(false, true)) {
                                            runCatching {
                                                view?.stopLoading()
                                                view?.webViewClient = WebViewClient()
                                                view?.webChromeClient = null
                                                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                                                view?.removeAllViews()
                                                view?.destroy()
                                            }
                                        }
                                        renderCrashed = true
                                        return true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        if (webViewDestroyed.get()) return
                                        cm.flush()
                                        if (!autoTriggered) {
                                            mainHandler.postDelayed({ runAutomation(view) }, 350L)
                                        }
                                    }
                                }
                                loadUrl(targetUrl)
                            }
                        },
                        onRelease = { webView ->
                            mainHandler.removeCallbacksAndMessages(null)
                            if (webViewDestroyed.compareAndSet(false, true)) {
                                runCatching {
                                    webView.stopLoading()
                                    webView.clearHistory()
                                    webView.webViewClient = WebViewClient()
                                    webView.webChromeClient = null
                                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                                    webView.removeAllViews()
                                    webView.destroy()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
