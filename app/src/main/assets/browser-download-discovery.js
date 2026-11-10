(function () {
  'use strict';
  if (window.__ydlpDownloadDiscovery) return;
  const frame = Math.random().toString(36).slice(2) + Date.now().toString(36);
  let ancestorVisible = window === window.top;
  let session=window===window.top?frame:"", visibilityAt=Date.now();const pending=[];
  const send = data => {
    try {
      if(!session && window!==window.top) { if(pending.length<32) pending.push(data);return; }
      window.YtdlpDownloadEvents.postMessage(JSON.stringify(Object.assign({frame,session,page:location.href,at:Date.now()}, data)));
    } catch (_) {}
  };
  const visible = el => {
    const r=el.getBoundingClientRect(),s=getComputedStyle(el);
    return r.width>80 && r.height>45 && s.display!=='none' && s.visibility!=='hidden' && r.bottom>0 && r.top<innerHeight && r.right>0 && r.left<innerWidth;
  };
  window.addEventListener('message',event=>{
    const d=event.data;
    if(!d || typeof d!=='object') return;
    if(d.__ydlpFrameVisibility && event.source===window.parent && d.frame===frame) {
      ancestorVisible=!!d.visible;visibilityAt=Date.now();session=String(d.session||'');
      if(session) while(pending.length) send(pending.shift());
    }
    if(d.__ydlpFrameQuery) {
      for(const el of document.querySelectorAll('iframe')) if(el.contentWindow===event.source) {
        event.source.postMessage({__ydlpFrameVisibility:true,frame:d.frame,session,visible:visible(el) && (window===window.top || ancestorVisible)},'*');
      }
    }
  });
  const network = (url,kind) => { try { const u=new URL(url,location.href);if(/^https?:$/.test(u.protocol)) send({kind:'network',url:u.href,mediaKind:kind||'observed'}); } catch (_) {} };
  // Vimeo can load HLS through its native player or a worker. Report the
  // frame's observed playlists as well as fetch/XHR responses, without fetching them again.
  if (/(^|\.)vimeo\.com$/i.test(location.hostname) && window.PerformanceObserver) {
    try {
      new PerformanceObserver(list => {
        for (const entry of list.getEntries()) {
          const u = new URL(entry.name);
          if (u.protocol === 'https:' && /(^|\.)(vimeo\.com|vimeocdn\.com)$/i.test(u.hostname) &&
              /\.m3u8$/i.test(u.pathname)) network(u.href, 'hls');
        }
      }).observe({type:'resource',buffered:true});
    } catch (_) {}
  }
  const classify = (url,text,mime) => {
    if(String(text||'').replace(/^\uFEFF/,'').trimStart().startsWith('#EXTM3U')) network(url,'hls');
    else if(/(?:mpegurl|dash\+xml)/i.test(mime||'')) network(url,/dash/i.test(mime)?'dash':'hls');
    else if(/video\/(?:mp4|webm)/i.test(mime||'')) network(url,'video');
  };
  if(window.fetch) {
    const original=window.fetch;
    window.fetch=function(){return original.apply(this,arguments).then(response=>{
      try {
        const mime=response.headers.get('content-type')||'';
        if(/mpegurl|dash\+xml|video\/(mp4|webm)/i.test(mime)) classify(response.url,'',mime);
        else if(response.body) {
          const reader=response.clone().body.getReader();
          reader.read().then(result=>{if(result.value) classify(response.url,new TextDecoder().decode(result.value.slice(0,128)),mime);reader.cancel().catch(()=>{});}).catch(()=>{});
        }
      } catch(_) {}
      return response;
    });};
  }
  const open=XMLHttpRequest.prototype.open,sendXHR=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(method,url){this.__ydlpUrl=String(url);return open.apply(this,arguments);};
  XMLHttpRequest.prototype.send=function(){
    this.addEventListener('load',()=>{
      try {
        let prefix='';
        if(this.responseType==='' || this.responseType==='text') prefix=this.responseText.slice(0,128);
        else if(this.responseType==='arraybuffer' && this.response) prefix=new TextDecoder().decode(this.response.slice(0,128));
        classify(this.responseURL||this.__ydlpUrl,prefix,this.getResponseHeader('content-type'));
      } catch(_) {}
    },{once:true});return sendXHR.apply(this,arguments);
  };
  const report=()=>{
    if(window===window.top) send({kind:'document'});
    if(window!==window.top) window.parent.postMessage({__ydlpFrameQuery:true,frame},'*');
    const adPlaying=['.ad-showing','.ad-playing','.video-ads','.ima-ad-container','[class*="adPlaying"]','[class*="ad-playing"]','[data-ad-state="playing"]'].some(selector=>Array.from(document.querySelectorAll(selector)).some(visible));
    send({kind:'ad',playing:adPlaying});
    const videos=Array.from(document.querySelectorAll('video')).filter(visible).sort((a,b)=>b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight);
    const v=videos[0];
    if(v) {
      const src=v.currentSrc||v.src||'';
      send({kind:'player',url:src,playing:!v.paused&&!v.ended,visible:window===window.top || (ancestorVisible && Date.now()-visibilityAt<2500),
        duration:Number.isFinite(v.duration)?v.duration:0,drm:!!v.mediaKeys,area:v.clientWidth*v.clientHeight,title:document.title});
      if(/^https?:/.test(src)) network(src,'player');
    }
    const root=document.querySelector('.layout-page-watchtv');
    if(root) {
      const selected=document.querySelector('.type li.active[data-link-id],.type li.selected[data-link-id],.type li[data-link-id][aria-selected="true"]');
      if(selected) send({kind:'choice',audio:(selected.closest('[data-type]')||{}).dataset?.type||'',server:selected.textContent.trim()});
    }
  };
  document.addEventListener('click',e=>{
    const target=e.target instanceof Element?e.target.closest('[data-link-id],button,a,[data-server],[data-source]'):null;
    if(!target) return;
    const label=target.textContent.trim().replace(/\s+/g,' ');
    const holder=target.closest('[data-type]');
    const audio=holder?.dataset?.type||'';
    const isServer=/(?:player\s*\(?[12]|[12]\.?\s*player)/i.test(label) || target.hasAttribute('data-link-id') || target.hasAttribute('data-server') || target.classList.contains('server-btn') || /^Server\s+\d+$/i.test(label);
    if(isServer && label.length<61) send({kind:'switch',audio,server:label,visible:window===window.top||ancestorVisible,trusted:e.isTrusted});
    else if(/^(SUB|DUB)$/i.test(label)) send({kind:'switch',audio:label.toLowerCase(),server:'',visible:window===window.top||ancestorVisible,trusted:e.isTrusted});
  },true);
  window.__ydlpDownloadDiscovery={report};
  setInterval(report,1000);
  document.addEventListener('play',report,true);document.addEventListener('loadedmetadata',report,true);
  if(document.readyState!=='loading') report();else document.addEventListener('DOMContentLoaded',report,{once:true});
})();
