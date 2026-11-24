package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eksi_okur_prefs")

enum class ThemeMode(val label: String) {
    SYSTEM("Sistem"),
    LIGHT("Kağıt (Açık)"),
    DARK("Mürekkep (Gece)")
}

enum class FontScaleOption(val label: String, val scale: Float) {
    SMALL("Küçük", 0.88f),
    NORMAL("Normal", 1.0f),
    LARGE("Büyük", 1.15f),
    XLARGE("Çok Büyük", 1.3f)
}

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
    val expandSpoilersByDefault: Boolean = false,
    val useDynamicColor: Boolean = false,
    val loggedInNick: String? = null
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val EXPAND_SPOILERS = booleanPreferencesKey("expand_spoilers")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LOGGED_IN_NICK = stringPreferencesKey("logged_in_nick")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        val mode = prefs[Keys.THEME_MODE]?.let { name ->
            ThemeMode.entries.firstOrNull { it.name == name }
        } ?: ThemeMode.SYSTEM

        UserPreferences(
            themeMode = mode,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            expandSpoilersByDefault = prefs[Keys.EXPAND_SPOILERS] ?: false,
            useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            loggedInNick = prefs[Keys.LOGGED_IN_NICK]?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun setExpandSpoilersByDefault(expand: Boolean) {
        context.dataStore.edit { it[Keys.EXPAND_SPOILERS] = expand }
    }

    suspend fun setUseDynamicColor(useDynamic: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = useDynamic }
    }

    suspend fun setLoggedInNick(nick: String?) {
        context.dataStore.edit { prefs ->
            if (nick.isNullOrBlank()) {
                prefs.remove(Keys.LOGGED_IN_NICK)
            } else {
                prefs[Keys.LOGGED_IN_NICK] = nick
            }
        }
    }
}
