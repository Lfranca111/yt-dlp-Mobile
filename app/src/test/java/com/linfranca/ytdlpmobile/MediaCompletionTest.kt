package com.linfranca.ytdlpmobile
import org.junit.Assert.*
import org.junit.Test
class MediaCompletionTest {
 @Test fun sixMinutesIsNotATwentyFiveMinuteEpisode() { assertFalse(MediaCompletion.acceptable(1497.872,360.0)) }
 @Test fun fullDurationAllowsSmallTimestampDifferences() { assertTrue(MediaCompletion.acceptable(1497.872,1496.0)) }
 @Test fun unknownDurationIsNotVerified() { assertFalse(MediaCompletion.acceptable(1497.872,0.0)) }
}
