package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostTextContentTest {
    private val share = "https://www.reddit.com/r/gardening/s/sample"
    private val permalink = "https://www.reddit.com/r/gardening/comments/abc123/spring_garden/"
    private val redditPage = """
        <shreddit-post id="t3_else" post-title="Suggested post"><div slot="text-body">Other text</div></shreddit-post>
        <shreddit-post id="t3_abc123" post-title="Spring garden">
          <div slot="text-body">
            <p>The peas are <em>sprouting</em>.</p><p>Next: café roses.<br>Water daily.</p>
            <div style="display: none">Hidden</div><div class="comments">Comment thread</div>
          </div>
        </shreddit-post>
        <shreddit-comment>Unrelated reply</shreddit-comment>
    """.trimIndent()

    @Test fun shareLinkUsesOnlyTheMatchingPostAndKeepsParagraphs() {
        val post = PostTextContent.parse(share, permalink, "Reddit", redditPage)
        assertEquals("Spring garden\n\nThe peas are sprouting.\n\nNext: café roses.\nWater daily.\n", post?.text())
    }

    @Test fun redditRejectsDifferentPostsAndNeverExportsACommentAsTheBody() {
        assertNull(PostTextContent.parse(permalink, permalink.replace("abc123", "else"), "Reddit", redditPage))
        assertNull(PostTextContent.parse(share, permalink, "Reddit", """
            <shreddit-post id="t3_abc123"><video></video><shreddit-comment>
                <div slot="text-body">Not the post</div>
            </shreddit-comment></shreddit-post>
        """))
        assertNull(PostTextContent.parse(share, permalink, "Reddit", """
            <shreddit-post id="t3_abc123"><div slot="text-body">[removed]</div></shreddit-post>
        """))
        assertNull(PostTextContent.parse(share, "https://www.reddit.com/login/", "Reddit", redditPage))
    }

    @Test fun legacyRedditPostBodyIsRecognized() {
        assertEquals("Seed notes\n\nOlder layout.\n", PostTextContent.parse(permalink, permalink, "Reddit", """
            <div class="thing link" data-fullname="t3_abc123"><a class="title">Seed notes</a>
              <div class="expando"><div class="usertext-body"><div class="md"><p>Older layout.</p></div></div></div>
            </div>
        """)?.text())
    }

    @Test fun articleUsesItsBodyAndOmitsSiteChrome() {
        val url = "https://example.org/posts/gardening"
        assertEquals("Garden notes\n\nPlant care notes.\n\nSecond paragraph.\n", PostTextContent.parse(url, url, "Website", """
            <article><h1 itemprop="headline">Garden notes</h1>
                <nav>Menu</nav><section itemprop="articleBody">
                    <p>Plant care notes.</p><p>Second paragraph.</p><aside>Recommendations</aside>
                </section><footer>Footer</footer>
            </article>
        """)?.text())
    }

    @Test fun forumTopicUsesOnlyTheOpeningPost() {
        val url = "https://example.org/t/seed-exchange/123"
        assertEquals("Seed exchange\n\nOpening message.\n", PostTextContent.parse(url, url, "Seed exchange", """
            <article id="post_1"><div class="cooked"><p>Opening message.</p></div></article>
            <article id="post_2"><div class="cooked"><p>Reply.</p></div></article>
        """)?.text())
    }

    @Test fun generalPagesDoNotExportFeedsOrAuthenticationScreens() {
        val url = "https://example.org/posts"
        val html = "<article><h1>Notes</h1><p>First item.</p></article>"
        assertNull(PostTextContent.parse(url, "https://example.org/", "Home", html))
        assertNull(PostTextContent.parse(url, "https://example.org/login/", "Sign in", html))
        assertNull(PostTextContent.parse(url, url, "Feed", html + html))
    }
}
