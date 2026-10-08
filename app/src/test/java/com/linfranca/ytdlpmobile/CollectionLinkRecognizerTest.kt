package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionLinkRecognizerTest {
    @Test fun identifiesAllDesktopSourcesFromPastedPages() {
        val examples = mapOf(
            "https://e621.net/posts?tags=example" to "E621",
            "https://e6ai.net/posts?tags=example" to "E6AI",
            "https://e926.net/posts?tags=example" to "E926",
            "https://furbooru.org/search?q=example" to "Furbooru",
            "https://rule34.xxx/index.php?page=post&s=list&tags=example" to "Rule34",
            "https://multporn.net/comics/double_trouble_18" to "Multporn",
            "https://yiffer.xyz/Howl%20%26%20Jasper" to "Yiffer",
            "https://www.luscious.net/albums/bifurcation-ongoing_437722" to "Luscious"
        )
        examples.forEach { (url, site) -> assertEquals(url, site, CollectionLinkRecognizer.recognize(url)?.site) }
    }

    @Test fun recognizesPageTypesWithoutDownloading() {
        assertEquals("Search results (images and videos)",
            CollectionLinkRecognizer.recognize("https://rule34.xxx/index.php?page=post&s=list&tags=foo+bar")?.pageType)
        assertEquals("Album", CollectionLinkRecognizer.recognize(
            "https://luscious.net/albums/name_123")?.pageType)
        assertEquals("Video page", CollectionLinkRecognizer.recognize(
            "https://multporn.net/video/title")?.pageType)
    }

    @Test fun rejectsImpostorHostsAndUnsafeSchemes() {
        assertNull(CollectionLinkRecognizer.recognize("https://rule34.xxx.attacker.example/post?tags=x"))
        assertNull(CollectionLinkRecognizer.recognize("file:///rule34.xxx/index.php"))
        assertNull(CollectionLinkRecognizer.recognize("https://rule34.xxx@attacker.example/post?tags=x"))
        assertNull(CollectionLinkRecognizer.recognize("https://person:secret@rule34.xxx/index.php?tags=x"))
    }
}
