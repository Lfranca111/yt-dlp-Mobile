"""App-scoped public-room compatibility adapter.

Based on yt-dlp's StripchatIE (Unlicense), retrieved 2026-10-10:
https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/stripchat.py
Does not decode MOUFLON or grant access to active private shows.
"""
import base64
import re
import time
from urllib.parse import unquote, urlsplit

from yt_dlp.extractor.stripchat import StripchatIE as _StripchatIE
from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.utils import ExtractorError, UserNotLive, lowercase_escape, parse_iso8601

__all__ = ['MobilePublicStreamIE', 'MobileXHamsterLiveIE']


def _ended_public_show(model, show, now=None):
    if not isinstance(model, dict) or not isinstance(show, dict):
        return False
    if model.get('isLive') is not True or model.get('status') != 'public':
        return False
    model_id, show_id = model.get('id'), show.get('modelId')
    if model_id is None or show_id is None or str(model_id) != str(show_id):
        return False
    ended = show.get('endedAt')
    if not isinstance(ended, str):
        return False
    timestamp = parse_iso8601(ended)
    return timestamp is not None and timestamp <= (time.time() if now is None else now)


def _browser_master(encoded, model_id):
    try:
        decoded = base64.urlsafe_b64decode(encoded).decode('utf-8')
        url = urlsplit(decoded)
        host = (url.hostname or '').lower()
        match = re.search(r'^/hls/(\d+)/master/', url.path)
        if url.scheme != 'https' or url.username or url.password or not match:
            raise ValueError('invalid source')
        if not host.endswith(('.doppiocdn.net', '.doppiocdn.media', '.doppiocdn.live')):
            raise ValueError('unverified media host')
        if match.group(1) != str(model_id):
            raise ValueError('model mismatch')
        return decoded
    except Exception as error:
        raise ExtractorError('Browser stream and page model could not be matched; recording was not started', expected=True) from error


class MobilePublicStreamIE(_StripchatIE, plugin_name='mobile_public_state'):
    _VALID_URL = r'https?://(?:www\.)?(?:stripchat\.com|xhamsterlive\.com)/(?P<id>[^/?#]+)'
    _TESTS = []

    def _real_extract(self, url):
        video_id = self._match_id(url)
        self.to_screen('App compatibility: public-state adapter selected; revision=2')
        webpage = self._download_webpage(url, video_id, headers=self.geo_verification_headers())
        data = self._search_json(
            r'<script\b[^>]*>\s*window\.__PRELOADED_STATE__\s*=',
            webpage, 'data', video_id, transform_source=lowercase_escape)
        view = data.get('viewCam') or {}
        model = view.get('model') or {}
        if not isinstance(model, dict) or model.get('id') is None:
            raise ExtractorError('Page did not provide a usable model identity', expected=True)
        page_name = model.get('username') or view.get('modelUsername')
        if not isinstance(page_name, str) or unquote(video_id).removesuffix('@xh').lower() != page_name.lower():
            raise ExtractorError('Page model does not match the requested room; recording was not started', expected=True)
        if model.get('isLive') is not True:
            raise UserNotLive(video_id=video_id)
        show = view.get('show')
        if show and not _ended_public_show(model, show):
            self.to_screen('App compatibility: active or unverified show record rejected')
            raise ExtractorError('Stream access is not confirmed public; active or unverified show state', expected=True)
        if model.get('status') != 'public':
            raise ExtractorError('Stream access is not confirmed public; recording was not started', expected=True)
        if show:
            self.to_screen('App compatibility: ended historical show ignored; current model is live and public')

        supplied = self._configuration_arg('browser_master', ie_key='Stripchat', casesense=True)
        if not supplied:
            raise ExtractorError('App compatibility requires the browser-detected master playlist; detect the stream again', expected=True)
        master_url = _browser_master(supplied[0], model['id'])
        self.to_screen('App compatibility: page identity and browser stream identity match')
        if self._configuration_arg('public_probe', ie_key='Stripchat') == ['true']:
            self.to_screen('App compatibility: public source verified; returning identity probe only; app relay verifies media')
            return {'id': video_id, 'title': video_id, 'is_live': True, 'age_limit': 18,
                    'formats': [{'url': master_url, 'format_id': 'browser-probe',
                                 'ext': 'mp4', 'protocol': 'm3u8_native'}]}
        manifest = self._download_webpage(master_url, video_id, note='Checking browser-selected master playlist')
        if '#EXTM3U' not in manifest:
            raise ExtractorError('Browser-selected master did not return an HLS playlist', expected=True)
        if 'MOUFLON' in manifest.upper():
            self.to_screen('App compatibility: public source verified; custom MOUFLON processing remains unsupported')
            raise ExtractorError('Public live source verified, but custom MOUFLON playlist processing is not implemented; no recording was started', expected=True)
        formats = self._extract_m3u8_formats(master_url, video_id, ext='mp4', m3u8_id='hls', live=True)
        return {'id': video_id, 'title': video_id, 'is_live': True,
                'formats': formats, 'age_limit': 18}


class MobileXHamsterLiveIE(InfoExtractor):
    # Standalone registration is essential: replacing StripchatIE does not change
    # its pre-generated lazy extractor's suitability expression on all runtimes.
    IE_NAME = 'mobile:xhamsterlive'
    _VALID_URL = r'https?://(?:www\.)?xhamsterlive\.com/(?P<id>[^/?#]+)'
    _TESTS = []
    _real_extract = MobilePublicStreamIE._real_extract
