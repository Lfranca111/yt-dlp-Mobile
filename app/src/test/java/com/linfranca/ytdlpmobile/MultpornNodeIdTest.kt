package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultpornNodeIdTest {
    @Test fun canonicalHeaderBeforeShortlinkDoesNotStealNodeId() {
        val header = "<https://multporn.net/comics/redacted?r=1>; rel=canonical, " +
            "<https://multporn.net/node/38472>; rel=shortlink"
        val result = MultpornNodeId.find(header, "")
        assertEquals("38472", result.id)
        assertEquals("HTTP shortlink", result.source)
    }

    @Test fun htmlLinkWorksWithEitherAttributeOrderAndQuery() {
        val html = "<link href='https://multporn.net/node/205?view=full' rel='shortlink'>"
        assertEquals("205", MultpornNodeId.find(null, html).id)
    }

    @Test fun pageMetadataOnlyUsedForCurrentPage() {
        val html = "<a href='/node/999'>Related</a><script>{\"currentPath\":\"node/63\"}</script>"
        assertEquals("63", MultpornNodeId.find("<https://multporn.net/comics/redacted>; rel=canonical", html).id)
        assertNull(MultpornNodeId.find(null, "<a href='/node/999'>Related</a>").id)
    }

    @Test fun foreignShortlinkIsNotTrusted() {
        val html = "<link rel='shortlink' href='https://example.com/node/19'>"
        assertNull(MultpornNodeId.find(null, html).id)
    }
}
