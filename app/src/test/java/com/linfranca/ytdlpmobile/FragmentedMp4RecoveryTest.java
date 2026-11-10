package com.linfranca.ytdlpmobile;
import java.io.*;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class FragmentedMp4RecoveryTest {
    private byte[] atom(String type,byte[] payload) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(payload.length+8);out.writeBytes(type);out.write(payload);return bytes.toByteArray();
    }
    @Test public void retainsCompleteFragmentsAndDropsIncompleteTail() throws Exception {
        File file=Files.createTempFile("fragment-test",".mp4").toFile();
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            for(String type:new String[]{"ftyp","moov","moof","mdat"}) bytes.write(atom(type,new byte[100]));
            int complete=bytes.size();bytes.write(atom("moof",new byte[100]));
            DataOutputStream tail=new DataOutputStream(bytes);tail.writeInt(10000);tail.writeBytes("mdat");tail.write(new byte[10]);
            Files.write(file.toPath(),bytes.toByteArray());
            assertEquals(complete,FragmentedMp4Recovery.completeEnd(file));
            long old=file.length();assertEquals(old-complete,FragmentedMp4Recovery.trimIncompleteTail(file));
            assertEquals(complete,file.length());
        } finally {file.delete();}
    }
    @Test public void initializationAloneCannotBeSavedAsARecording() throws Exception {
        File file=Files.createTempFile("fragment-test",".mp4").toFile();
        try {
            Files.write(file.toPath(),atom("ftyp",new byte[100]));
            assertEquals(0,FragmentedMp4Recovery.completeEnd(file));
            try {FragmentedMp4Recovery.trimIncompleteTail(file);fail();}catch(IOException expected){}
            assertEquals(108,file.length());
        } finally {file.delete();}
    }
    @Test public void malformedBoxCannotCauseUnboundedScan() throws Exception {
        File file=Files.createTempFile("fragment-test",".mp4").toFile();
        try {Files.write(file.toPath(),new byte[100]);assertEquals(0,FragmentedMp4Recovery.completeEnd(file));}
        finally {file.delete();}
    }
}
