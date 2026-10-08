package com.linfranca.ytdlpmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CumulativeEtaEstimatorTest {
    @Test
    fun calculatesEtaFromCumulativeObservedProgress() {
        val estimator = CumulativeEtaEstimator()
        assertNull(estimator.update(10f, 1_000L))
        assertEquals(70L, estimator.update(30f, 21_000L))
        assertEquals(60L, estimator.update(40f, 31_000L))
    }

    @Test
    fun waitsForEnoughDataAndResetsForANewDownloadPhase() {
        val estimator = CumulativeEtaEstimator()
        assertNull(estimator.update(10f, 1_000L))
        assertNull(estimator.update(10.1f, 4_000L))
        assertNull(estimator.update(2f, 5_000L))
        assertEquals(96L, estimator.update(4f, 7_000L))
    }
}
