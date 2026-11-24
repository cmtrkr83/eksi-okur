package com.example.data.model

enum class FeedCategory(val label: String) {
    GUNDEM("Gündem"),
    DEBE("Debe"),
    BUGUN("Bugün"),
    KANAL("Kanallar")
}

enum class TopicFilter(val label: String, val queryParam: String?) {
    ALL("Tümü", null),
    POPULAR("Popüler", "popular"),
    SUKELA("Şükela", "nice"),
    DAILY_SUKELA("Bugünün Şükelası", "dailynice")
}

enum class AuthorTab(val label: String, val pathSuffix: String) {
    RECENT("Son Entry'leri", ""),
    TOP_FAVORITED("En Beğenilenleri", "/en-begenilenleri"),
    FAVORITES("Favorileri", "/favorileri")
}

data class EksiChannel(
    val name: String,
    val slug: String
)

val DEFAULT_EKSI_CHANNELS = listOf(
    EksiChannel("Spor", "spor"),
    EksiChannel("Siyaset", "siyaset"),
    EksiChannel("Teknoloji", "teknoloji"),
    EksiChannel("Sinema", "sinema"),
    EksiChannel("Müzik", "muzik"),
    EksiChannel("Bilim", "bilim"),
    EksiChannel("Edebiyat", "edebiyat"),
    EksiChannel("Ekonomi", "ekonomi"),
    EksiChannel("Tarih", "tarih"),
    EksiChannel("Oyun", "oyun"),
    EksiChannel("Eğitim", "egitim"),
    EksiChannel("Sağlık", "saglik"),
    EksiChannel("Sanat", "sanat"),
    EksiChannel("Otomobil", "otomobil")
)

data class TopicItem(
    val title: String,
    val slugUrl: String,
    val entryCountText: String = "",
    val entryCount: Int = 0,
    val isDebe: Boolean = false,
    val entryId: Long? = null
)

sealed interface EntrySegment {
    data class Text(val text: String) : EntrySegment
    data class Bkz(
        val displayText: String,
        val targetTopic: String,
        val targetUrl: String,
        val isSmartBkz: Boolean = false,
        val tooltipText: String? = null
    ) : EntrySegment
    data class ExternalUrl(
        val displayText: String,
        val url: String
    ) : EntrySegment
    data class Spoiler(
        val segments: List<EntrySegment>
    ) : EntrySegment
}

data class EntryItem(
    val id: Long,
    val author: String,
    val authorSlug: String,
    val createdAt: String,
    val editedAt: String? = null,
    val plainText: String,
    val segments: List<EntrySegment>,
    val favoriteCount: Int = 0,
    val isFavorited: Boolean = false,
    val topicTitle: String? = null,
    val topicUrl: String? = null
)

data class ChannelTag(
    val name: String,
    val slug: String
)

data class TopicDetail(
    val title: String,
    val topicId: Long?,
    val canonicalUrl: String,
    val currentPage: Int,
    val totalPages: Int,
    val filter: TopicFilter,
    val entries: List<EntryItem>,
    val channels: List<ChannelTag> = emptyList(),
    val isUkde: Boolean = false,
    val verificationToken: String? = null,
    val entryFormAction: String? = null,
    val entryFormFields: Map<String, String> = emptyMap()
)

data class AuthorProfile(
    val nick: String,
    val slug: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val stats: List<Pair<String, String>> = emptyList(),
    val entries: List<EntryItem> = emptyList(),
    val activeTab: AuthorTab = AuthorTab.RECENT,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val tabUrls: Map<AuthorTab, String> = emptyMap()
)

data class AutocompleteResult(
    val titles: List<String> = emptyList(),
    val nicks: List<String> = emptyList()
)

data class MessageFormMeta(
    val action: String,
    val fields: Map<String, String> = emptyMap(),
    val recipientFieldName: String = "To",
    val bodyFieldName: String = "Message"
)

data class MessageThreadSummary(
    val threadId: String,
    val threadUrl: String,
    val participantNick: String,
    val preview: String,
    val dateText: String,
    val isUnread: Boolean = false
)

data class MessageInboxData(
    val threads: List<MessageThreadSummary> = emptyList(),
    val composeForm: MessageFormMeta? = null,
    val requiresLogin: Boolean = false
)

data class DirectMessageItem(
    val id: String,
    val senderNick: String,
    val body: String,
    val segments: List<EntrySegment> = emptyList(),
    val sentAt: String = "",
    val isOutgoing: Boolean = false
)

data class MessageThreadDetail(
    val threadUrl: String,
    val participantNick: String,
    val messages: List<DirectMessageItem> = emptyList(),
    val replyForm: MessageFormMeta? = null,
    val requiresLogin: Boolean = false
)

sealed interface UiResult<out T> {
    data object Loading : UiResult<Nothing>
    data class Success<T>(
        val data: T,
        val isFromCache: Boolean = false,
        val cachedAtMillis: Long? = null
    ) : UiResult<T>
    data class Error<T>(
        val message: String,
        val isCloudflareChallenge: Boolean = false,
        val challengeUrl: String? = null,
        val cachedData: T? = null,
        val cachedAtMillis: Long? = null
    ) : UiResult<T>
}
