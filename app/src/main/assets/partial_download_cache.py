"""App-scoped VOD cache. Loaded only by normal main/monitor downloads, never Browser Assist.
Keep yt-dlp's HTTP transport, decryption and assembly. Cache verified RAW fragments,
including the full resource and encryption identity; changed signed URLs are cache misses.
"""
import copy
import threading
import hashlib
import json
import os
import shutil
import time
import uuid
import zlib
from pathlib import Path
from yt_dlp.downloader.fragment import FragmentFD
from yt_dlp.downloader.http import HttpFD
from yt_dlp.utils import DownloadError

ROOT = Path(__file__).resolve().parents[4]
CACHE = ROOT / 'objects'
CACHE.mkdir(parents=True, exist_ok=True)
CONFIG = json.loads((ROOT / 'config.json').read_text())
BINDING = CONFIG['binding']
_orig_download = FragmentFD.download
_orig_fragment = FragmentFD._download_fragment
_orig_append = FragmentFD.download_and_append_fragments
_orig_skip = FragmentFD.report_skip_fragment
_orig_http = HttpFD.download
_orig_decrypter = FragmentFD.decrypter


def digest(data):
    return hashlib.sha256(data).hexdigest()


def stable(value):
    return json.dumps(value, sort_keys=True, default=lambda v: v.hex() if isinstance(v, bytes) else str(v), separators=(',', ':'))


def atomic(path, data):
    temp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.new')
    temp.write_text(stable(data))
    os.replace(temp, path)


def checkpoint(fd):
    with fd._app_lock:
        atomic(fd._app_state_path, fd._app_state)


PNG = b'\x89PNG\r\n\x1a\n'


def ts_start(data, start=8):
    for offset in range(start, min(len(data) - 376, 65536)):
        if all(offset + step * 188 + 3 < len(data) and data[offset + step * 188] == 0x47 and data[offset + step * 188 + 3] & 0x30 for step in range(3)):
            return offset
    return -1


def image_only(data):
    if not data or len(data) > 65536 or not data.startswith(PNG):
        return False
    position = 0
    while position < len(data):
        if not data.startswith(PNG, position):
            return False
        position += 8
        ended = False
        while position + 12 <= len(data):
            length = int.from_bytes(data[position:position + 4], 'big')
            kind = data[position + 4:position + 8]
            if length > 65536 or position + 12 + length > len(data):
                return False
            if kind == b'tsDa' and length:
                return False  # A small PNG can still carry real transport media.
            position += 12 + length
            if kind == b'IEND':
                if length:
                    return False
                ended = True
                break
        if not ended:
            return False
        while position < len(data) and data[position] == 0:
            position += 1
    return True


def png_chunk_framed(data):
    if not data or not data.startswith(PNG):
        return False
    position = 8
    while position + 8 <= len(data):
        length = int.from_bytes(data[position:position + 4], 'big')
        kind = data[position + 4:position + 8]
        if kind == b'tsDa':
            return True
        if kind == b'IEND' or position + 12 + length > len(data):
            return False
        position += 12 + length
    return False


def png_transport_chunks(data):
    # A real PNG container: TS is INSIDE tsDa, not merely after a PNG prefix.
    # Preserve the entire declared transport payload, including sync anomalies,
    # for FFmpeg's transport demuxer. Never append PNG CRC/IEND bytes to media.
    position, pieces, wrappers = 0, [], 0
    while position < len(data):
        if not data.startswith(PNG, position):
            raise DownloadError('Expected next PNG container after IEND; raw cache retained')
        position += 8
        wrappers += 1
        chunk_index = 0
        while True:
            if position + 12 > len(data):
                raise DownloadError('Truncated PNG chunk; raw cache retained')
            length = int.from_bytes(data[position:position + 4], 'big')
            kind = data[position + 4:position + 8]
            if length > 0x7fffffff or position + 12 + length > len(data):
                raise DownloadError('Invalid PNG chunk length; raw cache retained')
            if not all(65 <= c <= 90 or 97 <= c <= 122 for c in kind):
                raise DownloadError('Invalid PNG chunk type; raw cache retained')
            if chunk_index == 0 and (kind != b'IHDR' or length != 13):
                raise DownloadError('PNG IHDR missing or invalid; raw cache retained')
            chunk_index += 1
            body = data[position + 8:position + 8 + length]
            expected = int.from_bytes(data[position + 8 + length:position + 12 + length], 'big')
            if zlib.crc32(body, zlib.crc32(kind)) & 0xffffffff != expected:
                raise DownloadError('PNG chunk checksum mismatch; raw cache retained')
            if kind == b'tsDa' and length:
                if length < 564 or not all(body[k * 188] == 0x47 and body[k * 188 + 3] & 0x30 for k in range(3)):
                    raise DownloadError('PNG tsDa payload lacks verified transport sync; raw cache retained')
                pieces.append(body)
            position += 12 + length
            if kind == b'IEND':
                if length:
                    raise DownloadError('Invalid PNG IEND length; raw cache retained')
                break
    return b''.join(pieces) or None, wrappers


