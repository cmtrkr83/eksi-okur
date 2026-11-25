package com.example.data.network

import android.annotation.SuppressLint
import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class WebViewHtmlFetcher(
    context: Context
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var rendererDisabled = false

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun executeThreadDelete(
        targetUrl: String,
        threadId: String = "",
        threadPath: String = "",
        timeoutMs: Long = 10_000L
    ): Boolean {
        if (rendererDisabled) return false
        return mutex.withLock {
            if (rendererDisabled) return@withLock false
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    val handler = Handler(Looper.getMainLooper())
                    var finished = false
                    var webView: WebView? = null
                    var webViewDestroyed = false
                    var actionTriggered = false
                    var pollStarted = false

                    fun destroyWebViewOnce(target: WebView?) {
                        if (webViewDestroyed || target == null) return
                        webViewDestroyed = true
                        runCatching {
                            target.stopLoading()
                            target.onPause()
                            target.webViewClient = WebViewClient()
                            target.webChromeClient = null
                            target.removeAllViews()
                            target.destroy()
                        }
                    }

                    fun cleanupAndResume(success: Boolean) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        val target = webView
                        webView = null
                        destroyWebViewOnce(target)
                        if (cont.isActive) {
                            cont.resume(success)
                        }
                    }

                    val safeId = JSONObject.quote(threadId.trim())
                    val safePath = JSONObject.quote(threadPath.trim())

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
                            return JSON.stringify({ status: 'waiting' });
                          }
                          var targetId = $safeId;
                          var targetPath = $safePath;
                          var scope = document;
                          if (targetPath || targetId) {
                            var rows = document.querySelectorAll('ul#message-thread-list > li, ul#threads > li, .message-thread-list > li, table tr, div.thread-item, li[data-id], li[data-thread-id]');
                            for (var i = 0; i < rows.length; i++) {
                              var r = rows[i];
                              var rId = r.getAttribute('data-id') || r.getAttribute('data-thread-id') || '';
                              var link = r.querySelector('a[href*="/mesaj/"]');
                              var linkHref = link ? (link.getAttribute('href') || '') : '';
                              if ((targetId && rId === targetId) || (targetPath && linkHref.indexOf(targetPath) !== -1)) {
                                scope = r;
                                break;
                              }
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
                          var dropdownToggle = scope.querySelector('.dropdown-toggle, button[aria-haspopup], .other.dropdown > a');
                          if (dropdownToggle && !dropdownToggle.getAttribute('data-opened-by-eksiokur')) {
                            dropdownToggle.setAttribute('data-opened-by-eksiokur', '1');
                            dropdownToggle.click();
                            return JSON.stringify({ status: 'waiting' });
                          }
                          var cb = scope.querySelector('input[type="checkbox"]');
                          if (cb && scope !== document) {
                            cb.checked = true;
                            var bulkBtn = document.querySelector('form[action*="sil"] button[type="submit"], form[action*="sil"] input[type="submit"]');
                            if (bulkBtn) {
                              bulkBtn.click();
                              return JSON.stringify({ status: 'triggered' });
                            }
                          }
                          return JSON.stringify({ status: 'not_found' });
                        })();
                    """.trimIndent()

                    val confirmModalJs = """
                        (function() {
                          var modalBtns = document.querySelectorAll('.modal button, .modal a, [role="dialog"] button, .swal2-confirm, .confirm-dialog button');
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

                    lateinit var pollRunnable: Runnable
                    var attempts = 0
                    pollRunnable = Runnable {
                        val currentWebView = webView
                        if (finished || webViewDestroyed || currentWebView == null || actionTriggered) return@Runnable
                        attempts++
                        currentWebView.evaluateJavascript(triggerDeleteJs) { raw ->
                            if (finished || webViewDestroyed || actionTriggered) return@evaluateJavascript
                            val status = runCatching {
                                val unquoted = if (raw != null && raw.startsWith("\"") && raw.endsWith("\"")) {
                                    org.json.JSONTokener(raw).nextValue() as? String ?: raw
                                } else {
                                    raw.orEmpty()
                                }
                                JSONObject(unquoted).optString("status")
                            }.getOrDefault("")

                            when (status) {
                                "triggered" -> {
                                    actionTriggered = true
                                    handler.postDelayed({
                                        val wvNow = webView
                                        if (!finished && !webViewDestroyed && wvNow != null) {
                                            wvNow.evaluateJavascript(confirmModalJs, null)
                                        }
                                    }, 450L)
                                    handler.postDelayed({
                                        runCatching { CookieManager.getInstance().flush() }
                                        cleanupAndResume(true)
                                    }, 2400L)
                                }
                                "login_required" -> cleanupAndResume(false)
                                "not_found" -> {
                                    if (attempts >= 12) {
                                        cleanupAndResume(false)
                                    } else if (!finished && !webViewDestroyed) {
                                        handler.postDelayed(pollRunnable, 500L)
                                    }
                                }
                                else -> {
                                    if (!finished && !webViewDestroyed) {
                                        handler.postDelayed(pollRunnable, 500L)
                                    }
                                }
                            }
                        }
                    }

                    handler.postDelayed({
                        cleanupAndResume(actionTriggered)
                    }, timeoutMs)

                    try {
                        val wv = WebView(appContext)
                        webView = wv
                        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        wv.layout(0, 0, 1080, 1920)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                        }

                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadsImagesAutomatically = false
                            blockNetworkImage = true
                            userAgentString = EksiNetworkDataSource.USER_AGENT
                        }

                        wv.webChromeClient = object : WebChromeClient() {
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

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false

                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                runCatching { cm.flush() }
                                if (!finished && !webViewDestroyed && !pollStarted) {
                                    pollStarted = true
                                    handler.postDelayed(pollRunnable, 300L)
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                rendererDisabled = true
                                destroyWebViewOnce(view)
                                cleanupAndResume(false)
                                return true
                            }
                        }

                        cont.invokeOnCancellation {
                            handler.post { cleanupAndResume(false) }
                        }

                        wv.loadUrl(targetUrl)
                        handler.postDelayed({
                            if (!finished && !webViewDestroyed && !pollStarted) {
                                pollStarted = true
                                handler.post(pollRunnable)
                            }
                        }, 1400L)
                    } catch (_: Throwable) {
                        cleanupAndResume(false)
                    }
                }
            }
        }
    }
}
