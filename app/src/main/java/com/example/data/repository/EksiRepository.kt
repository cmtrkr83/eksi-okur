package com.example.data.repository

import com.example.data.local.BookmarkEntity
import com.example.data.local.CachedFeedEntity
import com.example.data.local.CachedTopicEntity
import com.example.data.local.EksiDao
import com.example.data.local.ReadingHistoryEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.UserPreferences
import com.example.data.local.UserPreferencesRepository
import com.example.data.model.AuthorProfile
import com.example.data.model.AuthorTab
import com.example.data.model.AutocompleteResult
import com.example.data.model.EntryItem
import com.example.data.model.FeedCategory
import com.example.data.model.TopicDetail
import com.example.data.model.TopicFilter
import com.example.data.model.TopicItem
import com.example.data.model.UiResult
import com.example.data.network.CloudflareChallengeException
import com.example.data.network.EksiHtmlParser
import com.example.data.network.EksiNetworkDataSource
import kotlinx.coroutines.flow.Flow

class EksiRepository(
    private val network: EksiNetworkDataSource,
    private val dao: EksiDao,
    val preferences: UserPreferencesRepository
) {
    val userPreferences: Flow<UserPreferences> = preferences.preferencesFlow
    val bookmarks: Flow<List<BookmarkEntity>> = dao.observeBookmarks()
    val readingHistory: Flow<List<ReadingHistoryEntity>> = dao.observeReadingHistory()
    val searchHistory: Flow<List<SearchHistoryEntity>> = dao.observeSearchHistory()

    fun observeIsTopicBookmarked(slugUrl: String): Flow<Boolean> =
        dao.observeIsBookmarked(topicBookmarkId(slugUrl))

    fun observeIsEntryBookmarked(entryId: Long): Flow<Boolean> =
        dao.observeIsBookmarked(entryBookmarkId(entryId))

    suspend fun getFeed(
        category: FeedCategory,
        channelSlug: String? = null,
        page: Int = 1
    ): UiResult<List<TopicItem>> {
        val feedKey = "${category.name}_${channelSlug.orEmpty()}_$page"
        return try {
            val result = network.fetchFeed(category, channelSlug, page)
            result.loggedInNick?.let { preferences.setLoggedInNick(it) }
            if (result.data.isNotEmpty()) {
                dao.upsertCachedFeed(
                    CachedFeedEntity(
                        feedKey = feedKey,
                        rawHtml = result.rawHtml,
                        isDebe = category == FeedCategory.DEBE,
                        cachedAtMillis = System.currentTimeMillis()
                    )
                )
            }
            UiResult.Success(result.data, isFromCache = false)
        } catch (cf: CloudflareChallengeException) {
            val cached = dao.getCachedFeed(feedKey)
            val cachedItems = cached?.let { EksiHtmlParser.parseTopicList(it.rawHtml, it.isDebe) }
            UiResult.Error(
                message = "Ekşi Sözlük güvenlik doğrulaması istiyor. Tarayıcı doğrulamasını tamamlayarak devam edebilirsiniz.",
                isCloudflareChallenge = true,
                challengeUrl = cf.challengeUrl,
                cachedData = cachedItems?.takeIf { it.isNotEmpty() },
                cachedAtMillis = cached?.cachedAtMillis
            )
        } catch (e: Exception) {
            val cached = dao.getCachedFeed(feedKey)
            val cachedItems = cached?.let { EksiHtmlParser.parseTopicList(it.rawHtml, it.isDebe) }
            UiResult.Error(
                message = e.localizedMessage ?: "Başlıklar yüklenirken bağlantı hatası oluştu.",
                cachedData = cachedItems?.takeIf { it.isNotEmpty() },
                cachedAtMillis = cached?.cachedAtMillis
            )
        }
    }

    suspend fun getTopic(
        slugOrUrl: String,
        page: Int = 1,
        filter: TopicFilter = TopicFilter.ALL
    ): UiResult<TopicDetail> {
        val normalizedKey = normalizeTopicKey(slugOrUrl)
        val cacheKey = "${normalizedKey}_${filter.name}_$page"
        return try {
            val result = network.fetchTopic(slugOrUrl, page, filter)
            result.loggedInNick?.let { preferences.setLoggedInNick(it) }

            val detail = result.data
            if (detail.entries.isNotEmpty() || detail.isUkde) {
                dao.upsertCachedTopic(
                    CachedTopicEntity(
                        cacheKey = cacheKey,
                        title = detail.title,
                        canonicalUrl = detail.canonicalUrl,
                        rawHtml = result.rawHtml,
                        filterName = filter.name,
                        page = detail.currentPage,
                        cachedAtMillis = System.currentTimeMillis()
                    )
                )
                dao.upsertReadingHistory(
                    ReadingHistoryEntity(
                        slugUrl = detail.canonicalUrl,
                        title = detail.title,
                        lastPage = detail.currentPage,
                        visitedAtMillis = System.currentTimeMillis()
                    )
                )
            }
            UiResult.Success(detail, isFromCache = false)
        } catch (cf: CloudflareChallengeException) {
            val cached = dao.getCachedTopic(cacheKey)
            val cachedDetail = cached?.let {
                EksiHtmlParser.parseTopicDetail(it.rawHtml, it.canonicalUrl, filter)
            }
            UiResult.Error(
                message = "Bu başlığı yüklemek için kısa bir tarayıcı doğrulaması gerekiyor.",
                isCloudflareChallenge = true,
                challengeUrl = cf.challengeUrl,
                cachedData = cachedDetail,
                cachedAtMillis = cached?.cachedAtMillis
            )
        } catch (e: Exception) {
            val cached = dao.getCachedTopic(cacheKey)
            val cachedDetail = cached?.let {
                EksiHtmlParser.parseTopicDetail(it.rawHtml, it.canonicalUrl, filter)
            }
            UiResult.Error(
                message = e.localizedMessage ?: "Başlık yüklenemedi.",
                cachedData = cachedDetail,
                cachedAtMillis = cached?.cachedAtMillis
            )
        }
    }

    suspend fun getSingleEntry(entryId: Long): UiResult<TopicDetail> {
        return try {
            val result = network.fetchSingleEntry(entryId)
            result.loggedInNick?.let { preferences.setLoggedInNick(it) }
            UiResult.Success(result.data)
        } catch (cf: CloudflareChallengeException) {
            UiResult.Error(
                message = "Entry'yi görüntülemek için tarayıcı doğrulaması gerekiyor.",
                isCloudflareChallenge = true,
                challengeUrl = cf.challengeUrl
            )
        } catch (e: Exception) {
            UiResult.Error(e.localizedMessage ?: "Entry yüklenemedi.")
        }
    }

    suspend fun getAuthorProfile(
        authorSlug: String,
        tab: AuthorTab = AuthorTab.RECENT,
        page: Int = 1
    ): UiResult<AuthorProfile> {
        return try {
            val result = network.fetchAuthorProfile(authorSlug, tab, page)
            result.loggedInNick?.let { preferences.setLoggedInNick(it) }
            UiResult.Success(result.data)
        } catch (cf: CloudflareChallengeException) {
            UiResult.Error(
                message = "Yazar profili için tarayıcı doğrulaması gerekiyor.",
                isCloudflareChallenge = true,
                challengeUrl = cf.challengeUrl
            )
        } catch (e: Exception) {
            UiResult.Error(e.localizedMessage ?: "Yazar profili yüklenemedi.")
        }
    }

    suspend fun getAutocomplete(query: String): AutocompleteResult {
        return runCatching { network.fetchAutocomplete(query) }.getOrDefault(AutocompleteResult())
    }

    suspend fun search(query: String, page: Int = 1): UiResult<List<TopicItem>> {
        val clean = query.trim()
        if (clean.isBlank()) return UiResult.Success(emptyList())
        dao.insertSearchHistory(SearchHistoryEntity(query = clean))
        return try {
            val result = network.searchTopics(clean, page)
            UiResult.Success(result.data)
        } catch (cf: CloudflareChallengeException) {
            UiResult.Error(
                message = "Arama için tarayıcı doğrulaması gerekiyor.",
                isCloudflareChallenge = true,
                challengeUrl = cf.challengeUrl
            )
        } catch (e: Exception) {
            UiResult.Error(e.localizedMessage ?: "Arama sırasında hata oluştu.")
        }
    }

    suspend fun toggleFavorite(
        entryId: Long,
        currentlyFavorited: Boolean,
        verificationToken: String?
    ): Result<Int> {
        return network.toggleFavorite(entryId, currentlyFavorited, verificationToken)
    }

    suspend fun postEntry(
        topicTitle: String,
        topicId: Long?,
        canonicalUrl: String,
        content: String,
        formAction: String?,
        formFields: Map<String, String>
    ): Result<String> {
        return network.postEntry(
            topicTitle = topicTitle,
            topicId = topicId,
            canonicalUrl = canonicalUrl,
            content = content,
            formAction = formAction,
            formFields = formFields
        )
    }

    suspend fun getMessageInbox(archived: Boolean = false): UiResult<com.example.data.model.MessageInboxData> {
        return try {
            val res = network.fetchMessageInbox(archived)
            res.loggedInNick?.let { preferences.setLoggedInNick(it) }
            UiResult.Success(res.data)
        } catch (cf: CloudflareChallengeException) {
            UiResult.Error(
                message = "Mesajları görüntülemek için güvenlik doğrulaması gerekiyor.",
                isCloudflareChallenge = true,
                challengeUrl = cf.challengeUrl
            )
        } catch (e: Exception) {
            UiResult.Error(e.localizedMessage ?: "Mesajlar yüklenemedi.")
        }
    }

    suspend fun getMessageThread(
        threadUrl: String,
        currentUserNick: String?
    ): UiResult<com.example.data.model.MessageThreadDetail> {
        return try {
            val res = network.fetchMessageThread(threadUrl, currentUserNick)
            res.loggedInNick?.let { preferences.setLoggedInNick(it) }
            UiResult.Success(res.data)
        } catch (cf: CloudflareChallengeException) {
            UiResult.Error(
                message = "Konuşmayı görüntülemek için güvenlik doğrulaması gerekiyor.",
                isCloudflareChallenge = true,
                challengeUrl = cf.challengeUrl
            )
        } catch (e: Exception) {
            UiResult.Error(e.localizedMessage ?: "Konuşma yüklenemedi.")
        }
    }

    suspend fun sendMessage(
        toNick: String?,
        body: String,
        formMeta: com.example.data.model.MessageFormMeta?,
        refererPath: String = "/mesaj"
    ): Result<String> {
        return network.sendMessage(
            toNick = toNick,
            body = body,
            formMeta = formMeta,
            refererPath = refererPath
        )
    }

    suspend fun refreshSessionUser(fallbackNick: String? = null): String? {
        val nick = network.checkLoggedInUser() ?: fallbackNick?.takeIf { it.isNotBlank() }
        preferences.setLoggedInNick(nick)
        return nick
    }

    suspend fun signOut() {
        network.cookieJar.clearAllCookies()
        preferences.setLoggedInNick(null)
    }

    suspend fun toggleTopicBookmark(title: String, slugUrl: String, currentlyBookmarked: Boolean) {
        val id = topicBookmarkId(slugUrl)
        if (currentlyBookmarked) {
            dao.deleteBookmark(id)
        } else {
            dao.insertBookmark(
                BookmarkEntity(
                    id = id,
                    isEntry = false,
                    title = title,
                    slugUrl = EksiHtmlParser.normalizePath(slugUrl)
                )
            )
        }
    }

    suspend fun toggleEntryBookmark(
        topicTitle: String,
        topicUrl: String,
        entry: EntryItem,
        currentlyBookmarked: Boolean
    ) {
        val id = entryBookmarkId(entry.id)
        if (currentlyBookmarked) {
            dao.deleteBookmark(id)
        } else {
            dao.insertBookmark(
                BookmarkEntity(
                    id = id,
                    isEntry = true,
                    title = topicTitle,
                    slugUrl = EksiHtmlParser.normalizePath(topicUrl),
                    entryId = entry.id,
                    author = entry.author,
                    excerpt = entry.plainText.take(160)
                )
            )
        }
    }

    suspend fun removeBookmark(id: String) = dao.deleteBookmark(id)
    suspend fun deleteSearchQuery(query: String) = dao.deleteSearchQuery(query)
    suspend fun clearSearchHistory() = dao.clearSearchHistory()
    suspend fun clearReadingHistory() = dao.clearReadingHistory()
    suspend fun clearOfflineCache() {
        dao.clearFeedCache()
        dao.clearTopicCache()
    }

    fun hasSessionCookies(): Boolean = network.cookieJar.hasAnyCookies()

    private fun topicBookmarkId(slugUrl: String): String =
        "topic:${EksiHtmlParser.normalizePath(slugUrl).substringBefore("?")}"

    private fun entryBookmarkId(entryId: Long): String = "entry:$entryId"

    private fun normalizeTopicKey(slugOrUrl: String): String =
        EksiHtmlParser.normalizePath(slugOrUrl).substringBefore("?").lowercase()
}
