package com.linfranca.ytdlpmobile

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CollectionImageQualityTest {
    @Test fun knownVariantFailuresAllowPreviousImageWithoutChangingLusciousPolicy() {
        assertTrue(CollectionImageQuality.canUsePrevious("AllPornComic", 403))
        assertTrue(CollectionImageQuality.canUsePrevious("Tailspace", 410))
        assertFalse(CollectionImageQuality.canUsePrevious("Tailspace", 429))
        assertFalse(CollectionImageQuality.canUsePrevious("Luscious", 403))
        assertTrue(CollectionImageQuality.canUsePrevious("Luscious", 404))
    }

    @Test fun upgradesVariantsWithoutChangingOrderIdentityOrFallback() {
        val url = "https://allporncomic.com/porncomic/example/chapter-1/"
        val html = """<div class='reading-content'>
            <div class='page-break'><img src='/a-480.jpg' srcset='/a-1800.jpg 1800w, /a-480.jpg 480w, /a-900.jpg 900w'></div>
            <div class='page-break'><img data-src='/b-thumbnail.jpg' src='/b-original.png'></div>
            <div class='page-break'><img data-file='/c-original.jpg' src='/c-480.jpg' srcset='/c-480.jpg 480w, /c-900.jpg 900w'></div>
            </div>"""
        val items = mutableListOf<CollectionItem>()
        CollectionWebExtractor({ false }, { html }, {}) { _, batch -> items.addAll(batch) }
            .discover(CollectionRoute.from(url))
        assertEquals(listOf(1, 2, 3), items.map { it.order })
        assertEquals(listOf("a-480", "b-original", "c-original"), items.map { it.id })
        assertEquals(listOf("a-1800.jpg", "b-original.png", "c-original.jpg"), items.map { it.url.substringAfterLast('/') })
        assertEquals(listOf("https://allporncomic.com/a-480.jpg", "", ""), items.map { it.fallbackUrl })
        assertEquals(listOf("jpg", "png", "jpg"), items.map { it.extension })
    }

    @Test fun refusesUnrelatedHostsFormatsAndPictureCrops() {
        val doc = Jsoup.parse("""<div class='reading-content'><div class='page-break'><picture>
            <source media='(min-width: 800px)' srcset='/base.jpg 480w, /crop.jpg 1800w'>
            <img src='/base.jpg' srcset='/base.jpg 480w, https://elsewhere.test/big.jpg 2600w, /big.png 2400w'>
            </picture></div></div>""", "https://allporncomic.com/")
        assertEquals(listOf("https://allporncomic.com/base.jpg"), CollectionWebExtractor.pageImages("AllPornComic", doc))
    }

    @Test fun tailspaceNeverDowngradesAPageWhenAnotherReaderPageIsLoaded() {
        val base = "https://pics.tailspace.com/comics/42"
        val original = TailspaceComicImages.read("""<img class='comicPage' src='$base/one.jpg' width='1800' alt='Example, page 1'>
            <img src='$base/one-mini.jpg' width='480' alt='Example, page 1'>
            <img src='$base/two-mini.jpg' width='480' alt='Example, page 2'>""").toMutableMap()
        TailspaceComicImages.merge(original, TailspaceComicImages.read("""<img src='$base/one-mini.jpg' alt='Page 1'>
            <img class='comicPage' data-src='$base/two.jpg' src='$base/two-mini.jpg' width='1800' alt='Page 2'>"""))
        assertEquals(listOf(1, 2), original.keys.toList())
        assertEquals("$base/one.jpg", original[1]?.image?.url)
        assertEquals("$base/two.jpg", original[2]?.image?.url)
        assertFalse(original.getValue(2).isPreview)
        assertTrue(TailspaceComicImages.read("<img src='$base/three-mini.jpg' alt='Page 3'>").getValue(3).isPreview)
    }
}
