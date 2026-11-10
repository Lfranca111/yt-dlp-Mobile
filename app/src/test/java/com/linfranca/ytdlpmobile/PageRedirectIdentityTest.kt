package com.linfranca.ytdlpmobile

import org.junit.Test
import org.junit.Assert.*

class PageRedirectIdentityTest {
    @Test fun uploadAndLiveIdsKeepOriginalAuthorityWhileTargetUrlIsUsable() {
        val old="https://old.example/creator";val target="https://new.example/creator";val item="https://new.example/video/123"
        assertEquals("custom:https://old.example/video/123",PageRedirectIdentity.stableId("custom:$item",old,target))
        assertEquals(CustomLivePolicy.identity("https://old.example/video/123"),PageRedirectIdentity.stableId(CustomLivePolicy.identity(item),old,target,item))
        assertEquals("https://new.example/video/123",item)
    }
    @Test fun opaqueOrExternalMediaIdentityIsNotRewritten() {
        assertEquals("video-123",PageRedirectIdentity.stableId("video-123","https://old.example/x","https://new.example/x"))
        assertEquals("custom:https://other.example/video/1",PageRedirectIdentity.stableId("custom:https://other.example/video/1","https://old.example/x","https://new.example/x"))
    }
}
