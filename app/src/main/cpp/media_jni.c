// JNI ownership/array transport and stable sort orchestration.
// Processing kernels are in the three ARM64 .S files. No C++ runtime dependency.
#include <jni.h>
#include "media_arm64.h"
extern void *malloc(size_t);
extern void free(void *);
#define JNI(name) Java_com_linfranca_ytdlpmobile_NativeMedia_##name
static void oom(JNIEnv *e) {
    jclass c=(*e)->FindClass(e,"java/lang/OutOfMemoryError");
    if(c) { (*e)->ThrowNew(e,c,"Native media buffer allocation failed"); (*e)->DeleteLocalRef(e,c); }
}
static void *buffer(JNIEnv *e,size_t n) { if((*e)->ExceptionCheck(e))return 0; void *p=malloc(n?n:1); if(!p)oom(e);return p; }
static int find_ascii(const jchar *s,int n,const char *needle,int ignore) {
    jchar p[128];int m=0;
    while(needle[m]) { if(m==128)return -1;p[m]=(unsigned char)needle[m];m++; }
    return media_find16(s,n,p,m,ignore);
}
static int has_any(const jchar *s,int n,const char *const *table,int count) {
    for(int i=0;i<count;i++)if(find_ascii(s,n,table[i],0)>=0)return 1;
    return 0;
}
JNIEXPORT jint JNICALL JNI(findNative)(JNIEnv *e,jclass c,jstring text,jstring needle,jboolean ignore) {
    (void)c; const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return -1;
    const jchar *p=(*e)->GetStringChars(e,needle,0);if(!p){(*e)->ReleaseStringChars(e,text,s);return -1;}
    int r=media_find16(s,(*e)->GetStringLength(e,text),p,(*e)->GetStringLength(e,needle),ignore);
    (*e)->ReleaseStringChars(e,needle,p);(*e)->ReleaseStringChars(e,text,s);return r;
}
JNIEXPORT jboolean JNICALL JNI(extensionsNative)(JNIEnv *e,jclass c,jstring text,jint mask) {
    (void)c;const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 0;
    int r=media_extensions16(s,(*e)->GetStringLength(e,text),(unsigned)mask);
    (*e)->ReleaseStringChars(e,text,s);return r;
}
// Mode 0 direct-media classification, mode 1 SABR/UMP controls.
JNIEXPORT jboolean JNICALL JNI(urlPartsNative)(JNIEnv *e,jclass c,jstring host,jstring path,jstring query,jint mode) {
    (void)c; const jchar *h=0,*p=0,*q=0;int r=0;
    h=(*e)->GetStringChars(e,host,0);if(!h)goto done;
    p=(*e)->GetStringChars(e,path,0);if(!p)goto done;
    q=(*e)->GetStringChars(e,query,0);if(!q)goto done;
    int hn=(*e)->GetStringLength(e,host),pn=(*e)->GetStringLength(e,path),qn=(*e)->GetStringLength(e,query);
    const char *const hostWords[]={"doubleclick","googlesyndication","googleadservices","adservice","adserver","adnxs","adsystem","imasdk","tracking","analytics"};
    const char *const pathWords[]={"/ads/","/ad/","adserver","advert","preroll","midroll","postroll","vast","vmap","tracking","analytics","beacon","/pixel","pre_videos","pre-video","preview","thumbnail","/thumb/","/poster/","promo_video"};
    const char *const queryWords[]={"adformat=","ad_type=","adtype=","is_ad=","preroll=","vast=","vmap="};
    const char *const labels[]={"ad","ads","advert","advertising","adserver"};
    const char *const keys[]={"ad","ads","ad_type","adtype","is_ad","preroll","vast","vmap"};
    const char *const controls[]={"sabr","ump","sabr_spec","sabr_contexts"};
    if(mode==0) {
        if(has_any(h,hn,hostWords,10)||has_any(p,pn,pathWords,20)||has_any(q,qn,queryWords,7))goto done;
        for(int start=0,end=0;start<=hn;start=end+1) {
            end=start;while(end<hn&&h[end]!='.')end++;
            for(int i=0;i<5;i++) {int m=0;while(labels[i][m])m++;
                if(end-start==m&&find_ascii(h+start,m,labels[i],0)==0)goto done;}
        }
    } else if(find_ascii(p,pn,"/videoplayback",0)<0)goto done;
    for(int start=0,end=0;start<=qn;start=end+1) {
        end=start;while(end<qn&&q[end]!='&')end++;
        int keyEnd=start;while(keyEnd<end&&q[keyEnd]!='=')keyEnd++;
        const char *const *table=mode?controls:keys;int count=mode?4:8;
        for(int i=0;i<count;i++) {int m=0;while(table[i][m])m++;
            if(keyEnd-start==m&&find_ascii(q+start,m,table[i],0)==0) {r=mode?1:0;goto done;}}
    }
    if(mode==0)r=media_extensions16(p,pn,31)||find_ascii(p,pn,"/videoplayback",0)>=0||
        find_ascii(p,pn,"/manifest/",0)>=0||(pn>=9&&find_ascii(p+pn-9,9,"/manifest",0)==0);
done:
    if(q)(*e)->ReleaseStringChars(e,query,q);if(p)(*e)->ReleaseStringChars(e,path,p);if(h)(*e)->ReleaseStringChars(e,host,h);return r;
}
JNIEXPORT jintArray JNICALL JNI(linesNative)(JNIEnv *e,jclass c,jstring text,jint mode) {
    (void)c;int n=(*e)->GetStringLength(e,text);
    const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 0;
    // Count separators before allocating, avoiding a worst-case 8*n buffer for long lines.
    int capacity=1;for(int i=0;i<n;i++)if(s[i]=='\n'||(!mode&&s[i]=='\r'))capacity++;
    int *out=buffer(e,(size_t)capacity*2*sizeof(int));jintArray r=0;
    if(out) {int count=media_lines16(s,n,out,mode);r=(*e)->NewIntArray(e,count*2);
        if(r)(*e)->SetIntArrayRegion(e,r,0,count*2,out);free(out);}
    (*e)->ReleaseStringChars(e,text,s);return r;
}
JNIEXPORT jobjectArray JNICALL JNI(tokensNative)(JNIEnv *e,jclass c,jstring text) {
    (void)c;int n=(*e)->GetStringLength(e,text);jobjectArray r=0;
    const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 0;
    jchar *out=buffer(e,((size_t)n+1)*sizeof(jchar));int *spans=buffer(e,((size_t)n+1)*2*sizeof(int));
    if(out&&spans) {
        int count=media_tokens16(s,n,out,spans);
        if(count<0) {jclass ex=(*e)->FindClass(e,"java/lang/IllegalArgumentException");
            if(ex){(*e)->ThrowNew(e,ex,"Custom arguments contain an unclosed quote.");(*e)->DeleteLocalRef(e,ex);}}
        else {jclass strings=(*e)->FindClass(e,"java/lang/String");if(strings) {
            r=(*e)->NewObjectArray(e,count,strings,0);(*e)->DeleteLocalRef(e,strings);
            for(int i=0;r&&i<count;i++){jstring token=(*e)->NewString(e,out+spans[2*i],spans[2*i+1]);
                if(!token)break;(*e)->SetObjectArrayElement(e,r,i,token);(*e)->DeleteLocalRef(e,token);
                if((*e)->ExceptionCheck(e))break;}
        }}
    }
    free(out);free(spans);(*e)->ReleaseStringChars(e,text,s);return r;
}
JNIEXPORT jobjectArray JNICALL JNI(attributesNative)(JNIEnv *e,jclass c,jobjectArray lines,jstring key) {
    (void)c;int n=(*e)->GetArrayLength(e,lines);jobjectArray r=0;
    const jchar *k=(*e)->GetStringChars(e,key,0);if(!k)return 0;
    jclass strings=(*e)->FindClass(e,"java/lang/String");if(strings){r=(*e)->NewObjectArray(e,n,strings,0);(*e)->DeleteLocalRef(e,strings);}
    for(int i=0;r&&i<n;i++) {
        jstring line=(*e)->GetObjectArrayElement(e,lines,i);if(!line)break;
        const jchar *s=(*e)->GetStringChars(e,line,0);if(!s){(*e)->DeleteLocalRef(e,line);break;}
        int span[2];if(media_attribute16(s,(*e)->GetStringLength(e,line),k,(*e)->GetStringLength(e,key),span)) {
            jstring value=(*e)->NewString(e,s+span[0],span[1]);if(value){(*e)->SetObjectArrayElement(e,r,i,value);(*e)->DeleteLocalRef(e,value);}}
        (*e)->ReleaseStringChars(e,line,s);(*e)->DeleteLocalRef(e,line);
        if((*e)->ExceptionCheck(e))break;
    }
    (*e)->ReleaseStringChars(e,key,k);return r;
}
JNIEXPORT jlongArray JNICALL JNI(longNative)(JNIEnv *e,jclass c,jstring text) {
    (void)c;const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 0;
    int64_t number=0;jlong out[2];out[0]=media_long16(s,(*e)->GetStringLength(e,text),&number);out[1]=number;
    (*e)->ReleaseStringChars(e,text,s);jlongArray r=(*e)->NewLongArray(e,2);if(r)(*e)->SetLongArrayRegion(e,r,0,2,out);return r;
}
JNIEXPORT jint JNICALL JNI(jpegNative)(JNIEnv *e,jclass c,jbyteArray bytes) {
    (void)c;jbyte *p=(*e)->GetByteArrayElements(e,bytes,0);if(!p)return 3;
    int r=media_jpeg((uint8_t *)p,(*e)->GetArrayLength(e,bytes));(*e)->ReleaseByteArrayElements(e,bytes,p,JNI_ABORT);return r;
}
JNIEXPORT jboolean JNICALL JNI(magicNative)(JNIEnv *e,jclass c,jbyteArray bytes,jint count,jint kind) {
    (void)c;int n=(*e)->GetArrayLength(e,bytes);if(count<0||count>n)return 0;
    jbyte *p=(*e)->GetByteArrayElements(e,bytes,0);if(!p)return 0;
    int r=media_magic((uint8_t *)p,count,kind);(*e)->ReleaseByteArrayElements(e,bytes,p,JNI_ABORT);return r;
}
JNIEXPORT jstring JNICALL JNI(hexNative)(JNIEnv *e,jclass c,jbyteArray bytes) {
    (void)c;int n=(*e)->GetArrayLength(e,bytes);char *out=buffer(e,(size_t)n*2+1);if(!out)return 0;
    jbyte *p=(*e)->GetByteArrayElements(e,bytes,0);jstring r=0;
    if(p){media_hex((uint8_t *)p,n,out);out[(size_t)n*2]=0;(*e)->ReleaseByteArrayElements(e,bytes,p,JNI_ABORT);r=(*e)->NewStringUTF(e,out);}free(out);return r;
}
JNIEXPORT jstring JNICALL JNI(literalNative)(JNIEnv *e,jclass c,jstring text) {
    (void)c;int n=(*e)->GetStringLength(e,text);jchar *out=buffer(e,((size_t)n*2+1)*sizeof(jchar));if(!out)return 0;
    const jchar *s=(*e)->GetStringChars(e,text,0);jstring r=0;
    if(s){int size=media_pdf_literal(s,n,out);(*e)->ReleaseStringChars(e,text,s);r=(*e)->NewString(e,out,size);}free(out);return r;
}
JNIEXPORT jlong JNICALL JNI(etaNative)(JNIEnv *e,jclass c,jfloat progress,jfloat start,jlong elapsed) {
    (void)e;(void)c;return media_eta(progress,start,elapsed);
}
// Stable mergesort keeps equal names in their input order. All JNI calls occur
// outside the comparison loop; lowercase names are generated once by Kotlin.
typedef struct {const jchar *o,*l;int on,ln;} Name;
static int compare(const Name *names,int a,int b) {
    int r=media_natural16(names[a].l,names[a].ln,names[b].l,names[b].ln);
    if(r)return r;
    int n=names[a].on<names[b].on?names[a].on:names[b].on;
    for(int i=0;i<n;i++)if(names[a].o[i]!=names[b].o[i])return names[a].o[i]<names[b].o[i]?-1:1;
    return (names[a].on>names[b].on)-(names[a].on<names[b].on);
}
JNIEXPORT jintArray JNICALL JNI(orderNative)(JNIEnv *e,jclass c,jobjectArray original,jobjectArray lower) {
    (void)c;int n=(*e)->GetArrayLength(e,original);if((*e)->GetArrayLength(e,lower)!=n)return 0;
    Name *names=buffer(e,(size_t)n*sizeof(Name));int *order=buffer(e,(size_t)n*sizeof(int)),*scratch=buffer(e,(size_t)n*sizeof(int));jintArray r=0;
    jchar *arena=0;size_t total=0;
    if(!names||!order||!scratch)goto done;
    // At most two local references, even for tens of thousands of filenames.
    // Copy once into one UTF-16 arena, instead of retaining/pinning every string.
    for(int i=0;i<n;i++) {
        jstring o=(*e)->GetObjectArrayElement(e,original,i);if(!o)goto done;
        jstring l=(*e)->GetObjectArrayElement(e,lower,i);if(!l){(*e)->DeleteLocalRef(e,o);goto done;}
        names[i].on=(*e)->GetStringLength(e,o);names[i].ln=(*e)->GetStringLength(e,l);
        total+=(size_t)names[i].on+(size_t)names[i].ln;order[i]=i;
        (*e)->DeleteLocalRef(e,l);(*e)->DeleteLocalRef(e,o);
    }
    if(total>SIZE_MAX/sizeof(jchar)){oom(e);goto done;}
    arena=buffer(e,total*sizeof(jchar));if(!arena)goto done;
    size_t cursor=0;
    for(int i=0;i<n;i++) {
        jstring o=(*e)->GetObjectArrayElement(e,original,i);if(!o)goto done;
        jstring l=(*e)->GetObjectArrayElement(e,lower,i);if(!l){(*e)->DeleteLocalRef(e,o);goto done;}
        names[i].o=arena+cursor;cursor+=names[i].on;
        names[i].l=arena+cursor;cursor+=names[i].ln;
        (*e)->GetStringRegion(e,o,0,names[i].on,(jchar *)names[i].o);
        if(!(*e)->ExceptionCheck(e))(*e)->GetStringRegion(e,l,0,names[i].ln,(jchar *)names[i].l);
        (*e)->DeleteLocalRef(e,l);(*e)->DeleteLocalRef(e,o);
        if((*e)->ExceptionCheck(e))goto done;
    }
    for(size_t width=1;width<(size_t)n;width*=2) {
        for(size_t start=0;start<(size_t)n;start+=width*2){size_t mid=start+width,end=start+width*2;
            if(mid>(size_t)n)mid=n;if(end>(size_t)n)end=n;
            size_t a=start,b=mid,k=start;
            while(a<mid||b<end) scratch[k++]=(b==end||(a<mid&&compare(names,order[a],order[b])<=0))?order[a++]:order[b++];
        }
        int *swap=order;order=scratch;scratch=swap;
    }
    r=(*e)->NewIntArray(e,n);if(r)(*e)->SetIntArrayRegion(e,r,0,n,order);
done:
    free(arena);free(names);free(order);free(scratch);return r;
}

