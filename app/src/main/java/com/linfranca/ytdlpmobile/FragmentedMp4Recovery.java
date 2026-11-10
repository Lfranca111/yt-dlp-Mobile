package com.linfranca.ytdlpmobile;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;

/** Preserve only closed moof/mdat pairs when an app-owned fragmented capture stops. */
final class FragmentedMp4Recovery {
    static long completeEnd(File file) throws IOException {
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            long end=0, at=0, length=in.length(); boolean ftyp=false, moov=false, moof=false;
            for(int boxes=0; at+8<=length; boxes++) {
                if(boxes>=1_000_000) throw new IOException("Fragment scan bounds exceeded");
                in.seek(at); long size=Integer.toUnsignedLong(in.readInt()); int type=in.readInt();
                int header=8;
                if(size==1) { if(at+16>length) break; size=in.readLong(); header=16; }
                // A size-zero atom is open-ended; don't treat an interrupted one as closed.
                if(size<header || size>length-at) break;
                if(type==0x66747970) ftyp=true;
                else if(type==0x6d6f6f76) moov=true;
                else if(type==0x6d6f6f66) moof=true;
                else if(type==0x6d646174 && ftyp && moov && moof) { end=at+size; moof=false; }
                at+=size;
            }
            return end;
        }
    }
    static long trimIncompleteTail(File file) throws IOException {
        long end=completeEnd(file);
        if(end<=0) throw new IOException("No complete fragmented MP4 media");
        long removed=file.length()-end;
        if(removed>0) try(RandomAccessFile out=new RandomAccessFile(file,"rw")) { out.setLength(end); }
        return removed;
    }
}
