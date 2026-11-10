package com.linfranca.ytdlpmobile

import org.json.*
import org.junit.Assert.*
import org.junit.Test

class Version116DiscoveryTest {
    private val base="https://example.com/creator/alex"
    private fun recipe()=JSONObject().put("schema",3).put("id","s").put("name","Creator").put("url",base).put("mode","uploads")
        .put("discovery","content").put("linkSelector","main").put("pathPrefix","/alex/posts/").put("pathPattern","/alex/posts/*")
    private fun parse(html:String,j:JSONObject=recipe())=CustomSources.parse(j,html,false)
    @Test fun ordinaryLinksStillWork() { assertEquals(2,parse("<main><a href='/alex/posts/1'>First</a><a href='/alex/posts/2'>Second</a></main>").items.size) }
    @Test fun cardMetadataComesFromContentNotTimestamp() {
        val r=parse("<main><article><p>Example content</p><span itemprop=author>Alex</span><a href='/alex/posts/1'><time datetime='2026-10-24'>9 hours ago</time></a><video poster='/cover.jpg'></video></article></main>")
        assertTrue(r.items.single().title.contains("Example content"));assertEquals("https://example.com/cover.jpg",r.items.single().thumbnail)
    }
    @Test fun cardAuxiliaryRoutesDoNotBecomeSeparatePosts() {
        val r=parse("<main><article><a href='/alex'>Profile</a><a href='/alex/posts/1'>Post</a><a href='/alex/posts/1/analytics'>Views</a><a href='/alex/posts/1/quotes'>Quotes</a></article></main>")
        assertEquals(1,r.items.size);assertEquals("https://example.com/alex/posts/1",r.items.single().url)
    }
    @Test fun navigationOutsideContentIsExcluded() {
        val r=parse("<nav><a href='/alex/posts/9'>Navigation</a></nav><main><a href='/alex/posts/1'>One</a></main>")
        assertEquals(1,r.items.size)
    }
    @Test fun attributeBasedClickableCardsWork() {
        for(attr in listOf("data-href","data-url","data-permalink","itemid")) {
            val r=parse("<main><div $attr='/alex/posts/1'><h3>Card title</h3></div></main>")
            assertEquals("https://example.com/alex/posts/1",r.items.single().url)
        }
    }
    @Test fun idOnlyCardsUseVerifiedTemplate() {
        val j=recipe().put("idAttribute","data-post-id").put("urlTemplate","https://example.com/alex/posts/{id}")
        assertEquals("https://example.com/alex/posts/123",parse("<main><div data-post-id='123'><h3>Example</h3></div></main>",j).items.single().url)
    }
    @Test fun twoDomIdsInferReusableTemplate() {
        val m=CustomContentDiscovery.inferMapping(listOf("https://example.com/alex/posts/123","https://example.com/alex/posts/456"),"<div data-post-id=123></div><div data-post-id=456></div>")!!
        assertEquals("data-post-id",m.attribute);assertEquals("https://example.com/alex/posts/{id}",m.template)
    }
    @Test fun oneObservedIdCannotInventTemplate() {
        assertNull(CustomContentDiscovery.inferMapping(listOf("https://example.com/alex/posts/123","https://example.com/alex/posts/456"),"<div data-post-id=123></div>"))
    }
    @Test fun observedJsonIdsCanInferTemplateWithoutDomLinks() {
        val m=CustomContentDiscovery.inferMapping(listOf("https://example.com/alex/posts/123","https://example.com/alex/posts/456"),"<script type='application/json'>[{\"id\":\"123\",\"title\":\"First\"},{\"id\":\"456\",\"title\":\"Second\"}]</script>")!!
        assertEquals("",m.attribute);assertTrue(m.template.contains("{id}"))
    }
    @Test fun jsonLdAndObservedRecordsUseSameIdentities() {
        val j=recipe().put("linkSelector","body")
        val r=parse("<script type='application/ld+json'>{\"@type\":\"VideoObject\",\"url\":\"https://example.com/alex/posts/1\",\"name\":\"Video\"}</script><script type='application/json'>[{\"url\":\"https://example.com/alex/posts/1\",\"title\":\"Duplicate\"},{\"url\":\"https://example.com/alex/posts/2\",\"video\":true}]</script>",j)
        assertEquals(2,r.items.size)
    }
    @Test fun structuredDiscoveryCanBeDisabled() {
        val j=recipe().put("structured",false)
        val html="<main><a href='/alex/posts/1'>One</a></main><script type='application/json'>{\"url\":\"https://example.com/alex/posts/2\"}</script>"
        assertEquals(1,parse(html,j).items.size)
    }
    @Test fun explicitVideoFilterExcludesUnknownAndFalseEvidence() {
        val j=recipe().put("videoOnly",true)
        val r=parse("<main><article><a href='/alex/posts/1'>Video</a><video></video></article><article><a href='/alex/posts/2'>Unknown</a><img src='/photo.jpg'></article></main><script type='application/json'>[{\"url\":\"https://example.com/alex/posts/3\",\"video\":false}]</script>",j)
        assertEquals(1,r.items.size);assertTrue(r.items.single().url.endsWith("/1"))
    }
    @Test fun duplicateTrackingLinksCollapseToOneItem() {
        assertEquals(1,parse("<main><a href='/alex/posts/1?si=a'>One</a><a href='/alex/posts/1?si=b#player'>Same</a></main>").items.size)
    }
    @Test fun pastedLinksDeduplicateAndInferStructure() {
        val r=CustomContentDiscovery.examples("https://example.com/alex/posts/1?si=a\nhttps://example.com/alex/posts/1?si=b\nhttps://example.com/alex/posts/2",base)
        assertEquals(2,r.size)
    }
    @Test(expected=IllegalArgumentException::class) fun duplicateItemIsNotTwoExamples() { CustomContentDiscovery.examples("https://example.com/alex/posts/1?si=a\nhttps://example.com/alex/posts/1?si=b",base) }
    @Test fun unsafeOrTemporaryAddressesAreNotStableItems() {
        for(raw in listOf("https://evil.example/alex/posts/1","https://u:p@example.com/alex/posts/1","https://example.com/alex/posts/1?token=secret","https://example.com/video.mp4","javascript:alert(1)","blob:https://example.com/id")) assertNull(raw,CustomContentDiscovery.stable(raw,base))
    }
    @Test fun idTemplateRejectsInjection() {
        assertNull(CustomContentDiscovery.resolveId("../other?token=secret","https://example.com/alex/posts/{id}",base))
        assertNull(CustomContentDiscovery.resolveId("123","https://evil.example/posts/{id}",base))
    }
    @Test fun schemaThreeRoundTripsWithoutTrustedVerification() {
        val j=recipe().put("examples",JSONArray(listOf("https://example.com/alex/posts/1","https://example.com/alex/posts/2"))).put("testedAt",123)
        CustomSources.validate(j);val imported=CustomSources.imported(j.toString())
        assertEquals(3,imported.getInt("schema"));assertEquals(0,imported.getLong("testedAt"));assertEquals(2,imported.getJSONArray("examples").length())
    }
    @Test(expected=IllegalArgumentException::class) fun importedExecutableDiscoveryIsRejected() { CustomSources.validate(recipe().put("script","alert(1)")) }
    @Test(expected=IllegalArgumentException::class) fun invalidAttributeTemplateIsRejected() { CustomSources.validate(recipe().put("idAttribute","onclick").put("urlTemplate","https://example.com/alex/posts/{id}")) }
    @Test fun oldSchemaStillUsesItsOriginalParser() {
        val j=recipe().put("schema",2).put("discovery","links").put("linkSelector","main a[href]")
        assertEquals(1,parse("<main><a href='/alex/posts/1'>One</a></main>",j).items.size)
    }
    @Test(expected=IllegalStateException::class) fun noResolvableAddressCannotEstablishBaseline() { parse("<main><button onclick='openSomething()'>Play</button></main>") }
    @Test fun nonExecutableJsonOnly() {
        val r=parse("<main><a href='/alex/posts/1'>One</a></main><script>window.data={url:'https://example.com/alex/posts/2'};</script>")
        assertEquals(1,r.items.size)
    }
    @Test fun queryBasedIdsCanBeLearnedWithoutSiteNames() {
        val values=CustomContentDiscovery.examples("https://example.com/watch?item=123\nhttps://example.com/watch?item=456",base)
        val m=CustomContentDiscovery.inferMapping(values,"<div data-item-id='123'></div><div data-item-id='456'></div>")!!
        assertEquals("https://example.com/watch?item={id}",m.template)
    }
    @Test fun largeStringIdentifiersKeepExactDigits() {
        val j=recipe().put("urlTemplate","https://example.com/alex/posts/{id}").put("jsonRecordKind","title")
        val r=parse("<script type='application/json'>[{\"id_str\":\"2111670015791403215\",\"title\":\"Item\"}]</script>",j)
        assertEquals("https://example.com/alex/posts/2111670015791403215",r.items.single().url)
    }
    @Test fun authorAndImageMetadataDoNotChangeItemIdentity() {
        val html="<main><article><span rel=author>Alex</span><a href='/alex/posts/1'>Post</a><video poster='/one.jpg'></video></article></main>"
        val a=parse(html);val b=parse(html.replace("Alex","Creator").replace("one.jpg","two.jpg"))
        assertEquals(a.items.single().id,b.items.single().id)
    }
    @Test fun stableAddressNeverUsesThumbnailPosition() {
        val first="<article data-url='/alex/posts/1'>First</article>";val second="<article data-url='/alex/posts/2'>Second</article>"
        assertEquals(parse("<main>"+first+second+"</main>").items.map { it.id }.toSet(),parse("<main>"+second+first+"</main>").items.map { it.id }.toSet())
    }
    @Test fun learnedJsonShapeExcludesUnrelatedProfileIds() {
        val j=recipe().put("urlTemplate","https://example.com/alex/posts/{id}").put("jsonRecordKind","title")
        val html="<script type='application/json'>[{\"id\":\"1\",\"title\":\"Post\"},{\"id\":\"2\",\"name\":\"Profile\"}]</script>"
        assertEquals(listOf("https://example.com/alex/posts/1"),parse(html,j).items.map { it.url })
    }
    @Test fun existingCanonicalMobileIdentityRemainsValidAsAnExample() {
        val mobile="https://m.youtube.com/@creator/videos"
        assertEquals("https://www.youtube.com/watch?v=one",CustomContentDiscovery.stable("https://www.youtube.com/watch?v=one",mobile))
        assertEquals(2,CustomContentDiscovery.examples("https://m.youtube.com/watch?v=one\nhttps://www.youtube.com/watch?v=two",mobile).size)
    }
    @Test fun publicationEvidenceSurvivesGenericCardDiscovery() {
        val r=parse("<main><article><a href='/alex/posts/1'>Post</a><time datetime='2026-10-24T12:00:00Z'></time></article></main>")
        assertEquals(java.time.Instant.parse("2026-10-24T12:00:00Z").toEpochMilli(),r.items.single().publishedAt)
    }
    @Test fun publicationEpochSecondsFromStructuredRecordsAreNormalized() {
        val r=parse("<script type='application/json'>{\"url\":\"https://example.com/alex/posts/1\",\"timestamp\":1700000000}</script>")
        assertEquals(1700000000000L,r.items.single().publishedAt);assertEquals(0L,CustomContentDiscovery.publication("yesterday"))
    }
}
