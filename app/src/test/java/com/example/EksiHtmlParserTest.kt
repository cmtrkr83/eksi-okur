package com.example

import com.example.data.model.EntrySegment
import com.example.data.model.TopicFilter
import com.example.data.network.EksiHtmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EksiHtmlParserTest {

    @Test
    fun parseTopicList_extractsTitlesAndCounts() {
        val html = """
            <ul class="topic-list partial">
                <li><a href="/yapay-zeka--12345?a=popular">yapay zeka <small>1,4b</small></a></li>
                <li class="ad"><a href="/reklam">reklam</a></li>
                <li><a href="/turk-kahvesi--67890?a=popular">türk kahvesi <small>42</small></a></li>
            </ul>
        """.trimIndent()

        val items = EksiHtmlParser.parseTopicList(html, isDebe = false)
        assertEquals(2, items.size)
        assertEquals("yapay zeka", items[0].title)
        assertEquals("1,4b", items[0].entryCountText)
        assertEquals(1400, items[0].entryCount)
        assertEquals("türk kahvesi", items[1].title)
        assertEquals(42, items[1].entryCount)
    }

    @Test
    fun parseTopicDetail_extractsRichSegmentsAndPager() {
        val html = """
            <html><body>
                <h1 id="title" data-id="555"><a href="/ornek-baslik--555"><span itemprop="name">örnek başlık</span></a></h1>
                <div class="pager" data-currentpage="2" data-pagecount="15"></div>
                <ul id="entry-item-list">
                    <li data-id="9901" data-author="ssg" data-favorite-count="128" data-isfavorite="true">
                        <div class="content">
                            merhaba <a class="b" href="/?q=d%c3%bcnya">dünya</a>!<br>
                            detaylar <sup class="ab"><a class="b" data-query="akıllı bkz" title="gizli not" href="/?q=ak%C4%B1ll%C4%B1+bkz">*</a></sup>
                            <div class="spoiler">gizli bilgi</div>
                            <a class="url" href="https://example.com">link</a>
                        </div>
                        <a class="entry-date" href="/entry/9901">23.11.2026 10:00 ~ 10:05</a>
                        <a class="entry-author" href="/biri/ssg">ssg</a>
                    </li>
                </ul>
            </body></html>
        """.trimIndent()

        val detail = EksiHtmlParser.parseTopicDetail(html, "/ornek-baslik--555", TopicFilter.ALL)
        assertEquals("örnek başlık", detail.title)
        assertEquals(2, detail.currentPage)
        assertEquals(15, detail.totalPages)
        assertEquals(1, detail.entries.size)

        val entry = detail.entries.first()
        assertEquals(9901L, entry.id)
        assertEquals("ssg", entry.author)
        assertEquals(128, entry.favoriteCount)
        assertTrue(entry.isFavorited)
        assertEquals("23.11.2026 10:00", entry.createdAt)
        assertEquals("10:05", entry.editedAt)
        assertTrue(entry.segments.any { it is EntrySegment.Bkz && !it.isSmartBkz && it.displayText == "dünya" })
        assertTrue(entry.segments.any { it is EntrySegment.Bkz && it.isSmartBkz && it.tooltipText == "gizli not" })
        assertTrue(entry.segments.any { it is EntrySegment.Spoiler })
        assertTrue(entry.segments.any { it is EntrySegment.ExternalUrl && it.url == "https://example.com" })
    }

    @Test
    fun parseLoggedInNick_detectsMobileAndDesktopHeaders() {
        val mobileHtml = """
            <html><body>
                <header>
                    <nav id="top-navigation">
                        <ul>
                            <li class="mobile-only"><a href="/biri/test%20yazar" title="ben">ben</a></li>
                        </ul>
                    </nav>
                </header>
            </body></html>
        """.trimIndent()
        assertEquals("test yazar", EksiHtmlParser.parseLoggedInNick(mobileHtml))

        val loggedOutHtml = """
            <html><body>
                <header>
                    <a id="top-login-link" href="/giris">giriş</a>
                </header>
            </body></html>
        """.trimIndent()
        assertEquals(null, EksiHtmlParser.parseLoggedInNick(loggedOutHtml))
    }

    @Test
    fun parseTopicDetail_extractsEntryComposeFormFields() {
        val html = """
            <html><body>
                <h1 id="title" data-id="777"><a href="/deneme--777"><span itemprop="name">deneme</span></a></h1>
                <form action="/entry/ekle" method="post">
                    <input type="hidden" name="__RequestVerificationToken" value="abc123token" />
                    <input type="hidden" name="Title" value="deneme" />
                    <input type="hidden" name="Id" value="777" />
                    <textarea id="editbox" name="Content"></textarea>
                </form>
            </body></html>
        """.trimIndent()

        val detail = EksiHtmlParser.parseTopicDetail(html, "/deneme--777", TopicFilter.ALL)
        assertEquals("/entry/ekle", detail.entryFormAction)
        assertEquals("abc123token", detail.verificationToken)
        assertEquals("777", detail.entryFormFields["Id"])
        assertEquals("deneme", detail.entryFormFields["Title"])
    }

    @Test
    fun parseAuthorProfile_extractsAvatarTabUrlsAndPartialEntries() {
        val profileHtml = """
            <html><body>
                <h1 id="user-profile-title"><a href="/biri/ssg">ssg</a></h1>
                <div id="user-profile-avatar">
                    <img class="avatar" src="//img.ekstat.com/avatar/ssg.jpg" />
                </div>
                <ul class="tabs">
                    <li><a data-url="/son-entryleri?nick=ssg">son entry'leri</a></li>
                    <li><a data-url="/en-begenilenleri?nick=ssg">en beğenilenleri</a></li>
                    <li><a data-url="/favori-entryleri?nick=ssg">favorileri</a></li>
                </ul>
            </body></html>
        """.trimIndent()

        val profile = EksiHtmlParser.parseAuthorProfile(
            profileHtml,
            "ssg",
            com.example.data.model.AuthorTab.RECENT
        )
        assertEquals("ssg", profile.nick)
        assertEquals("https://img.ekstat.com/avatar/ssg.jpg", profile.avatarUrl)
        assertEquals(
            "/son-entryleri?nick=ssg",
            profile.tabUrls[com.example.data.model.AuthorTab.RECENT]
        )
        assertEquals(
            "/en-begenilenleri?nick=ssg",
            profile.tabUrls[com.example.data.model.AuthorTab.TOP_FAVORITED]
        )
        assertEquals(
            "/favori-entryleri?nick=ssg",
            profile.tabUrls[com.example.data.model.AuthorTab.FAVORITES]
        )

        val partialHtml = """
            <div class="topic-item">
                <h1><a href="/eksisozluk--1">ekşi sözlük</a></h1>
                <ul id="entry-item-list">
                    <li data-id="101" data-author="ssg" data-favorite-count="50">
                        <div class="content">ilk entry</div>
                        <a class="entry-date" href="/entry/101">15.02.1999</a>
                        <a class="entry-author" href="/biri/ssg">ssg</a>
                    </li>
                </ul>
            </div>
        """.trimIndent()
        val (entries, currentPage, totalPages) = EksiHtmlParser.parseAuthorTabEntries(partialHtml)
        assertEquals(1, entries.size)
        assertEquals("ekşi sözlük", entries.first().topicTitle)
        assertEquals("/eksisozluk--1", entries.first().topicUrl)
        assertEquals(1, currentPage)
        assertEquals(1, totalPages)
    }

    @Test
    fun parseMessageInbox_extractsThreadsComposeFormAndLoginRedirect() {
        val inboxHtml = """
            <html><body>
                <ul id="message-thread-list">
                    <li class="unread" data-id="9001">
                        <a href="/mesaj/9001">
                            <span class="author">ssg</span>
                            <p class="preview">merhaba yazar</p>
                            <time>23.11.2026 19:00</time>
                        </a>
                    </li>
                    <li data-id="9002">
                        <a href="/mesaj/9002">
                            <span class="author">kanzukun</span>
                            <p class="preview">görüşürüz</p>
                            <time>22.11.2026 12:30</time>
                        </a>
                    </li>
                </ul>
                <form action="/mesaj/yeni" method="post">
                    <input type="hidden" name="__RequestVerificationToken" value="msgToken1" />
                    <input type="text" name="To" />
                    <textarea name="Message"></textarea>
                </form>
            </body></html>
        """.trimIndent()

        val inbox = EksiHtmlParser.parseMessageInbox(inboxHtml, "https://eksisozluk.com/mesaj")
        assertEquals(false, inbox.requiresLogin)
        assertEquals(2, inbox.threads.size)
        assertEquals("9001", inbox.threads[0].threadId)
        assertEquals("/mesaj/9001", inbox.threads[0].threadUrl)
        assertEquals("ssg", inbox.threads[0].participantNick)
        assertEquals("merhaba yazar", inbox.threads[0].preview)
        assertTrue(inbox.threads[0].isUnread)
        assertEquals(false, inbox.threads[1].isUnread)
        assertEquals("/mesaj/yeni", inbox.composeForm?.action)
        assertEquals("To", inbox.composeForm?.recipientFieldName)
        assertEquals("Message", inbox.composeForm?.bodyFieldName)
        assertEquals("msgToken1", inbox.composeForm?.fields?.get("__RequestVerificationToken"))

        val loginHtml = """
            <html><body>
                <form action="/giris" method="post">
                    <input type="text" name="UserName" />
                </form>
            </body></html>
        """.trimIndent()
        val unauthInbox = EksiHtmlParser.parseMessageInbox(loginHtml, "https://eksisozluk.com/giris?returnUrl=%2Fmesaj")
        assertTrue(unauthInbox.requiresLogin)
    }

    @Test
    fun parseMessageThread_extractsIncomingOutgoingAndReplyForm() {
        val threadHtml = """
            <html><body>
                <h1 id="title"><a href="/biri/ssg">ssg</a></h1>
                <ul id="message-thread">
                    <li class="incoming" data-id="m1" data-author="ssg">
                        <div class="content">selam <a class="b" href="/?q=ek%C5%9Fi+s%C3%B6zl%C3%BCk">ekşi sözlük</a></div>
                        <time>20:10</time>
                    </li>
                    <li class="outgoing" data-id="m2" data-author="benimnick">
                        <div class="content">selamlar!</div>
                        <time>20:12</time>
                    </li>
                </ul>
                <form action="/mesaj/9001" method="post">
                    <input type="hidden" name="__RequestVerificationToken" value="replyTok" />
                    <input type="hidden" name="ThreadId" value="9001" />
                    <textarea name="Message"></textarea>
                </form>
            </body></html>
        """.trimIndent()

        val detail = EksiHtmlParser.parseMessageThread(threadHtml, "/mesaj/9001", "benimnick")
        assertEquals(false, detail.requiresLogin)
        assertEquals("ssg", detail.participantNick)
        assertEquals(2, detail.messages.size)
        assertEquals(false, detail.messages[0].isOutgoing)
        assertEquals("ssg", detail.messages[0].senderNick)
        assertTrue(detail.messages[0].segments.any { it is EntrySegment.Bkz && it.displayText == "ekşi sözlük" })
        assertEquals(true, detail.messages[1].isOutgoing)
        assertEquals("selamlar!", detail.messages[1].body)
        assertEquals("replyTok", detail.replyForm?.fields?.get("__RequestVerificationToken"))
        assertEquals("9001", detail.replyForm?.fields?.get("ThreadId"))
    }
}
