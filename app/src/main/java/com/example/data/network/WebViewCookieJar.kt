package com.example.data.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class WebViewCookieJar : CookieJar {

    private val cookieManager: CookieManager? by lazy {
        runCatching { CookieManager.getInstance() }.getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = cookieManager ?: return
        val urlString = url.toString()
        cookies.forEach { cookie ->
            runCatching {
                manager.setCookie(urlString, cookie.toString())
            }
        }
        runCatching { manager.flush() }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = cookieManager ?: return emptyList()
        val urlsToCheck = listOf(
            url.toString(),
            "https://eksisozluk.com",
            "https://www.eksisozluk.com",
            "https://m.eksisozluk.com"
        ).distinct()

        val parsed = LinkedHashMap<String, Cookie>()
        for (target in urlsToCheck) {
            val cookieHeader = runCatching { manager.getCookie(target) }.getOrNull() ?: continue
            if (cookieHeader.isBlank()) continue
            cookieHeader.split(";").forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.isNotEmpty()) {
                    Cookie.parse(url, trimmed)?.let { cookie ->
                        parsed[cookie.name] = cookie
                    }
                }
            }
        }
        return parsed.values.toList()
    }

    fun clearAllCookies() {
        val manager = cookieManager ?: return
        runCatching {
            manager.removeAllCookies(null)
            manager.flush()
        }
    }

    fun hasAnyCookies(): Boolean {
        val manager = cookieManager ?: return false
        val urls = listOf(
            "https://eksisozluk.com",
            "https://www.eksisozluk.com",
            "https://m.eksisozluk.com"
        )
        return urls.any { u ->
            !runCatching { manager.getCookie(u) }.getOrNull().isNullOrBlank()
        }
    }
}
