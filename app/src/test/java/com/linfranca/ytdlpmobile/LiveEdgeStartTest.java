package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class LiveEdgeStartTest {
    @Test public void skipsLongAdvertisedHistoryAtTheSharedClock() {
        long[] video=new long[300], audio=new long[600];
        double[] vd=new double[300],ad=new double[600];
        for(int i=0;i<300;i++){video[i]=100000+i*2000L;vd[i]=2;}
        for(int i=0;i<600;i++){audio[i]=100006+i*1000L;ad[i]=1;}
        long anchor=LiveEdgeStart.sharedAnchor(video,vd,audio,ad,2);
        assertEquals(694000,anchor);
        assertEquals(297,LiveEdgeStart.index(video,vd,anchor,2));
        assertEquals(593,LiveEdgeStart.index(audio,ad,anchor,2));
    }
    @Test public void missingWallClockUsesTailNotBeginning() {
        assertEquals(97, LiveEdgeStart.index(new long[0],java.util.stream.IntStream.range(0,100).mapToDouble(i->2).toArray(),null,2));
    }
    @Test(expected=IllegalArgumentException.class) public void nonoverlappingTracksFailRatherThanGuessing() {
        LiveEdgeStart.sharedAnchor(new long[]{1000},new double[]{2},new long[]{5000},new double[]{2},2);
    }
    @Test public void sourceGapThatCaptureMayCompressKeepsOriginalInsteadOfMutingWrongTime() {
        var timeline = new LiveHlsRelay.AudioTimeline(1000L,1000L,
            java.util.List.of(new LiveHlsRelay.TimeGap(5000L,8000L)));
        assertNull(timeline.filter(0.0,12.0));
    }
}
