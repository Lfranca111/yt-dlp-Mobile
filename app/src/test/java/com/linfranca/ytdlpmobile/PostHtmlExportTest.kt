package com.linfranca.ytdlpmobile

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class PostHtmlExportTest {
    @Test fun htmlKeepsPostFormattingAndLinksWithoutActivePageContent() {
        val url = "https://example.org/posts/seeds"
        val post = requireNotNull(PostTextContent.parse(url, url, "Garden", """
            <article>
                <h1>&lt;Seed notes&gt; &amp; café</h1>
                <section itemprop="articleBody">
                    <p onclick="bad()">Try <strong>peas</strong>.</p>
                    <p><a href="/guide">Guide</a> <a href="javascript:bad()">Bad link</a></p>
                    <ul><li>Water daily</li></ul>
                    <img src="https://example.org/pixel" onerror="bad()">
                    <script>bad()</script><iframe src="https://example.org/widget"></iframe>
                    <div class="comments">Not part of the post.</div>
                </section>
            </article>
        """))
        val output = Jsoup.parse(post.html())
        assertEquals("<Seed notes> & café", output.title())
        assertEquals("peas", output.selectFirst("strong")?.text())
        assertEquals("Water daily", output.selectFirst("li")?.text())
        assertEquals(listOf("https://example.org/guide"), output.select("a[href]").eachAttr("href"))
        assertFalse(post.html().contains("Not part of the post."))
        assertFalse(post.html().contains("bad()", ignoreCase = true))
        assertEquals(0, output.select("script, iframe, img, [onclick], [onerror]").size)
    }

    @Test fun redditShareLinkCanSaveTheSamePostInBothFormats() {
        val post = requireNotNull(PostTextContent.parse(
            "https://www.reddit.com/r/gardening/s/sample",
            "https://www.reddit.com/r/gardening/comments/abc123/spring_garden/",
            "Reddit",
            """
            <shreddit-post id="t3_abc123" post-title="Spring garden">
                <div slot="text-body"><p>Plant peas.</p><p>Water them daily.</p></div>
            </shreddit-post>
            <shreddit-comment>Not the post.</shreddit-comment>
            """
        ))
        assertEquals("Spring garden\n\nPlant peas.\n\nWater them daily.\n", post.text())
        assertNotNull(Jsoup.parse(post.html()).selectFirst("article h1"))
        assertEquals(listOf("Plant peas.", "Water them daily."), Jsoup.parse(post.html()).select("article p").eachText())
    }
}
