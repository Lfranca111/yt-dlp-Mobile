package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class PostClickableBodyTest {
    @Test fun clickablePostBodyIsKeptButNestedQuoteIsExcluded() {
        val url = "https://x.com/example/status/42"
        val post = requireNotNull(PostTextContent.parse(url, url, "X", """
            <article data-post-export-x="42">
              <div role="link">
                <div data-testid="tweetText">Main text<p>Second paragraph</p>
                  <div role="link">Quoted text</div>
                </div>
              </div>
            </article>
        """))
        assertTrue(post.body.startsWith("Main text"))
        assertTrue(post.body.contains("Second paragraph"))
        assertFalse(post.body.contains("Quoted text"))
    }

    @Test fun publicArticleInDialogStillHasReadableBody() {
        val url = "https://example.org/posts/test"
        val post = requireNotNull(PostTextContent.parse(url, url, "Public post", """
            <div role="dialog"><article><h1>Public post</h1>
              <div class="post-content"><p>Readable public text</p></div>
            </article></div>
        """))
        assertEquals("Readable public text", post.body)
    }
}
