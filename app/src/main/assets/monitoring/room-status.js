(function (slug) {
  'use strict';
  if (window.__appRoomStatus && window.__appRoomStatus.slug === slug) return;
  const result = window.__appRoomStatus = {slug: slug, pending: true, status: 0, state: '', gate: ''};
  fetch('/get_edge_hls_url_ajax/', {
    method:'POST', credentials:'same-origin',
    headers:{'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'XMLHttpRequest'},
    body:'room_slug='+encodeURIComponent(slug)
  }).then(async function (response) {
    const text=await response.text(); result.status=response.status;
    if (/challenge-platform|cf-chl-|<title[^>]*>\s*just a moment/i.test(text)) result.gate='verification';
    if (response.ok) {
      try {
        const state=String(JSON.parse(text).room_status || '');
        if (['public','offline','private','group','away','hidden'].includes(state)) result.state=state;
      } catch (_) {}
    }
  }).catch(function () { result.status=-1; }).finally(function () { result.pending=false; });
})(__ROOM_SLUG__);
