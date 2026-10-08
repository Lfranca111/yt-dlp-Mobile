#!/usr/bin/env python3
"""Rebuild the shipped Android library with the official Android NDK.
python tools/build_arm64.py --ndk /path/to/ndk/28.2.13676358
Uses NDK clang, Android API 21, and 16 KiB page alignment. No Gradle edits.
"""
import argparse,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--ndk',default=os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT'));args=p.parse_args()
if not args.ndk:p.error('Pass --ndk or set ANDROID_NDK_HOME to your installed Android NDK directory.')
ndk=Path(args.ndk).expanduser().resolve();prebuilt=ndk/'toolchains/llvm/prebuilt'
hosts=[v for v in prebuilt.glob('*') if (v/'bin').is_dir()]
if len(hosts)!=1:p.error(f'Expected one NDK host toolchain in {prebuilt}')
host=hosts[0];windows=os.name=='nt';clang=host/'bin'/('clang.exe' if windows else 'clang')
if not clang.is_file():p.error(f'NDK clang missing: {clang}')
src=ROOT/'app/src/main/cpp';dest=ROOT/'app/src/main/jniLibs/arm64-v8a/libmedia_arm64.so';dest.parent.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
 out=Path(tmp)/'libmedia_arm64.so'
 cmd=[str(clang),'--target=aarch64-linux-android21','-O2','-fPIC','-fno-stack-protector','-Wall','-Wextra','-Werror','-shared',str(src/'media_jni.c'),*map(str,sorted(src.glob('media_*arm64.S'))),'-Wl,-soname,libmedia_arm64.so','-Wl,-z,max-page-size=16384,-z,relro,-z,now,--hash-style=both,--build-id=sha1','-Wl,--no-undefined','-o',str(out)]
 subprocess.run(cmd,check=True)
 # Preserve the last good library if compilation fails.
 dest.write_bytes(out.read_bytes())
print(f'Built {dest}')
