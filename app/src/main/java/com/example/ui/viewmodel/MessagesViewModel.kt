package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserPreferences
import com.example.data.model.MessageInboxData
import com.example.data.model.MessageThreadDetail
import com.example.data.model.MessageThreadSummary
import com.example.data.model.UiResult
import com.example.data.network.CloudflareChallengeException
import com.example.data.network.EksiHtmlParser
import com.example.data.network.WebDeleteFallbackException
import com.example.data.repository.EksiRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebDeleteThreadRequest(
    val thread: MessageThreadSummary,
    val targetUrl: String,
    val threadId: String,
    val threadPath: String
)

data class MessagesUiState(
    val showArchived: Boolean = false,
    val isRefreshingInbox: Boolean = false,
    val inboxResult: UiResult<MessageInboxData> = UiResult.Loading,
    val isComposeOpen: Boolean = false,
    val composeRecipient: String = "",
    val recipientSuggestions: List<String> = emptyList(),
    val isSendingNewMessage: Boolean = false,
    val sendNewMessageError: String? = null,
    val activeThreadUrl: String? = null,
    val isRefreshingThread: Boolean = false,
    val threadResult: UiResult<MessageThreadDetail> = UiResult.Loading,
    val isSendingReply: Boolean = false,
    val replyError: String? = null,
    val replyChallengeUrl: String? = null,
    val deletingThreadIds: Set<String> = emptySet(),
    val deleteChallengeUrl: String? = null,
    val pendingWebDelete: WebDeleteThreadRequest? = null,
    val actionMessage: String? = null
)

