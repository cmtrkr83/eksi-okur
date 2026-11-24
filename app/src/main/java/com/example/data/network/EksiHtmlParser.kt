package com.example.data.network

import com.example.data.model.AuthorProfile
import com.example.data.model.AuthorTab
import com.example.data.model.ChannelTag
import com.example.data.model.DirectMessageItem
import com.example.data.model.EntryItem
import com.example.data.model.EntrySegment
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

        val pagerEl = doc.selectFirst("div.pager")
        val currentPage = pagerEl?.attr("data-currentpage")?.toIntOrNull()
            ?: doc.selectFirst("select.pager option[selected]")?.attr("value")?.toIntOrNull()
            ?: 1
        val totalPages = pagerEl?.attr("data-pagecount")?.toIntOrNull()
            ?: doc.select("select.pager option").lastOrNull()?.attr("value")?.toIntOrNull()
            ?: 1

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

        val token = entryFormFields["__RequestVerificationToken"]
            ?: doc.selectFirst("input[name=__RequestVerificationToken]")?.attr("value")

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

        val pagerEl = doc.selectFirst("div.pager")
        val currentPage = pagerEl?.attr("data-currentpage")?.toIntOrNull()
            ?: doc.selectFirst("select.pager option[selected]")?.attr("value")?.toIntOrNull()
            ?: 1
        val totalPages = pagerEl?.attr("data-pagecount")?.toIntOrNull()
            ?: doc.select("select.pager option").lastOrNull()?.attr("value")?.toIntOrNull()
            ?: 1

        return Triple(entries.distinctBy { it.id }, currentPage.coerceAtLeast(1), totalPages.coerceAtLeast(1))
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
        val errors = doc.select(".validation-summary-errors li, .field-validation-error, div.error-message, p.error")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
        if (errors.isNotEmpty()) return errors.joinToString("\n")
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
                ?: item.text().trim()
                    .removePrefix(nick)
                    .removeSuffix(dateText)
                    .trim()

            val isUnread = item.hasClass("unread") ||
                item.attr("data-unread").equals("true", ignoreCase = true) ||
                item.selectFirst(".unread") != null

            val threadId = item.attr("data-id")
                .ifBlank { item.attr("data-thread-id") }
                .ifBlank { threadUrl.substringAfterLast("/").substringBefore("?") }

            threads.add(
                MessageThreadSummary(
                    threadId = threadId,
                    threadUrl = threadUrl,
                    participantNick = nick,
                    preview = preview.take(140),
                    dateText = dateText,
                    isUnread = isUnread
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
                val threadId = parent?.attr("data-id")?.takeIf { it.isNotBlank() }
                    ?: threadUrl.substringAfterLast("/").substringBefore("?")
                threads.add(
                    MessageThreadSummary(
                        threadId = threadId,
                        threadUrl = threadUrl,
                        participantNick = nick.take(48),
                        preview = preview.take(140),
                        dateText = dateText,
                        isUnread = isUnread
                    )
                )
            }
        }

        val composeForm = extractMessageFormMeta(doc)
        return MessageInboxData(
            threads = threads,
            composeForm = composeForm,
            requiresLogin = false
        )
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

        val participantNick = doc.selectFirst(
            "h1#title a, h1#title, h2#thread-title, #message-thread-title, h1, h2.author"
        )?.text()?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() && !it.equals("mesajlar", ignoreCase = true) }
            ?: doc.selectFirst("a[href^=/biri/]:not([title=ben])")?.text()?.trim()
            ?: "Sohbet"

        val messageElements = doc.select(
            "ul#message-thread > li, div#message-thread > article, div#message-thread > div, " +
                "ul.message-list > li, article.message, div.message-item, li.incoming, li.outgoing, div.incoming, div.outgoing"
        )

        val messages = mutableListOf<DirectMessageItem>()
        messageElements.forEachIndexed { idx, el ->
            val contentEl = el.selectFirst("div.content, p.message-body, div.message-content, p")
                ?: el
            val segments = parseContentSegments(contentEl)
            val body = segmentsToPlainText(segments).ifBlank { el.text().trim() }
            if (body.isBlank()) return@forEachIndexed

            val senderNick = el.attr("data-author")
                .ifBlank { el.selectFirst("a[href^=/biri/], .sender, .author, strong")?.text()?.trim().orEmpty() }
                .ifBlank { participantNick }

            val isOutgoing = el.hasClass("outgoing") ||
                el.hasClass("sent") ||
                el.hasClass("mine") ||
                el.hasClass("from-me") ||
                (!el.hasClass("incoming") &&
                    !currentUserNick.isNullOrBlank() &&
                    senderNick.equals(currentUserNick, ignoreCase = true))

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

        val replyForm = extractMessageFormMeta(doc)
        return MessageThreadDetail(
            threadUrl = normalizePath(requestedUrl),
            participantNick = participantNick,
            messages = messages,
            replyForm = replyForm,
            requiresLogin = false
        )
    }

    fun extractMessageFormMeta(doc: org.jsoup.nodes.Document): MessageFormMeta? {
        val form = doc.selectFirst("form[action*=/mesaj], form#message-send-form, form#new-message-form")
            ?: doc.select("form").firstOrNull { f ->
                f.selectFirst("textarea[name=Message], textarea[name=Body], textarea[name=Content], textarea") != null &&
                    !f.attr("action").contains("giris", ignoreCase = true)
            } ?: return null

        val action = form.attr("action").trim().ifBlank { "/mesaj/yeni" }
        val fields = LinkedHashMap<String, String>()
        form.select("input[name]").forEach { input ->
            val name = input.attr("name").trim()
            if (name.isNotBlank()) {
                fields[name] = input.attr("value")
            }
        }

        val recipientFieldName = form.select("input[name]")
            .firstOrNull { input ->
                val name = input.attr("name").lowercase()
                val type = input.attr("type").lowercase()
                type != "hidden" && (name.contains("to") || name.contains("nick") || name.contains("receiver") || name.contains("alici"))
            }?.attr("name")?.takeIf { it.isNotBlank() }
            ?: fields.keys.firstOrNull {
                val lower = it.lowercase()
                lower == "to" || lower == "nick" || lower == "receiver"
            }
            ?: "To"

        val bodyFieldName = form.selectFirst("textarea[name]")?.attr("name")?.takeIf { it.isNotBlank() }
            ?: "Message"

        return MessageFormMeta(
            action = action,
            fields = fields,
            recipientFieldName = recipientFieldName,
            bodyFieldName = bodyFieldName
        )
    }

    private fun isNonThreadMessagePath(rawHref: String): Boolean {
        val path = normalizePath(rawHref).lowercase().substringBefore("?")
        return path.isBlank() ||
            path == "/mesaj" ||
            path == "/mesaj/" ||
            path == "/mesaj/yeni" ||
            path == "/mesaj/arsiv" ||
            path == "/mesaj/sil" ||
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
        val isFavorited = li.attr("data-isfavorite").equals("true", ignoreCase = true)

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
        val withoutHost = href
            .removePrefix("https://eksisozluk.com")
            .removePrefix("http://eksisozluk.com")
        return if (withoutHost.startsWith("/")) withoutHost else "/$withoutHost"
    }
}
