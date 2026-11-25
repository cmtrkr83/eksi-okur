package com.example.data.network

import com.example.data.model.AuthorProfile
import com.example.data.model.AuthorTab
import com.example.data.model.AutocompleteResult
import com.example.data.model.FeedCategory
import com.example.data.model.TopicDetail
import com.example.data.model.TopicFilter
import com.example.data.model.TopicItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CloudflareChallengeException(
    val challengeUrl: String,
    message: String = "Ekşi Sözlük güvenlik doğrulaması gerekiyor."
) : IOException(message)

class WebDeleteFallbackException(
    val targetUrl: String,
    val threadId: String,
    val threadPath: String,
    message: String = "Otomatik silme için tarayıcı doğrulaması gerekiyor."
) : IOException(message)

data class NetworkPageResult<T>(
    val data: T,
    val rawHtml: String,
    val finalUrl: String,
    val loggedInNick: String? = null
)

class EksiNetworkDataSource(
    val cookieJar: WebViewCookieJar,
    private val webViewFetcher: WebViewHtmlFetcher? = null
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
        val isDebe = category == FeedCategory.DEBE
        val useAjaxHeader = !isDebe
        var items: List<TopicItem> = emptyList()
        var finalHtml = ""
        var finalUrl = url

        if (useAjaxHeader) {
            val ajaxAttempt = runCatching {
                executeGet(url, ajax = true, allowWebViewFallback = false)
            }.onFailure { err ->
                if (err is kotlinx.coroutines.CancellationException) throw err
            }.getOrNull()

            if (ajaxAttempt != null) {
                val parsed = EksiHtmlParser.parseTopicList(ajaxAttempt.first, isDebe = false)
                if (parsed.isNotEmpty()) {
                    items = parsed
                    finalHtml = ajaxAttempt.first
                    finalUrl = ajaxAttempt.second
                }
            }
        }

        if (items.isEmpty()) {
            val fullAttempt = executeGet(url, ajax = false, allowWebViewFallback = true)
            items = EksiHtmlParser.parseTopicList(fullAttempt.first, isDebe = isDebe)
            finalHtml = fullAttempt.first
            finalUrl = fullAttempt.second
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
        var (html, finalUrl) = runCatching {
            executeGet(url, ajax = false, allowWebViewFallback = true)
        }.getOrElse { err ->
            if (err is CloudflareChallengeException || page <= 1) throw err
            // Try discovering the live totalPages from page 1 in case page > current totalPages returned HTTP 404/empty
            val pageOneUrl = buildTopicUrl(slugOrUrl, 1, filter)
            val (p1Html, p1FinalUrl) = executeGet(pageOneUrl, ajax = false, allowWebViewFallback = true)
            val p1Detail = EksiHtmlParser.parseTopicDetail(p1Html, p1FinalUrl, filter)
            if (p1Detail.totalPages in 1 until page && (p1Detail.entries.isNotEmpty() || p1Detail.isUkde)) {
                if (p1Detail.totalPages == 1) {
                    p1Html to p1FinalUrl
                } else {
                    val lastUrl = buildTopicUrl(p1Detail.canonicalUrl.ifBlank { slugOrUrl }, p1Detail.totalPages, filter)
                    executeGet(lastUrl, ajax = false, allowWebViewFallback = true)
                }
            } else {
                throw err
            }
        }

        var detail = EksiHtmlParser.parseTopicDetail(html, finalUrl, filter)
        if (page > 1 && detail.entries.isEmpty() && !detail.isUkde) {
            val targetLastPage = if (detail.topicId != null && detail.totalPages in 1 until page) {
                detail.totalPages
            } else {
                val pageOneUrl = buildTopicUrl(slugOrUrl, 1, filter)
                val (p1Html, p1FinalUrl) = executeGet(pageOneUrl, ajax = false, allowWebViewFallback = true)
                val p1Detail = EksiHtmlParser.parseTopicDetail(p1Html, p1FinalUrl, filter)
                if (p1Detail.totalPages == 1 && (p1Detail.entries.isNotEmpty() || p1Detail.isUkde)) {
                    html = p1Html
                    finalUrl = p1FinalUrl
                    detail = p1Detail
                    null
                } else if (p1Detail.totalPages in 1 until page && p1Detail.entries.isNotEmpty()) {
                    p1Detail.totalPages
                } else {
                    null
                }
            }

            if (targetLastPage != null) {
                val clampedUrl = buildTopicUrl(detail.canonicalUrl.ifBlank { slugOrUrl }, targetLastPage, filter)
                val (clampedHtml, clampedFinalUrl) = executeGet(clampedUrl, ajax = false, allowWebViewFallback = true)
                val clampedDetail = EksiHtmlParser.parseTopicDetail(clampedHtml, clampedFinalUrl, filter)
                if (clampedDetail.entries.isNotEmpty() || clampedDetail.isUkde) {
                    html = clampedHtml
                    finalUrl = clampedFinalUrl
                    detail = clampedDetail
                }
            }

            if (detail.entries.isEmpty() && !detail.isUkde) {
                throw IOException("Sayfa $page bulunamadı.")
            }
        }
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
            val (html, finalUrl) = executeGet(url, ajax = false, allowWebViewFallback = true)
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
        val (profileHtml, finalUrl) = executeGet(profileUrl, ajax = false, allowWebViewFallback = true)
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
            val (html, finalUrl) = executeGet(url, ajax = false, allowWebViewFallback = true)
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
        verificationToken: String?,
        refererPath: String? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedReferer = refererPath
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    val norm = EksiHtmlParser.normalizePath(it)
                    if (norm.startsWith("http")) norm else "$BASE_URL$norm"
                }
                ?: "$BASE_URL/entry/$entryId"

            var activeToken = verificationToken?.takeIf { it.isNotBlank() }
            if (activeToken == null) {
                activeToken = fetchEntryVerificationToken(entryId)
            }

            val candidatePaths = if (currentlyFavorited) {
                listOf("/entry/favsil", "/entry/favlama", "/entry/removefavorite")
            } else {
                listOf("/entry/favori", "/entry/favla", "/entry/addfavorite")
            }

            var lastError: Throwable? = null
            var refreshedTokenTried = false

            for (path in candidatePaths) {
                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    val endpoint = "$BASE_URL$path"
                    val formBuilder = FormBody.Builder()
                        .add("entryId", entryId.toString())
                    if (!activeToken.isNullOrBlank()) {
                        formBuilder.add("__RequestVerificationToken", activeToken)
                    }

                    val request = Request.Builder()
                        .url(endpoint)
                        .post(formBuilder.build())
                        .header("User-Agent", USER_AGENT)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Accept", "application/json, text/javascript, */*; q=0.01")
                        .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                        .header("Origin", BASE_URL)
                        .header("Referer", resolvedReferer)
                        .build()

                    val outcome = client.newCall(request).execute().use { resp ->
                        val finalUrl = resp.request.url.toString()
                        val body = resp.body?.string().orEmpty()
                        val isCfHeader = resp.header("cf-mitigated") != null
                        val isCfBody = (resp.code == 403 || resp.code == 503) &&
                            (body.contains("Just a moment", ignoreCase = true) ||
                                body.contains("cf-browser-verification", ignoreCase = true) ||
                                body.contains("Attention Required", ignoreCase = true) ||
                                isCfHeader)
                        if (isCfBody) {
                            throw CloudflareChallengeException(finalUrl)
                        }

                        if (resp.code == 401 ||
                            finalUrl.contains("/giris", ignoreCase = true) ||
                            body.contains("form[action*=/giris]", ignoreCase = true) ||
                            body.contains("action=\"/giris\"", ignoreCase = true)
                        ) {
                            throw IllegalStateException("Bu işlem için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
                        }

                        // Parse JSON response if present (both success and error responses)
                        val trimmed = body.trimStart()
                        if (trimmed.startsWith("{")) {
                            val json = runCatching { JSONObject(trimmed) }.getOrNull()
                            if (json != null) {
                                val explicitFail = (json.has("Success") && !json.optBoolean("Success", true)) ||
                                    (json.has("success") && !json.optBoolean("success", true)) ||
                                    (json.has("IsSuccessful") && !json.optBoolean("IsSuccessful", true)) ||
                                    (json.has("isSuccessful") && !json.optBoolean("isSuccessful", true))
                                if (explicitFail || !resp.isSuccessful) {
                                    val msg = json.optString("Message")
                                        .ifBlank { json.optString("message") }
                                        .ifBlank { json.optString("ErrorMessage") }
                                        .ifBlank { json.optString("error") }
                                    if (msg.isNotBlank()) {
                                        throw IllegalStateException(msg)
                                    }
                                } else {
                                    val count = when {
                                        json.has("Count") -> json.optInt("Count", -1)
                                        json.has("count") -> json.optInt("count", -1)
                                        json.has("FavoriteCount") -> json.optInt("FavoriteCount", -1)
                                        json.has("favoriteCount") -> json.optInt("favoriteCount", -1)
                                        else -> -1
                                    }
                                    return@use FavoriteCallOutcome.Success(count)
                                }
                            }
                        }

                        if (resp.isSuccessful) {
                            return@use FavoriteCallOutcome.Success(-1)
                        }

                        if (resp.code == 403) {
                            throw IllegalStateException("Bu işlem için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
                        }

                        // Retry once with a fresh token on 400/500 if we haven't refreshed yet
                        if ((resp.code == 400 || resp.code == 500) && !refreshedTokenTried) {
                            refreshedTokenTried = true
                            val fresh = fetchEntryVerificationToken(entryId)
                            if (!fresh.isNullOrBlank() && fresh != activeToken) {
                                activeToken = fresh
                                return@use FavoriteCallOutcome.RetrySameEndpoint
                            }
                        }

                        val htmlErr = EksiHtmlParser.parsePostEntryError(body)
                        val err = IllegalStateException(
                            htmlErr?.takeIf { it.isNotBlank() } ?: "İşlem başarısız (${resp.code})"
                        )
                        if (resp.code == 404 || resp.code == 405) {
                            return@use FavoriteCallOutcome.TryNextEndpoint(err)
                        }
                        throw err
                    }

                    when (outcome) {
                        is FavoriteCallOutcome.Success -> return@runCatching outcome.count
                        is FavoriteCallOutcome.RetrySameEndpoint -> continue
                        is FavoriteCallOutcome.TryNextEndpoint -> {
                            lastError = outcome.error
                            break
                        }
                    }
                }
            }

            throw (lastError ?: IOException("Favori işlemi başarısız."))
        }
    }

    private suspend fun fetchEntryVerificationToken(entryId: Long): String? {
        return runCatching {
            val (html, finalUrl) = executeGet("$BASE_URL/entry/$entryId", ajax = false)
            if (finalUrl.contains("/giris", ignoreCase = true)) {
                throw IllegalStateException("Bu işlem için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
            }
            val doc = org.jsoup.Jsoup.parse(html, BASE_URL)
            if (doc.selectFirst("form[action*=/giris]") != null) {
                throw IllegalStateException("Bu işlem için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
            }
            doc.selectFirst("input[name=__RequestVerificationToken]")
                ?.attr("value")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=csrf-token], meta[name=__RequestVerificationToken]")
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }
        }.getOrElse { err ->
            if (err is kotlinx.coroutines.CancellationException ||
                err is CloudflareChallengeException ||
                err is IllegalStateException
            ) {
                throw err
            }
            null
        }
    }

    private sealed interface FavoriteCallOutcome {
        data class Success(val count: Int) : FavoriteCallOutcome
        data object RetrySameEndpoint : FavoriteCallOutcome
        data class TryNextEndpoint(val error: Throwable) : FavoriteCallOutcome
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
    ): Result<com.example.data.model.SendMessageOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            var resolvedForm = formMeta

            if (resolvedForm == null || resolvedForm.fields["__RequestVerificationToken"].isNullOrBlank()) {
                val targetFormUrl = if (refererPath.startsWith("http")) {
                    refererPath
                } else {
                    "$BASE_URL${EksiHtmlParser.normalizePath(refererPath)}"
                }
                val (freshHtml, freshUrl) = executeGet(targetFormUrl, ajax = false)
                if (freshUrl.contains("/giris")) {
                    throw IllegalStateException("Mesaj göndermek için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
                }
                val fallbackPath = EksiHtmlParser.normalizePath(freshUrl)
                    .ifBlank { EksiHtmlParser.normalizePath(refererPath) }
                val parsed = EksiHtmlParser.extractMessageFormMeta(
                    doc = org.jsoup.Jsoup.parse(freshHtml, BASE_URL),
                    fallbackAction = fallbackPath
                )
                if (parsed != null) {
                    resolvedForm = parsed
                } else if (refererPath != "/mesaj/yeni") {
                    val (yeniHtml, yeniUrl) = executeGet("$BASE_URL/mesaj/yeni", ajax = false)
                    if (yeniUrl.contains("/giris")) {
                        throw IllegalStateException("Mesaj göndermek için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
                    }
                    resolvedForm = EksiHtmlParser.extractMessageFormMeta(
                        doc = org.jsoup.Jsoup.parse(yeniHtml, BASE_URL),
                        fallbackAction = "/mesaj/yeni"
                    )
                }
            }

            val meta = resolvedForm ?: throw IllegalStateException(
                "Mesaj gönderme formu bulunamadı. Oturumunuzun açık olduğundan emin olun."
            )

            val actionPath = EksiHtmlParser.normalizePath(meta.action)
            val postUrl = if (actionPath.startsWith("http")) actionPath else "$BASE_URL$actionPath"

            val merged = LinkedHashMap<String, String>()
            merged.putAll(meta.fields)
            val existingRecipient = merged[meta.recipientFieldName]
            if (!toNick.isNullOrBlank() &&
                existingRecipient.isNullOrBlank() &&
                (meta.hasRecipientInput || actionPath.contains("/yeni", ignoreCase = true))
            ) {
                merged[meta.recipientFieldName] = toNick.trim().removePrefix("@")
            }
            merged[meta.bodyFieldName] = body

            val formBuilder = FormBody.Builder()
            merged.forEach { (k, v) -> formBuilder.add(k, v) }

            val referer = if (refererPath.startsWith("http")) {
                refererPath
            } else {
                "$BASE_URL${EksiHtmlParser.normalizePath(refererPath)}"
            }
            val requestBuilder = Request.Builder()
                .url(postUrl)
                .post(formBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Origin", BASE_URL)
                .header("Referer", referer)

            if (meta.isAjax) {
                requestBuilder.header("X-Requested-With", "XMLHttpRequest")
            }

            client.newCall(requestBuilder.build()).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val respBody = resp.body?.string().orEmpty()

                val isCfHeader = resp.header("cf-mitigated") != null
                val isCfBody = (resp.code == 403 || resp.code == 503) &&
                    (respBody.contains("Just a moment", ignoreCase = true) ||
                        respBody.contains("cf-browser-verification", ignoreCase = true) ||
                        respBody.contains("Attention Required", ignoreCase = true) ||
                        isCfHeader)
                if (isCfBody) {
                    throw CloudflareChallengeException(finalUrl)
                }

                if (resp.code == 401 || resp.code == 403 || finalUrl.contains("/giris")) {
                    throw IllegalStateException("Oturumunuz açık değil veya doğrulama gerekiyor.")
                }

                if (respBody.trimStart().startsWith("{")) {
                    val json = runCatching { JSONObject(respBody) }.getOrNull()
                    if (json != null) {
                        val success = json.optBoolean("Success", json.optBoolean("success", false))
                        if (!success) {
                            val msg = json.optString("Message")
                                .ifBlank { json.optString("message") }
                                .ifBlank { json.optString("ErrorMessage") }
                                .ifBlank { json.optString("error") }
                                .ifBlank { "Mesaj gönderilemedi." }
                            throw IllegalStateException(msg)
                        }
                        val redirectUrl = json.optString("RedirectUrl")
                            .ifBlank { json.optString("redirectUrl") }
                            .ifBlank { refererPath }
                        return@use com.example.data.model.SendMessageOutcome(
                            redirectPath = EksiHtmlParser.normalizePath(redirectUrl).ifBlank { refererPath },
                            responseHtml = ""
                        )
                    }
                }

                val parsedError = EksiHtmlParser.parsePostEntryError(respBody)
                if (!parsedError.isNullOrBlank()) {
                    throw IllegalStateException(parsedError)
                }

                if (!resp.isSuccessful) {
                    throw IOException("Mesaj gönderilemedi (HTTP ${resp.code})")
                }

                com.example.data.model.SendMessageOutcome(
                    redirectPath = EksiHtmlParser.normalizePath(finalUrl).ifBlank { refererPath },
                    responseHtml = respBody
                )
            }
        }
    }

    @Volatile
    private var cachedScriptMessageEndpoints: List<String>? = null

    suspend fun deleteMessageThread(
        threadUrl: String,
        threadId: String,
        deleteMeta: com.example.data.model.MessageDeleteMeta?,
        fallbackToken: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalizedThreadPath = EksiHtmlParser.normalizePath(threadUrl)
            val threadFullUrl = if (normalizedThreadPath.startsWith("http")) {
                normalizedThreadPath
            } else {
                "$BASE_URL$normalizedThreadPath"
            }
            var discoveredId = threadId.trim()
            var meaningfulError: Throwable? = null
            var last404Error: Throwable? = null
            var cfError: CloudflareChallengeException? = null
            var nominalSuccessCount = 0

            fun recordOutcomeError(err: Throwable) {
                when {
                    err is CloudflareChallengeException -> cfError = err
                    err.message?.contains("404") == true -> last404Error = err
                    else -> meaningfulError = err
                }
            }

            // A 2xx answer from a guessed endpoint is not proof of deletion: Ekşi can reply
            // with the inbox/thread page again. Only trust it when the thread is really gone.
            suspend fun confirmedDeleted(path: String): Boolean {
                val verified = verifyThreadDeletedFromInbox(path, discoveredId)
                if (!verified && meaningfulError == null) {
                    meaningfulError = IOException("Mesaj silinemedi.")
                }
                return verified
            }

            // Stage 1: try pre-parsed inbox delete metadata directly if token is already present
            if (deleteMeta != null && !deleteMeta.fields["__RequestVerificationToken"].isNullOrBlank()) {
                val stage1Token = deleteMeta.fields["__RequestVerificationToken"]
                val stage1Candidates = listOf(
                    DeleteCandidate(deleteMeta.action, deleteMeta.fields, deleteMeta.isAjax, "POST", isJsonBody = false),
                    DeleteCandidate(deleteMeta.action, deleteMeta.fields, !deleteMeta.isAjax, "POST", isJsonBody = false),
                    DeleteCandidate(deleteMeta.action, deleteMeta.fields, isAjax = true, method = "POST", isJsonBody = true)
                ).distinctBy { "${it.action}|${it.isAjax}|${it.isJsonBody}" }
                for (candidate in stage1Candidates) {
                    when (
                        val outcome = executeDeleteCandidate(
                            candidate = candidate,
                            refererUrl = "$BASE_URL/mesaj",
                            normalizedThreadPath = normalizedThreadPath,
                            threadId = discoveredId,
                            fallbackToken = stage1Token
                        )
                    ) {
                        DeleteCallOutcome.Success -> {
                            nominalSuccessCount++
                            if (nominalSuccessCount == 1 &&
                                confirmedDeleted(normalizedThreadPath)
                            ) {
                                return@withContext Result.success(Unit)
                            }
                        }
                        is DeleteCallOutcome.RetryNext -> recordOutcomeError(outcome.error)
                    }
                }
            }

            // Stage 2: always fetch live thread page (and /mesaj if needed) for fresh token, real numeric ID, and script/form endpoints
            val stage2Get = runCatching {
                executeGet(
                    url = threadFullUrl,
                    ajax = false,
                    referer = "$BASE_URL/mesaj"
                )
            }.onFailure { err ->
                if (err is kotlinx.coroutines.CancellationException) throw err
                recordOutcomeError(err)
            }.getOrNull()

            var resolvedTargetUrl = threadFullUrl
            var resolvedPathForFallback = normalizedThreadPath

            if (stage2Get != null) {
                val (threadHtml, finalThreadUrl) = stage2Get
                if (finalThreadUrl.contains("/giris", ignoreCase = true)) {
                    return@withContext Result.failure(
                        IllegalStateException("Mesaj silmek için Ekşi Sözlük hesabınızla giriş yapmalısınız.")
                    )
                }
                val refererUrl = finalThreadUrl.ifBlank { threadFullUrl }
                resolvedTargetUrl = refererUrl
                val resolvedThreadPath = EksiHtmlParser.normalizePath(finalThreadUrl)
                    .takeIf { it.startsWith("/mesaj/") }
                    ?: normalizedThreadPath
                resolvedPathForFallback = resolvedThreadPath
                val threadDoc = org.jsoup.Jsoup.parse(threadHtml, BASE_URL)
                val finalUrlTail = resolvedThreadPath.substringBefore("?").trimEnd('/').substringAfterLast("/").trim()
                val idSeed = finalUrlTail.takeIf { it.toLongOrNull()?.let { n -> n > 0L } == true } ?: discoveredId
                discoveredId = EksiHtmlParser.extractThreadIdFromDocument(threadDoc, idSeed)

                val maxMessageId = extractMaxMessageIdFromDocument(threadDoc)

                val parsedFromThread = EksiHtmlParser.extractMessageDeleteMetaFromPage(
                    doc = threadDoc,
                    threadId = discoveredId
                )
                val threadScriptEndpoints = EksiHtmlParser.extractDeleteEndpointsFromHtml(threadHtml)

                var pageToken = parsedFromThread?.fields?.get("__RequestVerificationToken")?.takeIf { it.isNotBlank() }
                    ?: threadDoc.selectFirst("input[name=__RequestVerificationToken]")
                        ?.attr("value")
                        ?.takeIf { it.isNotBlank() }
                    ?: threadDoc.selectFirst("meta[name=csrf-token], meta[name=__RequestVerificationToken]")
                        ?.attr("content")
                        ?.takeIf { it.isNotBlank() }

                var inboxDoc: org.jsoup.nodes.Document? = null
                var inboxMeta: com.example.data.model.MessageDeleteMeta? = null
                var inboxScriptEndpoints: List<String> = emptyList()
                if (pageToken.isNullOrBlank() || parsedFromThread == null) {
                    runCatching {
                        val (inboxHtml, _) = executeGet("$BASE_URL/mesaj", ajax = false, referer = refererUrl)
                        val parsedInboxDoc = org.jsoup.Jsoup.parse(inboxHtml, BASE_URL)
                        inboxDoc = parsedInboxDoc
                        if (pageToken.isNullOrBlank()) {
                            pageToken = parsedInboxDoc.selectFirst("input[name=__RequestVerificationToken]")
                                ?.attr("value")
                                ?.takeIf { it.isNotBlank() }
                                ?: parsedInboxDoc.selectFirst("meta[name=csrf-token], meta[name=__RequestVerificationToken]")
                                    ?.attr("content")
                                    ?.takeIf { it.isNotBlank() }
                        }
                        inboxMeta = EksiHtmlParser.extractMessageDeleteMetaFromPage(parsedInboxDoc, discoveredId)
                        inboxScriptEndpoints = EksiHtmlParser.extractDeleteEndpointsFromHtml(inboxHtml)
                    }.onFailure { err ->
                        if (err is kotlinx.coroutines.CancellationException) throw err
                    }
                }

                val jsBundleEndpoints = discoverMessageDeleteEndpointsFromScripts(
                    listOfNotNull(threadDoc, inboxDoc)
                )

                val resolvedToken = pageToken?.takeIf { it.isNotBlank() }
                    ?: fallbackToken?.takeIf { it.isNotBlank() }
                    ?: deleteMeta?.fields?.get("__RequestVerificationToken")?.takeIf { it.isNotBlank() }

                val extraEndpoints = (
                    listOfNotNull(parsedFromThread?.action, inboxMeta?.action, deleteMeta?.action) +
                        threadScriptEndpoints +
                        inboxScriptEndpoints +
                        jsBundleEndpoints
                    ).distinct()

                val baseMeta = parsedFromThread ?: inboxMeta ?: deleteMeta
                val primaryFields = LinkedHashMap<String, String>().apply {
                    baseMeta?.fields?.let { putAll(it) }
                    if (!resolvedToken.isNullOrBlank()) {
                        put("__RequestVerificationToken", resolvedToken)
                    }
                }
                val stage2Primary = com.example.data.model.MessageDeleteMeta(
                    action = baseMeta?.action ?: extraEndpoints.firstOrNull() ?: "/mesaj/sil",
                    fields = primaryFields,
                    isAjax = baseMeta?.isAjax ?: true
                )

                val stage2Candidates = buildDeleteCandidates(
                    primary = stage2Primary,
                    threadId = discoveredId,
                    normalizedThreadPath = resolvedThreadPath,
                    extraEndpoints = extraEndpoints,
                    maxMessageId = maxMessageId
                )
                for (candidate in stage2Candidates) {
                    when (
                        val outcome = executeDeleteCandidate(
                            candidate = candidate,
                            refererUrl = refererUrl,
                            normalizedThreadPath = resolvedThreadPath,
                            threadId = discoveredId,
                            fallbackToken = resolvedToken
                        )
                    ) {
                        DeleteCallOutcome.Success -> {
                            nominalSuccessCount++
                            if (nominalSuccessCount == 1 &&
                                confirmedDeleted(resolvedThreadPath)
                            ) {
                                return@withContext Result.success(Unit)
                            }
                        }
                        is DeleteCallOutcome.RetryNext -> recordOutcomeError(outcome.error)
                    }
                }
            }

            // Stage 3a: give the server a moment, then verify once more. This also covers the
            // case where one of the HTTP calls worked but answered with an unexpected status.
            if (nominalSuccessCount > 0) {
                delay(700)
            }
            if (verifyThreadDeletedFromInbox(resolvedPathForFallback, discoveredId)) {
                return@withContext Result.success(Unit)
            }

            // Hand off to window-attached WebThreadDeleteDialog (avoids headless WebView renderer crashes)
            val errMsg = (cfError ?: meaningfulError ?: last404Error)?.message ?: "Mesaj silinemedi."
            Result.failure(
                WebDeleteFallbackException(
                    targetUrl = resolvedTargetUrl,
                    threadId = discoveredId,
                    threadPath = resolvedPathForFallback,
                    message = errMsg
                )
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun extractMaxMessageIdFromDocument(doc: org.jsoup.nodes.Document): Long? {
        val attrMax = doc.select(
            "#message-thread [data-id], .message-thread [data-id], ul.message-list [data-id], " +
                "li.incoming[data-id], li.outgoing[data-id], article[data-id], [data-message-id]"
        ).mapNotNull { el ->
            el.attr("data-message-id").ifBlank { el.attr("data-id") }
                .trim()
                .toLongOrNull()
                ?.takeIf { it > 0L }
        }.maxOrNull()
        if (attrMax != null) return attrMax

        val html = doc.html()
        return Regex("""(?:maxMessageId|lastMessageId|MaxMessageId)\s*[:=]\s*['"]?(\d+)['"]?""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    suspend fun verifyThreadDeleted(
        threadUrl: String,
        threadId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val normalized = EksiHtmlParser.normalizePath(threadUrl)
        verifyThreadDeletedFromInbox(normalized, threadId)
    }

    private fun discoverMessageDeleteEndpointsFromScripts(docs: List<org.jsoup.nodes.Document>): List<String> {
        cachedScriptMessageEndpoints?.let { return it }
        val scriptUrls = docs.flatMap { doc ->
            doc.select("script[src]").mapNotNull { el ->
                val abs = el.absUrl("src").ifBlank { el.attr("src").trim() }
                when {
                    abs.startsWith("https://") || abs.startsWith("http://") -> abs
                    abs.startsWith("//") -> "https:$abs"
                    abs.startsWith("/") -> "$BASE_URL$abs"
                    else -> null
                }
            }
        }.filter { url ->
            val lower = url.lowercase()
            (lower.contains("eksisozluk.com") || lower.contains("ekstat.com")) &&
                !lower.contains("gtag") &&
                !lower.contains("analytics") &&
                !lower.contains("ads") &&
                !lower.contains("doubleclick")
        }.distinct()
            .sortedByDescending { u ->
                val l = u.lowercase()
                when {
                    l.contains("mesaj") || l.contains("message") -> 3
                    l.contains("app") || l.contains("main") || l.contains("site") || l.contains("bundle") || l.contains("eks") -> 2
                    else -> 1
                }
            }
            .take(8)

        if (scriptUrls.isEmpty()) return emptyList()

        val found = LinkedHashSet<String>()
        var anySucceeded = false
        for (jsUrl in scriptUrls) {
            runCatching {
                val req = Request.Builder()
                    .url(jsUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", "$BASE_URL/mesaj")
                    .header("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                    .header("Sec-Ch-Ua-Mobile", "?1")
                    .header("Sec-Ch-Ua-Platform", "\"Android\"")
                    .header("Sec-Fetch-Dest", "script")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "same-site")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        anySucceeded = true
                        val jsBody = resp.body?.string().orEmpty()
                        found.addAll(EksiHtmlParser.extractDeleteEndpointsFromHtml(jsBody))
                    }
                }
            }
        }
        val list = found.toList()
        if (anySucceeded) {
            cachedScriptMessageEndpoints = list
        }
        return list
    }

    /**
     * Returns true only when we have positive evidence that the conversation is gone:
     * it is missing from both the regular and archived inbox, and its own page no longer
     * renders any messages. Failed/ambiguous checks return false so the caller never
     * announces a deletion that did not happen.
     */
    private suspend fun verifyThreadDeletedFromInbox(
        normalizedThreadPath: String,
        threadId: String
    ): Boolean {
        val cleanTargetPath = EksiHtmlParser.normalizePath(normalizedThreadPath)
            .substringBefore("?")
            .trimEnd('/')
        val cleanId = threadId.trim()

        var usableInbox = false
        for (archived in listOf(false, true)) {
            val page = runCatching { fetchMessageInbox(archived = archived) }.getOrNull() ?: continue
            val inbox = page.data
            if (inbox.requiresLogin) continue
            if (inbox.threads.isEmpty() && !EksiHtmlParser.looksLikeMessageInbox(page.rawHtml)) continue
            usableInbox = true
            val stillPresent = inbox.threads.any { item ->
                EksiHtmlParser.messageThreadMatches(
                    itemPath = item.threadUrl,
                    itemId = item.threadId,
                    targetPath = cleanTargetPath,
                    targetId = cleanId
                )
            }
            if (stillPresent) return false
        }

        if (!usableInbox) return false

        // The inbox only exposes the first page, so also check the conversation itself.
        // This prevents a thread that lives on page 2+ from being reported as deleted.
        if (cleanTargetPath.isBlank()) return false
        val threadFullUrl = if (cleanTargetPath.startsWith("http")) {
            cleanTargetPath
        } else {
            "$BASE_URL$cleanTargetPath"
        }
        val direct = runCatching {
            executeGet(threadFullUrl, ajax = false, referer = "$BASE_URL/mesaj")
        }.getOrNull() ?: return false

        val (html, finalUrl) = direct
        val finalPath = EksiHtmlParser.normalizePath(finalUrl).substringBefore("?").trimEnd('/')
        if (finalPath.contains("/giris", ignoreCase = true)) return false
        if (finalPath != cleanTargetPath) {
            // Redirected back to the inbox/home because the conversation no longer exists.
            return true
        }
        val detail = runCatching {
            EksiHtmlParser.parseMessageThread(html, cleanTargetPath, null)
        }.getOrNull() ?: return false
        if (detail.requiresLogin) return false
        return detail.messages.isEmpty()
    }

    private fun executeDeleteCandidate(
        candidate: DeleteCandidate,
        refererUrl: String,
        normalizedThreadPath: String,
        threadId: String,
        fallbackToken: String? = null
    ): DeleteCallOutcome {
        val actionPath = EksiHtmlParser.normalizePath(candidate.action).ifBlank { "/mesaj/sil" }
        val targetUrl = if (actionPath.startsWith("http")) actionPath else "$BASE_URL$actionPath"

        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01, text/html; q=0.8")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Origin", BASE_URL)
            .header("Referer", refererUrl)
            .header("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            .header("Sec-Ch-Ua-Mobile", "?1")
            .header("Sec-Ch-Ua-Platform", "\"Android\"")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", if (candidate.isAjax) "cors" else "navigate")
            .header("Sec-Fetch-Dest", if (candidate.isAjax) "empty" else "document")

        fun buildJsonPayload(): okhttp3.RequestBody {
            candidate.rawJsonBody?.let { raw ->
                return raw.toRequestBody("application/json; charset=utf-8".toMediaType())
            }
            val json = JSONObject()
            candidate.fields.forEach { (k, v) ->
                if (k == "__RequestVerificationToken") return@forEach
                val numeric = v.toLongOrNull()
                if (numeric != null && numeric > 0L) {
                    json.put(k, numeric)
                } else {
                    json.put(k, v)
                }
            }
            return json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        }

        when (candidate.method) {
            "GET" -> requestBuilder.get()
            "DELETE" -> {
                if (candidate.isJsonBody && (candidate.rawJsonBody != null || candidate.fields.any { it.key != "__RequestVerificationToken" })) {
                    requestBuilder.delete(buildJsonPayload())
                } else {
                    requestBuilder.delete()
                }
            }
            else -> {
                if (candidate.isJsonBody) {
                    requestBuilder.post(buildJsonPayload())
                } else {
                    val formBuilder = FormBody.Builder()
                    candidate.fields.forEach { (k, v) -> formBuilder.add(k, v) }
                    requestBuilder.post(formBuilder.build())
                }
            }
        }

        if (candidate.isAjax) {
            requestBuilder.header("X-Requested-With", "XMLHttpRequest")
        }
        val token = candidate.fields["__RequestVerificationToken"]?.takeIf { it.isNotBlank() }
            ?: fallbackToken?.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank() && (candidate.isAjax || candidate.isJsonBody || candidate.method == "DELETE")) {
            requestBuilder.header("RequestVerificationToken", token)
            requestBuilder.header("X-RequestVerificationToken", token)
            requestBuilder.header("X-CSRF-TOKEN", token)
        }

        return client.newCall(requestBuilder.build()).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val respBody = resp.body?.string().orEmpty()

            val isCfHeader = resp.header("cf-mitigated") != null
            val isCfBody = (resp.code == 403 || resp.code == 503) &&
                (respBody.contains("Just a moment", ignoreCase = true) ||
                    respBody.contains("cf-browser-verification", ignoreCase = true) ||
                    respBody.contains("Attention Required", ignoreCase = true) ||
                    isCfHeader)
            if (isCfBody) {
                return@use DeleteCallOutcome.RetryNext(CloudflareChallengeException(finalUrl))
            }

            if (resp.code == 401 || finalUrl.contains("/giris", ignoreCase = true)) {
                throw IllegalStateException("Oturumunuz açık değil veya doğrulama gerekiyor.")
            }

            val trimmed = respBody.trimStart()
            if (trimmed.startsWith("{")) {
                val json = runCatching { JSONObject(trimmed) }.getOrNull()
                if (json != null) {
                    val explicitFail = (json.has("Success") && !json.optBoolean("Success", true)) ||
                        (json.has("success") && !json.optBoolean("success", true)) ||
                        (json.has("IsSuccessful") && !json.optBoolean("IsSuccessful", true)) ||
                        (json.has("isSuccessful") && !json.optBoolean("isSuccessful", true)) ||
                        (json.has("Result") && !json.optBoolean("Result", true)) ||
                        (json.has("result") && !json.optBoolean("result", true))
                    if (explicitFail || !resp.isSuccessful) {
                        val msg = json.optString("Message")
                            .ifBlank { json.optString("message") }
                            .ifBlank { json.optString("ErrorMessage") }
                            .ifBlank { json.optString("error") }
                            .ifBlank { "Mesaj silinemedi." }
                        return@use DeleteCallOutcome.RetryNext(IllegalStateException(msg))
                    }
                    return@use DeleteCallOutcome.Success
                }
            }

            val parsedError = EksiHtmlParser.parsePostEntryError(respBody)
            if (!parsedError.isNullOrBlank()) {
                return@use DeleteCallOutcome.RetryNext(IllegalStateException(parsedError))
            }

            if (resp.isSuccessful) {
                // If POST redirected to inbox HTML, verify the thread wasn't silently retained
                val finalPath = EksiHtmlParser.normalizePath(finalUrl).substringBefore("?").trimEnd('/')
                if (finalPath == "/mesaj" || respBody.contains("message-thread-list") || respBody.contains("id=\"threads\"")) {
                    val inbox = runCatching { EksiHtmlParser.parseMessageInbox(respBody, finalUrl) }.getOrNull()
                    if (inbox != null && !inbox.requiresLogin) {
                        val cleanTarget = normalizedThreadPath.substringBefore("?").trimEnd('/')
                        val cleanId = threadId.trim()
                        val stillThere = inbox.threads.any { item ->
                            val itemPath = EksiHtmlParser.normalizePath(item.threadUrl).substringBefore("?").trimEnd('/')
                            (cleanTarget.isNotBlank() && itemPath.equals(cleanTarget, ignoreCase = true)) ||
                                (cleanId.isNotBlank() && item.threadId.trim() == cleanId)
                        }
                        if (stillThere) {
                            return@use DeleteCallOutcome.RetryNext(IOException("Mesaj silinemedi."))
                        }
                    }
                }
                return@use DeleteCallOutcome.Success
            }

            if (resp.code == 403) {
                return@use DeleteCallOutcome.RetryNext(
                    IllegalStateException("Oturumunuz açık değil veya doğrulama gerekiyor.")
                )
            }

            val err = IOException("Mesaj silinemedi (HTTP ${resp.code})")
            if (resp.code == 400 || resp.code == 404 || resp.code == 405 || resp.code == 415 || resp.code == 500) {
                DeleteCallOutcome.RetryNext(err)
            } else {
                throw err
            }
        }
    }

    private fun buildDeleteCandidates(
        primary: com.example.data.model.MessageDeleteMeta,
        threadId: String,
        normalizedThreadPath: String,
        extraEndpoints: List<String>,
        maxMessageId: Long? = null
    ): List<DeleteCandidate> {
        val list = mutableListOf<DeleteCandidate>()
        val token = primary.fields["__RequestVerificationToken"].orEmpty()
        val cleanId = threadId.trim().takeIf { it.isNotBlank() && it != "0" }.orEmpty()
        val cleanThreadPath = normalizedThreadPath.substringBefore("?").trimEnd('/')
        val pathTail = cleanThreadPath.substringAfterLast("/").trim()
            .takeIf { it.isNotBlank() && it != "0" && !it.equals("mesaj", ignoreCase = true) }
            .orEmpty()
        val candidateIds = listOf(cleanId, pathTail).filter { it.isNotBlank() }.distinct()
        val preferredId = candidateIds.firstOrNull().orEmpty()

        val primaryFields = LinkedHashMap<String, String>().apply {
            putAll(primary.fields)
            val hasId = keys.any {
                val k = it.lowercase()
                k == "id" || k == "threadid" || k == "konusmaid" || k == "messageid" || k == "threadids"
            }
            if (!hasId && preferredId.isNotBlank() && !primary.action.contains("/$preferredId")) {
                put("id", preferredId)
            }
        }
        list.add(DeleteCandidate(primary.action, primaryFields, primary.isAjax, "POST", isJsonBody = false))
        list.add(DeleteCandidate(primary.action, primaryFields, true, "POST", isJsonBody = true))

        val idForFastProbe = preferredId
        if (idForFastProbe.isNotBlank()) {
            val numericId = idForFastProbe.toLongOrNull()?.takeIf { it > 0L }
            val threadListJson = if (numericId != null) {
                val item = JSONObject().apply {
                    put("ThreadId", numericId)
                    if (maxMessageId != null) put("MaxMessageId", maxMessageId)
                }
                JSONObject().put("ThreadIdList", org.json.JSONArray().put(item)).toString()
            } else {
                null
            }

            // 1. Discovered endpoints from HTML/JS bundles first
            val discoveredActions = extraEndpoints.flatMap { rawAct ->
                val norm = EksiHtmlParser.normalizePath(rawAct)
                when {
                    norm.endsWith("/") -> listOf("$norm$idForFastProbe", norm.trimEnd('/'))
                    norm.endsWith("=") -> listOf("$norm$idForFastProbe")
                    else -> listOf(norm)
                }
            }.filter { it.isNotBlank() }.distinct()

            for (act in discoveredActions) {
                if (act.contains("/$idForFastProbe") || act.contains("=$idForFastProbe")) {
                    val tokenOnly = LinkedHashMap<String, String>().apply {
                        if (token.isNotBlank()) put("__RequestVerificationToken", token)
                    }
                    list.add(DeleteCandidate(act, tokenOnly, isAjax = true, method = "POST", isJsonBody = false))
                    list.add(DeleteCandidate(act, tokenOnly, isAjax = true, method = "DELETE", isJsonBody = false))
                } else {
                    val idFields = LinkedHashMap<String, String>().apply {
                        if (token.isNotBlank()) put("__RequestVerificationToken", token)
                        put("id", idForFastProbe)
                    }
                    val threadFields = LinkedHashMap<String, String>().apply {
                        if (token.isNotBlank()) put("__RequestVerificationToken", token)
                        put("threadId", idForFastProbe)
                        if (maxMessageId != null) put("maxMessageId", maxMessageId.toString())
                    }
                    list.add(DeleteCandidate(act, idFields, isAjax = true, method = "POST", isJsonBody = false))
                    list.add(DeleteCandidate(act, threadFields, isAjax = true, method = "POST", isJsonBody = false))
                    list.add(DeleteCandidate(act, threadFields, isAjax = true, method = "POST", isJsonBody = true))
                    if (threadListJson != null) {
                        list.add(DeleteCandidate(act, idFields, isAjax = true, method = "POST", isJsonBody = true, rawJsonBody = threadListJson))
                    }
                }
            }

            // 2. Concise high-probability fallback endpoints.
            // /mesaj/sil expects the bulk-form field name "threadId"; older variants used
            // "id"/"threadIds", so all of them are probed (form-encoded first).
            val fallbackRoutes = listOf(
                "/mesaj/sil",
                "/mesaj/konusma/sil"
            )
            val idFieldNames = listOf("threadId", "id", "threadIds").distinct()
            for (route in fallbackRoutes) {
                if (route.contains("konusma", ignoreCase = true)) {
                    for (fieldName in listOf("id", "threadId").distinct()) {
                        val fields = LinkedHashMap<String, String>().apply {
                            if (token.isNotBlank()) put("__RequestVerificationToken", token)
                            put(fieldName, idForFastProbe)
                        }
                        list.add(DeleteCandidate(route, fields, isAjax = true, method = "POST", isJsonBody = false))
                    }
                    continue
                }
                for (fieldName in idFieldNames) {
                    val fields = LinkedHashMap<String, String>().apply {
                        if (token.isNotBlank()) put("__RequestVerificationToken", token)
                        put(fieldName, idForFastProbe)
                        if (fieldName == "threadId" && maxMessageId != null) {
                            put("maxMessageId", maxMessageId.toString())
                        }
                    }
                    list.add(DeleteCandidate(route, fields, isAjax = true, method = "POST", isJsonBody = false))
                }
                if (threadListJson != null) {
                    val idFields = LinkedHashMap<String, String>().apply {
                        if (token.isNotBlank()) put("__RequestVerificationToken", token)
                        put("id", idForFastProbe)
                    }
                    list.add(
                        DeleteCandidate(
                            route,
                            idFields,
                            isAjax = true,
                            method = "POST",
                            isJsonBody = true,
                            rawJsonBody = threadListJson
                        )
                    )
                }
            }

            // 3. Direct path-ID & DELETE probe
            val tokenOnly = LinkedHashMap<String, String>().apply {
                if (token.isNotBlank()) put("__RequestVerificationToken", token)
            }
            list.add(DeleteCandidate("/mesaj/sil/$idForFastProbe", tokenOnly, isAjax = true, method = "POST", isJsonBody = false))
            if (cleanThreadPath.startsWith("/mesaj/") && !cleanThreadPath.equals("/mesaj/yeni", ignoreCase = true)) {
                list.add(DeleteCandidate(cleanThreadPath, tokenOnly, isAjax = true, method = "DELETE", isJsonBody = false))
            }
        }

        return list.distinctBy {
            "${it.method}|${it.action}|${it.isAjax}|${it.isJsonBody}|${it.rawJsonBody}|${it.fields.entries.joinToString()}"
        }.take(16)
    }

    private data class DeleteCandidate(
        val action: String,
        val fields: Map<String, String>,
        val isAjax: Boolean,
        val method: String = "POST",
        val isJsonBody: Boolean = false,
        val rawJsonBody: String? = null
    )

    private sealed interface DeleteCallOutcome {
        data object Success : DeleteCallOutcome
        data class RetryNext(val error: Throwable) : DeleteCallOutcome
    }

    fun buildTopicUrl(slugOrUrl: String, page: Int, filter: TopicFilter): String {
        val clean = slugOrUrl.trim().substringBefore("#")
        val base = when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "$BASE_URL$clean"
            else -> "$BASE_URL/?q=${URLEncoder.encode(clean, "UTF-8")}"
        }
        val pathPart = base.substringBefore("?")
        val queryPart = base.substringAfter("?", "")
        val preservedParams = queryPart
            .split("&")
            .filter { pair ->
                if (pair.isBlank()) return@filter false
                val key = pair.substringBefore("=").lowercase()
                key != "p" && key != "a"
            }

        val allParams = preservedParams.toMutableList()
        if (page > 1) {
            allParams.add("p=$page")
        }
        filter.queryParam?.let { param ->
            allParams.add("a=$param")
        }

        return if (allParams.isEmpty()) pathPart else "$pathPart?${allParams.joinToString("&")}"
    }

    private suspend fun executeGet(
        url: String,
        ajax: Boolean,
        referer: String = BASE_URL,
        @Suppress("UNUSED_PARAMETER") allowWebViewFallback: Boolean = false
    ): Pair<String, String> {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", referer)
            .header("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            .header("Sec-Ch-Ua-Mobile", "?1")
            .header("Sec-Ch-Ua-Platform", "\"Android\"")

        if (ajax) {
            builder
                .header("Accept", "text/html, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
        } else {
            builder
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
        }

        val outcome = client.newCall(builder.build()).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val body = response.body?.string().orEmpty()
            val isCfHeader = response.header("cf-mitigated") != null
            val isCfBody = (response.code == 403 || response.code == 503) &&
                (body.contains("Just a moment", ignoreCase = true) ||
                    body.contains("cf-browser-verification", ignoreCase = true) ||
                    body.contains("Attention Required", ignoreCase = true) ||
                    isCfHeader)

            Triple(response.code, response.isSuccessful, Triple(isCfBody || response.code == 403, body, finalUrl))
        }

        val (httpCode, isSuccessful, payload) = outcome
        val (needsCfFallback, body, finalUrl) = payload

        if (needsCfFallback) {
            throw CloudflareChallengeException(finalUrl)
        }
        if (!isSuccessful && body.isBlank()) {
            throw IOException("Sunucu yanıt vermedi (HTTP $httpCode)")
        }
        return body to finalUrl
    }
}
