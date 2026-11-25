package com.example

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.EksiDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.network.EksiNetworkDataSource
import com.example.data.network.WebViewCookieJar
import com.example.data.network.WebViewHtmlFetcher
import com.example.data.repository.EksiRepository
import com.example.notifications.MessageNotificationHelper
import com.example.notifications.MessageNotificationScheduler
import com.example.ui.viewmodel.AuthorViewModel
import com.example.ui.viewmodel.FeedViewModel
import com.example.ui.viewmodel.LibraryViewModel
import com.example.ui.viewmodel.MessagesViewModel
import com.example.ui.viewmodel.SearchViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.TopicViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EksiAppContainer(context: Context) {
    private val appContext = context.applicationContext
    val cookieJar = WebViewCookieJar()
    private val webViewFetcher = WebViewHtmlFetcher(appContext)
    private val networkDataSource = EksiNetworkDataSource(cookieJar, webViewFetcher)
    private val database = EksiDatabase.getInstance(appContext)
    private val preferencesRepository = UserPreferencesRepository(appContext)
    val repository = EksiRepository(
        network = networkDataSource,
        dao = database.eksiDao(),
        preferences = preferencesRepository
    )
}

class EksiApplication : Application() {
    lateinit var container: EksiAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // CookieManager'ı erken başlat ki WebViewCookieJar çerezleri
        // uygulama yeniden başlatıldığında da süresin (oturum kalıcılığı).
        android.webkit.CookieManager.getInstance()
        container = EksiAppContainer(this)
        MessageNotificationHelper.ensureChannel(this)
        appScope.launch {
            // Çerezlerden oturumu geri yükle (giriş yapılmışsa nick'i doldurur).
            container.repository.refreshSessionUser()
            val prefs = runCatching { container.repository.userPreferences.first() }.getOrNull()
            if (prefs?.messageNotificationsEnabled != false) {
                MessageNotificationScheduler.syncPeriodicWork(this@EksiApplication, true)
            }
        }
    }
}

class EksiViewModelFactory(
    private val repository: EksiRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(FeedViewModel::class.java) -> FeedViewModel(repository) as T
            modelClass.isAssignableFrom(TopicViewModel::class.java) -> TopicViewModel(repository) as T
            modelClass.isAssignableFrom(SearchViewModel::class.java) -> SearchViewModel(repository) as T
            modelClass.isAssignableFrom(AuthorViewModel::class.java) -> AuthorViewModel(repository) as T
            modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel(repository) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(repository) as T
            modelClass.isAssignableFrom(MessagesViewModel::class.java) -> MessagesViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
