package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class PostManualFallbackTest {
    @Test fun manualTextIsPreservedAndHtmlEscaped() {
        val post = requireNotNull(PostTextContent.fromSelectedText("Post", "First line\n\n<script>example</script> & café"))
        assertEquals("First line\n\n<script>example</script> & café", post.body)
        assertFalse(post.html().contains("<script>"))
        assertTrue(post.html().contains("&lt;script&gt;"))
        assertTrue(post.html().contains("<br><br>"))
    }
    @Test fun emptyOrOversizedSelectionIsRejected() {
        assertNull(PostTextContent.fromSelectedText("Post", "   "))
        assertNull(PostTextContent.fromSelectedText("Post", "x".repeat(300001)))
    }
}
