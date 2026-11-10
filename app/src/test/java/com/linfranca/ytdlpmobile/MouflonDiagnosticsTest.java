package com.linfranca.ytdlpmobile;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class MouflonDiagnosticsTest {
    @Test public void unknownPayloadsAndUrlsNeverAppear() {
        List<String> logs=new ArrayList<>();
        MouflonDiagnostics.record("#EXTM3U\n#EXT-X-MOUFLON:NEW:secret-token\n#EXT-X-MAP:URI=\"https://example.com/init?key=secret\"\n#EXTINF:2.000,\nhttps://example.com/segment?key=secret\n",logs::add);
        String text=String.join("\n",logs);
        assertTrue(text.contains("NEW=1")); assertTrue(text.contains("line=2"));
        assertTrue(text.contains("#EXTINF:2.000,"));
        assertFalse(text.contains("secret")); assertFalse(text.contains("example.com"));
    }
    @Test public void ordinaryHlsProducesNoCustomDiagnostics() {
        List<String> logs=new ArrayList<>();
        MouflonDiagnostics.record("#EXTM3U\n#EXTINF:2,\nfile.ts",logs::add);
        assertTrue(logs.isEmpty());
    }
    @Test public void malformedCustomNamesAreRedactedAndOutputBounded() {
        List<String> logs=new ArrayList<>();
        MouflonDiagnostics.record("#EXTM3U\n#EXT-X-MOUFLON:password=value\n"+"#EXT-X-MOUFLON:URI:https://secret/\n".repeat(1000),logs::add);
        assertTrue(logs.size()<=162);
        assertFalse(String.join("\n",logs).contains("password"));
        assertFalse(String.join("\n",logs).contains("https://"));
    }
}
