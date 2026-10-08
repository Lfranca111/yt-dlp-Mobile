#!/usr/bin/env python3
"""Execute the shipped ARM64 instructions and JNI bridge in Unicorn.
Requires: pip install unicorn pyelftools
This checks native behavior/ABI; it does not build or exercise an Android APK.
"""
from pathlib import Path
import math,random,re,struct
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
ROOT=Path(__file__).resolve().parents[1]
LIB=ROOT/'app/src/main/jniLibs/arm64-v8a/libmedia_arm64.so'
BASE,DATA,STACK,STOP,HOOKS=0x100000,0x300000,0x3000000,0x3100000,0x3200000
ENV,TABLE=DATA,DATA+0x1000
uc=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
for p,n in [(BASE,0x100000),(DATA,0x2000000),(STACK,0x10000),(STOP,0x1000),(HOOKS,0x1000)]:uc.mem_map(p,n)
with LIB.open('rb') as f:
 elf=ELFFile(f);assert elf['e_machine']=='EM_AARCH64'
 for seg in elf.iter_segments():
  if seg['p_type']=='PT_LOAD':
   assert seg['p_align']==16384
   uc.mem_write(BASE+seg['p_vaddr'],seg.data())
  if seg['p_type']=='PT_GNU_STACK':assert not seg['p_flags']&1
 symbols={s.name:BASE+s['st_value'] for s in elf.get_section_by_name('.symtab').iter_symbols() if s['st_shndx']!='SHN_UNDEF'}
 exports={s.name for s in elf.get_section_by_name('.dynsym').iter_symbols() if s['st_shndx']!='SHN_UNDEF'}
 needed=[t.needed for t in elf.get_section_by_name('.dynamic').iter_tags() if t.entry.d_tag=='DT_NEEDED'];assert needed==['libc.so']
 hooks={}
 for section in elf.iter_sections():
  if section.name.startswith('.rela'):
   dyn=elf.get_section(section['sh_link'])
   for rel in section.iter_relocations():
    if rel['r_info_type']==1027:value=BASE+rel['r_addend']
    else:
     name=dyn.get_symbol(rel['r_info_sym']).name;assert name in ('malloc','free','strlen'),name
     value=HOOKS+0x800+len(hooks)*4;hooks[value]=name;uc.mem_write(value,bytes.fromhex('c0035fd6'))
    uc.mem_write(BASE+rel['r_offset'],struct.pack('<Q',value))
slots=[6,14,23,163,164,165,166,167,171,172,173,174,176,179,180,184,188,192,196,208,211,212,220,228]
uc.mem_write(ENV,struct.pack('<Q',TABLE))
for slot in slots:
 p=HOOKS+4*slot;uc.mem_write(p,bytes.fromhex('c0035fd6'));uc.mem_write(TABLE+8*slot,struct.pack('<Q',p));hooks[p]=slot
heap=DATA+0x10000;objects={};error=None;checks=0
rng=random.Random(20261008)
def alloc(size):
 global heap
 p=heap;heap+=(max(size,1)+15)//16*16
 assert heap<DATA+0x2000000
 return p
