package com.linfranca.ytdlpmobile

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class WebsiteMetadataTest {
    private val json="""{"id":"fixture","title":"Episode","formats":[{"url":"https://media.example/master.m3u8"}]}"""
    @Test fun parsesCleanMetadataAndValidatesPlayableFormats() {
        assertEquals("fixture",WebsiteMetadata.parse(json).getString("id"))
        assertEquals("fixture",WebsiteMetadata.parse("\uFEFF$json").getString("id"))
        assertEquals("fixture",WebsiteMetadata.parse(JSONObject(json).toString(2)).getString("id"))
    }
    @Test fun incidentalExtractorStdoutCannotHideValidSingleJsonLine() {
        assertEquals("fixture",WebsiteMetadata.parse("[generic] Downloading manifest\n$json\n").getString("id"))
        assertEquals("fixture",WebsiteMetadata.parse("WARNING: fixture diagnostic\n$json\n").getString("id"))
    }
    @Test fun refusesEmptyOrMalformedOrAmbiguousMetadata() {
        for(raw in listOf("", "{}", "WARNING: no JSON", "<html>Blocked</html>", "$json\n$json", "{\"url\":\"file:///private/data\"}")) {
            try { WebsiteMetadata.parse(raw);fail("Invalid/ambiguous metadata accepted") }
            catch(_: IllegalStateException) { }
        }
    }
    @Test fun logsOnlyOutputClassificationRatherThanResponseValues() {
        assertEquals("empty",WebsiteMetadata.outputKind(""))
        assertEquals("diagnostic prefix",WebsiteMetadata.outputKind("WARNING: https://private.example/?token=private"))
        assertEquals("JSON object candidate",WebsiteMetadata.outputKind(json))
    }
    @Test fun regressionWarningOnStderrDoesNotCorruptMetadataStdout() {
        val java=File(System.getProperty("java.home"),"bin/java").absolutePath
        fun launch(merge: Boolean)=ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),WarningFixture::class.java.name)
            .redirectErrorStream(merge).start()
        val old=launch(true)
        val mixed=old.inputStream.bufferedReader().use { it.readText() }
        assertTrue(old.waitFor(5,TimeUnit.SECONDS));assertEquals(0,old.exitValue())
        try { JSONObject(mixed);fail("The old whole-output parser should reject a leading warning") }
        catch(_: JSONException) { }
        val fixed=launch(false)
        val stdout=fixed.inputStream.bufferedReader().use { it.readText() }
        val diagnostics=fixed.errorStream.bufferedReader().use { it.readText() }
        assertTrue(fixed.waitFor(5,TimeUnit.SECONDS));assertEquals(0,fixed.exitValue())
        assertTrue(diagnostics.startsWith("WARNING:"));assertFalse(stdout.contains("WARNING:"))
        assertEquals("fixture",WebsiteMetadata.parse(stdout).getString("id"))
    }
    class WarningFixture {
        companion object {
            @JvmStatic fun main(args: Array<String>) {
                System.err.println("WARNING: fixture warning before metadata")
                System.err.flush()
                System.out.println("""{"id":"fixture","formats":[{"url":"https://media.example/master.m3u8"}]}""")
            }
        }
    }
}
