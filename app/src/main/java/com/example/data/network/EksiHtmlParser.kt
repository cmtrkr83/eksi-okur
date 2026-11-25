package com.example.data.network

import com.example.data.model.AuthorProfile
import com.example.data.model.AuthorTab
import com.example.data.model.ChannelTag
import com.example.data.model.DirectMessageItem
import com.example.data.model.EntryItem
import com.example.data.model.EntrySegment
import com.example.data.model.MessageDeleteMeta
import com.example.data.model.MessageFormMeta
import com.example.data.model.MessageInboxData
import com.example.data.model.MessageThreadDetail
import com.example.data.model.MessageThreadSummary
import com.example.data.model.TopicDetail
import com.example.data.model.TopicFilter
import com.example.data.model.TopicItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URLDecoder

object EksiHtmlParser {

    fun parseTopicList(html: String, isDebe: Boolean = false): List<TopicItem> {
        val doc = Jsoup.parse(html, "https://eksisozluk.com")
        val items = mutableListOf<TopicItem>()
        val links = doc.select("ul.topic-list > li:not(.ad) > a, ul.topic-list li > a")
        val seen = HashSet<String>()

        for (anchor in links) {
            val href = anchor.attr("href").trim()
            if (href.isBlank() || href.startsWith("http") && !href.contains("eksisozluk.com")) continue
            if (href.contains("/reklam") || anchor.parent()?.hasClass("ad") == true) continue

            val smallText = anchor.selectFirst("small")?.text()?.trim().orEmpty()
            val cloned = anchor.clone()
            cloned.select("small").remove()
            val title = cloned.text().trim()
            if (title.isBlank()) continue

            val normalizedHref = normalizePath(href)
            if (!seen.add("$normalizedHref|$title")) continue

            val countInt = parseTurkishEntryCount(smallText)
            val entryId = if (isDebe && normalizedHref.startsWith("/entry/")) {
                normalizedHref.removePrefix("/entry/").substringBefore("?").toLongOrNull()
            } else {
                null
            }

            items.add(
                TopicItem(
                    title = title,
                    slugUrl = normalizedHref,
                    entryCountText = smallText,
                    entryCount = countInt,
                    isDebe = isDebe,
                    entryId = entryId
                )
            )
        }
        return items
    }

    fun parseTopicDetail(
        html: String,
        requestedUrl: String,
        filter: TopicFilter
    ): TopicDetail {
        val doc = Jsoup.parse(html, "https://eksisozluk.com")

        val titleEl = doc.selectFirst("h1#title")
        val titleSpan = titleEl?.selectFirst("span[itemprop=name]")
        val title = titleSpan?.text()?.trim()
            ?: titleEl?.attr("data-title")?.takeIf { it.isNotBlank() }
            ?: titleEl?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" - ekşi sözlük")?.trim()
            ?: "Başlık"

        val topicId = titleEl?.attr("data-id")?.toLongOrNull()
        val canonicalHref = titleEl?.selectFirst("a")?.attr("href")
            ?: doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?: requestedUrl
        val canonicalUrl = normalizePath(canonicalHref).substringBefore("?")

        val (currentPage, totalPages) = extractPagerInfo(doc, requestedUrl)

        val channels = doc.select("section#topic-channels ul li a").mapNotNull { ch ->
            val name = ch.text().trim()
            val slug = ch.attr("href").trim()
                .removePrefix("/basliklar/kanal/")
                .substringBefore("?")
                .trim('/')
            if (name.isNotBlank() && slug.isNotBlank()) ChannelTag(name, slug) else null
        }

        val entryElements = doc.select("ul#entry-item-list > li, li[data-id][data-author]")
        val entries = entryElements.mapNotNull { parseEntryElement(it) }.distinctBy { it.id }

        val isUkde = entries.isEmpty() && (
            doc.selectFirst("div#topic-research-menu") != null ||
                doc.text().contains("böyle bir şey yok", ignoreCase = true) ||
                doc.text().contains("ukde", ignoreCase = true)
            )

        val entryForm = doc.selectFirst("form[action*=/entry/ekle], form#entry-add-form")
            ?: doc.select("form").firstOrNull { it.selectFirst("textarea#editbox, textarea[name=Content]") != null }
        val entryFormAction = entryForm?.attr("action")?.takeIf { it.isNotBlank() }
        val entryFormFields = buildMap<String, String> {
            entryForm?.select("input[name]")?.forEach { input ->
                val name = input.attr("name").trim()
                if (name.isNotBlank()) {
                    put(name, input.attr("value"))
                }
            }
        }

        val token = entryFormFields["__RequestVerificationToken"]?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("input[name=__RequestVerificationToken]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=csrf-token], meta[name=__RequestVerificationToken]")?.attr("content")?.takeIf { it.isNotBlank() }