def chars(s):return s.encode('utf-16le','surrogatepass')
def make(value,kind=None):
 p=alloc(8)
 if isinstance(value,str):
  raw=chars(value);buf=alloc(len(raw));uc.mem_write(buf,raw);objects[p]=dict(kind='string',value=value,buf=buf,n=len(raw)//2)
 elif kind=='object':objects[p]=dict(kind=kind,value=[make(v) for v in value],n=len(value))
 else:
  fmt={'byte':'b','int':'i','long':'q'}[kind]
  if kind=='byte':raw=bytes(value)
  else:raw=struct.pack('<'+fmt*len(value),*value)
  buf=alloc(len(raw));uc.mem_write(buf,raw);objects[p]=dict(kind=kind,buf=buf,n=len(value))
 return p
def x(i):return uc.reg_read(UC_ARM64_REG_X0+i)
def ret(v):uc.reg_write(UC_ARM64_REG_X0,v&((1<<64)-1))
def cstr(p):
 out=bytearray()
 while (b:=uc.mem_read(p,1)[0]):out.append(b);p+=1
 return out.decode('utf-8')
def hook(machine,address,size,user):
 global error
 h=hooks.get(address)
 if h is None:return
 if h=='malloc':ret(alloc(x(0)))
 elif h=='free':pass
 elif h=='strlen':ret(len(cstr(x(0))))
 elif h==6:ret(1)
 elif h==14:error=cstr(x(2));ret(0)
 elif h in (23,166,192,196):pass
 elif h==228:ret(int(error is not None))
 elif h==164:ret(objects[x(1)]['n'])
 elif h in (165,184,188):ret(objects[x(1)]['buf'])
 elif h==163:ret(make(bytes(uc.mem_read(x(1),x(2)*2)).decode('utf-16le','surrogatepass')))
 elif h==167:ret(make(cstr(x(1))))
 elif h==220:uc.mem_write(x(4),bytes(uc.mem_read(objects[x(1)]['buf']+2*x(2),2*x(3))))
 elif h==171:ret(objects[x(1)]['n'])
 elif h==172:
  p=alloc(8);objects[p]=dict(kind='object',value=[0]*x(1),n=x(1));ret(p)
 elif h==173:ret(objects[x(1)]['value'][x(2)])
 elif h==174:objects[x(1)]['value'][x(2)]=x(3)
 elif h in (176,179,180):ret(make([0]*x(1),{176:'byte',179:'int',180:'long'}[h]))
 elif h in (208,211,212):
  step={208:1,211:4,212:8}[h];uc.mem_write(objects[x(1)]['buf']+x(2)*step,bytes(uc.mem_read(x(4),x(3)*step)))
 else:raise AssertionError(h)
 uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
uc.hook_add(UC_HOOK_CODE,hook)
def call(name,args=(),floats=()):
 for i,n in enumerate(args):uc.reg_write(UC_ARM64_REG_X0+i,n&((1<<64)-1))
 for i,n in enumerate(floats):uc.reg_write(UC_ARM64_REG_S0+i,struct.unpack('<I',struct.pack('<f',n))[0])
 uc.reg_write(UC_ARM64_REG_SP,STACK+0x8000);uc.reg_write(UC_ARM64_REG_LR,STOP)
 uc.emu_start(symbols[name],STOP,count=10000000)
 assert uc.reg_read(UC_ARM64_REG_PC)==STOP,name
 return x(0)
def decode(p):
 if not p:return None
 o=objects[p]
 if o['kind']=='string':return o['value']
 if o['kind']=='object':return [decode(v) for v in o['value']]
 fmt={'byte':'B','int':'i','long':'q'}[o['kind']];step={'byte':1,'int':4,'long':8}[o['kind']]
 return list(struct.unpack('<'+fmt*o['n'],uc.mem_read(o['buf'],o['n']*step)))
def jni(name,args=(),result='object',floats=()):
 global heap,objects,error
 heap=DATA+0x10000;objects={};error=None
 values=[]
 for a in args:
  if isinstance(a,str):values.append(make(a))
  elif isinstance(a,tuple):values.append(make(a[1],a[0]))
  else:values.append(a)
 symbol='Java_com_linfranca_ytdlpmobile_NativeMedia_'+name
 assert symbol in exports
 out=call(symbol,[ENV,0,*values],floats)
 if result=='object':return decode(out)
 if result=='signed':return out if out<1<<63 else out-(1<<64)
 if result=='int':return out&0xffffffff if out&0xffffffff<1<<31 else (out&0xffffffff)-(1<<32)
 return out
def same(a,b):
 global checks
 checks+=1;assert a==b,(a,b)
def whitespace(c):return c in '\t\n\v\f\r' or 28<=ord(c)<=32 or ord(c) in (0xa0,0x1680,0x2028,0x2029,0x202f,0x205f,0x3000) or 0x2000<=ord(c)<=0x200a
def tokenize(s):
 tokens=[];out='';quote=None;escape=False
 for c in s:
  if escape:out+=c;escape=False
  elif c=='\\' and quote!="'":escape=True
  elif quote and quote==c:quote=None
  elif not quote and c in "'\"":quote=c
  elif not quote and whitespace(c):
   if out:tokens.append(out);out=''
  else:out+=c
 if quote:return None
 if escape:out+='\\'
 if out:tokens.append(out)
 return tokens
def natural(a,b):
 first=re.findall(r'[0-9]+|[^0-9]+',a.lower());second=re.findall(r'[0-9]+|[^0-9]+',b.lower())
 def lex(x,y):
  ux=list(struct.unpack('<'+'H'*(len(chars(x))//2),chars(x)));uy=list(struct.unpack('<'+'H'*(len(chars(y))//2),chars(y)))
  return (ux>uy)-(ux<uy)
 for x,y in zip(first,second):
  if x[0].isascii() and x[0].isdigit() and y[0].isascii() and y[0].isdigit():
   u=x.lstrip('0') or '0';v=y.lstrip('0') or '0';c=(len(u)>len(v))-(len(u)<len(v)) or lex(u,v)
  else:c=lex(x,y)
  if c:return c
 return (len(first)>len(second))-(len(first)<len(second)) or lex(a,b)
# Actual JNI transport and all native entry points.
for n in range(0,160):
 data=bytes(rng.randrange(256) for _ in range(n))
 same(jni('hexNative',[('byte',data)]),data.hex().upper())
for s in ['',r'a(b)\c','漢字(🙂)', '\ud800', 'a\x00(b)']:
 same(jni('literalNative',[s]),s.replace('\\','\\\\').replace('(','\\(').replace(')','\\)'))
for _ in range(1200):
 s=''.join(rng.choice("ab漢\u00a0\u2007 \\'\"\t\r\n") for _ in range(rng.randrange(100)))
 actual=jni('tokensNative',[s]);expected=tokenize(s)
 if expected is None:same(error,'Custom arguments contain an unclosed quote.')
 else:same(actual,expected)
for s in ['0','+0','-0','123','-123',str(2**63-1),str(-2**63),str(2**63),str(-2**63-1),'','+',' 1','1 ','1.0','١٢']:
 status,value=jni('longNative',[s]);exp=2 if any(ord(c)>127 for c in s) else int(bool(re.fullmatch(r'[+-]?[0-9]+',s)) and -2**63<=int(s)<=2**63-1)
 same(status,exp)
 if status==1:same(value,int(s))
for _ in range(1500):
 n=rng.randint(-2**63,2**63-1);same(jni('longNative',[str(n)]),[1,n])
spaces=''.join(chr(i) for i in range(65536) if whitespace(chr(i)))
for _ in range(500):
 s=''.join(rng.choice('ab\r\n\t\u00a0\u2007 ') for _ in range(rng.randrange(120)))
 for mode in [0,1]:
  spans=jni('linesNative',[s,mode]);actual=[s[spans[i]:spans[i]+spans[i+1]] for i in range(0,len(spans),2)]
  expected=re.split(r'\r?\n' if mode else r'\r\n|\r|\n',s)
  expected=[v.strip(''.join(chr(i) for i in range(33))) if mode else v.strip(spaces) for v in expected]
  same(actual,expected)
attributes=['#EXT-X-MEDIA:TYPE=AUDIO,URI="a,b.m3u8"','#EXT-X-MEDIA:type=AUDIO,uri=x.m3u8', 'URI=""', 'X:URI="unterminated', 'X: URI="wrong",URI="right"', 'X:URIX=q,URI=abc', 'X:URI=,URI=ok']
for key in ['URI','TYPE','CODECS']:
 pattern=re.compile(r'(?:^|,)'+key+r'=(?:"([^"]+)"|([^,]+))',re.I)
 expected=[]
 for s in attributes:
  match=pattern.search(s.split(':',1)[-1]);expected.append((match[1] if match[1] is not None else match[2]) if match else None)
 same(jni('attributesNative',[('object',attributes),key]),expected)
for _ in range(500):
 a=''.join(rng.choice('ABab09-_漢🙂') for _ in range(rng.randrange(50)));b=''.join(rng.choice('ABab09-_漢🙂') for _ in range(rng.randrange(50)))
 order=jni('orderNative',[('object',[a,b]),('object',[a.lower(),b.lower()])]);same(order,[0,1] if natural(a,b)<=0 else [1,0])
from functools import cmp_to_key
names=[f'Page{rng.randrange(10000):0{rng.randrange(1,10)}}_漢{rng.randrange(20)}.jpg' for _ in range(1000)]
order=jni('orderNative',[('object',names),('object',[s.lower() for s in names])]);same(order,sorted(range(len(names)),key=cmp_to_key(lambda a,b:natural(names[a],names[b]))))
for delta in [-2**63,-301,-300,-299,0,299,300,30000,30001,2**63-1]:same(jni('gapNative',[delta],'int'),1 if 300<=delta<=30000 else 2 if delta>30000 or delta<=-300 else 0)
for target in range(1,16):
 for failures in range(15):
  for exception in [0,1]:
   base=900 if exception else max(400,min(1500,target*400));expected=min(15000,base*(1<<min(4,failures))) if failures>0 or exception else base
   same(jni('retryNative',[target,failures,exception],'signed'),expected)
for host,i in [('e621.net',1),('e6ai.net',2),('e926.net',3),('furbooru.org',4),('rule34.xxx',5),('multporn.net',6),('tailspace.com',7),('yiffer.xyz',8),('luscious.net',9)]:
 for h in [host,'www.'+host]:same(jni('siteNative',[h],'int'),i)
 same(jni('siteNative',[host+'.evil.example'],'int'),0)
same(jni('siteNative',['2.multporn.net'],'int'),6);same(jni('siteNative',['members.luscious.net'],'int'),9)
for s in ['', '1','0012','+1','١٢','12345678901234567890']:same(bool(jni('digitsNative',[s],'int')),bool(re.fullmatch(r'[0-9]+',s)))
for ext,mask in [('mp4',1),('m4v',2),('webm',4),('m3u8',8),('mpd',16)]:
 for tail in ['', '?x=1','#fragment','/bad','x']:
  same(bool(jni('extensionsNative',['/a.'+ext.upper()+tail,mask],'int')),tail in ('','?x=1','#fragment'))
for path in ['/movie.mp4','/hls/master.m3u8','/dash/manifest.mpd','/videoplayback','/manifest','/manifest/live','/api','/preview.mp4','/pre_videos/x.mp4']:
 accepted=path not in ['/api','/preview.mp4','/pre_videos/x.mp4']
 same(bool(jni('urlPartsNative',['cdn.example.com',path,'token=abc',0],'int')),accepted)
for h,p,q in [('ads.example.com','/x.mp4',''),('pubads.doubleclick.net','/x.mp4',''),('cdn.example.com','/ads/x.mp4',''),('cdn.example.com','/x.mp4','ad_type=x'),('cdn.example.com','/x.mp4','ads')]:same(jni('urlPartsNative',[h,p,q,0],'int'),0)
for q in ['sabr=1','id=x&ump=1','sabr_spec','sabr_contexts=x']:same(jni('urlPartsNative',['','/videoplayback',q,1],'int'),1)
for _ in range(200):
 w,h=rng.randrange(1,20000),rng.randrange(1,20000);rotation=rng.choice([1,3,6,8])
 rw,rh=(h,w) if rotation in (6,8) else (w,h);factor=1440.0/max(rw,rh)
 same(jni('pdfSizeNative',[w,h,rotation]),[max(1,int(rw*factor)),max(1,int(rh*factor))])
for offsets in [[0],[0,123,456,2**63-1],[0]+[rng.randrange(10**12) for _ in range(200)]]:
 same(bytes(jni('xrefNative',[('long',offsets)])),''.join(f'{n:010d} 00000 n \n' for n in offsets[1:]).encode())
video='https://cdn.example.com/video.m3u8?token=x';audio='https://cdn.example.com/audio.m3u8?token=y'
master='#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="recording-audio",NAME="Audio",DEFAULT=YES,AUTOSELECT=YES,URI="'+audio+'"\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,AUDIO="recording-audio"\n'+video+'\n'
same(jni('masterNative',[video,audio]),master)
for typ,data in [(1,b'0000ftyp'),(2,bytes.fromhex('1a45dfa300000000')),(3,b'OggS0000'),(4,b'ID300000'),(4,b'\xff\xe0000000'),(5,b'G0000000'),(6,b'G'+bytes(187)+b'G'+bytes(187)+b'G'),(7,b'\x7fELF'+bytes(16))]:
 same(jni('magicNative',[('byte',data),len(data),typ],'int'),1)
 same(jni('magicNative',[('byte',data),0,typ],'int'),0)
for comp in [1,3,4]:
 data=b'\xff\xd8\xff\xc0\x00\x0b\x08\x00\x20\x00\x30'+bytes([comp])+bytes(3)
 same(jni('jpegNative',[('byte',data)],'int'),comp)
def f32(n):return struct.unpack('<f',struct.pack('<f',n))[0]
for _ in range(1500):
 start=f32(rng.uniform(0,90));progress=f32(rng.uniform(start,100));elapsed=rng.randrange(1,10000000)
 delta=f32(progress-start)
 if elapsed<2000 or delta<f32(.2):expected=-1
 else:
  value=f32(100-progress)/(delta/(elapsed/1000.0));floor=math.floor(value);expected=floor+int(value-floor>=.5)
 same(jni('etaNative',[elapsed],'signed',floats=[progress,start]),expected)
same(jni('findNative',['漢ab🙂CD','ab',0],'int'),1)
same(jni('findNative',['abCD','cd',1],'int'),2)
# URL scoring and stream-hint regex equivalence (ASCII digits, UTF-16 text).
audio_hints=re.compile(r'(?:^|[/_.?&=:-])(audio|aac|m4a|opus|mp3)(?:$|[/_.?&=:-])')
video_hints=re.compile(r'(?:^|[/_.?&=:-])(video|avc|h264|h265|hevc|vp9|av1|[0-9]{3,4}p)(?:$|[/_.?&=:-])')
audio_itags={139,140,141,171,172,249,250,251}
video_itags={133,134,135,136,137,138,160,242,243,244,247,248,264,266,271,272,278,298,299,302,303,308,313,315}
for _ in range(800):
 text=''.join(rng.choice(['audio','video','aac','1080p','480p','12p','12345p','master.m3u8','manifest.mpd','/manifest/','_','?','&','x','/','漢']) for _ in range(rng.randrange(12)))
 itag=rng.choice([-1,18,139,140,133,399]);active=rng.randrange(2);captured=rng.randrange(-10000,10**15)
 expected=2 if itag in audio_itags or audio_hints.search(text) else 1 if itag in video_itags or video_hints.search(text) else 0 if 'master.m3u8' in text or re.search(r'\.mpd(?:$|[?#])',text) or '/manifest/' in text or text.endswith('/manifest') else 3
 same(jni('kindNative',[text,itag],'int'),expected)
 fmt=5000 if 'master.m3u8' in text else 4500 if re.search(r'\.m3u8(?:$|[?#])',text) else 4250 if re.search(r'\.mpd(?:$|[?#])',text) else 4000 if re.search(r'\.(mp4|m4v|webm)(?:$|[?#])',text) else 3750 if '/videoplayback' in text else 3000
 hint=400 if '/hls/' in text else 350 if '/dash/' in text else 300 if '/videos/' in text else 0
 same(jni('scoreNative',[text,active,captured],'signed'),fmt+hint+active*10000+max(0,captured)//1000000)
html_pattern=re.compile(r"\bhref\s*=\s*[\"']([^\"']+)[\"']",re.I)
for _ in range(400):
 tags=[''.join(rng.choice(['<a ','href=','HREF =','hrefx=','xhref=','-href=',' ', '\t', '\"', "'", 'a,b', '/x', '>']) for _ in range(rng.randrange(12))) for _ in range(3)]
 expected=[(m[1] if (m:=html_pattern.search(t)) else None) for t in tags]
 same(jni('htmlAttributesNative',[('object',tags),'href']),expected)
for line in ['GET /paired.m3u8 HTTP/1.1','GET /paired.m3u8','GET  HTTP/1.1','POST /x HTTP/1.1','','GET /x?q=1 HTTP/1.0']:
 for require in [0,1]:
  expected=line[4:].split(' ',1)[0] if line.startswith('GET ') and (not require or ' ' in line[4:]) else ''
  same(jni('httpPathNative',[line,require]),expected)
print(f'PASS: {checks:,} behavior checks; real ARM64 kernels + all JNI entry points; 16 KiB ELF alignment; libc-only dependency.')
