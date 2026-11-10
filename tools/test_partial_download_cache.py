"""Real yt-dlp/FFmpeg regression: local VOD with missing, empty, blocked and corrupt pieces.
Run: python3 tools/test_partial_download_cache.py (yt-dlp and ffmpeg required).
"""
import base64
import collections
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import struct
import zlib

ASSET = Path(__file__).resolve().parents[1] / 'app/src/main/assets/partial_download_cache.py'


class PartialCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.media = self.root / 'media'
        self.media.mkdir()
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=160x90:rate=10', '-f', 'lavfi', '-i', 'sine=frequency=440', '-t', '12', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-g', '10', '-c:a', 'aac', '-f', 'hls', '-hls_time', '1', '-hls_list_size', '0', str(self.media / 'video.m3u8')], check=True)
        self.counts = collections.Counter()
        self.failed = set()
        self.empty = set()
        self.code = 503
        owner = self
        class Handler(http.server.SimpleHTTPRequestHandler):
            def __init__(self, *a, **kw): super().__init__(*a, directory=str(owner.media), **kw)
            def log_message(self, *a): pass
            def do_GET(self):
                name = self.path.split('?')[0].lstrip('/')
                owner.counts[name] += 1
                if name in owner.failed:
                    self.send_response(owner.code); self.end_headers(); return
                if name in owner.empty:
                    self.send_response(200); self.send_header('Content-Length', '0'); self.end_headers(); return
                super().do_GET()
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.work = self.root / 'work'; self.work.mkdir()
        self.cache = self.work / '.download-cache'; self.cache.mkdir()
        (self.cache / 'config.json').write_text(json.dumps({'binding': 'episode-sub-server3'}))
        self.plugins = self.cache / 'plugins'
        module = self.plugins / 'app/yt_dlp_plugins/postprocessor/app_partial_cache.py'
        module.parent.mkdir(parents=True)
        shutil.copyfile(ASSET, module)

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.temp.cleanup()

    def download(self, suffix='', resource='video.m3u8'):
        # Match the Android launcher: preserve final assembly before yt-dlp's
        # overwrite check, which runs before its fragment downloader.
        final = self.work / 'video.mp4'
        if final.is_file():
            archive = self.cache / 'recovery' / str(len(list((self.cache / 'recovery').glob('*'))))
            archive.mkdir(parents=True, exist_ok=True)
            shutil.move(str(final), str(archive / final.name))
        url = 'http://127.0.0.1:%d/%s%s' % (self.server.server_port, resource, suffix)
        result = subprocess.run([sys.executable, '-m', 'yt_dlp', '--ignore-config', '--plugin-dirs', str(self.plugins), '--use-postprocessor', 'AppPartialCache', '--force-overwrites', '--skip-unavailable-fragments', '--fragment-retries', '2', '--retries', '0', '--retry-sleep', 'fragment:0', '--concurrent-fragments', '1', '--no-progress', '-o', str(self.work / 'video.%(ext)s'), url], capture_output=True, text=True, timeout=90)
        return result.returncode, result.stdout + result.stderr

    def states(self): return [json.loads(p.read_text()) for p in self.cache.glob('track-*.json')]

    def test_skip_then_repair_reuses_compatible_bytes(self):
        self.failed = {'video4.ts'}
        code, log = self.download()
        self.assertEqual(code, 0, log)
        self.assertIn('Skipping fragment', log)
        self.assertGreaterEqual(self.counts['video4.ts'], 3)
        self.assertEqual(sum(len(s['missing']) for s in self.states()), 1)
        self.assertTrue((self.work / 'video.mp4').is_file(), log)
        previous = self.counts.copy()
        self.failed.clear()
        code, log = self.download()
        self.assertEqual(code, 0, log)
        self.assertIn('Reused verified fragment', log)
        self.assertEqual(self.counts['video0.ts'], previous['video0.ts'])
        self.assertGreater(self.counts['video4.ts'], previous['video4.ts'])
        self.assertEqual(sum(len(s['missing']) for s in self.states()), 0)
        self.assertTrue(list((self.cache / 'recovery').rglob('*.mp4')))
        duration = float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',str(self.work / 'video.mp4')]))
        self.assertGreater(duration, 11)

    def test_empty_body_retries_then_skips(self):
        self.empty = {'video4.ts'}
        code, log = self.download()
        self.assertEqual(code, 0, log)
        self.assertGreaterEqual(self.counts['video4.ts'], 3)
        self.assertIn('Empty fragment', log)
        self.assertEqual(sum(len(s['missing']) for s in self.states()), 1)

    def test_widespread_access_failure_stops_with_partial(self):
        self.failed = {'video4.ts', 'video5.ts', 'video6.ts', 'video7.ts'}; self.code = 403
        code, log = self.download()
        self.assertNotEqual(code, 0, log)
        self.assertIn('Too many unavailable fragments', log)
        self.assertTrue(list(self.work.glob('*.part')))
        self.assertEqual(self.counts['video7.ts'], 0)

    def test_corrupted_cache_refetched(self):
        self.failed = {'video4.ts'}
        self.assertEqual(self.download()[0], 0)
        for obj in (self.cache / 'objects').glob('*.bin'): obj.write_bytes(b'corrupted')
        old = self.counts.copy(); self.failed.clear()
        code, log = self.download()
        self.assertEqual(code, 0, log)
        self.assertNotIn('Reused verified fragment', log)
        self.assertGreater(self.counts['video0.ts'], old['video0.ts'])

    def test_changed_fragment_resource_not_reused(self):
        self.assertEqual(self.download()[0], 0)
        manifest = self.media / 'video.m3u8'
        manifest.write_text(manifest.read_text().replace('.ts', '.ts?different-selection=1'))
        previous = self.counts.copy()
        code, log = self.download()
        self.assertEqual(code, 0, log)
        self.assertNotIn('Reused verified fragment', log)
        self.assertGreater(self.counts['video0.ts'], previous['video0.ts'])

    def test_different_selection_binding_does_not_reuse(self):
        self.assertEqual(self.download()[0], 0)
        (self.cache / 'config.json').write_text(json.dumps({'binding': 'episode-dub-server3'}))
        previous = self.counts.copy()
        code, log = self.download()
        self.assertEqual(code, 0, log)
        self.assertNotIn('Reused verified fragment', log)
        self.assertGreater(self.counts['video0.ts'], previous['video0.ts'])

    def test_local_partial_copy_reconstruction_preserves_resume_data(self):
        self.failed = {'video4.ts', 'video5.ts', 'video6.ts'}
        self.code = 403
        code, log = self.download()
        self.assertNotEqual(code, 0, log)
        partial = next(self.work.glob('*.part'))
        snapshot = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.work.rglob('*') if p.is_file()}
        output = self.work / 'Partial-recovered.mkv'
        subprocess.run(['ffmpeg','-v','error','-nostdin','-protocol_whitelist','file,pipe','-fflags','+genpts+discardcorrupt','-i',str(partial),'-map','0:v:0','-map','0:a?','-c','copy','-avoid_negative_ts','make_zero',str(output)],check=True)
        data = json.loads(subprocess.check_output(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(output)]))
        self.assertGreater(float(data['format']['duration']), 3)
        self.assertEqual({t['codec_type'] for t in data['streams']}, {'video','audio'})
        self.assertTrue(all(p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest() == checksum for p, checksum in snapshot.items()))
        self.failed.clear()
        code, log = self.download()
        self.assertEqual(code, 0, log)
        self.assertIn('Reused verified fragment', log)

    def test_separate_dash_audio_video_resume_and_merge(self):
        subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc=size=160x90:rate=10','-f','lavfi','-i','sine=frequency=440','-t','12','-c:v','libx264','-pix_fmt','yuv420p','-g','10','-c:a','aac','-f','dash','-seg_duration','1','-use_timeline','0',str(self.media / 'video.mpd')],check=True)
        self.failed = {'chunk-stream0-00005.m4s'}
        code, log = self.download(resource='video.mpd')
        self.assertEqual(code, 0, log)
        self.assertEqual(sum(len(s['missing']) for s in self.states()), 1)
        previous = self.counts.copy(); self.failed.clear()
        code, log = self.download(resource='video.mpd')
        self.assertEqual(code, 0, log)
        self.assertEqual(self.counts['init-stream0.m4s'], previous['init-stream0.m4s'])
        self.assertEqual(self.counts['init-stream1.m4s'], previous['init-stream1.m4s'])
        self.assertGreater(self.counts['chunk-stream0-00005.m4s'],previous['chunk-stream0-00005.m4s'])
        self.assertEqual(sum(len(s['missing']) for s in self.states()), 0)
        output = next(self.work.glob('video.mp4'))
        data = json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(output)]))
        self.assertEqual({t['codec_type'] for t in data['streams']}, {'video','audio'})

    def wrap_segments(self):
        png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5d0AAAAASUVORK5CYII=')
        png += b'\x00' * (80-len(png))
        for file in self.media.glob('*.ts'): file.write_bytes(png + file.read_bytes())
        return png

    def assert_real_video(self):
        data = json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(self.work / 'video.mp4')]))
        self.assertEqual({t['codec_type'] for t in data['streams']}, {'video','audio'})
        self.assertNotIn('png', {t['codec_name'] for t in data['streams']})
        self.assertGreater(float(data['format']['duration']), 11)

    def test_wrapped_segments_download_as_video_with_audio(self):
        self.wrap_segments()
        code, log = self.download()
        self.assertEqual(code,0,log)
        self.assertIn('Verified PNG-prefixed TS fragment normalized',log)
        self.assert_real_video()
        objects = list((self.cache / 'objects').glob('*.bin'))
        self.assertTrue(objects)
        self.assertTrue(all(p.read_bytes().startswith(b'\x89PNG') for p in objects))

    def test_image_only_placeholder_retried_then_marked_missing(self):
        png = self.wrap_segments()
        (self.media / 'video4.ts').write_bytes(png)
        code, log = self.download()
        self.assertEqual(code,0,log)
        self.assertGreaterEqual(self.counts['video4.ts'],3)
        self.assertEqual(sum(len(s['missing']) for s in self.states()),1)
        self.assertIn('image-only response',log)
        self.assert_real_video()

    def test_legacy_wrapped_raw_cache_reused_without_redownload(self):
        self.wrap_segments()
        module = self.plugins / 'app/yt_dlp_plugins/postprocessor/app_partial_cache.py'
        current = ASSET.read_text()
        module.write_text(current.replace('FragmentFD.decrypter = decrypter',''))
        code, log = self.download()
        self.assertEqual(code,0,log)
        old = json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json',str(self.work / 'video.mp4')]))
        self.assertEqual(old['streams'][0]['codec_name'],'png')
        snapshot = {p:hashlib.sha256(p.read_bytes()).hexdigest() for p in (self.cache / 'objects').glob('*.bin')}
        previous = self.counts.copy()
        module.write_text(current)
        shutil.rmtree(module.parent / '__pycache__',ignore_errors=True)
        code, log = self.download()
        self.assertEqual(code,0,log)
        self.assertIn('Reused verified fragment',log)
        self.assertEqual(self.counts['video0.ts'],previous['video0.ts'])
        self.assert_real_video()
        self.assertTrue(all(hashlib.sha256(p.read_bytes()).hexdigest()==sha for p,sha in snapshot.items()))


    @staticmethod
    def png_chunks(payload):
        def chunk(kind, body):
            return struct.pack('>I',len(body))+kind+body+struct.pack('>I',zlib.crc32(body,zlib.crc32(kind)) & 0xffffffff)
        return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00'*5))+chunk(b'tsDa',payload)+chunk(b'IEND',b'')

    def wrap_chunks(self, irregular=False):
        for file in self.media.glob('*.ts'):
            payload=file.read_bytes()
            if irregular: payload=payload[:188*10]+b'abcdefghij'+payload[188*10:]
            file.write_bytes(self.png_chunks(payload))

    def test_real_png_chunk_framing_with_inline_sync_anomalies(self):
        self.wrap_chunks(irregular=True)
        originals={p.name:p.read_bytes() for p in self.media.glob('*.ts')}
        code,log=self.download()
        self.assertEqual(code,0,log);self.assert_real_video()
        raw={p.read_bytes() for p in (self.cache / 'objects').glob('*.bin')}
        self.assertEqual(set(originals.values()),raw)
        run=subprocess.run(['ffmpeg','-v','error','-i',str(self.work / 'video.mp4'),'-f','null','-'],capture_output=True)
        self.assertEqual(run.returncode,0,run.stderr.decode());self.assertEqual(run.stderr,b'')

    def test_empty_tsda_is_retried_and_marked_missing(self):
        self.wrap_chunks();(self.media / 'video4.ts').write_bytes(self.png_chunks(b''))
        code,log=self.download()
        self.assertEqual(code,0,log);self.assertGreaterEqual(self.counts['video4.ts'],3)
        self.assertEqual(sum(len(s['missing']) for s in self.states()),1);self.assert_real_video()

    def test_png_crc_failure_is_not_reported_as_complete(self):
        self.wrap_chunks();file=self.media / 'video4.ts';data=bytearray(file.read_bytes());data[-17]^=1;file.write_bytes(data)
        code,log=self.download()
        self.assertNotEqual(code,0,log);self.assertIn('PNG chunk checksum mismatch',log)
        self.assertTrue(list((self.cache / 'objects').glob('*.bin')))

    @unittest.skipUnless(os.environ.get('FRAGMENT_SAMPLE_FIXTURES'),'Uploaded media fixture directory not set')
    def test_actual_uploaded_chunks_through_normal_download_and_old_cache(self):
        fixture=Path(os.environ['FRAGMENT_SAMPLE_FIXTURES'])
        for i in [1,2,3,6,7]:
            raw=(fixture / ('sample-%03d.bin' % i)).read_bytes()
            (self.media / ('actual%d.ts' % i)).write_bytes(raw)
            resource='actual%d.m3u8' % i
            (self.media / resource).write_text('#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:5.1,\nactual%d.ts\n#EXT-X-ENDLIST\n' % i)
            code,log=self.download(resource=resource);self.assertEqual(code,0,log)
            self.assertIn('Verified PNG-prefixed TS fragment normalized',log)
            metadata=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(self.work / 'video.mp4')]))
            self.assertEqual({s['codec_name'] for s in metadata['streams']},{'h264','aac'})
            self.assertTrue(4.8<float(metadata['format']['duration'])<5.3)
            decode=subprocess.run(['ffmpeg','-nostdin','-v','error','-i',str(self.work / 'video.mp4'),'-map','0:v:0','-map','0:a:0','-f','null','-'],capture_output=True)
            self.assertEqual(decode.returncode,0,decode.stderr.decode());self.assertEqual(decode.stderr,b'')
            previous=self.counts.copy()
            code,log=self.download(resource=resource);self.assertEqual(code,0,log)
            self.assertIn('Reused verified fragment',log);self.assertEqual(previous['actual%d.ts'%i],self.counts['actual%d.ts'%i])
            self.assertTrue(any(p.read_bytes()==raw for p in (self.cache / 'objects').glob('*.bin')))


if __name__ == '__main__': unittest.main()
