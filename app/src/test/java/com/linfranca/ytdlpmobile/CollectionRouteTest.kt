package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CollectionRouteTest {
    @Test fun searchTermsComeFromPastedLink() {
        val e6 = CollectionRoute.from("https://e621.net/posts?tags=fox+blue_eyes")
        assertEquals("fox blue_eyes", e6.query["tags"])
        assertEquals("fox blue_eyes", e6.title)

        val rule34 = CollectionRoute.from("https://rule34.xxx/index.php?page=post&s=list&tags=foo%20bar")
        assertEquals("foo bar", rule34.query["tags"])
        assertEquals("Rule34", rule34.site)
    }

    @Test fun comicAndAlbumRoutesRetainOrderingName() {
        assertEquals("Howl & Jasper",
            CollectionRoute.from("https://yiffer.xyz/Howl%20%26%20Jasper").title)
        assertEquals("bifurcation-ongoing_437722",
            CollectionRoute.from("https://www.luscious.net/albums/bifurcation-ongoing_437722").title)
    }

    @Test fun unsupportedMediaAndHomepageAreRejectedBeforeStartingAJob() {
        assertThrows(IllegalArgumentException::class.java) {
            CollectionRoute.from("https://multporn.net/video/example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CollectionRoute.from("https://e621.net/")
        }
    }

    @Test fun listingAndTailspaceLinksAreRecognized() {
        assertEquals("Tag results", CollectionLinkRecognizer.recognize("https://furbooru.org/tags/nipples")?.pageType)
        for (kind in listOf("category", "characters", "category_hentai", "characters_hentai")) {
            val url = "https://multporn.net/$kind/example"
            assertEquals("Comic listing", CollectionLinkRecognizer.recognize(url)?.pageType)
            assertEquals("Multporn", CollectionRoute.from(url).site)
        }
        assertEquals("Naked Penny - Chapter 2", CollectionRoute.from(
            "https://tailspace.com/c/Naked%20Penny%20-%20Chapter%202").title)
        assertEquals("Artist post", CollectionLinkRecognizer.recognize(
            "https://tailspace.com/artist/Call_me_INK/post/232")?.pageType)
    }

    @Test fun lusciousPictureRouteUsesAlbumSlug() {
        val route = CollectionRoute.from("https://www.luscious.net/pictures/album/twitter-best-off-hentai-album_538089/id/62740171/@1000063925")
        assertEquals("Luscious", route.site)
        assertEquals("twitter-best-off-hentai-album_538089", route.parts[2])
    }
}
