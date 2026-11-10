package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
public class TransportStreamTimingTest {
    static byte[] packet(int id,long pts){
        byte[] p=new byte[188]; p[0]=0x47;p[1]=0x40;p[3]=0x10;p[6]=1;p[7]=(byte)id;p[11]=(byte)0x80;p[12]=5;
        p[13]=(byte)(0x21|((pts>>29)&14));p[14]=(byte)(pts>>22);p[15]=(byte)(1|((pts>>14)&254));p[16]=(byte)(pts>>7);p[17]=(byte)(1|((pts<<1)&254));return p;
    }
    @Test public void measuresPacketBoundsSeparatelyAndRetainsOffset() throws Exception {
        var f=Files.createTempFile("clock", ".ts");
        try(var out=Files.newOutputStream(f)) {out.write(packet(224,127920));out.write(packet(192,126000));out.write(packet(224,1027920));out.write(packet(192,1206000));}
        try {
            var t=TransportStreamTiming.inspect(f.toFile());
            assertEquals(10,t.video.spanSeconds(),.0001);assertEquals(12,t.audio.spanSeconds(),.0001);
            assertEquals(.021333,t.video.firstSeconds()-t.audio.firstSeconds(),.00001);assertTrue(t.complete);
        } finally {Files.delete(f);}
    }
}
