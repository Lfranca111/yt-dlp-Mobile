package com.linfranca.ytdlpmobile

import org.junit.Assert.*
import org.junit.Test

class ForegroundNotificationPolicyTest {
    @Test fun lastFailedTaskRemovesForegroundRatherThanDetaching() {
        assertEquals(ForegroundNotificationPolicy.Action.REMOVE_LAST,ForegroundNotificationPolicy.removal(10,10,null))
    }
    @Test fun lastSuccessfulOrStoppedTaskUsesTheSameRemovalPolicy() {
        for(unneededPrimary in listOf<Int?>(null,10,99)) assertEquals(ForegroundNotificationPolicy.Action.REMOVE_LAST,ForegroundNotificationPolicy.removal(10,unneededPrimary,null))
    }
    @Test fun failingNonPrimaryTaskDoesNotDemoteAnotherActiveTask() {
        assertEquals(ForegroundNotificationPolicy.Action.KEEP_PRIMARY,ForegroundNotificationPolicy.removal(10,20,20))
    }
    @Test fun failingPrimaryHandsForegroundToRemainingTask() {
        assertEquals(ForegroundNotificationPolicy.Action.HAND_OVER,ForegroundNotificationPolicy.removal(20,20,10))
    }
    @Test fun missingOwnershipIsReestablishedForRemainingTask() {
        assertEquals(ForegroundNotificationPolicy.Action.HAND_OVER,ForegroundNotificationPolicy.removal(20,null,10))
    }
}
