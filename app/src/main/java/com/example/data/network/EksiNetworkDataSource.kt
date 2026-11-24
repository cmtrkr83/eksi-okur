package com.example.data.network

import com.example.data.model.AuthorProfile
import com.example.data.model.AuthorTab
import com.example.data.model.AutocompleteResult
import com.example.data.model.FeedCategory
import com.example.data.model.TopicDetail
import com.example.data.model.TopicFilter
import com.example.data.model.TopicItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CloudflareChallengeException(
    val challengeUrl: String,
    message: String = "Ekşi Sözlük güvenlik doğrulaması gerekiyor."
) : IOException(message)

data class NetworkPageResult<T>(
    val data: T,
    val rawHtml: String,
    val finalUrl: String,
    val loggedInNick: String? = null
)

class EksiNetworkDataSource(
    val cookieJar: WebViewCookieJar
) {
    companion object {
        const val BASE_URL = "https://eksisozluk.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchFeed(
        category: FeedCategory,
        channelSlug: String? = null,
        page: Int = 1
    ): NetworkPageResult<List<TopicItem>> = withContext(Dispatchers.IO) {
        val path = when (category) {
            FeedCategory.GUNDEM -> "/basliklar/gundem?p=$page"
            FeedCategory.DEBE -> "/debe"
            FeedCategory.BUGUN -> "/basliklar/bugun?p=$page"
            FeedCategory.KANAL -> {
                val slug = channelSlug?.takeIf { it.isNotBlank() } ?: "spor"
                "/basliklar/kanal/$slug?p=$page"
            }
        }
        val url = "$BASE_URL$path"
        val useAjaxHeader = category != FeedCategory.DEBE
        val response = executeGet(url, ajax = useAjaxHeader)

        // Fallback to non-AJAX endpoint if AJAX returned empty list
        var items = EksiHtmlParser.parseTopicList(
            html = response.first,
            isDebe = category == FeedCategory.DEBE
        )
        var finalHtml = response.first
        var finalUrl = response.second

        if (items.isEmpty() && useAjaxHeader) {
            val fallback = executeGet(url, ajax = false)
            items = EksiHtmlParser.parseTopicList(fallback.first, isDebe = false)
            finalHtml = fallback.first
            finalUrl = fallback.second
        }

        val nick = EksiHtmlParser.parseLoggedInNick(finalHtml)
        NetworkPageResult(
            data = items,
            rawHtml = finalHtml,
            finalUrl = finalUrl,
            loggedInNick = nick
        )
    }

    suspend fun fetchTopic(
        slugOrUrl: String,
        page: Int = 1,
        filter: TopicFilter = TopicFilter.ALL
    ): NetworkPageResult<TopicDetail> = withContext(Dispatchers.IO) {
        val url = buildTopicUrl(slugOrUrl, page, filter)
        val (html, finalUrl) = executeGet(url, ajax = false)
        val detail = EksiHtmlParser.parseTopicDetail(html, finalUrl, filter)
        val nick = EksiHtmlParser.parseLoggedInNick(html)
        NetworkPageResult(
            data = detail,
            rawHtml = html,
            finalUrl = finalUrl,
            loggedInNick = nick
        )
    }

    suspend fun fetchSingleEntry(entryId: Long): NetworkPageResult<TopicDetail> =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/entry/$entryId"
            val (html, finalUrl) = executeGet(url, ajax = false)
            val detail = EksiHtmlParser.parseSingleEntryPage(html)
            val nick = EksiHtmlParser.parseLoggedInNick(html)
            NetworkPageResult(
                data = detail,
                rawHtml = html,
                finalUrl = finalUrl,
                loggedInNick = nick
            )
        }

    suspend fun fetchAuthorProfile(
        authorSlug: String,
        tab: AuthorTab = AuthorTab.RECENT,
        page: Int = 1
    ): NetworkPageResult<AuthorProfile> = withContext(Dispatchers.IO) {
        val cleanSlug = URLEncoder.encode(authorSlug.trim().removePrefix("@"), "UTF-8")
            .replace("+", "%20")
        val profileUrl = "$BASE_URL/biri/$cleanSlug"
        val (profileHtml, finalUrl) = executeGet(profileUrl, ajax = false)
        var profile = EksiHtmlParser.parseAuthorProfile(profileHtml, authorSlug, tab)
        val loggedInNick = EksiHtmlParser.parseLoggedInNick(profileHtml)

        val resolvedNick = profile.nick.ifBlank { authorSlug.removePrefix("@") }
        val encodedNick = URLEncoder.encode(resolvedNick, "UTF-8").replace("+", "%20")

        // If the requested tab is not RECENT, or page > 1, or initial profile HTML didn't include inline entries,
        // fetch the tab's dedicated endpoint (discovered from the profile page or Ekşi's known tab endpoints).
        val needSeparateTabFetch = tab != AuthorTab.RECENT || page > 1 || profile.entries.isEmpty()
        if (needSeparateTabFetch) {
            val candidates = buildAuthorTabCandidateUrls(
                discoveredUrl = profile.tabUrls[tab],
                cleanSlug = cleanSlug,
                encodedNick = encodedNick,
                tab = tab,
                page = page
            )

            for (candidateUrl in candidates) {
                val fetched = runCatching {
                    val (ajaxHtml, _) = executeGet(candidateUrl, ajax = true, referer = profileUrl)
                    var parsed = EksiHtmlParser.parseAuthorTabEntries(ajaxHtml)
                    if (parsed.first.isEmpty()) {
                        val (fullHtml, _) = executeGet(candidateUrl, ajax = false, referer = profileUrl)
                        parsed = EksiHtmlParser.parseAuthorTabEntries(fullHtml)
                    }
                    parsed
                }.getOrNull()

                if (fetched != null && fetched.first.isNotEmpty()) {
                    profile = profile.copy(
                        entries = fetched.first,
                        currentPage = fetched.second.coerceAtLeast(page),
                        totalPages = fetched.third.coerceAtLeast(1)
                    )
                    break
                }
            }
        }

        NetworkPageResult(
            data = profile,
            rawHtml = profileHtml,
            finalUrl = finalUrl,
            loggedInNick = loggedInNick
        )
    }

    private fun buildAuthorTabCandidateUrls(
        discoveredUrl: String?,
        cleanSlug: String,
        encodedNick: String,
        tab: AuthorTab,
        page: Int
    ): List<String> {
        val list = mutableListOf<String>()

        fun appendPage(rawPath: String, paramName: String = "pageIndex"): String {
            val base = if (rawPath.startsWith("http")) rawPath else "$BASE_URL${EksiHtmlParser.normalizePath(rawPath)}"
            val clean = base.replace(Regex("([?&])(pageIndex|p)=\\d+"), "$1")
                .replace("?&", "?")
                .trimEnd('?', '&')
            val sep = if (clean.contains("?")) "&" else "?"
            return if (page > 1 || clean.contains("nick=")) "$clean$sep$paramName=$page" else clean
        }

        if (!discoveredUrl.isNullOrBlank()) {
            list.add(appendPage(discoveredUrl, "pageIndex"))
            list.add(appendPage(discoveredUrl, "p"))
        }

        when (tab) {
            AuthorTab.RECENT -> {
                list.add("$BASE_URL/son-entryleri?nick=$encodedNick&pageIndex=$page")
                list.add("$BASE_URL/son-entryleri?nick=$encodedNick&p=$page")
                list.add("$BASE_URL/biri/$cleanSlug?p=$page")
            }
            AuthorTab.TOP_FAVORITED -> {
                list.add("$BASE_URL/en-begenilenleri?nick=$encodedNick&pageIndex=$page")
                list.add("$BASE_URL/en-begenilenleri?nick=$encodedNick&p=$page")
                list.add("$BASE_URL/biri/$cleanSlug/en-begenilenleri?p=$page")
            }
            AuthorTab.FAVORITES -> {
                list.add("$BASE_URL/favori-entryleri?nick=$encodedNick&pageIndex=$page")
                list.add("$BASE_URL/favorileri?nick=$encodedNick&pageIndex=$page")
                list.add("$BASE_URL/favori-entryleri?nick=$encodedNick&p=$page")
                list.add("$BASE_URL/biri/$cleanSlug/favorileri?p=$page")
            }
        }
        return list.distinct()
    }

    suspend fun fetchAutocomplete(query: String): AutocompleteResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext AutocompleteResult()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val url = "$BASE_URL/autocomplete/query?q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", BASE_URL)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext AutocompleteResult()
            val body = resp.body?.string().orEmpty()
            if (body.isBlank() || body.trimStart().startsWith("<")) {
                return@withContext AutocompleteResult()
            }
            val json = JSONObject(body)
            val titlesJson = json.optJSONArray("Titles")
            val nicksJson = json.optJSONArray("Nicks")
            val titles = buildList {
                if (titlesJson != null) {
                    for (i in 0 until titlesJson.length()) {
                        titlesJson.optString(i)?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            val nicks = buildList {
                if (nicksJson != null) {
                    for (i in 0 until nicksJson.length()) {
                        nicksJson.optString(i)?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            AutocompleteResult(titles = titles.distinct(), nicks = nicks.distinct())
        }
    }

    suspend fun searchTopics(query: String, page: Int = 1): NetworkPageResult<List<TopicItem>> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$BASE_URL/basliklar/ara?SearchForm.Keywords=$encoded&p=$page"
            val (html, finalUrl) = executeGet(url, ajax = false)
            // If Ekşi redirected straight to a topic page instead of a list:
            val listItems = EksiHtmlParser.parseTopicList(html, isDebe = false)
            val items = if (listItems.isNotEmpty()) {
                listItems
            } else {
                val parsedTopic = EksiHtmlParser.parseTopicDetail(html, finalUrl, TopicFilter.ALL)
                if (parsedTopic.entries.isNotEmpty() || parsedTopic.title != "Başlık") {
                    listOf(
                        TopicItem(
                            title = parsedTopic.title,
                            slugUrl = parsedTopic.canonicalUrl,
                            entryCountText = "${parsedTopic.entries.size}+",
                            entryCount = parsedTopic.entries.size
                        )
                    )
                } else {
                    emptyList()
                }
            }
            NetworkPageResult(
                data = items,
                rawHtml = html,
                finalUrl = finalUrl,
                loggedInNick = EksiHtmlParser.parseLoggedInNick(html)
            )
        }

    suspend fun toggleFavorite(
        entryId: Long,
        currentlyFavorited: Boolean,
        verificationToken: String?
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = if (currentlyFavorited) {
                "$BASE_URL/entry/favsil"
            } else {
                "$BASE_URL/entry/favori"
            }
            val formBuilder = FormBody.Builder().add("entryId", entryId.toString())
            if (!verificationToken.isNullOrBlank()) {
                formBuilder.add("__RequestVerificationToken", verificationToken)
            }
            val request = Request.Builder()
                .url(endpoint)
                .post(formBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Referer", "$BASE_URL/entry/$entryId")
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw IllegalStateException("Bu işlem için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
                }
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("İşlem başarısız (${resp.code})")
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json != null && json.has("Count")) {
                    json.optInt("Count", 0)
                } else {
                    if (currentlyFavorited) -1 else 1
                }
            }
        }
    }

    suspend fun checkLoggedInUser(): String? = withContext(Dispatchers.IO) {
        if (!cookieJar.hasAnyCookies()) return@withContext null
        runCatching {
            val (homeHtml, _) = executeGet("$BASE_URL/", ajax = false)
            EksiHtmlParser.parseLoggedInNick(homeHtml)?.let { return@runCatching it }

            val (biriHtml, biriUrl) = executeGet("$BASE_URL/biri", ajax = false)
            val fromRedirect = biriUrl.substringAfter("/biri/", "")
                .substringBefore("?")
                .substringBefore("/")
                .takeIf { it.isNotBlank() && !biriUrl.contains("/giris") }
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            fromRedirect ?: EksiHtmlParser.parseLoggedInNick(biriHtml)
        }.getOrNull()
    }

    suspend fun postEntry(
        topicTitle: String,
        topicId: Long?,
        canonicalUrl: String,
        content: String,
        formAction: String?,
        formFields: Map<String, String>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var resolvedAction = formAction
            var resolvedFields = formFields

            // Ensure we have a fresh __RequestVerificationToken and hidden fields from the live topic page
            if (resolvedFields["__RequestVerificationToken"].isNullOrBlank()) {
                val targetUrl = buildTopicUrl(canonicalUrl, page = 1, filter = TopicFilter.ALL)
                val (freshHtml, freshUrl) = executeGet(targetUrl, ajax = false)
                val freshDetail = EksiHtmlParser.parseTopicDetail(freshHtml, freshUrl, TopicFilter.ALL)
                resolvedAction = freshDetail.entryFormAction ?: resolvedAction
                resolvedFields = freshDetail.entryFormFields
                if (resolvedFields["__RequestVerificationToken"].isNullOrBlank() &&
                    !freshDetail.verificationToken.isNullOrBlank()
                ) {
                    resolvedFields = resolvedFields + ("__RequestVerificationToken" to freshDetail.verificationToken)
                }
            }

            if (resolvedFields["__RequestVerificationToken"].isNullOrBlank()) {
                throw IllegalStateException(
                    "Entry gönderme formu bulunamadı. Oturumunuzun açık olduğundan ve yazar yetkinizin bulunduğundan emin olun."
                )
            }

            val actionPath = resolvedAction
                ?.let { EksiHtmlParser.normalizePath(it) }
                ?: "/entry/ekle"
            val postUrl = if (actionPath.startsWith("http")) actionPath else "$BASE_URL$actionPath"

            val formBuilder = FormBody.Builder()
            val mergedFields = LinkedHashMap<String, String>()
            mergedFields.putAll(resolvedFields)
            mergedFields["Content"] = content
            if (!mergedFields.containsKey("Title") || mergedFields["Title"].isNullOrBlank()) {
                mergedFields["Title"] = topicTitle
            }
            if (topicId != null && (!mergedFields.containsKey("Id") || mergedFields["Id"].isNullOrBlank())) {
                mergedFields["Id"] = topicId.toString()
            }
            if (!mergedFields.containsKey("ReturnUrl") || mergedFields["ReturnUrl"].isNullOrBlank()) {
                mergedFields["ReturnUrl"] = canonicalUrl
            }
            mergedFields["AddAsHidden"] = "false"

            mergedFields.forEach { (key, value) ->
                formBuilder.add(key, value)
            }

            val refererPath = EksiHtmlParser.normalizePath(canonicalUrl)
            val request = Request.Builder()
                .url(postUrl)
                .post(formBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Origin", BASE_URL)
                .header("Referer", "$BASE_URL$refererPath")
                .build()

            client.newCall(request).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val body = resp.body?.string().orEmpty()

                if (resp.code == 401 || resp.code == 403 || finalUrl.contains("/giris")) {
                    throw IllegalStateException("Oturumunuz açık değil veya güvenlik doğrulaması gerekiyor.")
                }

                // Check JSON response if Ekşi returned JSON
                if (body.trimStart().startsWith("{")) {
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    if (json != null) {
                        val success = json.optBoolean("Success", false)
                        if (!success) {
                            val msg = json.optString("Message").ifBlank {
                                json.optString("ErrorMessage").ifBlank { "Entry gönderilemedi." }
                            }
                            throw IllegalStateException(msg)
                        }
                        return@use json.optString("RedirectUrl").ifBlank { canonicalUrl }
                    }
                }

                if (!resp.isSuccessful && body.isBlank()) {
                    throw IOException("Entry gönderilemedi (HTTP ${resp.code})")
                }

                val parsedError = EksiHtmlParser.parsePostEntryError(body)
                if (!parsedError.isNullOrBlank()) {
                    throw IllegalStateException(parsedError)
                }

                EksiHtmlParser.normalizePath(finalUrl).ifBlank { canonicalUrl }
            }
        }
    }

    suspend fun fetchMessageInbox(archived: Boolean = false): NetworkPageResult<com.example.data.model.MessageInboxData> =
        withContext(Dispatchers.IO) {
            val path = if (archived) "/mesaj/arsiv" else "/mesaj"
            val url = "$BASE_URL$path"
            val (html, finalUrl) = executeGet(url, ajax = false)
            var inbox = EksiHtmlParser.parseMessageInbox(html, finalUrl)

            // If logged in and inbox page didn't embed compose form, fetch /mesaj/yeni to get token & form metadata
            if (!inbox.requiresLogin && inbox.composeForm == null) {
                runCatching {
                    val (yeniHtml, _) = executeGet("$BASE_URL/mesaj/yeni", ajax = false, referer = url)
                    val doc = org.jsoup.Jsoup.parse(yeniHtml, BASE_URL)
                    EksiHtmlParser.extractMessageFormMeta(doc)
                }.getOrNull()?.let { form ->
                    inbox = inbox.copy(composeForm = form)
                }
            }

            NetworkPageResult(
                data = inbox,
                rawHtml = html,
                finalUrl = finalUrl,
                loggedInNick = EksiHtmlParser.parseLoggedInNick(html)
            )
        }

    suspend fun fetchMessageThread(
        threadUrl: String,
        currentUserNick: String?
    ): NetworkPageResult<com.example.data.model.MessageThreadDetail> = withContext(Dispatchers.IO) {
        val normalized = EksiHtmlParser.normalizePath(threadUrl)
        val url = if (normalized.startsWith("http")) normalized else "$BASE_URL$normalized"
        val (html, finalUrl) = executeGet(url, ajax = false, referer = "$BASE_URL/mesaj")
        val detail = EksiHtmlParser.parseMessageThread(html, normalized, currentUserNick)
        NetworkPageResult(
            data = detail,
            rawHtml = html,
            finalUrl = finalUrl,
            loggedInNick = EksiHtmlParser.parseLoggedInNick(html)
        )
    }

    suspend fun sendMessage(
        toNick: String?,
        body: String,
        formMeta: com.example.data.model.MessageFormMeta?,
        refererPath: String = "/mesaj"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var resolvedForm = formMeta

            if (resolvedForm == null || resolvedForm.fields["__RequestVerificationToken"].isNullOrBlank()) {
                val targetFormUrl = if (refererPath.startsWith("http")) refererPath else "$BASE_URL${EksiHtmlParser.normalizePath(refererPath)}"
                val (freshHtml, freshUrl) = executeGet(targetFormUrl, ajax = false)
                if (freshUrl.contains("/giris")) {
                    throw IllegalStateException("Mesaj göndermek için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
                }
                val parsed = EksiHtmlParser.extractMessageFormMeta(org.jsoup.Jsoup.parse(freshHtml, BASE_URL))
                if (parsed != null) {
                    resolvedForm = parsed
                } else if (refererPath != "/mesaj/yeni") {
                    val (yeniHtml, yeniUrl) = executeGet("$BASE_URL/mesaj/yeni", ajax = false)
                    if (yeniUrl.contains("/giris")) {
                        throw IllegalStateException("Mesaj göndermek için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
                    }
                    resolvedForm = EksiHtmlParser.extractMessageFormMeta(org.jsoup.Jsoup.parse(yeniHtml, BASE_URL))
                }
            }

            val meta = resolvedForm ?: throw IllegalStateException(
                "Mesaj gönderme formu bulunamadı. Oturumunuzun açık olduğundan emin olun."
            )

            val actionPath = EksiHtmlParser.normalizePath(meta.action)
            val postUrl = if (actionPath.startsWith("http")) actionPath else "$BASE_URL$actionPath"

            val merged = LinkedHashMap<String, String>()
            merged.putAll(meta.fields)
            if (!toNick.isNullOrBlank()) {
                merged[meta.recipientFieldName] = toNick.trim().removePrefix("@")
            }
            merged[meta.bodyFieldName] = body

            val formBuilder = FormBody.Builder()
            merged.forEach { (k, v) -> formBuilder.add(k, v) }

            val referer = if (refererPath.startsWith("http")) refererPath else "$BASE_URL${EksiHtmlParser.normalizePath(refererPath)}"
            val request = Request.Builder()
                .url(postUrl)
                .post(formBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Origin", BASE_URL)
                .header("Referer", referer)
                .build()

            client.newCall(request).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val respBody = resp.body?.string().orEmpty()

                if (resp.code == 401 || resp.code == 403 || finalUrl.contains("/giris")) {
                    throw IllegalStateException("Oturumunuz açık değil veya doğrulama gerekiyor.")
                }

                if (respBody.trimStart().startsWith("{")) {
                    val json = runCatching { JSONObject(respBody) }.getOrNull()
                    if (json != null) {
                        val success = json.optBoolean("Success", false)
                        if (!success) {
                            val msg = json.optString("Message").ifBlank {
                                json.optString("ErrorMessage").ifBlank { "Mesaj gönderilemedi." }
                            }
                            throw IllegalStateException(msg)
                        }
                        return@use json.optString("RedirectUrl").ifBlank { refererPath }
                    }
                }

                if (!resp.isSuccessful && respBody.isBlank()) {
                    throw IOException("Mesaj gönderilemedi (HTTP ${resp.code})")
                }

                val parsedError = EksiHtmlParser.parsePostEntryError(respBody)
                if (!parsedError.isNullOrBlank()) {
                    throw IllegalStateException(parsedError)
                }

                EksiHtmlParser.normalizePath(finalUrl).ifBlank { refererPath }
            }
        }
    }

    fun buildTopicUrl(slugOrUrl: String, page: Int, filter: TopicFilter): String {
        val clean = slugOrUrl.trim()
        val base = when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "$BASE_URL$clean"
            else -> "$BASE_URL/?q=${URLEncoder.encode(clean, "UTF-8")}"
        }
        val withoutPage = base.replace(Regex("([?&])p=\\d+"), "$1")
            .replace("?&", "?")
            .trimEnd('?', '&')

        val separator = if (withoutPage.contains("?")) "&" else "?"
        val params = mutableListOf<String>()
        if (page > 1) params.add("p=$page")
        if (filter.queryParam != null && !withoutPage.contains("a=${filter.queryParam}")) {
            params.add("a=${filter.queryParam}")
        }
        return if (params.isEmpty()) withoutPage else "$withoutPage$separator${params.joinToString("&")}"
    }

    private fun executeGet(url: String, ajax: Boolean, referer: String = BASE_URL): Pair<String, String> {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", referer)

        if (ajax) {
            builder.header("X-Requested-With", "XMLHttpRequest")
        }

        client.newCall(builder.build()).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val body = response.body?.string().orEmpty()
            val isCfHeader = response.header("cf-mitigated") != null
            val isCfBody = (response.code == 403 || response.code == 503) &&
                (body.contains("Just a moment", ignoreCase = true) ||
                    body.contains("cf-browser-verification", ignoreCase = true) ||
                    body.contains("Attention Required", ignoreCase = true) ||
                    isCfHeader)

            if (isCfBody || response.code == 403) {
                throw CloudflareChallengeException(finalUrl)
            }
            if (!response.isSuccessful && body.isBlank()) {
                throw IOException("Sunucu yanıt vermedi (HTTP ${response.code})")
            }
            return body to finalUrl
        }
    }
}
