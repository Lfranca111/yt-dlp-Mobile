(function () {
  'use strict';
  if (window.__youtubeLiveEvidence) return;
  function capture() {
    try {
      const host = location.hostname.toLowerCase();
      if (!['youtube.com', 'www.youtube.com', 'm.youtube.com'].includes(host)) return null;
      const expected = location.pathname === '/watch' ? new URL(location.href).searchParams.get('v') :
        location.pathname.startsWith('/live/') ? location.pathname.slice(6).replace(/\/$/, '') : '';
      if (!/^[A-Za-z0-9_-]{11}$/.test(expected || '')) return null;
      const responses = [];
      // Current player first; a stale initial response must never select another broadcast.
      const player = document.getElementById('movie_player');
      if (player && typeof player.getPlayerResponse === 'function') {
        try {
          const current = player.getPlayerResponse();
          if (current && current.videoDetails && current.videoDetails.videoId !== expected) return null;
          responses.push(current);
        } catch (_) {}
      }
      responses.push(window.ytInitialPlayerResponse);
      const args = window.ytplayer && window.ytplayer.config && window.ytplayer.config.args;
      if (args && typeof args.player_response === 'string' && args.player_response.length < 2000000) {
        try { responses.push(JSON.parse(args.player_response)); } catch (_) {}
      }
      for (const response of responses) {
        if (!response || !response.videoDetails || response.videoDetails.videoId !== expected) continue;
        const status = response.playabilityStatus && response.playabilityStatus.status;
        const live = response.microformat && response.microformat.playerMicroformatRenderer &&
          response.microformat.playerMicroformatRenderer.liveBroadcastDetails;
        const data = response.streamingData;
        if ((status && status !== 'OK') || (live && (live.isLiveNow === false || live.endTimestamp)) ||
          (data && data.licenseInfos && data.licenseInfos.length)) return null;
        // A getter can omit microformat or the HLS URL. Use another same-video response, never a recommendation.
        if (status !== 'OK' || !live || live.isLiveNow !== true || !data || typeof data.hlsManifestUrl !== 'string') continue;
        if (data.hlsManifestUrl.length > 16384) return null;
        const url = new URL(data.hlsManifestUrl);
        if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') ||
          !(url.hostname === 'googlevideo.com' || url.hostname.endsWith('.googlevideo.com')) ||
          !url.pathname.startsWith('/api/manifest/hls_') || !url.pathname.endsWith('.m3u8')) return null;
        return { videoId: expected, status: status, liveNow: true, manifest: url.href };
      }
    } catch (_) {}
    return null;
  }
  window.__youtubeLiveEvidence = { capture: capture };
})();
