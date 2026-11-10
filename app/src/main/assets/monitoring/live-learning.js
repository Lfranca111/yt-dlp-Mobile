(function () {
  'use strict';
  if (window.__customLive && window.__customLive.version === 124) return true;
  function selector(element) {
    const steps = [];
    for (let n = element; n && n.nodeType === 1 && steps.length < 7; n = n.parentElement) {
      let part = n.tagName.toLowerCase();
      const classes = Array.from(n.classList || []).filter(c => /^[A-Za-z_-]{2,35}$/.test(c)).slice(0, 3);
      if (classes.length) part += '.' + classes.join('.');
      steps.unshift(part);
      if (document.querySelectorAll(steps.join(' > ')).length === 1) break;
    }
    return steps.join(' > ');
  }
  function cancel() {
    if (window.__customLivePick) document.removeEventListener('click', window.__customLivePick, true);
    window.__customLivePick = null;
  }
  function pickLink() {
    cancel();
    if (window.__channelLearning) window.__channelLearning.cancelPick();
    if (window.__monitorPickHandler) document.removeEventListener('click', window.__monitorPickHandler, true);
    window.__customLivePicked = null;
    window.__customLivePick = function (event) {
      const element = event.target.closest('a[href],button,[role=button],[onclick]') ||
        event.target.querySelector('a[href],button,[role=button],[onclick]');
      if (!element) return;
      event.preventDefault(); event.stopImmediatePropagation();
      window.__customLivePicked = { selector: selector(element), url: element.href || '' };
      element.style.outline = '3px solid #7c4dff';
      cancel();
    };
    document.addEventListener('click', window.__customLivePick, true);
  }
  function follow(rule) {
    const elements = document.querySelectorAll(rule);
    if (elements.length !== 1) return String(elements.length);
    const element = elements[0];
    const target = element.matches('a,button,[role=button],[onclick]') ? element : element.querySelector('a,button,[role=button],[onclick]');
    if (!target) return 'missing';
    document.querySelectorAll('video,audio').forEach(v => v.pause());
    target.click();
    return 'clicked';
  }
  const attempts = new WeakMap();
  function capture() {
    const videos = Array.from(document.querySelectorAll('video'));
    videos.sort((a, b) => {
      const x = a.getBoundingClientRect(), y = b.getBoundingClientRect();
      return y.width * y.height - x.width * x.height;
    });
    videos.forEach(v => { v.muted = true; });
    const chosen = videos[0];
    if (chosen && chosen.paused) {
      let attempt = attempts.get(chosen);
      if (!attempt || (!attempt.pending && Date.now() - attempt.at >= 1500)) {
        attempt = { at: Date.now(), pending: true, error: '' };
        attempts.set(chosen, attempt);
        function failed(error) {
          attempt.pending = false;
          const allowed = ['NotAllowedError', 'NotSupportedError', 'AbortError', 'SecurityError', 'InvalidStateError'];
          attempt.error = allowed.includes(error && error.name) ? error.name : 'PlaybackError';
        }
        try {
          const promise = chosen.play();
          if (promise && promise.then) promise.then(() => { attempt.pending = false; attempt.error = ''; }, failed);
          else attempt.pending = false;
        } catch (error) { failed(error); }
      }
    }
    const attempt = chosen && attempts.get(chosen);
    const resources = [];
    try {
      const origin = Number(performance.timeOrigin) || Date.now() - performance.now();
      for (const r of performance.getEntriesByType('resource').slice(-500)) {
        // Keep the real observation time. Re-reading history must not refresh an expired URL.
        const end = Number(r.responseEnd);
        if (end > 0 && typeof r.name === 'string' && r.name.length <= 8192)
          resources.push({ url: r.name, at: Math.floor(origin + end) });
      }
    } catch (_) {}
    const playing = videos.find(v => !v.paused && !v.ended);
    return {
      url: location.href, title: document.title,
      visibleText: (document.body ? document.body.innerText || document.body.textContent : '').slice(0, 200000),
      html: window.__channelLearning ? window.__channelLearning.snapshot() : document.documentElement.outerHTML.slice(0, 3000001),
      player: JSON.stringify({ playing: !!playing, videoCount: videos.length,
        youtube: window.__youtubeLiveEvidence ? window.__youtubeLiveEvidence.capture() : null,
        frameCount: document.querySelectorAll('iframe').length,
        playPending: !!(attempt && attempt.pending), playError: attempt ? attempt.error : '',
        mediaError: chosen && chosen.error ? Number(chosen.error.code) || 0 : 0,
        readyState: chosen ? chosen.readyState : 0, resources: resources, drm: videos.some(v => !!v.mediaKeys),
        ad: !!document.querySelector('.ad-showing,.ad-playing,.ima-ad-container,[data-ad-state="playing"]'),
        urls: videos.flatMap(v => [v.currentSrc, v.src].concat(Array.from(v.querySelectorAll('source')).map(s => s.src))).filter(Boolean) })
    };
  }
  window.__customLive = { version: 124, pickLink, cancel, follow, capture };
  return true;
})();