def normalize_ts(data):
    if png_chunk_framed(data):
        return png_transport_chunks(data)
    if not data or not data.startswith(PNG):
        return data, 0
    position, pieces, wrappers = 0, [], 0
    while position < len(data):
        if data[position] == 0x47 and position + 188 <= len(data):
            begin = position
            while position + 188 <= len(data) and data[position] == 0x47:
                position += 188
            pieces.append(data[begin:position])
            continue
        if not data.startswith(PNG, position):
            raise DownloadError('Unrecognized bytes after a wrapped transport segment; raw cache retained')
        offset = ts_start(data[position:position + 65536])
        if offset < 0:
            if image_only(data[position:]):
                if pieces:
                    raise DownloadError('Image-only tail within a media fragment; raw cache retained for local recovery')
                break
            raise DownloadError('Image-prefixed response has no verified transport payload; raw cache retained')
        wrappers += data[position:position + offset].count(PNG)
        position += offset
    return b''.join(pieces) or None, wrappers


def decrypter(self, info):
    original = _orig_decrypter(self, info)
    def decode(fragment, content):
        plain = original(fragment, content)
        if not hasattr(self, '_app_state'):
            return plain
        normalized, count = normalize_ts(plain)
        if normalized:
            self._app_streak = 0  # Assembly order, never out-of-order worker completion.
        if count:
            self._app_state['normalized_image_wrappers'] = self._app_state.get('normalized_image_wrappers', 0) + count
            total = self._app_state['normalized_image_wrappers']
            if total <= 3 or total % 100 == 0:
                self.to_screen('[app-media] Verified PNG-prefixed TS fragment normalized; total wrappers=%d; raw cache kept' % total)
                checkpoint(self)
        return normalized
    return decode


def download(self, filename, info_dict, *args, **kwargs):
    if info_dict.get('is_live') or filename == '-':
        return _orig_download(self, filename, info_dict, *args, **kwargs)
    # A new assembly starts at fragment one, reading compatible completed fragments
    # from disk. This also revisits holes skipped by an earlier attempt.
    archive = ROOT / 'recovery' / uuid.uuid4().hex
    for name in (filename, self.temp_name(filename), self.ytdl_filename(filename)):
        source = Path(name)
        if source.is_file():
            archive.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), str(archive / source.name))
    track = digest(stable([BINDING, info_dict.get('id'), info_dict.get('format_id'), info_dict.get('vcodec'), info_dict.get('acodec'), info_dict.get('width'), info_dict.get('height')]).encode())
    self._app_lock = threading.RLock()
    self._app_track = track
    self._app_state_path = ROOT / ('track-' + track + '.json')
    self._app_state = {'missing': [], 'complete': False, 'reused': 0}
    self._app_streak = 0
    self._app_seen = 0
    checkpoint(self)
    self.params['continuedl'] = False
    self.to_screen('[app-resume] Fragment cache active; compatible pieces reused, missing pieces retried; resource values omitted')
    result = _orig_download(self, filename, info_dict, *args, **kwargs)
    self._app_state['complete'] = bool(result[0])
    checkpoint(self)
    return result


def append(self, ctx, fragments, info_dict, **kwargs):
    if not hasattr(self, '_app_state'):
        return _orig_append(self, ctx, fragments, info_dict, **kwargs)
    # Materialize only finite VOD lists. Include byte ranges and encryption/MAP
    # metadata in the identity: a segment ordinal alone is NEVER a cache key.
    fragments = list(fragments)
    info_dict = dict(info_dict)
    info_dict['_app_assembly_identity'] = stable([info_dict.get('url'), fragments[0] if fragments else None])
    info_dict['_app_fragment_identity'] = {f['frag_index']: stable(f) for f in fragments}
    # An unchanged key URI does not prove that the key bytes stayed unchanged.
    # Re-fetch encrypted fragments unless the actual key is part of the identity.
    info_dict['_app_no_reuse'] = {f['frag_index'] for f in fragments if f.get('decrypt_info', {}).get('METHOD', 'NONE') != 'NONE' and not f.get('decrypt_info', {}).get('KEY')}
    self._app_state['total'] = len(fragments)
    checkpoint(self)
    return _orig_append(self, ctx, fragments, info_dict, **kwargs)