class MessagesViewModel(
    private val repository: EksiRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()

    val preferences: StateFlow<UserPreferences> = repository.userPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    private var nickAutocompleteJob: Job? = null

    init {
        loadInbox()
    }

    fun loadInbox(isPullRefresh: Boolean = false, excludeThreadId: String? = null, excludeThreadUrl: String? = null) {
        viewModelScope.launch {
            val archived = _uiState.value.showArchived
            _uiState.update {
                if (isPullRefresh) it.copy(isRefreshingInbox = true)
                else it.copy(inboxResult = UiResult.Loading)
            }
            val res = repository.getMessageInbox(archived = archived)
            // Silinen mesajın sunucu yanıtıyla geri gelmesini engelle (race condition).
            val filteredRes = when (res) {
                is UiResult.Success -> {
                    val excludedThreads = if (excludeThreadId != null || excludeThreadUrl != null) {
                        res.data.threads.filterNot { t ->
                            (excludeThreadId != null && t.threadId == excludeThreadId) ||
                                (excludeThreadUrl != null && t.threadUrl == excludeThreadUrl)
                        }
                    } else {
                        res.data.threads
                    }
                    UiResult.Success(
                        data = res.data.copy(threads = excludedThreads),
                        isFromCache = res.isFromCache,
                        cachedAtMillis = res.cachedAtMillis
                    )
                }
                else -> res
            }
            _uiState.update {
                it.copy(
                    isRefreshingInbox = false,
                    inboxResult = filteredRes
                )
            }
        }
    }

    fun setArchivedFilter(archived: Boolean) {
        if (_uiState.value.showArchived == archived) return
        _uiState.update { it.copy(showArchived = archived) }
        loadInbox()
    }

    fun openCompose(prefillNick: String? = null) {
        _uiState.update {
            it.copy(
                isComposeOpen = true,
                composeRecipient = prefillNick?.trim()?.removePrefix("@").orEmpty(),
                recipientSuggestions = emptyList(),
                sendNewMessageError = null
            )
        }
    }

    fun closeCompose() {
        if (_uiState.value.isSendingNewMessage) return
        _uiState.update {
            it.copy(
                isComposeOpen = false,
                sendNewMessageError = null,
                recipientSuggestions = emptyList()
            )
        }
    }

    fun onRecipientChanged(value: String) {
        _uiState.update { it.copy(composeRecipient = value, sendNewMessageError = null) }
        nickAutocompleteJob?.cancel()
        val clean = value.trim().removePrefix("@")
        if (clean.length < 2) {
            _uiState.update { it.copy(recipientSuggestions = emptyList()) }
            return
        }
        nickAutocompleteJob = viewModelScope.launch {
            delay(250)
            val ac = repository.getAutocomplete("@$clean")
            val nicks = if (ac.nicks.isNotEmpty()) ac.nicks else repository.getAutocomplete(clean).nicks
            _uiState.update { it.copy(recipientSuggestions = nicks.take(6)) }
        }
    }

    fun selectRecipientSuggestion(nick: String) {
        nickAutocompleteJob?.cancel()
        _uiState.update {
            it.copy(
                composeRecipient = nick.removePrefix("@"),
                recipientSuggestions = emptyList()
            )
        }
    }

    fun sendNewMessage(body: String, onThreadRedirect: ((String) -> Unit)? = null) {
        val to = _uiState.value.composeRecipient.trim().removePrefix("@")
        val trimmedBody = body.trim()
        if (to.isBlank()) {
            _uiState.update { it.copy(sendNewMessageError = "Alıcı yazar adını girmelisiniz.") }
            return
        }
        if (trimmedBody.isBlank()) {
            _uiState.update { it.copy(sendNewMessageError = "Mesaj metni boş olamaz.") }
            return
        }

        val formMeta = (_uiState.value.inboxResult as? UiResult.Success)?.data?.composeForm
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingNewMessage = true, sendNewMessageError = null) }
            val result = repository.sendMessage(
                toNick = to,
                body = trimmedBody,
                formMeta = formMeta,
                refererPath = "/mesaj/yeni"
            )
            result.onSuccess { outcome ->
                _uiState.update {
                    it.copy(
                        isSendingNewMessage = false,
                        isComposeOpen = false,
                        composeRecipient = "",
                        recipientSuggestions = emptyList(),
                        sendNewMessageError = null,
                        actionMessage = "@$to yazarına mesaj gönderildi"
                    )
                }
                loadInbox()
                if (isSpecificThreadPath(outcome.redirectPath)) {
                    onThreadRedirect?.invoke(outcome.redirectPath)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSendingNewMessage = false,
                        sendNewMessageError = err.message ?: "Mesaj gönderilemedi."
                    )
                }
            }
        }
    }

    fun loadThread(threadUrl: String, isPullRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                if (isPullRefresh && it.activeThreadUrl == threadUrl) {
                    it.copy(isRefreshingThread = true)
                } else {
                    it.copy(
                        activeThreadUrl = threadUrl,
                        isRefreshingThread = false,
                        threadResult = UiResult.Loading,
                        replyError = null,
                        replyChallengeUrl = null
                    )
                }
            }
            val currentNick = preferences.value.loggedInNick
            val res = repository.getMessageThread(threadUrl, currentNick)
            _uiState.update {
                it.copy(
                    isRefreshingThread = false,
                    threadResult = res
                )
            }
        }
    }

    fun sendReply(body: String) {
        val threadUrl = _uiState.value.activeThreadUrl ?: return
        val detail = (_uiState.value.threadResult as? UiResult.Success)?.data
        val trimmedBody = body.trim()
        if (trimmedBody.isEmpty()) return

        val participantNick = detail?.participantNick?.takeIf {
            it.isNotBlank() && !it.equals("Sohbet", ignoreCase = true)
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSendingReply = true,
                    replyError = null,
                    replyChallengeUrl = null
                )
            }
            val res = repository.sendMessage(
                toNick = participantNick,
                body = trimmedBody,
                formMeta = detail?.replyForm,
                refererPath = threadUrl
            )
            res.onSuccess { outcome ->
                val targetUrl = if (isSpecificThreadPath(outcome.redirectPath)) {
                    outcome.redirectPath
                } else {
                    threadUrl
                }
                val currentNick = preferences.value.loggedInNick
                val parsedImmediate = if (outcome.responseHtml.isNotBlank()) {
                    runCatching {
                        EksiHtmlParser.parseMessageThread(
                            html = outcome.responseHtml,
                            requestedUrl = targetUrl,
                            currentUserNick = currentNick
                        )
                    }.getOrNull()
                } else null

                if (parsedImmediate?.requiresLogin == true) {
                    _uiState.update {
                        it.copy(
                            isSendingReply = false,
                            replyError = "Oturumunuz sona ermiş görünüyor. Lütfen tekrar giriş yapın."
                        )
                    }
                    return@onSuccess
                }

                if (parsedImmediate != null && parsedImmediate.messages.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            activeThreadUrl = targetUrl,
                            isSendingReply = false,
                            replyError = null,
                            replyChallengeUrl = null,
                            threadResult = UiResult.Success(
                                parsedImmediate.copy(
                                    replyForm = parsedImmediate.replyForm ?: detail?.replyForm
                                )
                            )
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            activeThreadUrl = targetUrl,
                            isSendingReply = false,
                            replyError = null,
                            replyChallengeUrl = null
                        )
                    }
                }
                loadThread(targetUrl, isPullRefresh = true)
                loadInbox(isPullRefresh = true)
            }.onFailure { err ->
                val cfUrl = (err as? CloudflareChallengeException)?.challengeUrl
                _uiState.update {
                    it.copy(
                        isSendingReply = false,
                        replyError = if (cfUrl != null) {
                            "Yanıt göndermek için güvenlik doğrulaması gerekiyor."
                        } else {
                            err.message ?: "Yanıt gönderilemedi."
                        },
                        replyChallengeUrl = cfUrl
                    )
                }
            }
        }
    }

    fun onWebLoginCompleted(detectedNick: String?) {
        viewModelScope.launch {
            repository.refreshSessionUser(detectedNick)
            loadInbox()
            _uiState.value.activeThreadUrl?.let { loadThread(it) }
        }
    }

    fun deleteThread(
        thread: MessageThreadSummary,
        onDeleted: (() -> Unit)? = null
    ) {
        if (thread.threadId in _uiState.value.deletingThreadIds) return

        val inboxSuccess = (_uiState.value.inboxResult as? UiResult.Success)?.data
        val fallbackToken = inboxSuccess?.verificationToken
            ?: inboxSuccess?.composeForm?.fields?.get("__RequestVerificationToken")

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    deletingThreadIds = it.deletingThreadIds + thread.threadId,
                    deleteChallengeUrl = null
                )
            }
            val result = repository.deleteMessageThread(
                threadUrl = thread.threadUrl,
                threadId = thread.threadId,
                deleteMeta = thread.deleteMeta,
                fallbackToken = fallbackToken
            )
            result.onSuccess {
                // Sunucuda gerçekten silinip silinmediğini doğrula; sadece optimist silmeyle
                // "silindi" dememek için gerçek silme doğrulaması gerekiyor.
                val normalizedPath = EksiHtmlParser.normalizePath(thread.threadUrl)
                val isReallyDeleted = repository.verifyMessageThreadDeleted(normalizedPath, thread.threadId)

                if (isReallyDeleted) {
                    _uiState.update { current ->
                        val currentInbox = current.inboxResult as? UiResult.Success
                        val updatedInbox = if (currentInbox != null) {
                            UiResult.Success(
                                data = currentInbox.data.copy(
                                    threads = currentInbox.data.threads.filterNot {
                                        it.threadId == thread.threadId && it.threadUrl == thread.threadUrl
                                    }
                                ),
                                isFromCache = currentInbox.isFromCache,
                                cachedAtMillis = currentInbox.cachedAtMillis
                            )
                        } else {
                            current.inboxResult
                        }
                        val clearedActive = if (current.activeThreadUrl == thread.threadUrl) null else current.activeThreadUrl
                        current.copy(
                            deletingThreadIds = current.deletingThreadIds - thread.threadId,
                            inboxResult = updatedInbox,
                            activeThreadUrl = clearedActive,
                            actionMessage = "@${thread.participantNick} ile olan mesaj silindi"
                        )
                    }
                    onDeleted?.invoke()
                    delay(1000)
                    loadInbox(isPullRefresh = true, excludeThreadId = thread.threadId, excludeThreadUrl = thread.threadUrl)
                } else {
                    _uiState.update { current ->
                        current.copy(
                            deletingThreadIds = current.deletingThreadIds - thread.threadId,
                            actionMessage = "Mesaj silinemedi. Lütfen tekrar deneyiniz."
                        )
                    }
                }
            }.onFailure { err ->
                when (err) {
                    is WebDeleteFallbackException -> {
                        _uiState.update { current ->
                            current.copy(
                                deletingThreadIds = current.deletingThreadIds - thread.threadId,
                                pendingWebDelete = WebDeleteThreadRequest(
                                    thread = thread,
                                    targetUrl = err.targetUrl,
                                    threadId = err.threadId,
                                    threadPath = err.threadPath
                                )
                            )
                        }
                    }
                    is CloudflareChallengeException -> {
                        val normalized = EksiHtmlParser.normalizePath(thread.threadUrl)
                        val fullUrl = if (normalized.startsWith("http")) normalized else "https://eksisozluk.com$normalized"
                        _uiState.update { current ->
                            current.copy(
                                deletingThreadIds = current.deletingThreadIds - thread.threadId,
                                pendingWebDelete = WebDeleteThreadRequest(
                                    thread = thread,
                                    targetUrl = fullUrl,
                                    threadId = thread.threadId,
                                    threadPath = normalized
                                )
                            )
                        }
                    }
                    else -> {
                        _uiState.update { current ->
                            current.copy(
                                deletingThreadIds = current.deletingThreadIds - thread.threadId,
                                actionMessage = err.message ?: "Mesaj silinemedi."
                            )
                        }
                    }
                }
            }
        }
    }

    fun dismissWebThreadDelete() {
        _uiState.update { it.copy(pendingWebDelete = null) }
    }

    fun onWebThreadDeleteCompleted(
        request: WebDeleteThreadRequest,
        onDeleted: (() -> Unit)? = null
    ) {
        _uiState.update { current ->
            current.copy(
                pendingWebDelete = null,
                deletingThreadIds = current.deletingThreadIds + request.thread.threadId
            )
        }
        viewModelScope.launch {
            val isDeleted = repository.verifyMessageThreadDeleted(
                threadUrl = request.threadPath,
                threadId = request.thread.threadId
            ) || (
                request.threadId.isNotBlank() &&
                    request.threadId != request.thread.threadId &&
                    repository.verifyMessageThreadDeleted(
                        threadUrl = request.threadPath,
                        threadId = request.threadId
                    )
                )

            if (isDeleted) {
                _uiState.update { current ->
                    val currentInbox = current.inboxResult as? UiResult.Success
                    val updatedInbox = if (currentInbox != null) {
                        UiResult.Success(
                            data = currentInbox.data.copy(
                                threads = currentInbox.data.threads.filterNot {
                                    it.threadId == request.thread.threadId && it.threadUrl == request.thread.threadUrl
                                }
                            ),
                            isFromCache = currentInbox.isFromCache,
                            cachedAtMillis = currentInbox.cachedAtMillis
                        )
                    } else {
                        current.inboxResult
                    }
                    val clearedActive = if (current.activeThreadUrl == request.thread.threadUrl) null else current.activeThreadUrl
                    current.copy(
                        deletingThreadIds = current.deletingThreadIds - request.thread.threadId,
                        inboxResult = updatedInbox,
                        activeThreadUrl = clearedActive,
                        actionMessage = "@${request.thread.participantNick} ile olan mesaj silindi"
                    )
                }
                onDeleted?.invoke()
                delay(1000)
                loadInbox(isPullRefresh = true, excludeThreadId = request.thread.threadId, excludeThreadUrl = request.thread.threadUrl)
            } else {
                _uiState.update { current ->
                    current.copy(
                        deletingThreadIds = current.deletingThreadIds - request.thread.threadId,
                        actionMessage = "Mesaj silinemedi. Sayfa içinden Sil'e basıp Tamamlandı ile onaylayabilirsiniz."
                    )
                }
                delay(1000)
                loadInbox(isPullRefresh = true, excludeThreadId = request.thread.threadId, excludeThreadUrl = request.thread.threadUrl)
            }
        }
    }

    fun clearDeleteChallenge() {
        _uiState.update { it.copy(deleteChallengeUrl = null) }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    private fun isSpecificThreadPath(path: String): Boolean {
        val clean = path.lowercase().substringBefore("?")
        return clean.startsWith("/mesaj/") &&
            clean != "/mesaj/" &&
            clean != "/mesaj/yeni" &&
            clean != "/mesaj/arsiv" &&
            clean != "/mesaj/sil" &&
            clean != "/mesaj/ara" &&
            clean != "/mesaj/send"
    }
}
