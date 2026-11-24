package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.BookmarkEntity
import com.example.data.local.FontScaleOption
import com.example.data.local.ReadingHistoryEntity
import com.example.data.local.ThemeMode
import com.example.data.local.UserPreferences
import com.example.data.repository.EksiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: EksiRepository
) : ViewModel() {
    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val readingHistory: StateFlow<List<ReadingHistoryEntity>> = repository.readingHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeBookmark(id: String) {
        viewModelScope.launch { repository.removeBookmark(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearReadingHistory() }
    }
}

class SettingsViewModel(
    private val repository: EksiRepository
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = repository.userPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.preferences.setThemeMode(mode) }
    }

    fun setFontScale(option: FontScaleOption) {
        viewModelScope.launch { repository.preferences.setFontScale(option.scale) }
    }

    fun setExpandSpoilers(expand: Boolean) {
        viewModelScope.launch { repository.preferences.setExpandSpoilersByDefault(expand) }
    }

    fun setUseDynamicColor(useDynamic: Boolean) {
        viewModelScope.launch { repository.preferences.setUseDynamicColor(useDynamic) }
    }

    fun refreshSession(fallbackNick: String? = null) {
        viewModelScope.launch {
            val nick = repository.refreshSessionUser(fallbackNick)
            _statusMessage.value = if (nick != null) {
                "Hoş geldiniz, @$nick"
            } else if (repository.hasSessionCookies()) {
                "Tarayıcı çerezleri senkronize edildi, ancak aktif hesap doğrulanamadı. Lütfen girişi kontrol edin."
            } else {
                "Aktif oturum bulunamadı"
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut()
            _statusMessage.value = "Oturum kapatıldı"
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearOfflineCache()
            _statusMessage.value = "Önbellek temizlendi"
        }
    }

    fun clearMessage() {
        _statusMessage.value = null
    }
}
