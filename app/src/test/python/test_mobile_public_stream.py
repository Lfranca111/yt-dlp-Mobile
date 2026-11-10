"""Offline tests. Run with yt-dlp installed; no stream or credential downloads."""
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from yt_dlp import YoutubeDL
from yt_dlp.utils import ExtractorError, UserNotLive

PLUGIN = Path(__file__).resolve().parents[2] / 'main/assets/yt_dlp_plugins/extractor/mobile_public_stream.py'
spec = importlib.util.spec_from_file_location('mobile_public_state_test', PLUGIN)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class PublicStateTests(unittest.TestCase):
    def model(self, **changes):
        return dict({'id': 123, 'username': 'test_model', 'isLive': True, 'status': 'public'}, **changes)

    def show(self, **changes):
        return dict({'modelId': 123, 'mode': 'private', 'endedAt': '2020-01-01T00:00:00Z'}, **changes)

    def extractor(self, model=None, show=None, manifest='#EXTM3U\n#EXT-X-MOUFLON:PSCH:v2:placeholder\n', stream_id=123):
        master = f'https://edge-hls.doppiocdn.net/hls/{stream_id}/master/{stream_id}_auto.m3u8?case=AbC'
        encoded = base64.urlsafe_b64encode(master.encode()).decode()
        downloader = YoutubeDL({'quiet': True, 'no_warnings': True,
            'extractor_args': {'stripchat': {'browser_master': [encoded]}}}, auto_init=False)
        ie = module.MobilePublicStreamIE(downloader)
        messages = []
        ie.to_screen = messages.append
        page = '<script>window.__PRELOADED_STATE__=' + json.dumps({'viewCam': {'model': model or self.model(), 'show': show}}) + '</script>'
        ie._download_webpage = lambda url, *a, **k: manifest if 'doppiocdn' in url else page
        ie._extract_m3u8_formats = lambda *a, **k: [{'url': 'https://example.test/public.ts', 'format_id': 'hls'}]
        return ie, messages

    def test_ended_public_record_advances_to_custom_format_diagnostic(self):
        ie, messages = self.extractor(show=self.show())
        with self.assertRaisesRegex(ExtractorError, 'custom MOUFLON playlist processing is not implemented'):
            ie._real_extract('https://stripchat.com/test_model')
        self.assertTrue(any('historical show ignored' in x for x in messages))
        self.assertTrue(any('identity match' in x for x in messages))

    def test_active_show_is_not_ignored(self):
        ie, _ = self.extractor(show=self.show(endedAt=None))
        with self.assertRaisesRegex(ExtractorError, 'active or unverified show'):
            ie._real_extract('https://stripchat.com/test_model')

    def test_private_status_still_rejected(self):
        ie, _ = self.extractor(model=self.model(status='private'), show=self.show())
        with self.assertRaisesRegex(ExtractorError, 'not confirmed public'):
            ie._real_extract('https://stripchat.com/test_model')

    def test_unknown_status_is_not_assumed_public(self):
        ie, _ = self.extractor(model=self.model(status=None), manifest='#EXTM3U\n')
        with self.assertRaisesRegex(ExtractorError, 'not confirmed public'):
            ie._real_extract('https://stripchat.com/test_model')

    def test_future_invalid_and_other_model_end_records_are_rejected(self):
        for change in [{'endedAt': '2999-01-01T00:00:00Z'}, {'endedAt': 'invalid'}, {'modelId': 999}]:
            with self.subTest(change=change):
                self.assertFalse(module._ended_public_show(self.model(), self.show(**change)))

    def test_browser_stream_identity_mismatch_rejected(self):
        ie, _ = self.extractor(show=self.show(), stream_id=999)
        with self.assertRaisesRegex(ExtractorError, 'could not be matched'):
            ie._real_extract('https://stripchat.com/test_model')

    def test_page_room_mismatch_rejected(self):
        ie, _ = self.extractor()
        with self.assertRaisesRegex(ExtractorError, 'does not match'):
            ie._real_extract('https://stripchat.com/another_model')

    def test_offline_remains_offline(self):
        ie, _ = self.extractor(model=self.model(isLive=False))
        with self.assertRaises(UserNotLive):
            ie._real_extract('https://stripchat.com/test_model')

    def test_domains_match_without_affecting_other_sites(self):
        for host in ['stripchat.com', 'www.stripchat.com', 'xhamsterlive.com', 'www.xhamsterlive.com']:
            self.assertTrue(module.MobilePublicStreamIE.suitable('https://' + host + '/test_model'))
        self.assertFalse(module.MobilePublicStreamIE.suitable('https://youtube.com/test_model'))
        self.assertFalse(module.MobilePublicStreamIE.suitable('https://stripchat.com.evil.test/test_model'))

    def test_alias_can_extract_ordinary_public_manifest(self):
        ie, messages = self.extractor(manifest='#EXTM3U\n')
        result = ie._real_extract('https://xhamsterlive.com/test_model')
        self.assertTrue(result['is_live'])
        self.assertEqual(len(result['formats']), 1)
        self.assertTrue(any('identity match' in x for x in messages))

    def test_encoded_master_preserves_case_and_restricts_hosts(self):
        original = 'https://edge-hls.doppiocdn.media/hls/123/master/123_auto.m3u8?signature=AbCdE'
        self.assertEqual(module._browser_master(base64.urlsafe_b64encode(original.encode()).decode(), 123), original)
        with self.assertRaises(ExtractorError):
            module._browser_master(base64.urlsafe_b64encode(b'https://evil.test/hls/123/master/x.m3u8').decode(), 123)

    def test_actual_runtime_loads_plugin_from_folder_layout(self):
        with tempfile.TemporaryDirectory() as root:
            destination = Path(root) / 'mobile-public-state/yt_dlp_plugins/extractor/mobile_public_stream.py'
            destination.parent.mkdir(parents=True)
            shutil.copyfile(PLUGIN, destination)
            loader = "from yt_dlp.globals import plugin_dirs; import sys; plugin_dirs.value=[sys.argv[1]]; from yt_dlp import YoutubeDL; y=YoutubeDL({'quiet':True}); ie=y.get_info_extractor('Stripchat'); print(ie.IE_NAME); print(ie.suitable('https://xhamsterlive.com/test_model'))"
            result = subprocess.run([sys.executable, '-c', loader, root],
                capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('Stripchat+mobile_public_state', result.stdout)
            self.assertIn('True', result.stdout)

    def test_runtime_automatic_alias_dispatch_with_lazy_extractors(self):
        with tempfile.TemporaryDirectory() as root:
            destination = Path(root) / 'mobile-public-state/yt_dlp_plugins/extractor/mobile_public_stream.py'
            destination.parent.mkdir(parents=True)
            shutil.copyfile(PLUGIN, destination)
            loader = """
from yt_dlp.globals import plugin_dirs
import sys
plugin_dirs.value=[sys.argv[1]]
from yt_dlp import YoutubeDL
y=YoutubeDL({'quiet':True, 'skip_download':True})
ie=y.get_info_extractor('MobileXHamsterLive')
ie._real_extract=lambda url: {'id':'test', 'title':'test', 'url':'https://example.test/test.mp4', 'ext':'mp4'}
result=y.extract_info('https://xhamsterlive.com/test_model',download=False)
print(result['extractor_key'])
"""
            result = subprocess.run([sys.executable, '-c', loader, root],capture_output=True,text=True,timeout=20)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('MobileXHamsterLive',result.stdout)

    def test_public_probe_passes_identity_only_without_fetching_media(self):
        ie, messages = self.extractor(show=self.show())
        old = ie._configuration_arg
        ie._configuration_arg = lambda name, *a, **k: ['true'] if name=='public_probe' else old(name,*a,**k)
        result = ie._real_extract('https://stripchat.com/test_model')
        self.assertEqual(result['formats'][0]['format_id'],'browser-probe')
        self.assertTrue(any('identity probe only' in line for line in messages))

    def test_probe_cannot_skip_private_state_check(self):
        ie, _ = self.extractor(model=self.model(status='private'))
        old = ie._configuration_arg
        ie._configuration_arg = lambda name, *a, **k: ['true'] if name=='public_probe' else old(name,*a,**k)
        with self.assertRaisesRegex(ExtractorError,'not confirmed public'):
            ie._real_extract('https://stripchat.com/test_model')


if __name__ == '__main__':
    unittest.main()
