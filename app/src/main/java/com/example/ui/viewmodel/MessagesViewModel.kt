package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserPreferences
import com.example.data.model.MessageInboxData
import com.example.data.model.MessageThreadDetail
import com.example.data.model.UiResult
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

    fun loadInbox(isPullRefresh: Boolean = false) {
        viewModelScope.launch {
            val archived = _uiState.value.showArchived
            _uiState.update {
                if (isPullRefresh) it.copy(isRefreshingInbox = true)
                else it.copy(inboxResult = UiResult.Loading)
            }
            val res = repository.getMessageInbox(archived = archived)
            _uiState.update {
                it.copy(
                    isRefreshingInbox = false,
                    inboxResult = res
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
            result.onSuccess { redirectPath ->
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
                if (redirectPath.startsWith("/mesaj/") &&
                    redirectPath != "/mesaj/yeni" &&
                    redirectPath != "/mesaj/arsiv"
                ) {
                    onThreadRedirect?.invoke(redirectPath)
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
                        replyError = null
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

        viewModelScope.launch {
            _uiState.update { it.copy(isSendingReply = true, replyError = null) }
            val res = repository.sendMessage(
                toNick = detail?.participantNick,
                body = trimmedBody,
                formMeta = detail?.replyForm,
                refererPath = threadUrl
            )
            res.onSuccess {
                _uiState.update { it.copy(isSendingReply = false, replyError = null) }
                loadThread(threadUrl, isPullRefresh = true)
                loadInbox(isPullRefresh = true)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSendingReply = false,
                        replyError = err.message ?: "Yanıt gönderilemedi."
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

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }
}
