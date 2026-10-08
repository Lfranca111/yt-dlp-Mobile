package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandTokenizerTest {
    @Test fun parsesQuotedArguments() {
        assertEquals(
            listOf("--format", "bv*[height<=1080]+ba/b", "--embed-metadata"),
            CommandTokenizer.parse("--format \"bv*[height<=1080]+ba/b\" --embed-metadata")
        )
    }

    @Test fun acceptsOptionalCommandPrefix() {
        assertEquals(listOf("-x", "--audio-format", "mp3"), CommandTokenizer.parse("yt-dlp -x --audio-format mp3"))
    }

    @Test fun blocksManagedOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            CommandTokenizer.parse("-o /sdcard/example.mp4")
        }
    }

    @Test fun blocksUrlInCustomArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            CommandTokenizer.parse("--embed-metadata https://example.com/video")
        }
    }

    @Test fun explainsMissingOptionValue() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CommandTokenizer.parse("--extractor-args")
        }
        assertEquals("--extractor-args needs a value.", error.message)
    }

    @Test fun redirectsCookiesToImporter() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CommandTokenizer.parse("--cookies")
        }
        assertEquals("Use the app's Import cookies.txt button instead of --cookies.", error.message)
    }
}