extern int media_gap_kind(int64_t);
extern int64_t media_retry(int64_t,int,int);
extern int media_digits16(const uint16_t *,int);
extern int media_site16(const uint16_t *,int);
extern int media_xref(const int64_t *,int,char *);
extern void media_pdf_size(int,int,int,int *);
extern int media_master16(const uint16_t *,int,const uint16_t *,int,uint16_t *);
JNIEXPORT jint JNICALL JNI(gapNative)(JNIEnv *e,jclass c,jlong delta) {(void)e;(void)c;return media_gap_kind(delta);}
JNIEXPORT jlong JNICALL JNI(retryNative)(JNIEnv *e,jclass c,jint target,jint failures,jboolean exception) {(void)e;(void)c;return media_retry(target,failures,exception);}
JNIEXPORT jboolean JNICALL JNI(digitsNative)(JNIEnv *e,jclass c,jstring text) {
    (void)c;const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 0;
    int r=media_digits16(s,(*e)->GetStringLength(e,text));(*e)->ReleaseStringChars(e,text,s);return r;
}
JNIEXPORT jint JNICALL JNI(siteNative)(JNIEnv *e,jclass c,jstring text) {
    (void)c;const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 0;
    int r=media_site16(s,(*e)->GetStringLength(e,text));(*e)->ReleaseStringChars(e,text,s);return r;
}
JNIEXPORT jbyteArray JNICALL JNI(xrefNative)(JNIEnv *e,jclass c,jlongArray offsets) {
    (void)c;int n=(*e)->GetArrayLength(e,offsets);if(n>71582788){oom(e);return 0;}
    char *out=buffer(e,(size_t)n*30);if(!out)return 0;
    jlong *p=(*e)->GetLongArrayElements(e,offsets,0);jbyteArray r=0;
    if(p){int size=media_xref((int64_t *)p,n,out);(*e)->ReleaseLongArrayElements(e,offsets,p,JNI_ABORT);
        r=(*e)->NewByteArray(e,size);if(r)(*e)->SetByteArrayRegion(e,r,0,size,(jbyte *)out);}
    free(out);return r;
}
JNIEXPORT jintArray JNICALL JNI(pdfSizeNative)(JNIEnv *e,jclass c,jint w,jint h,jint rotation) {
    (void)c;int out[2];media_pdf_size(w,h,rotation,out);jintArray r=(*e)->NewIntArray(e,2);if(r)(*e)->SetIntArrayRegion(e,r,0,2,out);return r;
}
JNIEXPORT jstring JNICALL JNI(masterNative)(JNIEnv *e,jclass c,jstring video,jstring audio) {
    (void)c;int vn=(*e)->GetStringLength(e,video),an=(*e)->GetStringLength(e,audio);
    jchar *out=buffer(e,((size_t)vn+an+512)*sizeof(jchar));if(!out)return 0;jstring r=0;
    const jchar *v=(*e)->GetStringChars(e,video,0),*a=0;
    if(v){a=(*e)->GetStringChars(e,audio,0);if(a){int size=media_master16(v,vn,a,an,out);r=(*e)->NewString(e,out,size);(*e)->ReleaseStringChars(e,audio,a);}(*e)->ReleaseStringChars(e,video,v);}
    free(out);return r;
}
extern int64_t media_url_score(const uint16_t *,int,int,int64_t);
extern int media_stream_kind(const uint16_t *,int,int);
JNIEXPORT jlong JNICALL JNI(scoreNative)(JNIEnv *e,jclass c,jstring text,jboolean active,jlong captured) {
    (void)c;const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 0;
    int64_t r=media_url_score(s,(*e)->GetStringLength(e,text),active,captured);(*e)->ReleaseStringChars(e,text,s);return r;
}
JNIEXPORT jint JNICALL JNI(kindNative)(JNIEnv *e,jclass c,jstring text,jint itag) {
    (void)c;const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 3;
    int r=media_stream_kind(s,(*e)->GetStringLength(e,text),itag);(*e)->ReleaseStringChars(e,text,s);return r;
}
extern int media_html_attribute16(const uint16_t *,int,const uint16_t *,int,int *);
extern int media_http_path16(const uint16_t *,int,int);
JNIEXPORT jobjectArray JNICALL JNI(htmlAttributesNative)(JNIEnv *e,jclass c,jobjectArray lines,jstring key) {
    (void)c;int n=(*e)->GetArrayLength(e,lines);jobjectArray r=0;
    const jchar *k=(*e)->GetStringChars(e,key,0);if(!k)return 0;
    jclass strings=(*e)->FindClass(e,"java/lang/String");if(strings){r=(*e)->NewObjectArray(e,n,strings,0);(*e)->DeleteLocalRef(e,strings);}
    for(int i=0;r&&i<n;i++) {
        jstring line=(*e)->GetObjectArrayElement(e,lines,i);if(!line)break;
        const jchar *s=(*e)->GetStringChars(e,line,0);if(!s){(*e)->DeleteLocalRef(e,line);break;}
        int span[2];if(media_html_attribute16(s,(*e)->GetStringLength(e,line),k,(*e)->GetStringLength(e,key),span)) {
            jstring value=(*e)->NewString(e,s+span[0],span[1]);if(value){(*e)->SetObjectArrayElement(e,r,i,value);(*e)->DeleteLocalRef(e,value);}}
        (*e)->ReleaseStringChars(e,line,s);(*e)->DeleteLocalRef(e,line);
        if((*e)->ExceptionCheck(e))break;
    }
    (*e)->ReleaseStringChars(e,key,k);return r;
}
JNIEXPORT jstring JNICALL JNI(httpPathNative)(JNIEnv *e,jclass c,jstring text,jboolean requireSpace) {
    (void)c;const jchar *s=(*e)->GetStringChars(e,text,0);if(!s)return 0;
    int length=media_http_path16(s,(*e)->GetStringLength(e,text),requireSpace);
    jstring r=(*e)->NewString(e,length>=0?s+4:s,length>=0?length:0);(*e)->ReleaseStringChars(e,text,s);return r;
}