def fragment(self, ctx, url, info, headers=None, request_data=None):
    if not hasattr(self, '_app_state'):
        return _orig_fragment(self, ctx, url, info, headers, request_data)
    index = ctx['fragment_index']
    identity = info.get('_app_fragment_identity', {}).get(index)
    if identity is None:
        raise DownloadError('Fragment identity unavailable; retained media kept')
    key = digest(stable([BINDING, info.get('id'), info.get('format_id'), info.get('_app_assembly_identity'), identity, url, (headers or {}).get('Range'), request_data]).encode())
    obj, receipt = CACHE / (key + '.bin'), CACHE / (key + '.json')
    target = Path('%s-Frag%d' % (ctx['tmpfilename'], index))
    ctx.pop('fragment_filename_sanitized', None)
    with self._app_lock:
        self._app_seen += 1
    if self.params.get('concurrent_fragment_downloads', 1) > 1:
        # Native workers copy ctx but share HttpFD. Our local progress hooks/params
        # must not mutate another worker's downloader. HTTP transport stays native.
        ctx['dl'] = copy.copy(ctx['dl'])
        ctx['dl'].params = dict(ctx['dl'].params)
        ctx['dl']._progress_hooks = list(ctx['dl']._progress_hooks)
    if index not in info.get('_app_no_reuse', set()) and obj.is_file() and receipt.is_file():
        try:
            meta = json.loads(receipt.read_text())
            raw = obj.read_bytes()
            if len(raw) == meta['bytes'] and digest(raw) == meta['sha256'] and not image_only(raw):
                atomic(receipt, {**meta, 'fragment_index': index, 'track_key': self._app_track})
                shutil.copyfile(obj, target)
                ctx['fragment_filename_sanitized'] = str(target)
                with self._app_lock:
                    self._app_state['reused'] += 1
                    checkpoint(self)
                if self._app_state['reused'] <= 3 or self._app_state['reused'] % 100 == 0:
                    self.to_screen('[app-resume] Reused verified fragment %d' % index)
                return True
        except (OSError, ValueError, KeyError):
            pass
    # Prevent yt-dlp accepting a leftover fragment without a verified receipt.
    target.unlink(missing_ok=True)
    Path(str(target) + '.part').unlink(missing_ok=True)
    # Empty response bodies return False without the native fragment retry loop.
    # Keep that failure local, and retry it here three times before skipping.
    ctx['dl'].params['retries'] = 0
    ctx['dl'].report_error = lambda message, *a, **kw: self.report_warning('Fragment response: ' + str(message))
    hooks = ctx['dl']._progress_hooks
    pending = []
    def progress(status):
        if status.get('status') == 'finished':
            pending.append(status.copy())
        else:
            for hook in hooks:
                hook(status)
    ctx['dl']._progress_hooks = [progress]
    try:
        for attempt in range(3):
            ctx['fragment_index'] = index
            pending.clear()
            if _orig_fragment(self, ctx, url, info, headers, request_data):
                data = target.read_bytes()
                if data and not image_only(data):
                    temporary = obj.with_suffix('.new')
                    temporary.write_bytes(data)
                    os.replace(temporary, obj)
                    atomic(receipt, {'bytes': len(data), 'sha256': digest(data), 'fragment_index': index, 'track_key': self._app_track})
                    for status in pending:
                        for hook in hooks:
                            hook(status)
                    return True
            ctx.pop('fragment_filename_sanitized', None)
            target.unlink(missing_ok=True)
            Path(str(target) + '.part').unlink(missing_ok=True)
            if attempt < 2:
                self.to_screen('[app-media] Empty fragment or image-only response %d; retry %d/2' % (index, attempt + 1))
                time.sleep(attempt + 1)
        return False
    finally:
        ctx['dl']._progress_hooks = hooks


def skip(self, index, err=None):
    if hasattr(self, '_app_state'):
        self._app_streak += 1
        self._app_state['missing'].append(index)
        checkpoint(self)
        # A few isolated holes are salvageable; whole blocked stretches are not.
        if self._app_streak >= 3 or (self._app_seen >= 20 and len(self._app_state['missing']) > self._app_seen / 10):
            raise DownloadError('Too many unavailable fragments; download stopped, partial media and cache retained')
    return _orig_skip(self, index, err)


def http(self, filename, info, *args, **kwargs):
    # --force-overwrites prevents an old assembled VOD being mistaken for complete.
    # Plain HTTP downloads still use their ordinary byte-range resume support.
    self.params['continuedl'] = True
    return _orig_http(self, filename, info, *args, **kwargs)


FragmentFD.download = download
FragmentFD.download_and_append_fragments = append
FragmentFD._download_fragment = fragment
FragmentFD.report_skip_fragment = skip
FragmentFD.decrypter = decrypter
HttpFD.download = http


from yt_dlp.postprocessor.common import PostProcessor


class AppPartialCachePP(PostProcessor):
    def run(self, info):
        return [], info
