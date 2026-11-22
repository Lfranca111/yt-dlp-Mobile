package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PostSiteSupportTest {
    @Test fun xExportDoesNotMixPostsAndKeepsParagraphs() {
        val current = "https://x.com/example/status/42"
        val body = """<article data-post-export-x="99"><div data-testid="tweetText">Reply</div></article>
            <article data-post-export-x="42"><div data-testid="tweetText"><span>First line <img alt="🌱"></span><span>

Second line</span><div role="link">Quoted text</div></div></article>"""
        val post = requireNotNull(PostTextContent.parse("https://twitter.com/example/status/42", current, "X", body))
        assertEquals("First line 🌱\n\nSecond line", post.body)
        assertFalse(post.html().contains("Quoted text"))
        assertNull(PostTextContent.parse("https://x.com/example/status/99", current, "X", body))
    }

    @Test fun genericPageCanIdentifyOneArticleAmongMultiplePosts() {
        val current = "https://example.org/posts/seed-notes"
        val html = """
            <article><a rel="bookmark" href="/posts/seed-notes">Permalink</a>
                <h1>Seed notes</h1><div class="article-body"><p>Plant peas.</p></div>
            </article>
            <article><a rel="bookmark" href="/posts/reply">Permalink</a>
                <h1>Other post</h1><div class="article-body"><p>Do not export me.</p></div>
            </article>"""
        assertEquals("Seed notes\n\nPlant peas.\n", PostTextContent.parse(current, current, "Site", html)?.text())
        assertNull(PostTextContent.parse("https://example.org/posts", "https://example.org/posts", "Feed", html))
    }
    @Test fun dedicatedSocialRoutesAlsoAcceptAnIdentifiableRenderedBody() {
        // Synthetic pages exercise the fallback on these routes; they are not live site captures.
        val pages = listOf(
            "https://bsky.app/profile/example.test/post/test" to "itemprop=articleBody",
            "https://www.instagram.com/p/test/" to "itemprop=articleBody",
            "https://www.threads.com/@example/post/test" to "itemprop=articleBody",
            "https://www.facebook.com/example/posts/42" to "data-ad-comet-preview=message",
            "https://www.tumblr.com/example/42" to "class=post-content",
            "https://mastodon.social/@example/42" to "class=status__content",
            "https://www.tiktok.com/@example/video/42" to "data-e2e=browse-video-desc",
            "https://www.pinterest.com/pin/42" to "itemprop=articleBody",
            "https://www.snapchat.com/spotlight/test" to "itemprop=articleBody"
        )
        for ((url, marker) in pages) {
            val html = "<div $marker><p>Example text.</p></div>"
            assertEquals(url, "Example text.", PostTextContent.parse(url, url, "Example", html)?.body)
            assertNull(url, PostTextContent.parse(url, url, "Example", "<article><video></video></article>"))
        }
    }

}