        return TopicDetail(
            title = title,
            topicId = topicId,
            canonicalUrl = canonicalUrl,
            currentPage = currentPage.coerceAtLeast(1),
            totalPages = totalPages.coerceAtLeast(1),
            filter = filter,
            entries = entries,
            channels = channels,
            isUkde = isUkde,
            verificationToken = token,
            entryFormAction = entryFormAction,
            entryFormFields = entryFormFields
        )
    }

    fun parseSingleEntryPage(html: String): TopicDetail {
        return parseTopicDetail(html, "/entry", TopicFilter.ALL)
    }

    fun parseAuthorProfile(html: String, slug: String, activeTab: AuthorTab): AuthorProfile {
        val doc = Jsoup.parse(html, "https://eksisozluk.com")
        val nick = doc.selectFirst("h1#user-profile-title a")?.text()?.trim()
            ?: doc.selectFirst("h1#user-profile-title")?.attr("data-nick")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1#user-profile-title")?.text()?.trim()
            ?: doc.selectFirst("#user-profile-title")?.text()?.trim()
            ?: slug

        val avatarUrl = extractAvatarUrl(doc)
        val bio = doc.selectFirst("div#user-profile-bio, #profile-bio, .profile-bio")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }

        val stats = mutableListOf<Pair<String, String>>()
        doc.select("ul#user-entry-stats li, ul.user-stats li, #profile-stats-section li").forEach { li ->
            val count = li.selectFirst("span, strong")?.text()?.trim()
            val label = li.ownText().trim().ifBlank { li.text().trim() }
            if (!count.isNullOrBlank() && label.isNotBlank()) {
                val cleanLabel = label.removePrefix(count).removeSuffix(count).trim().ifBlank { label }
                stats.add(cleanLabel to count)
            }
        }
        if (stats.isEmpty()) {
            val totalCount = doc.selectFirst("#entry-count-total")?.text()?.trim()
            val lastMonthCount = doc.selectFirst("#entry-count-lastmonth")?.text()?.trim()
            val lastWeekCount = doc.selectFirst("#entry-count-lastweek")?.text()?.trim()
            val todayCount = doc.selectFirst("#entry-count-today")?.text()?.trim()
            if (!totalCount.isNullOrBlank()) stats.add("toplam" to totalCount)
            if (!lastMonthCount.isNullOrBlank()) stats.add("son 1 ay" to lastMonthCount)
            if (!lastWeekCount.isNullOrBlank()) stats.add("son 1 hafta" to lastWeekCount)
            if (!todayCount.isNullOrBlank()) stats.add("bugün" to todayCount)
        }

        val tabUrls = extractAuthorTabUrls(doc)
        val (entries, currentPage, totalPages) = parseAuthorEntriesAndPager(doc)

        return AuthorProfile(
            nick = nick,
            slug = slug,
            avatarUrl = avatarUrl,
            bio = bio,
            stats = stats.distinctBy { it.first },
            entries = entries,
            activeTab = activeTab,
            currentPage = currentPage,
            totalPages = totalPages,
            tabUrls = tabUrls
        )
    }

    fun parseAuthorTabEntries(html: String): Triple<List<EntryItem>, Int, Int> {
        val doc = Jsoup.parse(html, "https://eksisozluk.com")
        return parseAuthorEntriesAndPager(doc)
    }

    private fun extractAvatarUrl(doc: org.jsoup.nodes.Document): String? {
        val candidates = listOfNotNull(
            doc.selectFirst("img.avatar"),
            doc.selectFirst("#user-profile-avatar img"),
            doc.selectFirst(".avatar-container img"),
            doc.selectFirst("div.profile-logo img"),
            doc.selectFirst(".profile-avatar img"),
            doc.selectFirst("div[class*=avatar] img"),
            doc.selectFirst("img[class*=avatar]"),
            doc.selectFirst("img[src*=/avatar]"),
            doc.selectFirst("img[src*=ekstat.com][alt]")
        )
        for (img in candidates) {
            val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
            val resolved = resolveImageUrl(raw)
            if (resolved != null && !resolved.contains("eksilogo", ignoreCase = true)) {
                return resolved
            }
        }
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val resolvedOg = ogImage?.let { resolveImageUrl(it) }
        if (resolvedOg != null &&
            !resolvedOg.contains("eksilogo", ignoreCase = true) &&
            !resolvedOg.contains("default", ignoreCase = true) &&
            !resolvedOg.contains("share", ignoreCase = true)
        ) {
            return resolvedOg
        }
        return null
    }

    private fun resolveImageUrl(raw: String): String? {
        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) return null
        return when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://eksisozluk.com$raw"
            else -> "https://eksisozluk.com/$raw"
        }
    }

    private fun extractAuthorTabUrls(doc: org.jsoup.nodes.Document): Map<AuthorTab, String> {
        val result = mutableMapOf<AuthorTab, String>()
        val links = doc.select(
            "ul.tabs a, #profile-stats-section a, #user-profile-tabs a, .profile-tabs a, a[data-url], ul.sub-title-menu a"
        )
        for (a in links) {
            val candidateUrl = a.attr("data-url").ifBlank { a.attr("href") }.trim()
            if (candidateUrl.isBlank() || candidateUrl == "#" || candidateUrl.startsWith("javascript")) continue
            val lowerUrl = candidateUrl.lowercase()
            val text = a.text().lowercase()
            val tab = when {
                lowerUrl.contains("favori") || text.contains("favori") -> AuthorTab.FAVORITES
                lowerUrl.contains("begen") || lowerUrl.contains("beğen") || text.contains("beğen") -> AuthorTab.TOP_FAVORITED
                lowerUrl.contains("son-entry") || lowerUrl.contains("enson") || text.contains("son entry") || text == "entry'ler" -> AuthorTab.RECENT
                else -> null
            }
            if (tab != null && !result.containsKey(tab)) {
                result[tab] = normalizePath(candidateUrl)
            }
        }
        return result
    }

    private fun parseAuthorEntriesAndPager(doc: org.jsoup.nodes.Document): Triple<List<EntryItem>, Int, Int> {
        val entries = mutableListOf<EntryItem>()

        // Layout 1: div.topic-item containers wrapping heading + entry
        val topicContainers = doc.select("div.topic-item, article.topic-item, div.profile-entry-item")
        for (container in topicContainers) {
            val topicAnchor = container.selectFirst("h1#title a, h1 a, h2 a, h3 a, .topic-title a, a[itemprop=url]")
            val topicTitle = topicAnchor?.text()?.trim()
                ?: container.selectFirst("h1#title, h1, h2, .topic-title")?.text()?.trim()
            val topicUrl = topicAnchor?.attr("href")?.let { normalizePath(it) }
            val entryLis = container.select("ul#entry-item-list > li[data-id], ul.entry-list > li[data-id], li[data-id]")
            for (entryLi in entryLis) {
                parseEntryElement(entryLi, topicTitle, topicUrl)?.let { entries.add(it) }
            }
        }

        // Layout 2: Flat list of li[data-id] or ul#entry-item-list > li (e.g., AJAX partial responses)
        if (entries.isEmpty()) {
            val allLis = doc.select("ul#entry-item-list > li[data-id], ul.entry-list > li[data-id], li[data-id]")
            for (li in allLis) {
                val parentBlock = li.parents().firstOrNull { p ->
                    p.selectFirst("h1#title a, h1 a, h2 a, .topic-title a") != null
                }
                val prevHeading = li.previousElementSiblings().firstOrNull { sib ->
                    sib.tagName() in listOf("h1", "h2", "h3") || sib.selectFirst("h1 a, h2 a") != null
                }
                val anchor = li.selectFirst("h1 a, h2 a, .topic-title a")
                    ?: parentBlock?.selectFirst("h1#title a, h1 a, h2 a, .topic-title a")
                    ?: prevHeading?.selectFirst("a")
                val topicTitle = anchor?.text()?.trim()
                    ?: li.attr("data-topic").takeIf { it.isNotBlank() }
                    ?: li.attr("data-title").takeIf { it.isNotBlank() }
                val topicUrl = anchor?.attr("href")?.takeIf { it.isNotBlank() }?.let { normalizePath(it) }
                parseEntryElement(li, topicTitle, topicUrl)?.let { entries.add(it) }
            }
        }

        val (currentPage, totalPages) = extractPagerInfo(doc)

        return Triple(entries.distinctBy { it.id }, currentPage, totalPages)
    }

    internal fun extractPagerInfo(
        doc: org.jsoup.nodes.Document,
        requestedUrl: String = ""
    ): Pair<Int, Int> {
        val pagerElements = doc.select("div.pager, .pager")

        val currentPage = pagerElements.firstNotNullOfOrNull { el ->
            parsePositivePageInt(el.attr("data-currentpage"))
        } ?: pagerElements.firstNotNullOfOrNull { el ->
            val opt = el.selectFirst("select option[selected]")
            parsePositivePageInt(opt?.attr("value")) ?: parsePositivePageInt(opt?.text())
        } ?: doc.selectFirst("select.pager option[selected]")?.let { opt ->
            parsePositivePageInt(opt.attr("value")) ?: parsePositivePageInt(opt.text())
        } ?: pagerElements.firstNotNullOfOrNull { el ->
            parsePositivePageInt(el.selectFirst("a.current, span.current, .active")?.text())
        } ?: extractPageParamFromUrl(requestedUrl)
            ?: 1

        val totalFromAttr = pagerElements.firstNotNullOfOrNull { el ->
            parsePositivePageInt(el.attr("data-pagecount"))
        }
        val totalFromLastLink = pagerElements.firstNotNullOfOrNull { el ->
            el.selectFirst("a.last")?.let { last ->
                parsePositivePageInt(last.text()) ?: extractPageParamFromUrl(last.attr("href"))
            }
        } ?: doc.selectFirst("a.last")?.let { last ->
            parsePositivePageInt(last.text()) ?: extractPageParamFromUrl(last.attr("href"))
        }
        val totalFromSelect = pagerElements.flatMap { it.select("select option") }
            .plus(doc.select("select.pager option"))
            .mapNotNull { opt ->
                parsePositivePageInt(opt.attr("value")) ?: parsePositivePageInt(opt.text())
            }
            .maxOrNull()
        val totalFromLinks = pagerElements.flatMap { it.select("a[href]") }
            .mapNotNull { a -> extractPageParamFromUrl(a.attr("href")) ?: parsePositivePageInt(a.text()) }
            .maxOrNull()

        val rawTotal = totalFromAttr
            ?: totalFromLastLink
            ?: totalFromSelect
            ?: totalFromLinks
            ?: 1

        val safeCurrent = currentPage.coerceAtLeast(1)
        val safeTotal = rawTotal.coerceAtLeast(safeCurrent).coerceAtLeast(1)
        return safeCurrent to safeTotal
    }

    private fun parsePositivePageInt(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace(".", "").replace(",", "").replace(" ", "")
        return cleaned.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun extractPageParamFromUrl(url: String?): Int? {
        if (url.isNullOrBlank()) return null
        val query = url.substringAfter("?", "").substringBefore("#")
        if (query.isBlank()) return null
        return query.split("&")
            .firstOrNull { it.startsWith("p=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.let(::parsePositivePageInt)
    }

    fun parseLoggedInNick(html: String): String? {
        val doc = Jsoup.parse(html, "https://eksisozluk.com")
        // If explicit login link is visible in top bar, user is logged out
        val loginLink = doc.selectFirst("a#top-login-link, li.not-mobile a[href=/giris], header a[href=/giris]")

        val candidates = listOfNotNull(
            doc.selectFirst("header a[title=ben]"),
            doc.selectFirst("a[title=ben]"),
            doc.selectFirst("a[aria-label=ben]"),
            doc.selectFirst("nav#top-navigation a[href^=/biri/]"),
            doc.selectFirst("ul#top-navigation a[href^=/biri/]"),
            doc.selectFirst("li.top-navigation-user a[href^=/biri/]"),
            doc.selectFirst("li.not-mobile a[href^=/biri/]"),
            doc.selectFirst("li.mobile-only a[href^=/biri/]"),
            doc.selectFirst("header a[href^=/biri/]"),
            doc.selectFirst("div#top-bar a[href^=/biri/]")
        )

        for (el in candidates) {
            val href = el.attr("href").trim()
            if (href.startsWith("/biri/") && !href.equals("/biri/", ignoreCase = true)) {
                val raw = href.removePrefix("/biri/").substringBefore("?").substringBefore("/")
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                if (decoded.isNotBlank()) return decoded
            }
        }

        // Check /ayarlar/hesap or redirected /biri/<nick> if loginLink is absent
        if (loginLink == null) {
            val profileTitle = doc.selectFirst("h1#user-profile-title a[href^=/biri/]")
            if (profileTitle != null && doc.selectFirst("a[href=/ayarlar/hesap], form[action*=/ayarlar]") != null) {
                val raw = profileTitle.attr("href").removePrefix("/biri/").substringBefore("?")
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                if (decoded.isNotBlank()) return decoded
            }
            val editForm = doc.selectFirst("form[action*=/entry/ekle], textarea#editbox")
            if (editForm != null) {
                val authorAttr = doc.selectFirst("[data-logged-in-user], [data-user-nick]")
                    ?.let { it.attr("data-logged-in-user").ifBlank { it.attr("data-user-nick") } }
                if (!authorAttr.isNullOrBlank()) return authorAttr
            }
        }
        return null
    }

    fun parsePostEntryError(html: String): String? {
        val doc = Jsoup.parse(html, "https://eksisozluk.com")
        val errors = doc.select(
            ".validation-summary-errors li, .field-validation-error, span.field-validation-error, " +
                "div.error-message, p.error, .toast-error, div.alert-danger, #error-message"
        ).mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
        if (errors.isNotEmpty()) return errors.distinct().joinToString("\n")
        if (doc.selectFirst("form[action*=/giris]") != null) {
            return "Oturumunuz sona ermiş görünüyor. Lütfen tekrar giriş yapın."
        }
        return null
    }

    fun parseMessageInbox(html: String, finalUrl: String): MessageInboxData {
        val doc = Jsoup.parse(html, "https://eksisozluk.com")
        val isLoginPage = finalUrl.contains("/giris", ignoreCase = true) ||
            doc.selectFirst("form[action*=/giris]") != null
        if (isLoginPage) {
            return MessageInboxData(requiresLogin = true)
        }

        val threads = mutableListOf<MessageThreadSummary>()
        val seenUrls = HashSet<String>()

        val containers = doc.select(
            "ul#threads > li, ul.thread-list > li, div#threads > div, div.thread-list > div, " +
                "table.message-list tbody tr, ul#message-thread-list > li, ul.message-thread-list > li, " +
                "li[data-thread-id]"
        )

        val composeForm = extractMessageFormMeta(doc)
        val pageToken = doc.selectFirst("input[name=__RequestVerificationToken]")
            ?.attr("value")
            ?.takeIf { it.isNotBlank() }
            ?: composeForm?.fields?.get("__RequestVerificationToken")?.takeIf { it.isNotBlank() }
        val pageDeleteForm = extractPageLevelDeleteFormMeta(doc, pageToken)

        for (item in containers) {
            val link = item.selectFirst("a[href*=/mesaj/], a[href^=/mesaj?]")
                ?: item.takeIf { it.tagName() == "a" }
                ?: continue
            val rawHref = link.attr("href").trim()
            if (isNonThreadMessagePath(rawHref)) continue

            val threadUrl = normalizePath(rawHref)
            if (!seenUrls.add(threadUrl)) continue

            val nick = item.selectFirst("h2, .author, .sender, .nick, a[href^=/biri/], strong")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.selectFirst("h2, strong, span")?.text()?.trim()
                ?: link.text().trim().lineSequence().firstOrNull()?.trim().orEmpty()
                .ifBlank { "Yazar" }

            val dateText = item.selectFirst("time, .date, small, span.time")?.text()?.trim().orEmpty()
            val previewEl = item.selectFirst("p, .preview, .last-message, .message-preview, .content")
            val preview = previewEl?.text()?.trim()
                ?: item.clone().apply {
                    select("form, button, .actions, .delete, a[href*=/mesaj/sil], a[href*=/mesaj/arsiv]").remove()
                }.text().trim()
                    .removePrefix(nick)
                    .removeSuffix(dateText)
                    .trim()

            val isUnread = item.hasClass("unread") ||
                item.attr("data-unread").equals("true", ignoreCase = true) ||
                item.selectFirst(".unread") != null

            val threadId = extractThreadId(item, threadUrl)

            val deleteMeta = extractMessageDeleteMeta(
                container = item,
                fallbackToken = pageToken,
                threadId = threadId,
                pageDeleteForm = pageDeleteForm
            )

            threads.add(
                MessageThreadSummary(
                    threadId = threadId,
                    threadUrl = threadUrl,
                    participantNick = nick,
                    preview = preview.take(140),
                    dateText = dateText,
                    isUnread = isUnread,
                    deleteMeta = deleteMeta
                )
            )
        }

        // Fallback: direct anchors to /mesaj/<id> if container classes changed
        if (threads.isEmpty()) {
            doc.select("a[href^=/mesaj/]").forEach { a ->
                val rawHref = a.attr("href").trim()
                if (isNonThreadMessagePath(rawHref)) return@forEach
                val threadUrl = normalizePath(rawHref)
                if (!seenUrls.add(threadUrl)) return@forEach
                val nick = a.selectFirst("h2, .author, .sender, .nick, strong, span")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: a.text().trim()
                if (nick.isBlank()) return@forEach
                val parent = a.parent()
                val dateText = a.selectFirst("time, .date, small")?.text()?.trim()
                    ?: parent?.selectFirst("time, .date, small")?.text()?.trim().orEmpty()
                val preview = a.selectFirst("p, .preview, .last-message")?.text()?.trim()
                    ?: parent?.selectFirst("p, .preview, .last-message")?.text()?.trim().orEmpty()
                val isUnread = a.hasClass("unread") || parent?.hasClass("unread") == true
                val threadId = extractThreadId(parent ?: a, threadUrl)
                val deleteMeta = parent?.let {
                    extractMessageDeleteMeta(
                        container = it,
                        fallbackToken = pageToken,
                        threadId = threadId,
                        pageDeleteForm = pageDeleteForm
                    )
                }
                threads.add(
                    MessageThreadSummary(
                        threadId = threadId,
                        threadUrl = threadUrl,
                        participantNick = nick.take(48),
                        preview = preview.take(140),
                        dateText = dateText,
                        isUnread = isUnread,
                        deleteMeta = deleteMeta
                    )
                )
            }
        }

        return MessageInboxData(
            threads = threads,
            composeForm = composeForm,
            verificationToken = pageToken,
            requiresLogin = false
        )
    }

    /**
     * True when the given HTML is an actual message inbox (even an empty one), so callers don't
     * mistake an error/blank page for "the thread is gone".
     */
    fun looksLikeMessageInbox(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        return lower.contains("id=\"threads\"") ||
            lower.contains("id='threads'") ||
            lower.contains("message-thread-list") ||
            lower.contains("id=\"message-thread\"") ||
            lower.contains("mesajlarım") ||
            lower.contains("/mesaj/sil")
    }

    fun messageThreadMatches(
        itemPath: String,
        itemId: String,
        targetPath: String,
        targetId: String
    ): Boolean {
        val cleanItemPath = normalizePath(itemPath).substringBefore("?").trimEnd('/')
        val cleanTargetPath = normalizePath(targetPath).substringBefore("?").trimEnd('/')
        if (cleanTargetPath.isNotBlank() &&
            cleanItemPath.isNotBlank() &&
            cleanItemPath.equals(cleanTargetPath, ignoreCase = true)
        ) {
            return true
        }
        val cleanItemId = itemId.trim()
        val cleanTargetId = targetId.trim()
        return cleanTargetId.isNotBlank() && cleanItemId == cleanTargetId
    }

    fun parseMessageThread(
        html: String,
        requestedUrl: String,
        currentUserNick: String?
    ): MessageThreadDetail {
        val doc = Jsoup.parse(html, "https://eksisozluk.com")
        val isLoginPage = doc.selectFirst("form[action*=/giris]") != null
        if (isLoginPage) {
            return MessageThreadDetail(
                threadUrl = requestedUrl,
                participantNick = "",
                requiresLogin = true
            )
        }

        val resolvedCurrentNick = currentUserNick?.takeIf { it.isNotBlank() } ?: parseLoggedInNick(html)

        val headerAuthorNick = doc.selectFirst(
            "h1#title a[href^=/biri/], h1 a[href^=/biri/], h2 a[href^=/biri/], " +
                "#message-thread-title a[href^=/biri/], .thread-title a[href^=/biri/]"
        )?.text()?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }

        val headingNick = doc.selectFirst(
            "h1#title, h2#thread-title, #message-thread-title, h2.author, h1"
        )?.text()?.trim()
            ?.removePrefix("@")
            ?.replace(Regex("\\s+ile\\s+mesaj.*$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("mesajlar", ignoreCase = true) &&
                    !it.equals("gelen kutusu", ignoreCase = true)
            }

        val participantNick = headerAuthorNick
            ?: headingNick
            ?: doc.selectFirst("#content-body a[href^=/biri/]:not([title=ben]), main a[href^=/biri/]:not([title=ben]), a[href^=/biri/]:not([title=ben])")
                ?.text()?.trim()?.removePrefix("@")
            ?: "Sohbet"

        val messageElements = doc.select(
            "ul#message-thread > li, ol#message-thread > li, div#message-thread > article, " +
                "div#message-thread > div, section#message-thread article, " +
                ".message-thread > li, .message-thread article, .message-thread > div, " +
                "ul.message-list > li, article.message, div.message-item, " +
                "li.incoming, li.outgoing, div.incoming, div.outgoing, " +
                "#messages article, #messages > li, #messages > div.message"
        )

        val messages = mutableListOf<DirectMessageItem>()
        messageElements.forEachIndexed { idx, el ->
            // Skip containers that are actually the reply form or pagination/actions bar
            if (el.tagName().equals("form", ignoreCase = true) ||
                el.selectFirst("textarea") != null ||
                el.hasClass("pager") ||
                el.hasClass("actions")
            ) {
                return@forEachIndexed
            }

            val contentEl = el.selectFirst("div.content, p.message-body, div.message-content, div.body, p")
                ?: el.clone().apply {
                    select("time, .date, small, footer, .sender, .author, a[href^=/biri/], .actions").remove()
                }
            val segments = parseContentSegments(contentEl)
            val body = segmentsToPlainText(segments).ifBlank { contentEl.text().trim() }
            if (body.isBlank()) return@forEachIndexed

            val senderNick = el.attr("data-author")
                .ifBlank { el.selectFirst("a[href^=/biri/], .sender, .author, strong")?.text()?.trim().orEmpty() }
                .ifBlank { participantNick }

            val isOutgoing = el.hasClass("outgoing") ||
                el.hasClass("sent") ||
                el.hasClass("mine") ||
                el.hasClass("from-me") ||
                el.attr("data-outgoing").equals("true", ignoreCase = true) ||
                (!el.hasClass("incoming") &&
                    !resolvedCurrentNick.isNullOrBlank() &&
                    senderNick.equals(resolvedCurrentNick, ignoreCase = true))

            val sentAt = el.selectFirst("time, .date, small, footer a, span.time")?.text()?.trim().orEmpty()
            val id = el.attr("data-id").ifBlank { "${idx}_${body.hashCode()}" }

            messages.add(
                DirectMessageItem(
                    id = id,
                    senderNick = senderNick,
                    body = body,
                    segments = segments,
                    sentAt = sentAt,
                    isOutgoing = isOutgoing
                )
            )
        }

        val normalizedUrl = normalizePath(requestedUrl).ifBlank { "/mesaj/yeni" }
        val replyForm = extractMessageFormMeta(doc, fallbackAction = normalizedUrl)
        return MessageThreadDetail(
            threadUrl = normalizedUrl,
            participantNick = participantNick,
            messages = messages,
            replyForm = replyForm,
            requiresLogin = false
        )
    }

    fun extractMessageFormMeta(
        doc: org.jsoup.nodes.Document,
        fallbackAction: String = "/mesaj/yeni"
    ): MessageFormMeta? {
        val formsWithTextarea = doc.select("form").filter { f ->
            val action = f.attr("action").lowercase()
            f.selectFirst("textarea") != null &&
                !action.contains("giris") &&
                !action.contains("/basliklar/ara") &&
                !action.contains("/arama") &&
                !action.contains("/mesaj/sil") &&
                !action.contains("/mesaj/arsiv")
        }

        val form = formsWithTextarea.firstOrNull { f ->
            val id = f.id().lowercase()
            val action = f.attr("action").lowercase()
            id.contains("message") || action.contains("/mesaj")
        }
            ?: formsWithTextarea.firstOrNull { f ->
                f.selectFirst("textarea[name=Message], textarea[name=Body], textarea[name=Content]") != null
            }
            ?: formsWithTextarea.firstOrNull()
            ?: return null

        val action = form.attr("action").trim().ifBlank { fallbackAction }
        val fields = LinkedHashMap<String, String>()
        form.select("input[name]").forEach { input ->
            val name = input.attr("name").trim()
            val type = input.attr("type").lowercase()
            if (name.isBlank()) return@forEach
            if (type == "submit" || type == "button" || type == "reset" || type == "image") return@forEach
            if ((type == "checkbox" || type == "radio") && !input.hasAttr("checked")) return@forEach
            fields[name] = input.attr("value")
        }
        form.select("select[name]").forEach { select ->
            val name = select.attr("name").trim()
            if (name.isNotBlank()) {
                val selected = select.selectFirst("option[selected]")?.attr("value")
                    ?: select.selectFirst("option")?.attr("value")
                    ?: ""
                fields[name] = selected
            }
        }

        if (!fields.containsKey("__RequestVerificationToken")) {
            doc.selectFirst("input[name=__RequestVerificationToken]")?.attr("value")?.let { token ->
                if (token.isNotBlank()) {
                    fields["__RequestVerificationToken"] = token
                }
            }
        }

        val visibleRecipientInput = form.select("input[name]")
            .firstOrNull { input ->
                val name = input.attr("name").lowercase()
                val type = input.attr("type").lowercase()
                type != "hidden" && (
                    name.contains("to") ||
                        name.contains("nick") ||
                        name.contains("receiver") ||
                        name.contains("recipient") ||
                        name.contains("alici")
                    )
            }?.attr("name")?.takeIf { it.isNotBlank() }

        val hiddenRecipientField = fields.keys.firstOrNull {
            val lower = it.lowercase()
            lower == "to" || lower == "nick" || lower == "receiver" || lower == "recipient"
        }

        val recipientFieldName = visibleRecipientInput ?: hiddenRecipientField ?: "To"
        val hasRecipientInput = visibleRecipientInput != null || hiddenRecipientField != null

        val bodyFieldName = form.selectFirst("textarea[name]")?.attr("name")?.takeIf { it.isNotBlank() }
            ?: "Message"

        val isAjax = form.attr("data-ajax").equals("true", ignoreCase = true)

        return MessageFormMeta(
            action = action,
            fields = fields,
            recipientFieldName = recipientFieldName,
            bodyFieldName = bodyFieldName,
            hasRecipientInput = hasRecipientInput,
            isAjax = isAjax
        )
    }

    private fun extractThreadId(container: Element, threadUrl: String): String {
        val attrId = listOf(
            container.attr("data-id"),
            container.attr("data-thread-id"),
            container.attr("data-konusma-id"),
            container.selectFirst("[data-thread-id]")?.attr("data-thread-id").orEmpty(),
            container.selectFirst("[data-konusma-id]")?.attr("data-konusma-id").orEmpty(),
            container.selectFirst("input[type=checkbox][value]")?.attr("value").orEmpty()
        ).map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != "0" }
        if (attrId != null) return attrId

        val query = threadUrl.substringAfter("?", "")
        if (query.isNotBlank()) {
            Regex("(?:^|&)(?:threadId|konusmaId|id)=([^&]+)", RegexOption.IGNORE_CASE)
                .find(query)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "0" }
                ?.let { return it }
        }
        val tail = threadUrl.substringBefore("?").trimEnd('/').substringAfterLast("/").trim()
        return tail.takeIf { it.isNotBlank() && it != "0" && !it.equals("mesaj", ignoreCase = true) }.orEmpty()
    }

    private fun extractPageLevelDeleteFormMeta(
        doc: org.jsoup.nodes.Document,
        fallbackToken: String?
    ): MessageDeleteMeta? {
        val deleteForm = doc.select("form").firstOrNull { form ->
            isDeleteMessageForm(form)
        } ?: return null

        val deleteButton = deleteForm.select("button, input[type=submit]")
            .firstOrNull { isDeleteSubmitControl(it) }
        // The bulk form on /mesaj may point its action at the archive endpoint and use
        // formaction="..." on the delete button, so prefer the button target.
        val action = deleteButton?.attr("formaction")?.trim()?.takeIf { it.isNotBlank() }
            ?: deleteForm.attr("action").trim().ifBlank { "/mesaj/sil" }
        val fields = collectDeleteFormFields(deleteForm, fallbackToken)
        deleteButton?.let { btn ->
            val name = btn.attr("name").trim()
            if (name.isNotBlank() && !fields.containsKey(name)) {
                fields[name] = btn.attr("value").ifBlank { btn.text().trim().ifBlank { "sil" } }
            }
        }
        val isAjax = deleteForm.attr("data-ajax").equals("true", ignoreCase = true)
        return MessageDeleteMeta(action = action, fields = fields, isAjax = isAjax)
    }

    fun extractMessageDeleteMetaFromPage(
        doc: org.jsoup.nodes.Document,
        threadId: String? = null
    ): MessageDeleteMeta? {
        val pageToken = doc.selectFirst("input[name=__RequestVerificationToken]")
            ?.attr("value")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=csrf-token], meta[name=__RequestVerificationToken]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
        val resolvedId = extractThreadIdFromDocument(doc, threadId.orEmpty())
            .takeIf { it.isNotBlank() }
        return extractMessageDeleteMeta(
            container = doc,
            fallbackToken = pageToken,
            threadId = resolvedId,
            pageDeleteForm = null
        )
    }

    fun extractThreadIdFromDocument(
        doc: org.jsoup.nodes.Document,
        fallbackId: String = ""
    ): String {
        fun isValidId(value: String): Boolean = value.isNotBlank() && value != "0"
        fun isPositiveNumeric(value: String): Boolean = value.toLongOrNull()?.let { it > 0L } == true

        val scopes = doc.select(
            "#content-body, main, #main, #message-thread, form[action*=/mesaj], " +
                ".message-thread, #threads, #message-thread-list"
        )
        val scopedCandidates = mutableListOf<String>()

        fun collectFromRoot(root: Element, includeGenericIdInputs: Boolean) {
            val inputSelectors = if (includeGenericIdInputs) {
                "input[name=ThreadId], input[name=threadId], input[name=konusmaId], input[name=KonusmaId], " +
                    "input[name=messageThreadId], input[name=Id], input[name=id], " +
                    "input[name=threadIds], input[name=thread], input[name=Thread]"
            } else {
                "input[name=ThreadId], input[name=threadId], input[name=konusmaId], input[name=KonusmaId], " +
                    "input[name=messageThreadId], input[name=threadIds]"
            }
            root.select(inputSelectors).forEach { el ->
                val v = el.attr("value").trim()
                if (isValidId(v)) scopedCandidates.add(v)
            }

            val attrSelectors = if (includeGenericIdInputs) {
                "[data-thread-id], [data-konusma-id], [data-thread], " +
                    "#message-thread[data-id], .delete-message[data-id], .delete-thread[data-id], " +
                    "form[action*=sil] [data-id], a[data-id][href*=mesaj], button[data-id]"
            } else {
                "[data-thread-id], [data-konusma-id], [data-thread], " +
                    "#message-thread[data-id], .delete-message[data-id], .delete-thread[data-id], " +
                    "form[action*=sil] [data-id], a[data-id][href*=mesaj]"
            }
            root.select(attrSelectors).forEach { el ->
                listOf(
                    el.attr("data-thread-id"),
                    el.attr("data-konusma-id"),
                    el.attr("data-thread"),
                    el.attr("data-id")
                ).map { it.trim() }
                    .filter(::isValidId)
                    .forEach { scopedCandidates.add(it) }
            }
        }

        if (scopes.isNotEmpty()) {
            scopes.forEach { collectFromRoot(it, includeGenericIdInputs = true) }
        }
        collectFromRoot(doc, includeGenericIdInputs = scopes.isEmpty())

        doc.select("form[action*=/mesaj/]").forEach { f ->
            Regex("/mesaj/(?:konusma/)?(?:sil/)?(\\d+)", RegexOption.IGNORE_CASE)
                .find(f.attr("action"))
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf(::isValidId)
                ?.let { scopedCandidates.add(it) }
        }

        val html = doc.html()
        Regex("""(?:threadId|konusmaId|messageThreadId|thread_id)\s*[:=]\s*['"]?(\d+)['"]?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(::isValidId) }
            .forEach { scopedCandidates.add(it) }

        scopedCandidates.firstOrNull(::isPositiveNumeric)?.let { return it }

        val cleanFallback = fallbackId.trim().takeIf(::isValidId)
        if (cleanFallback != null && isPositiveNumeric(cleanFallback)) {
            return cleanFallback
        }

        return scopedCandidates.firstOrNull()
            ?: cleanFallback
            ?: ""
    }

    fun extractDeleteEndpointsFromHtml(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val regex = Regex(
            """['"`]((?:https?://(?:www\.|m\.)?eksisozluk\.com)?/(?:mesaj|api)/[^'"`\s<>]+)['"`]""",
            RegexOption.IGNORE_CASE
        )
        return regex.findAll(html)
            .mapNotNull { m ->
                val rawCaptured = m.groupValues.getOrNull(1)?.trim() ?: return@mapNotNull null
                val strippedInterpolation = if (rawCaptured.contains("\${")) {
                    rawCaptured.substringBefore("\${")
                } else {
                    rawCaptured
                }
                val normalized = normalizePath(strippedInterpolation).trim()
                if (normalized.isBlank() || normalized.startsWith("http")) return@mapNotNull null
                val lower = normalized.lowercase()
                val looksLikeDelete = lower.contains("sil") ||
                    lower.contains("delete") ||
                    lower.contains("remove") ||
                    lower.contains("kaldir") ||
                    lower.contains("temizle")
                val isMessageApi = if (lower.startsWith("/api/")) {
                    lower.contains("mesaj") ||
                        lower.contains("message") ||
                        lower.contains("thread") ||
                        lower.contains("konusma") ||
                        lower.contains("dm") ||
                        lower.contains("pm")
                } else {
                    true
                }
                val excluded = lower.startsWith("/mesaj/yeni") ||
                    lower.startsWith("/mesaj/arsiv") ||
                    lower.startsWith("/mesaj/ara") ||
                    lower.startsWith("/mesaj/send") ||
                    lower.startsWith("/mesaj/engelle") ||
                    lower.startsWith("/mesaj/okundu") ||
                    lower.endsWith(".js") ||
                    lower.endsWith(".css") ||
                    lower.endsWith(".png") ||
                    lower.endsWith(".svg")
                if (looksLikeDelete && isMessageApi && !excluded) normalized else null
            }
            .distinct()
            .toList()
    }

    private fun normalizeMessageActionPath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "/mesaj/sil"
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return normalizePath(trimmed)
        }
        if (trimmed.startsWith("/")) return trimmed
        val clean = trimmed.removePrefix("./")
        return if (clean.startsWith("mesaj/")) "/$clean" else "/mesaj/$clean"
    }

    private fun isDeleteMessageForm(form: Element): Boolean {
        if (form.selectFirst("textarea") != null) return false
        val deleteSubmit = form.select("button, input[type=submit]")
            .firstOrNull { isDeleteSubmitControl(it) }
        if (deleteSubmit != null) {
            val submitAction = deleteSubmit.attr("formaction").lowercase()
            return !(submitAction.contains("giris") || submitAction.contains("arsiv") ||
                submitAction.contains("engelle") || submitAction.contains("okundu"))
        }
        val action = form.attr("action").lowercase()
        val id = form.id().lowercase()
        val cls = form.className().lowercase()
        if (action.contains("giris") || action.contains("arsiv") || action.contains("engelle") || action.contains("okundu")) {
            return false
        }
        val submitText = form.select("button, input[type=submit], a")
            .joinToString(" ") { el ->
                listOf(
                    el.text(),
                    el.attr("value"),
                    el.attr("title"),
                    el.attr("aria-label"),
                    el.attr("data-original-title")
                ).joinToString(" ")
            }.trim().lowercase()
        val actionIsDelete = (action.contains("sil") || action.contains("delete") || action.contains("remove") ||
            action.contains("kaldir") || action.contains("temizle"))
        val attrIsDelete = id.contains("delete") || id.contains("sil") || cls.contains("delete")
        val textIsDelete = submitText.contains("sil") ||
            submitText.contains("delete") ||
            submitText.contains("kaldır") ||
            submitText.contains("temizle")
        return actionIsDelete || attrIsDelete || textIsDelete
    }

    /**
     * Matches a button/input that submits a "delete" action, ignoring archive/block/read actions.
     */
    private fun isDeleteSubmitControl(el: Element): Boolean {
        val tag = el.tagName().lowercase()
        val type = el.attr("type").lowercase()
        if (tag == "input" && type != "submit" && type != "button") return false
        if (tag == "a") return false
        val label = listOf(
            el.text(),
            el.attr("value"),
            el.attr("title"),
            el.attr("aria-label"),
            el.attr("data-original-title"),
            el.attr("formaction")
        ).joinToString(" ").lowercase()
        if (label.contains("arsiv") || label.contains("arşiv") ||
            label.contains("engelle") || label.contains("okundu") ||
            label.contains("giris") || label.contains("giriş")
        ) {
            return false
        }
        return label.contains("sil") || label.contains("delete") ||
            label.contains("kaldır") || label.contains("kaldir") ||
            label.contains("temizle")
    }

    private fun collectDeleteFormFields(
        form: Element,
        fallbackToken: String?
    ): LinkedHashMap<String, String> {
        val fields = LinkedHashMap<String, String>()
        form.select("input[name]").forEach { input ->
            val name = input.attr("name").trim()
            val type = input.attr("type").lowercase()
            if (name.isBlank()) return@forEach
            if (type == "button" || type == "reset" || type == "image" || type == "checkbox") return@forEach
            if (type == "submit") {
                val v = input.attr("value").trim()
                if (v.contains("sil", ignoreCase = true) || v.contains("delete", ignoreCase = true)) {
                    fields[name] = input.attr("value")
                }
                return@forEach
            }
            if (type == "radio" && !input.hasAttr("checked")) return@forEach
            fields[name] = input.attr("value")
        }
        form.select("button[type=submit][name], button:not([type])[name]").forEach { btn ->
            val name = btn.attr("name").trim()
            val txt = (btn.text() + " " + btn.attr("value") + " " + btn.attr("title")).lowercase()
            if (name.isNotBlank() && (txt.contains("sil") || txt.contains("delete"))) {
                fields[name] = btn.attr("value").ifBlank { "sil" }
            }
        }
        if (!fields.containsKey("__RequestVerificationToken") && !fallbackToken.isNullOrBlank()) {
            fields["__RequestVerificationToken"] = fallbackToken
        }
        return fields
    }

    fun extractMessageDeleteMeta(
        container: Element,
        fallbackToken: String? = null,
        threadId: String? = null,
        pageDeleteForm: MessageDeleteMeta? = null
    ): MessageDeleteMeta? {
        val deleteForm = container.select("form").firstOrNull { isDeleteMessageForm(it) }
        if (deleteForm != null) {
            val action = normalizeMessageActionPath(deleteForm.attr("action").trim().ifBlank { "/mesaj/sil" })
            val fields = collectDeleteFormFields(deleteForm, fallbackToken)
            val hasIdField = fields.keys.any {
                val lower = it.lowercase()
                lower == "id" || lower == "threadid" || lower == "messageid" || lower == "threadids"
            }
            if (!hasIdField && !threadId.isNullOrBlank() && !action.contains("/$threadId")) {
                fields["id"] = threadId
            }
            val isAjax = deleteForm.attr("data-ajax").equals("true", ignoreCase = true)
            return MessageDeleteMeta(
                action = action,
                fields = fields,
                isAjax = isAjax
            )
        }

        // If row has a checkbox inside an inbox page-level bulk-delete form
        val checkbox = container.selectFirst("input[type=checkbox][name]")
        if (checkbox != null && pageDeleteForm != null) {
            val cbName = checkbox.attr("name").trim()
            val cbVal = checkbox.attr("value").ifBlank { threadId.orEmpty() }
            if (cbName.isNotBlank() && cbVal.isNotBlank()) {
                val fields = LinkedHashMap<String, String>()
                fields.putAll(pageDeleteForm.fields)
                fields[cbName] = cbVal
                return MessageDeleteMeta(
                    action = normalizeMessageActionPath(pageDeleteForm.action),
                    fields = fields,
                    isAjax = pageDeleteForm.isAjax
                )
            }
        }

        val deleteTrigger = container.selectFirst(
            "a[href*=/mesaj/][href*=sil], a[data-url*=/mesaj/][data-url*=sil], button[data-url*=/mesaj/][data-url*=sil], " +
                "a[data-action*=/mesaj/][data-action*=sil], button[data-action*=/mesaj/][data-action*=sil], " +
                "a[href*=konusmasil], button[data-url*=sil], a[data-url*=sil], " +
                "a.delete-message, button.delete-message, a.delete-thread, button.delete-thread, " +
                "[data-delete-url], .actions a[href*=sil], .actions button[data-action*=delete]"
        ) ?: container.select("a, button").firstOrNull { el ->
            val txt = el.text().trim().lowercase()
            val label = (el.attr("title") + " " + el.attr("aria-label") + " " + el.attr("data-original-title")).trim().lowercase()
            val cls = el.className().lowercase()
            val href = el.attr("href").lowercase()
            if (href.contains("arsiv") || href.contains("engelle")) return@firstOrNull false
            val hasDeleteAttr = el.hasAttr("data-delete-url") ||
                el.attr("data-action").contains("delete", ignoreCase = true) ||
                el.attr("data-action").contains("sil", ignoreCase = true) ||
                el.attr("data-url").contains("sil", ignoreCase = true) ||
                el.attr("data-url").contains("delete", ignoreCase = true) ||
                cls.contains("delete")
            val textMatchesDelete = txt == "sil" ||
                txt == "konuşmayı sil" ||
                txt == "sohbeti sil" ||
                txt == "mesajı sil" ||
                label == "sil" ||
                label.contains("konuşmayı sil") ||
                label.contains("sohbeti sil") ||
                label.contains("mesajı sil")
            hasDeleteAttr || (textMatchesDelete && (!threadId.isNullOrBlank() || el.hasAttr("data-id") || el.hasAttr("data-thread-id") || href.contains("/mesaj/")))
        } ?: return null

        val rawUrl = deleteTrigger.attr("data-url")
            .ifBlank { deleteTrigger.attr("data-delete-url") }
            .ifBlank { deleteTrigger.attr("data-action") }
            .ifBlank { deleteTrigger.attr("href") }
            .trim()
            .takeIf { it.isNotBlank() && it != "#" && !it.startsWith("javascript", ignoreCase = true) }
            ?: "/mesaj/sil"

        val action = normalizeMessageActionPath(rawUrl)
        val fields = LinkedHashMap<String, String>()
        if (!fallbackToken.isNullOrBlank()) {
            fields["__RequestVerificationToken"] = fallbackToken
        }
        val explicitId = deleteTrigger.attr("data-id")
            .ifBlank { deleteTrigger.attr("data-thread-id") }
            .ifBlank { threadId.orEmpty() }
        if (explicitId.isNotBlank() && !action.contains("/$explicitId")) {
            fields["id"] = explicitId
        }
        return MessageDeleteMeta(
            action = action,
            fields = fields,
            isAjax = true
        )
    }

    private fun isNonThreadMessagePath(rawHref: String): Boolean {
        val path = normalizePath(rawHref).lowercase().substringBefore("?")
        return path.isBlank() ||
            path == "/mesaj" ||
            path == "/mesaj/" ||
            path == "/mesaj/yeni" ||
            path.startsWith("/mesaj/arsiv") ||
            path.startsWith("/mesaj/sil") ||
            path.startsWith("/mesaj/ara") ||
            path.startsWith("/mesaj/send") ||
            path.startsWith("/mesaj/engelle") ||
            path.startsWith("/mesaj/okundu") ||
            !path.startsWith("/mesaj")
    }

    private fun parseEntryElement(
        li: Element,
        fallbackTopicTitle: String? = null,
        fallbackTopicUrl: String? = null
    ): EntryItem? {
        val id = li.attr("data-id").toLongOrNull() ?: return null
        val authorEl = li.selectFirst("a.entry-author")
        val author = li.attr("data-author").ifBlank { authorEl?.text()?.trim().orEmpty() }
            .ifBlank { "anonim" }
        val authorSlug = authorEl?.attr("href")
            ?.removePrefix("/biri/")
            ?.substringBefore("?")
            ?.takeIf { it.isNotBlank() }
            ?: author.lowercase().replace(" ", "-")

        val dateText = li.selectFirst("a.entry-date")?.text()?.trim().orEmpty()
        val parts = dateText.split("~")
        val createdAt = parts.firstOrNull()?.trim().orEmpty()
        val editedAt = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

        val favoriteCount = li.attr("data-favorite-count").toIntOrNull()
            ?: li.selectFirst("a.favorite-count")?.text()?.trim()?.toIntOrNull()
            ?: 0
        val isFavorited = li.attr("data-isfavorite").equals("true", ignoreCase = true) ||
            li.selectFirst("a.favorite-link.favorited, .favorite-link.active, [data-isfavorite=true]") != null

        val contentEl = li.selectFirst("div.content")
        val segments = if (contentEl != null) {
            parseContentSegments(contentEl)
        } else {
            emptyList()
        }
        val plainText = segmentsToPlainText(segments)

        return EntryItem(
            id = id,
            author = author,
            authorSlug = authorSlug,
            createdAt = createdAt,
            editedAt = editedAt,
            plainText = plainText,
            segments = segments,
            favoriteCount = favoriteCount,
            isFavorited = isFavorited,
            topicTitle = fallbackTopicTitle,
            topicUrl = fallbackTopicUrl
        )
    }

    fun parseContentSegments(root: Element): List<EntrySegment> {
        val rawSegments = mutableListOf<EntrySegment>()
        collectSegments(root, rawSegments)
        return mergeAdjacentText(rawSegments)
    }

    private fun collectSegments(node: Node, out: MutableList<EntrySegment>) {
        when (node) {
            is TextNode -> {
                val cleaned = node.wholeText
                    .replace("\r\n", "\n")
                    .replace(Regex("[ \t\n]+"), " ")
                if (cleaned.isNotEmpty()) {
                    out.add(EntrySegment.Text(cleaned))
                }
            }
            is Element -> {
                val tag = node.tagName().lowercase()
                when {
                    tag == "br" -> out.add(EntrySegment.Text("\n"))
                    tag == "p" -> {
                        if (out.isNotEmpty()) out.add(EntrySegment.Text("\n\n"))
                        node.childNodes().forEach { collectSegments(it, out) }
                    }
                    tag == "div" && node.hasClass("spoiler") -> {
                        val inner = mutableListOf<EntrySegment>()
                        node.childNodes().forEach { collectSegments(it, inner) }
                        if (inner.isNotEmpty()) {
                            out.add(EntrySegment.Spoiler(mergeAdjacentText(inner)))
                        }
                    }
                    tag == "sup" && node.hasClass("ab") -> {
                        val link = node.selectFirst("a")
                        if (link != null) {
                            val tooltip = link.attr("title").ifBlank { link.attr("data-query") }
                            val targetTopic = link.attr("data-query").ifBlank { tooltip }
                            val href = normalizePath(link.attr("href"))
                            out.add(
                                EntrySegment.Bkz(
                                    displayText = "*",
                                    targetTopic = targetTopic,
                                    targetUrl = href.ifBlank { "/?q=${encodeQuery(targetTopic)}" },
                                    isSmartBkz = true,
                                    tooltipText = tooltip.takeIf { it.isNotBlank() }
                                )
                            )
                        } else {
                            node.childNodes().forEach { collectSegments(it, out) }
                        }
                    }
                    tag == "a" && node.hasClass("b") -> {
                        val href = node.attr("href").trim()
                        val display = node.text().trim()
                        val query = extractQueryFromHref(href) ?: display
                        val tooltip = node.attr("title").takeIf { it.isNotBlank() }
                        val isSmart = display == "*" && tooltip != null
                        out.add(
                            EntrySegment.Bkz(
                                displayText = display,
                                targetTopic = query,
                                targetUrl = normalizePath(href).ifBlank { "/?q=${encodeQuery(query)}" },
                                isSmartBkz = isSmart,
                                tooltipText = tooltip
                            )
                        )
                    }
                    tag == "a" && (node.hasClass("url") || node.attr("href").startsWith("http")) -> {
                        val href = node.attr("href").trim()
                        val display = node.text().trim().ifBlank { href }
                        out.add(EntrySegment.ExternalUrl(display, href))
                    }
                    tag == "a" -> {
                        val href = node.attr("href").trim()
                        val display = node.text().trim()
                        if (href.startsWith("/") && display.isNotBlank()) {
                            out.add(
                                EntrySegment.Bkz(
                                    displayText = display,
                                    targetTopic = extractQueryFromHref(href) ?: display,
                                    targetUrl = normalizePath(href)
                                )
                            )
                        } else {
                            node.childNodes().forEach { collectSegments(it, out) }
                        }
                    }
                    else -> {
                        node.childNodes().forEach { collectSegments(it, out) }
                    }
                }
            }
        }
    }

    private fun mergeAdjacentText(segments: List<EntrySegment>): List<EntrySegment> {
        val merged = mutableListOf<EntrySegment>()
        for (segment in segments) {
            if (segment is EntrySegment.Text && merged.lastOrNull() is EntrySegment.Text) {
                val prev = merged.removeAt(merged.lastIndex) as EntrySegment.Text
                merged.add(EntrySegment.Text(prev.text + segment.text))
            } else {
                merged.add(segment)
            }
        }
        // Trim leading/trailing whitespace on the first/last text segment
        if (merged.firstOrNull() is EntrySegment.Text) {
            val first = (merged.first() as EntrySegment.Text).text.trimStart()
            if (first.isEmpty()) merged.removeAt(0) else merged[0] = EntrySegment.Text(first)
        }
        if (merged.lastOrNull() is EntrySegment.Text) {
            val last = (merged.last() as EntrySegment.Text).text.trimEnd()
            if (last.isEmpty()) merged.removeAt(merged.lastIndex) else merged[merged.lastIndex] = EntrySegment.Text(last)
        }
        return merged
    }

    private fun segmentsToPlainText(segments: List<EntrySegment>): String = buildString {
        segments.forEach { seg ->
            when (seg) {
                is EntrySegment.Text -> append(seg.text)
                is EntrySegment.Bkz -> append(seg.displayText)
                is EntrySegment.ExternalUrl -> append(seg.displayText)
                is EntrySegment.Spoiler -> {
                    append("--- spoiler --- ")
                    append(segmentsToPlainText(seg.segments))
                    append(" --- spoiler ---")
                }
            }
        }
    }.trim()

    private fun extractQueryFromHref(href: String): String? {
        if (href.contains("q=")) {
            val encoded = href.substringAfter("q=").substringBefore("&")
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
        }
        return null
    }

    private fun encodeQuery(query: String): String =
        java.net.URLEncoder.encode(query, "UTF-8")

    private fun parseTurkishEntryCount(text: String): Int {
        if (text.isBlank()) return 0
        val clean = text.lowercase().replace(",", ".").trim()
        return when {
            clean.endsWith("b") -> {
                val num = clean.removeSuffix("b").toDoubleOrNull() ?: 0.0
                (num * 1000).toInt()
            }
            clean.endsWith("k") -> {
                val num = clean.removeSuffix("k").toDoubleOrNull() ?: 0.0
                (num * 1000).toInt()
            }
            else -> clean.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    fun normalizePath(href: String): String {
        val trimmed = href.trim()
        val withoutHost = trimmed
            .removePrefix("https://eksisozluk.com")
            .removePrefix("http://eksisozluk.com")
            .removePrefix("https://www.eksisozluk.com")
            .removePrefix("http://www.eksisozluk.com")
            .removePrefix("https://m.eksisozluk.com")
            .removePrefix("http://m.eksisozluk.com")
            .removePrefix("//eksisozluk.com")
            .removePrefix("//www.eksisozluk.com")
            .removePrefix("//m.eksisozluk.com")
        if (withoutHost.startsWith("http://") || withoutHost.startsWith("https://")) {
            return withoutHost
        }
        return if (withoutHost.startsWith("/")) withoutHost else "/$withoutHost"
    }
}
