package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserPreferences
import com.example.data.model.EntryItem
import com.example.data.model.TopicDetail
import com.example.data.model.TopicFilter
import com.example.data.model.UiResult
import com.example.data.network.CloudflareChallengeException
import com.example.data.repository.EksiRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TopicUiState(
    val requestedSlugOrUrl: String = "",
    val canonicalSlug: String? = null,
    val singleEntryId: Long? = null,
    val currentPage: Int = 1,
    val filter: TopicFilter = TopicFilter.ALL,
    val isRefreshing: Boolean = false,
    val isAppendingNextPage: Boolean = false,
    val appendError: String? = null,
    val appendChallengeUrl: String? = null,
    val scrollToTopTrigger: Int = 0,
    val result: UiResult<TopicDetail> = UiResult.Loading,
    val actionMessage: String? = null,
    val actionChallengeUrl: String? = null,
    val isComposeOpen: Boolean = false,
    val isSubmittingEntry: Boolean = false,
    val submitEntryError: String? = null,
    val loginPromptMessage: String? = null,
    val pendingComposeAfterLogin: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModel(
    private val repository: EksiRepository
) : ViewModel() {

    private var appendJob: Job? = null

    private val _uiState = MutableStateFlow(TopicUiState())
    val uiState: StateFlow<TopicUiState> = _uiState.asStateFlow()

    val preferences: StateFlow<UserPreferences> = repository.userPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val bookmarkedEntryIds: StateFlow<Set<Long>> = repository.bookmarks
        .map { list -> list.mapNotNull { it.entryId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isTopicBookmarked: StateFlow<Boolean> = _uiState
        .map { state ->
            val detail = (state.result as? UiResult.Success)?.data
                ?: (state.result as? UiResult.Error)?.cachedData
            detail?.canonicalUrl ?: state.requestedSlugOrUrl
        }
        .flatMapLatest { slug ->
            if (slug.isBlank()) flowOf(false) else repository.observeIsTopicBookmarked(slug)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun loadTopic(slugOrUrl: String, page: Int = 1, filter: TopicFilter? = null) {
        val resolvedFilter = filter ?: inferFilterFromUrl(slugOrUrl)
        val resolvedPage = if (page > 1) page else (inferPageFromUrl(slugOrUrl) ?: 1)
        appendJob?.cancel()
        _uiState.update {
            it.copy(
                requestedSlugOrUrl = slugOrUrl,
                canonicalSlug = null,
                singleEntryId = null,
                currentPage = resolvedPage.coerceAtLeast(1),
                filter = resolvedFilter,
                isAppendingNextPage = false,
                appendError = null,
                appendChallengeUrl = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
                result = UiResult.Loading
            )
        }
        fetchTopicInternal(isPullRefresh = false)
    }

    fun loadSingleEntry(entryId: Long) {
        appendJob?.cancel()
        _uiState.update {
            it.copy(
                requestedSlugOrUrl = "/entry/$entryId",
                canonicalSlug = null,
                singleEntryId = entryId,
                currentPage = 1,
                filter = TopicFilter.ALL,
                isAppendingNextPage = false,
                appendError = null,
                appendChallengeUrl = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
                result = UiResult.Loading
            )
        }
        viewModelScope.launch {
            val res = repository.getSingleEntry(entryId)
            _uiState.update { it.copy(isRefreshing = false, result = res) }
        }
    }

    fun selectFilter(filter: TopicFilter) {
        if (_uiState.value.filter == filter && _uiState.value.singleEntryId == null) return
        appendJob?.cancel()
        _uiState.update {
            it.copy(
                singleEntryId = null,
                currentPage = 1,
                filter = filter,
                isAppendingNextPage = false,
                appendError = null,
                appendChallengeUrl = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
                result = UiResult.Loading
            )
        }
        fetchTopicInternal(isPullRefresh = false)
    }

    fun goToPage(page: Int) {
        val current = _uiState.value
        val detail = (current.result as? UiResult.Success)?.data
            ?: (current.result as? UiResult.Error)?.cachedData
        val maxPage = detail?.totalPages ?: Int.MAX_VALUE
        val target = page.coerceIn(1, maxPage.coerceAtLeast(1))
        if (target == current.currentPage && current.singleEntryId == null) return
        appendJob?.cancel()
        _uiState.update {
            it.copy(
                currentPage = target,
                singleEntryId = null,
                isAppendingNextPage = false,
                appendError = null,
                appendChallengeUrl = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1
            )
        }
        fetchTopicInternal(isPullRefresh = false)
    }

    fun loadNextPage(forceRetry: Boolean = false) {
        val current = _uiState.value
        if (current.singleEntryId != null || current.isRefreshing || current.isAppendingNextPage) return
        if (!forceRetry && current.appendError != null) return
        val currentDetail = (current.result as? UiResult.Success)?.data ?: return
        val nextPage = currentDetail.currentPage + 1
        if (nextPage > currentDetail.totalPages) return
        val slug = currentCanonicalOrRequested()
        if (slug.isBlank()) return

        val expectedRequested = current.requestedSlugOrUrl
        val expectedFilter = current.filter
        val expectedBasePage = currentDetail.currentPage

        appendJob?.cancel()
        appendJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAppendingNextPage = true,
                    appendError = null,
                    appendChallengeUrl = null
                )
            }
            val res = repository.getTopic(
                slugOrUrl = slug,
                page = nextPage,
                filter = expectedFilter
            )
            _uiState.update { latest ->
                if (latest.requestedSlugOrUrl != expectedRequested ||
                    latest.filter != expectedFilter ||
                    latest.singleEntryId != null
                ) {
                    return@update latest
                }
                val latestSuccess = latest.result as? UiResult.Success ?: return@update latest
                val latestDetail = latestSuccess.data
                if (latestDetail.currentPage != expectedBasePage) {
                    return@update latest.copy(isAppendingNextPage = false)
                }
                when (res) {
                    is UiResult.Success -> {
                        val incoming = res.data
                        val mergedEntries = (latestDetail.entries + incoming.entries).distinctBy { it.id }
                        val resolvedPage = incoming.currentPage.coerceAtLeast(1)
                        val resolvedTotalPages = incoming.totalPages.coerceAtLeast(resolvedPage)
                        val updatedDetail = latestDetail.copy(
                            currentPage = resolvedPage,
                            totalPages = resolvedTotalPages,
                            entries = mergedEntries,
                            verificationToken = incoming.verificationToken ?: latestDetail.verificationToken,
                            entryFormAction = incoming.entryFormAction ?: latestDetail.entryFormAction,
                            entryFormFields = incoming.entryFormFields.ifEmpty { latestDetail.entryFormFields }
                        )
                        latest.copy(
                            currentPage = resolvedPage,
                            isAppendingNextPage = false,
                            appendError = null,
                            appendChallengeUrl = null,
                            result = UiResult.Success(
                                data = updatedDetail,
                                isFromCache = latestSuccess.isFromCache && res.isFromCache,
                                cachedAtMillis = res.cachedAtMillis ?: latestSuccess.cachedAtMillis
                            )
                        )
                    }
                    is UiResult.Error -> {
                        latest.copy(
                            isAppendingNextPage = false,
                            appendError = res.message,
                            appendChallengeUrl = res.challengeUrl
                        )
                    }
                    is UiResult.Loading -> latest.copy(isAppendingNextPage = false)
                }
            }
        }
    }

    fun refresh() {
        appendJob?.cancel()
        _uiState.update {
            it.copy(
                isAppendingNextPage = false,
                appendError = null,
                appendChallengeUrl = null
            )
        }
        val singleId = _uiState.value.singleEntryId
        if (singleId != null) {
            _uiState.update { it.copy(isRefreshing = true) }
            viewModelScope.launch {
                val res = repository.getSingleEntry(singleId)
                _uiState.update { it.copy(isRefreshing = false, result = res) }
            }
        } else {
            fetchTopicInternal(isPullRefresh = true)
        }
    }

    fun openFullTopicFromSingleEntry() {
        val detail = (_uiState.value.result as? UiResult.Success)?.data ?: return
        loadTopic(detail.canonicalUrl, page = 1, filter = TopicFilter.ALL)
    }

    fun onWriteEntryClicked() {
        val detail = (_uiState.value.result as? UiResult.Success)?.data
        if (detail?.entryFormAction != null) {
            _uiState.update { it.copy(isComposeOpen = true, submitEntryError = null) }
        } else {
            viewModelScope.launch {
                val detected = repository.refreshSessionUser()
                if (detected != null) {
                    _uiState.update { it.copy(isComposeOpen = true, submitEntryError = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            loginPromptMessage = "Bu başlığa entry girebilmek için Ekşi Sözlük hesabınızla oturum açmalısınız.",
                            pendingComposeAfterLogin = true
                        )
                    }
                }
            }
        }
    }

    fun dismissLoginPrompt() {
        _uiState.update {
            it.copy(
                loginPromptMessage = null,
                pendingComposeAfterLogin = false
            )
        }
    }

    fun closeComposeSheet() {
        if (_uiState.value.isSubmittingEntry) return
        _uiState.update { it.copy(isComposeOpen = false, submitEntryError = null) }
    }

    fun onWebAuthCompleted(
        detectedNick: String?,
        openComposeAfter: Boolean = false,
        retryAppendAfter: Boolean = false
    ) {
        viewModelScope.launch {
            val nick = repository.refreshSessionUser(detectedNick)
            if (retryAppendAfter) {
                loadNextPage(forceRetry = true)
            } else {
                refresh()
            }
            if (openComposeAfter && nick != null) {
                _uiState.update { it.copy(isComposeOpen = true, submitEntryError = null) }
            }
        }
    }

    fun submitEntry(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(submitEntryError = "Entry metni boş olamaz.") }
            return
        }
        val detail = (_uiState.value.result as? UiResult.Success)?.data
            ?: (_uiState.value.result as? UiResult.Error)?.cachedData
            ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingEntry = true, submitEntryError = null) }
            val result = repository.postEntry(
                topicTitle = detail.title,
                topicId = detail.topicId,
                canonicalUrl = detail.canonicalUrl,
                content = trimmed,
                formAction = detail.entryFormAction,
                formFields = detail.entryFormFields
            )
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isSubmittingEntry = false,
                        isComposeOpen = false,
                        submitEntryError = null,
                        actionMessage = "Entry'niz yayınlandı!"
                    )
                }
                val targetPage = if (detail.filter == TopicFilter.ALL) {
                    detail.totalPages.coerceAtLeast(1)
                } else {
                    val allFirstPage = repository.getTopic(
                        slugOrUrl = detail.canonicalUrl,
                        page = 1,
                        filter = TopicFilter.ALL
                    )
                    (allFirstPage as? UiResult.Success)?.data?.totalPages?.coerceAtLeast(1) ?: 1
                }
                loadTopic(
                    slugOrUrl = detail.canonicalUrl,
                    page = targetPage,
                    filter = TopicFilter.ALL
                )
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmittingEntry = false,
                        submitEntryError = err.message ?: "Entry gönderilemedi."
                    )
                }
            }
        }
    }

    fun toggleTopicBookmark() {
        val detail = (_uiState.value.result as? UiResult.Success)?.data
            ?: (_uiState.value.result as? UiResult.Error)?.cachedData
            ?: return
        val currently = isTopicBookmarked.value
        viewModelScope.launch {
            repository.toggleTopicBookmark(
                title = detail.title,
                slugUrl = detail.canonicalUrl,
                currentlyBookmarked = currently
            )
            _uiState.update {
                it.copy(
                    actionMessage = if (currently) "Başlık yer imlerinden kaldırıldı"
                    else "Başlık yer imlerine kaydedildi"
                )
            }
        }
    }

    fun toggleEntryBookmark(entry: EntryItem) {
        val detail = (_uiState.value.result as? UiResult.Success)?.data
            ?: (_uiState.value.result as? UiResult.Error)?.cachedData
            ?: return
        val currently = bookmarkedEntryIds.value.contains(entry.id)
        viewModelScope.launch {
            repository.toggleEntryBookmark(
                topicTitle = detail.title,
                topicUrl = detail.canonicalUrl,
                entry = entry,
                currentlyBookmarked = currently
            )
            _uiState.update {
                it.copy(
                    actionMessage = if (currently) "Entry yer imlerinden kaldırıldı"
                    else "Entry yer imlerine kaydedildi"
                )
            }
        }
    }

    fun toggleFavorite(entry: EntryItem) {
        val successDetail = (_uiState.value.result as? UiResult.Success)?.data
        val cachedDetail = (_uiState.value.result as? UiResult.Error)?.cachedData
        val detail = successDetail ?: cachedDetail ?: return
        if (preferences.value.loggedInNick.isNullOrBlank() &&
            detail.entryFormAction == null &&
            !repository.hasSessionCookies()
        ) {
            _uiState.update {
                it.copy(
                    loginPromptMessage = "Entry'leri favorileyebilmek için Ekşi Sözlük hesabınızla oturum açmalısınız.",
                    pendingComposeAfterLogin = false
                )
            }
            return
        }
        viewModelScope.launch {
            val res = repository.toggleFavorite(
                entryId = entry.id,
                currentlyFavorited = entry.isFavorited,
                verificationToken = detail.verificationToken,
                refererPath = detail.canonicalUrl
            )
            res.onSuccess { newCount ->
                val updatedEntries = detail.entries.map { item ->
                    if (item.id == entry.id) {
                        val updatedFav = !item.isFavorited
                        val resolvedCount = if (newCount >= 0) newCount else (item.favoriteCount + if (updatedFav) 1 else -1).coerceAtLeast(0)
                        item.copy(isFavorited = updatedFav, favoriteCount = resolvedCount)
                    } else item
                }
                val updatedDetail = detail.copy(entries = updatedEntries)
                _uiState.update { state ->
                    when (val currentRes = state.result) {
                        is UiResult.Success -> state.copy(result = UiResult.Success(updatedDetail))
                        is UiResult.Error -> state.copy(result = currentRes.copy(cachedData = updatedDetail))
                        else -> state
                    }
                }
            }.onFailure { err ->
                val cfUrl = (err as? CloudflareChallengeException)?.challengeUrl
                val errMsg = err.message.orEmpty()
                if (cfUrl == null && errMsg.contains("giriş", ignoreCase = true)) {
                    _uiState.update {
                        it.copy(
                            loginPromptMessage = errMsg.ifBlank {
                                "Favori işlemi için Ekşi Sözlük hesabınızla giriş yapmalısınız."
                            },
                            pendingComposeAfterLogin = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            actionMessage = if (cfUrl != null) {
                                "Favori işlemi için güvenlik doğrulaması gerekiyor."
                            } else {
                                errMsg.ifBlank { "Favori işlemi için giriş yapmalısınız." }
                            },
                            actionChallengeUrl = cfUrl
                        )
                    }
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(actionMessage = null, actionChallengeUrl = null) }
    }

    private fun currentCanonicalOrRequested(): String {
        val state = _uiState.value
        val detail = (state.result as? UiResult.Success)?.data
            ?: (state.result as? UiResult.Error)?.cachedData
        val fromDetail = detail?.canonicalUrl?.trim()
            ?.takeIf { it.isNotBlank() && it != "/" && !it.equals("/entry", ignoreCase = true) }
        val fromState = state.canonicalSlug?.trim()
            ?.takeIf { it.isNotBlank() && it != "/" && !it.equals("/entry", ignoreCase = true) }
        return fromDetail ?: fromState ?: state.requestedSlugOrUrl
    }

    private fun inferFilterFromUrl(slugOrUrl: String): TopicFilter {
        val query = slugOrUrl.substringAfter("?", "")
        if (query.isBlank()) return TopicFilter.ALL
        val aValue = query.split("&")
            .firstOrNull { it.startsWith("a=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.substringBefore("#")
            ?.lowercase()
        return when (aValue) {
            "popular" -> TopicFilter.POPULAR
            "nice" -> TopicFilter.SUKELA
            "dailynice" -> TopicFilter.DAILY_SUKELA
            else -> TopicFilter.ALL
        }
    }

    private fun inferPageFromUrl(slugOrUrl: String): Int? {
        val query = slugOrUrl.substringAfter("?", "")
        if (query.isBlank()) return null
        return query.split("&")
            .firstOrNull { it.startsWith("p=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.substringBefore("#")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun fetchTopicInternal(isPullRefresh: Boolean) {
        appendJob?.cancel()
        viewModelScope.launch {
            val current = _uiState.value
            val slugToFetch = currentCanonicalOrRequested()
            if (slugToFetch.isBlank()) return@launch

            val expectedRequested = current.requestedSlugOrUrl
            val expectedPage = current.currentPage
            val expectedFilter = current.filter

            _uiState.update {
                if (isPullRefresh) it.copy(isRefreshing = true)
                else it.copy(result = UiResult.Loading)
            }

            val res = repository.getTopic(
                slugOrUrl = slugToFetch,
                page = expectedPage,
                filter = expectedFilter
            )

            _uiState.update { state ->
                if (state.requestedSlugOrUrl != expectedRequested ||
                    state.filter != expectedFilter ||
                    state.currentPage != expectedPage ||
                    state.singleEntryId != null
                ) {
                    return@update state
                }
                val successDetail = (res as? UiResult.Success)?.data
                val resolvedPage = successDetail?.currentPage ?: state.currentPage
                val resolvedCanonical = successDetail?.canonicalUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "/" && !it.equals("/entry", ignoreCase = true) }
                    ?: state.canonicalSlug
                state.copy(
                    isRefreshing = false,
                    currentPage = resolvedPage,
                    canonicalSlug = resolvedCanonical,
                    result = res
                )
            }
        }
    }
}
