package com.linfranca.ytdlpmobile

import org.jsoup.Jsoup
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class CollectionWebExtractorTest {
    @Test fun chapterImagesExcludeCoverRecommendationsAndThumbnailAds() {
        val doc = Jsoup.parse("""<img src="/cover.jpg"><div class="reading-content">
            <div class="page-break"><img data-src="https://cdn.example/pages/001.jpg"></div>
            <div class="page-break"><img data-src="https://cdn.example/pages/002.jpg"></div>
            <div class="page-break"><img src="https://cdn.example/banner.jpg"></div></div>
            <aside><img src="https://cdn.example/recommend.jpg"></aside>""", "https://allporncomic.com/porncomic/book/chapter-1/")
        assertEquals(listOf("https://cdn.example/pages/001.jpg", "https://cdn.example/pages/002.jpg"),
            CollectionWebExtractor.pageImages("AllPornComic",doc))
    }
    @Test fun paginationPreservesEncodedFiltersButRejectsOtherPathsAndHosts() {
        val u=URI("https://www.luscious.net/albums/list/?tagged=%2Bfull_color&audience_ids=%2B1%2B10&page=1")
        val doc=Jsoup.parse("<a rel=next href='?page=2'>Next</a>",u.toString())
        assertEquals("https://www.luscious.net/albums/list/?tagged=%2Bfull_color&audience_ids=%2B1%2B10&page=2",CollectionWebExtractor.nextPage(doc,u))
        assertNull(CollectionWebExtractor.nextPage(Jsoup.parse("<a rel=next href='https://ads.example/?page=2'>Next</a>",u.toString()),u))
    }
    @Test fun directComicOnlyFollowsItsOwnChapters() {
        val u=URI("https://honeytoon.com/comic/example")
        val doc=Jsoup.parse("""<a href="/comic/example/1">1</a><a href="/comic/other/1">Other</a>
            <div class="comic-list__item--lock"><a href="/comic/example/2">Locked</a></div>""",u.toString())
        assertEquals(listOf("https://honeytoon.com/comic/example/1"),CollectionWebExtractor.childLinks("Honeytoon",doc,u,false))
    }
    @Test fun readerLinksRemainInTheSameGalleryAndNumericPageOrder() {
        val u=URI("https://nhentai.net/g/123/")
        val d=Jsoup.parse("<a href='/g/123/10/'>10</a><a href='/g/123/2/'>2</a><a href='/g/999/1/'>Other</a>",u.toString())
        assertEquals(listOf("https://nhentai.net/g/123/2/", "https://nhentai.net/g/123/10/"),CollectionWebExtractor.readerLinks("nHentai",d,u))
    }
    @Test fun emptyReadersNeverSubstituteRandomPageImages() {
        val d=Jsoup.parse("<img src='https://cdn.example/random.jpg'>", "https://hentaifox.com/gallery/1/")
        assertTrue(CollectionWebExtractor.pageImages("HentaiFox",d).isEmpty())
    }
    @Test fun galleryFormatTableProducesOrderedOriginalsNotDisplayedThumbs() {
        val doc=Jsoup.parse("""<div class="inner_thumb"><img data-src="https://cdn.example/gallery/100/1t.jpg"></div>
            <script>var g_th = $.parseJSON('{"1":"j,100,200","2":"p,100,200"}');</script>""", "https://hentaifox.com/gallery/100/")
        assertEquals(listOf("https://cdn.example/gallery/100/1.jpg", "https://cdn.example/gallery/100/2.png"),CollectionWebExtractor.galleryRegistry(doc))
    }
    @Test fun chapterTraversalUsesExplicitChapterNumbersAndSeparateFolders() {
        val root="https://allporncomic.com/porncomic/example/"
        val docs=mapOf(root to "<a href='chapter-2/'>Chapter 2</a><a href='chapter-1/'>Chapter 1</a>",
            root+"chapter-1/" to "<div class='reading-content'><div class='page-break'><img data-src='https://cdn.example/ch1/1.jpg'></div></div>",
            root+"chapter-2/" to "<div class='reading-content'><div class='page-break'><img data-src='https://cdn.example/ch2/1.jpg'></div></div>")
        val items=mutableListOf<CollectionItem>()
        CollectionWebExtractor({false},{docs[it] ?: error("Unexpected request: $it")},{}) { _, batch -> items.addAll(batch) }
            .discover(CollectionRoute.from(root))
        assertEquals(listOf("https://cdn.example/ch1/1.jpg", "https://cdn.example/ch2/1.jpg"),items.map { it.url })
        assertEquals(2,items.map { it.folder }.distinct().size)
        assertEquals(listOf(1,1),items.map { it.order })
    }
}
