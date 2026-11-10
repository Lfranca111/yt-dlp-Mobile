package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
class CollectionExpandedRoutesTest {
    @Test fun recognizesEveryRecordedTargetLinkAndKeepsNonCollectionsSeparate() {
        val lines=javaClass.getResourceAsStream("/recorded-collection-links.txt")!!.bufferedReader().readLines()
        assertTrue(lines.size > 150)
        lines.forEach { url -> assertNotNull(url,CollectionLinkRecognizer.recognize(url)) }
        assertEquals("Account page",CollectionLinkRecognizer.recognize("https://honeytoon.com/mylibrary")?.pageType)
        assertEquals("Non-collection page",CollectionLinkRecognizer.recognize("https://nhentai.net/community/taxonomy/")?.pageType)
    }
    @Test fun deferredRoutesAreListingsAndSpecificMangaRemainsAComic() {
        listOf("https://multporn.net/authors_hentai_comics/example", "https://multporn.net/manga/example", "https://multporn.net/category_hentai/example", "https://tailspace.com/browse?tag=5").forEach {
            assertEquals(it,"Comic listing",CollectionLinkRecognizer.recognize(it)?.pageType)
            assertTrue(CollectionRoute.from(it).title.isNotBlank())
        }
        assertEquals("Album listing",CollectionLinkRecognizer.recognize("https://luscious.net/albums/list/?tagged=%2Bfull_color&page=1")?.pageType)
        assertEquals("Comic",CollectionLinkRecognizer.recognize("https://multporn.net/hentai_manga/example")?.pageType)
    }
    @Test fun explicitHostsDoNotTrustSubstringsOrEmbeddedCredentials() {
        assertNull(CollectionLinkRecognizer.recognize("https://nhentai.net.attacker.example/g/1/"))
        assertNull(CollectionLinkRecognizer.recognize("https://name:secret@honeytoon.com/comic/example"))
    }
}
