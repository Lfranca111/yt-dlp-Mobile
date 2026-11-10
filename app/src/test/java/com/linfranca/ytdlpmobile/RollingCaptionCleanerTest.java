package com.linfranca.ytdlpmobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class RollingCaptionCleanerTest {
    @Test public void stripsCarriedTextAndTenMillisecondRepeats() {
        String original = "WEBVTT\nKind: captions\nLanguage: en\n\n"
                + "00:00:00.000 --> 00:00:03.350 align:start\n"
                + "Probably<00:00:00.720><c> the</c><00:00:00.920><c> funniest</c> part of the entire\n\n"
                + "00:00:03.350 --> 00:00:03.360 align:start\nProbably the funniest part of the entire\n\n"
                + "00:00:03.360 --> 00:00:04.750 align:start\nProbably the funniest part of the entire\n"
                + "trip<00:00:03.640><c> though</c>\n\n"
                + "00:00:04.750 --> 00:00:04.760 align:start\ntrip though\n\n"
                + "00:00:04.760 --> 00:00:08.070 align:start\ntrip though\n"
                + "was<00:00:05.200><c> our</c> meal at the restaurant after\n\n"
                + "00:00:08.070 --> 00:00:08.080 align:start\nwas our meal at the restaurant after\n\n"
                + "00:00:08.080 --> 00:00:10.150 align:start\nwas our meal at the restaurant after\n"
                + "DJI.<00:00:09.120><c> So,</c> this is halfway through the\n\n";
        RollingCaptionCleaner.Result result = RollingCaptionCleaner.cleanText(original);
        assertTrue(result.changed);
        assertEquals(3, result.removed);
        assertTrue(result.text.startsWith("WEBVTT\nKind: captions\nLanguage: en\n\n"));
        assertTrue(result.text.contains("00:00:04.760 --> 00:00:08.070 align:start\nwas our meal at the restaurant after\n"));
        assertFalse(result.text.contains("00:00:03.350 -->"));
        assertEquals(1, occurrences(result.text, "Probably the funniest part of the entire"));
        assertEquals(1, occurrences(result.text, "trip though"));
        assertEquals(1, occurrences(result.text, "was our meal at the restaurant after"));
        assertFalse(result.text.contains("<c>"));
        assertFalse(result.text.contains("<00:00:"));
        assertEquals(result.text, RollingCaptionCleaner.cleanText(result.text).text);
    }

    @Test public void leavesNormalCaptionFileByteForByteUntouched() {
        String vtt = "WEBVTT\r\n\r\n00:00:00.000 --> 00:00:02.000\r\nHello!\r\n\r\n"
                + "00:00:02.000 --> 00:00:04.000\r\nHello again!\r\n";
        assertEquals(vtt, RollingCaptionCleaner.cleanText(vtt).text);
    }

    @Test public void preservesSpokenRepetitionAfterPause() {
        String vtt = "WEBVTT\n\n"
                + "00:00:00.000 --> 00:00:01.000\nOne<00:00:00.100><c> two</c> three\n\n"
                + "00:00:01.000 --> 00:00:01.010\nOne two three\n\n"
                + "00:00:01.010 --> 00:00:02.000\nOne two three\nfour<00:00:01.200><c> five</c>\n\n"
                + "00:00:04.000 --> 00:00:05.000\nFour five<00:00:04.200><c> four</c> five\n\n";
        String result = RollingCaptionCleaner.cleanText(vtt).text;
        assertTrue(result.contains("00:00:04.000 --> 00:00:05.000\nFour five four five\n"));
    }

    @Test public void keepsFreshTimedSpeechEvenWhenTheSamePhraseIsSpokenImmediatelyAgain() {
        String vtt = "WEBVTT\n\n"
                + "00:00:00.000 --> 00:00:01.000\nThank<00:00:00.200><c> you</c>\n\n"
                + "00:00:01.000 --> 00:00:01.010\nThank you\n\n"
                + "00:00:01.010 --> 00:00:02.000\nThank you\nvery<00:00:01.200><c> much</c>\n\n"
                + "00:00:02.000 --> 00:00:03.000\nvery<00:00:02.200><c> much</c>\n\n";
        RollingCaptionCleaner.Result result = RollingCaptionCleaner.cleanText(vtt);
        assertTrue(result.changed);
        assertEquals(2, occurrences(result.text, "very much"));
    }

    private static int occurrences(String text, String part) {
        int n=0, index=0;
        while((index=text.indexOf(part,index))>=0) {n++; index+=part.length();}
        return n;
    }
}
