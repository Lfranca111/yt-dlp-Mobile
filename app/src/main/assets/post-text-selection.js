/* Shared Save Post body evidence. Runs only when the user requests Save Post. */
(function () {
  if (window.__postTextSelection) return;
  const X_RENDERED = 'div[dir=auto][lang].whitespace-pre-wrap.wrap-anywhere';
  const STRONG = '[data-ad-rendering-role=story_message], [data-testid=tweetText], [data-ad-preview=message], [data-ad-comet-preview=message], [data-testid=post_message], [itemprop~=articleBody], .post-content, .entry-content, .status__content';
  const BLOCK = STRONG + ', p, [role=paragraph], [data-lexical-text=true], [dir=auto][lang], [dir=auto], [data-mcomponent=TextArea]';
  const NOISE = '[hidden],[aria-hidden=true],nav,aside,footer,form,input,textarea,select,[role=navigation],[role=alert],[data-testid=User-Name],[data-testid=socialContext],[role=group],time,shreddit-comment,.comment,.comments,[data-testid=quoteTweet],[data-testid="card.wrapper"],[data-testid=reply]';
  const HEADING = 'header,h1,h2,h3,h4,h5,h6,[role=heading]';
  const INTERACTIVE = 'a[href],button,input,textarea,select,label,[role=button],[role=link],[contenteditable],nav,[role=navigation]';
  const X_ARTICLE = 'article,[role=article],[data-testid=tweet]';
  const FB_HOST = /^(?:(?:www|m|mbasic|web)\.)?facebook\.com$/i;
  const REDDIT_HOST = /^(?:(?:www|m|old|new)\.)?reddit\.com$|^redd\.it$/i;
  const FB_RENDERED = 'div[dir=auto].wbloks_1';
  const FB_ROOT = 'article,[role=article],[data-ft],[data-testid=post-container],[aria-posinset]';
  const FB_BODY = '[data-ad-preview=message],[data-ad-comet-preview=message],[data-testid=post_message],[data-ad-rendering-role=story_message],[data-sigil~="story-body"]';
  const MAX = 300000;
  const normalized = value => String(value || '').replace(/\s+/g, ' ').trim();
  const absolute = value => { try { return new URL(value, location.href); } catch (_) { return null; } };
  function fbRoute(url) {
    if (!url || !FB_HOST.test(url.hostname) || !/^https?:$/.test(url.protocol)) return null;
    const match = /^\/(?:share\/p\/([A-Za-z0-9_-]+)|(?:groups\/[^/]+|[^/]+)\/(?:posts|permalink)\/([A-Za-z0-9_.-]+))(?:\/)?$/i.exec(url.pathname);
    if (match) return {kind: match[1] ? 'share' : 'post', id: match[1] || match[2]};
    if (/^\/(?:story|permalink)\.php$/i.test(url.pathname)) {
      const id = url.searchParams.get('story_fbid');
      if (id && /^[a-z0-9_.-]+$/i.test(id)) return {kind: 'post', id};
    }
    return null;
  }
  function visible(el) {
    if (!el || el.closest(NOISE) || !el.getClientRects().length) return false;
    for (let n=el; n && n!==document.documentElement; n=n.parentElement) {
      const s=getComputedStyle(n);
      if (s.display==='none' || s.visibility!=='visible' || Number(s.opacity)===0) return false;
    }
    return true;
  }
  function painted(body, items=[body]) {
    const width=document.documentElement.clientWidth;
    const height=document.documentElement.clientHeight;
    return items.some(item=>Array.from(item.getClientRects()).slice(0,32).some(rect=>{
      const left=Math.max(rect.left,0), right=Math.min(rect.right,width);
      const top=Math.max(rect.top,0), bottom=Math.min(rect.bottom,height);
      if(right-left<4 || bottom-top<4) return false;
      const hit=document.elementFromPoint((left+right)/2,(top+bottom)/2);
      return !!hit && (hit===body || body.contains(hit));
    }));
  }
  const MORE = /^(?:see|show|read) more(?:…|\.\.\.)?$/i;
  const LESS = /^(?:see|show|read) less$/i;
  const REEL_MORE = /^(?:(?:see|show|read) )?more(?:…|\.\.\.)?$/i;
  const REEL_PATH = /^\/reel\/[0-9]+\/?$/i;
  const INTERACTION_CONTROL = 'button,[role=button],[tabindex="0"],[aria-expanded],a[href]';
  function expansionControl(node, pattern=MORE) {
    const leafLabel=node?.childElementCount===0 && pattern.test(normalized(node.innerText));
    for(let depth=0;node && depth<4;depth++,node=node.parentElement) {
      if(node.matches(INTERACTION_CONTROL) && (leafLabel ||
         [node.getAttribute('aria-label'),node.innerText].some(value=>pattern.test(normalized(value))))) return node;
    }
    return null;
  }
  function ownedExpansion(body, pattern=MORE) {
    if (!body) return false;
    return Array.from(body.querySelectorAll(INTERACTION_CONTROL + ',span')).slice(0,96).some(node=>{
      if(!visible(node)) return false;
      const control=expansionControl(node,pattern);
      return control && body.contains(control);
    });
  }
  function mobileTextParts(body) {
    const children=Array.from(body.children);
    if(children.length<1 || children.length>24) return [];
    const readable=children.filter(node=>node.tagName==='SPAN' || node.tagName==='A');
    if(!readable.length || children.some(node=>!readable.includes(node) &&
        node.tagName!=='BR' && !expansionControl(node,MORE) && !expansionControl(node,LESS))) return [];
    // Inline links/mentions and BRs are part of the owned body, but a second
    // card, media element, toolbar, or reply scaffold never is.
    if(body.querySelector('article,[role=article],video,audio,iframe,[role=navigation]')) return [];
    return readable.filter(node=>visible(node) && normalized(node.innerText));
  }
  function preserveAuthoredLines(root) {
    function visit(node) {
      Array.from(node.childNodes).forEach(child=>{
        if(child.nodeType!==Node.TEXT_NODE) { visit(child); return; }
        const text=child.nodeValue;
        if(!text.includes('\n') || !text.trim()) return;
        const parts=text.replace(/\r\n?/g,'\n').split('\n');
        const line=document.createDocumentFragment();
        parts.forEach((part,index)=>{
          if(index) line.append(document.createElement('br'));
          if(part) line.append(document.createTextNode(part));
        });
        child.replaceWith(line);
      });
    }
    visit(root);
  }
  function mobileDetailCard(item, main) {
    // Observed Android story detail layout: a four-slot body/media section
    // between header and footer. Suggested-post cards use a different
    // structure and must not become the main post when scrolled into view.
    const media=item.card;
    const slots=Array.from(media.children);
    if(slots.length!==4 || !slots[1].contains(item.body) ||
      slots[0].getBoundingClientRect().height>1 || slots[2].getBoundingClientRect().height>1) return false;
    let section=media;
    for(let depth=0;depth<3 && section!==main && section.parentElement;depth++) {
      const outer=section.parentElement;
      if(outer.matches('.wbloks_1') && outer.children.length===3 && outer.children[1]===section &&
        Array.from(outer.children).every(node=>node.matches('.wbloks_1'))) return true;
      section=outer;
    }
    return false;
  }
  function cleanClone(el) {
    const clone=el.cloneNode(true);
    const liveChildren=el.querySelectorAll('*');
    const cloneChildren=clone.querySelectorAll('*');
    if(liveChildren.length>1600) return null;
    for(let i=0;i<liveChildren.length;i++){
      const style=getComputedStyle(liveChildren[i]);
      if(style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||Number(style.opacity)===0)
        cloneChildren[i].remove();
    }
    clone.querySelectorAll(NOISE + ',script,style,template,iframe,video,audio,object,embed,svg,canvas,button,[role=button]').forEach(x=>x.remove());
    // Keep alt for emoji (Kotlin X parser also handles it), not pictures/media.
    clone.querySelectorAll('img').forEach(x=>{
      const alt=x.getAttribute('alt') || '';
      if (alt && alt.length <= 16 && !/[a-z0-9]/i.test(alt)) x.replaceWith(document.createTextNode(alt));
      else x.remove();
    });
    // Remove clones of links styled by the app as inline reaction/call-to-action buttons.
    clone.querySelectorAll('[role=link]').forEach(x=>{
      if (x.closest('[data-testid=quoteTweet],[data-testid="card.wrapper"]')) x.remove();
    });
    return clone;
  }
  function meaningful(el) {
    if (!visible(el)) return '';
    const text=normalized(el.innerText);
    return /^(?:see |read |show )?(?:more|less|view [\d,.]+ comments?|like|share|reply|comment|log in|sign up)$/i.test(text) ? '' : text;
  }
  function candidate(target, scope=null) {
    if (!(target instanceof Element)) return null;
    let body=target.closest(BLOCK);
    if (!body) {
      body=target;
      for(let i=0;i<3 && body.parentElement && (!scope || scope.contains(body.parentElement)) &&
          (body.innerText || '').trim().length<20;i++) body=body.parentElement;
    }
    if (!body || (scope && !scope.contains(body)) || !meaningful(body)) return null;
    return body;
  }
  function ranked(scope, hardBoundary, preferred) {
    const raw=Array.from(scope.querySelectorAll(BLOCK)).slice(0,114);
    // Some responsive layouts use plain styled DIVs for the readable text.
    // Only consider direct-text nodes, never a whole card/column by length.
    const rawFallback=Array.from(scope.querySelectorAll('div,span')).slice(0,230).filter(node=>
      !node.closest(BLOCK) && Array.from(node.childNodes).some(child=>
        child.nodeType===Node.TEXT_NODE && normalized(child.nodeValue).length>=24));
    const set=new Map();
    raw.forEach(node=>{
      if (hardBoundary && !hardBoundary(node)) return;
      const body=candidate(node,scope);
      if (!body || (hardBoundary && !hardBoundary(body))) return;
      const text=meaningful(body);
      const score=preferred && body.matches(preferred)?5 : body.matches(STRONG)?4 : body.matches('[dir=auto][lang]')?3 :
        body.matches('p,[role=paragraph],[data-lexical-text=true]')?2 : 1;
      if (text && text.length<=MAX) set.set(body,{body,text,score});
    });
    // Nested representations of the same rendered body are not multiple posts.
    if (!set.size) rawFallback.forEach(node=>{
      if (hardBoundary && !hardBoundary(node)) return;
      const text=meaningful(node);
      if (text.length>=24 && text.length<=MAX) set.set(node,{body:node,text,score:1});
    });
    const entries=Array.from(set.values());
    const independent=entries.filter(x=>!entries.some(y=>y!==x&&y.body.contains(x.body)&&y.score>=x.score));
    return independent.sort((a,b)=>b.score-a.score);
  }
  function wrapped(body, network, id, keepLines=false) {
    const article=document.createElement('article');
    const child=cleanClone(body);
    if(!child || !normalized(child.textContent)) return '';
    if(keepLines) preserveAuthoredLines(child);
    if (network==='x') {
      article.dataset.postExportX=id;
      child.setAttribute('data-testid','tweetText');
    } else {
      child.setAttribute('itemprop','articleBody');
    }
    article.append(child);
    return article.outerHTML.length<=MAX ? article.outerHTML : '';
  }
  function xFallback(article,id,exact) {
    if(!exact) return {reason:'x-fallback-needs-id'};
    const list=ranked(article,node=>node.closest(X_ARTICLE)===article &&
      !node.closest(HEADING + ',a[href*="/status/"],[data-testid=quoteTweet],[data-testid="card.wrapper"]'), X_RENDERED);
    if(!list.length) return {reason:'x-fallback-no-body',candidates:0};
    // A content body with a semantic/lang marker and no peer is better than a
    // bare dir=auto paragraph, which can also be a display name or text fragment.
    const best=list[0], peers=list.filter(x=>x.score===best.score);
    if(peers.length>1) {
      const draft=best.score>=2 && peers.every(x=>x.body.parentElement===best.body.parentElement) ? best.body.parentElement : null;
      return draft && article.contains(draft) && draft!==article ? {
        html:wrapped(draft,'x',id),review:true,reason:'x-review-neighbors',candidates:list.length
      } : {reason:'x-ambiguous-body',candidates:list.length};
    }
    return {html:wrapped(best.body,'x',id), review:best.score<3, reason:'x-clickable-body',candidates:list.length};
  }
  function xIdentityGap(eligible,statusId) {
    const main=document.querySelectorAll('main,[role=main]');
    const primary=Array.from(document.querySelectorAll('[data-testid=primaryColumn]'));
    const region=primary.length===1 && Array.from(main).some(parent=>parent.contains(primary[0]))
      ? primary[0] : main.length===1 && !primary.length ? main[0] : null;
    if(!region) return {reason:'x-gap-no-detail-scope',candidates:0};
    const roots=Array.from(region.querySelectorAll(X_ARTICLE)).filter(node=>
      visible(node) && !node.parentElement?.closest(X_ARTICLE) &&
      !node.closest('[data-testid=quoteTweet],[data-testid="card.wrapper"]'));
    const count=roots.length;
    if(count!==1) return {reason:'x-gap-ambiguous-owner',candidates:count};
    const article=roots[0];
    if(!eligible.includes(article)) return {reason:'x-gap-conflicting-id',candidates:1};
    // A localized/unrecognized presentation never becomes a positive match.
    // An explicit semantic main entity can stand on its own; elsewhere an
    // exposed landmark heading must be paired with a matching page canonical.
    const entity=article.closest('[itemprop~=mainEntity]');
    const semanticOwner=entity && region.contains(entity);
    const heading=Array.from(region.querySelectorAll('h1,h2,[role=heading]')).slice(0,32).filter(node=>
      !node.closest(X_ARTICLE) && visible(node) && painted(node) &&
      /^(?:post|tweet)$/i.test(normalized(node.innerText)));
    const urls=Array.from(document.querySelectorAll('link[rel~=canonical]')).map(node=>absolute(node.href));
    const current=absolute(location.href);
    const sameCanonical=urls.length===1 && urls[0] && current &&
      /^(?:(?:www|mobile|m)\.)?(?:x|twitter)\.com$/i.test(urls[0].hostname) &&
      /^https?:$/.test(urls[0].protocol) &&
      urls[0].pathname.replace(/\/$/,'')===current.pathname.replace(/\/$/,'');
    if(!semanticOwner && !(heading.length===1 && sameCanonical))
      return {reason:'x-gap-no-detail-heading',candidates:1};
    if(urls.length && !sameCanonical) return {reason:'x-gap-conflicting-canonical',candidates:1};
    const bodies=ranked(article,node=>node.closest(X_ARTICLE)===article &&
      !node.closest(HEADING + ',a[href*="/status/"],[data-testid=quoteTweet],[data-testid="card.wrapper"]'), X_RENDERED);
    const plausible=bodies.filter(item=>item.score>=3);
    // Establish uniqueness before hit-testing; a covered primary may not
    // promote another visible card, body, reply, or suggestion.
    if(plausible.length!==1) return {reason:'x-gap-ambiguous-body',candidates:plausible.length};
    const body=plausible[0].body;
    if(!painted(body) || ownedExpansion(body)) return {reason:'x-gap-body-unexposed',candidates:1};
    return {html:wrapped(body,'x',statusId),review:true,reason:'x-review-identity-gap',candidates:1};
  }
  function fbReelCaption() {
    const ownerSelector='div[role=button][tabindex="0"][aria-label][data-log-comp-type].wbloks_1';
    const candidates=[];
    const directions=document.querySelectorAll(FB_RENDERED);
    if(directions.length>160) return {reason:'fb-reel-candidate-limit'};
    for(const body of directions) {
      const parts=visible(body) ? mobileTextParts(body) : [];
      if(!parts.length) continue;
      const button=body.closest(ownerSelector);
      const group=button?.parentElement;
      if(!group || group.children.length!==3 || group.children[1]!==button ||
          !normalized(group.children[0].innerText) || normalized(group.children[2].innerText)) continue;
      const opening=normalized(body.innerText).slice(0,24);
      if(!opening || !normalized(button.getAttribute('aria-label')).includes(opening)) continue;
      // The active surface has two peer branches, one owning exactly one
      // video, the other owning the author/caption/actions. Stop before the
      // feed/deck: videos elsewhere may be suggestions, not the current reel.
      let overlay=group;
      for(let depth=0;depth<8 && overlay.parentElement && overlay.parentElement!==document.body;depth++) {
        const frame=overlay.parentElement;
        if(frame.children.length===2 && frame.children[1]===overlay && !overlay.querySelector('video')) {
          const videos=frame.children[0].querySelectorAll('video');
          if(videos.length!==1 || !videos[0].matches('[muted][playsinline]')) {overlay=frame;continue;}
          const media=videos[0], vr=media.getBoundingClientRect(), fr=frame.getBoundingClientRect();
          const viewportWidth=document.documentElement.clientWidth, viewportHeight=document.documentElement.clientHeight;
          const br=body.getBoundingClientRect();
          const coverX=Math.min(vr.right,viewportWidth)-Math.max(vr.left,0);
          const coverY=Math.min(vr.bottom,viewportHeight)-Math.max(vr.top,0);
          if(visible(media) && coverX>=viewportWidth*0.75 && coverY>=viewportHeight*0.75 &&
             Math.abs(vr.width-fr.width)<=3 && Math.abs(vr.height-fr.height)<=3 &&
             Math.abs(vr.left-fr.left)<=3 && Math.abs(vr.top-fr.top)<=3 &&
             br.left>=vr.left-2 && br.right<=vr.right+2 && br.top>=vr.top-2 && br.bottom<=vr.bottom+2) {
            candidates.push({body,parts,button,frame});
          }
          break;
        }
        overlay=frame;
      }
    }
    if(candidates.length!==1) return {
      reason:candidates.length?'fb-reel-ambiguous-caption':'fb-reel-no-caption',candidates:candidates.length
    };
    const item=candidates[0];
    if(!painted(item.body,item.parts)) return {reason:'fb-reel-caption-unexposed',candidates:1};
    if(expansionControl(item.button,REEL_MORE) || ownedExpansion(item.button,REEL_MORE))
      return {reason:'fb-expand-text-first',candidates:1};
    return {html:wrapped(item.body,'fb',null,true),review:true,reason:'fb-review-reel-caption',candidates:1};
  }
  function fbFallback() {
    const current=absolute(location.href);
    if(current && FB_HOST.test(current.hostname) && /^https?:$/.test(current.protocol) && REEL_PATH.test(current.pathname))
      return fbReelCaption();
    const route=fbRoute(current) || (current && FB_HOST.test(current.hostname) &&
      /^https?:$/.test(current.protocol) && /^\/story\.php$/i.test(current.pathname)
      ? {kind:'unverified',id:null} : null);
    if(!route) return {reason:'fb-not-post-route'};
    const main=document.querySelector('[role=main],main,#m_story_permalink_view,#permalink') || document.body;
    const messageNodes=Array.from(main.querySelectorAll(FB_BODY)).filter(visible).slice(0,48);
    const roots=Array.from(main.querySelectorAll(FB_ROOT)).filter(visible).slice(0,48);
    const identity=root=>Array.from(root.querySelectorAll('a[href]')).slice(0,60).some(link=>{
      if(link.closest(NOISE) || (!link.querySelector('time') &&
        (link.closest(FB_BODY + ',p,[role=paragraph],[dir=auto][lang]') ||
         Array.from(link.parentElement?.childNodes || []).some(n=>n.nodeType===Node.TEXT_NODE && normalized(n.nodeValue).length>8)))) return false;
      const near=link.closest(FB_ROOT);
      if(near && near!==root && root.contains(near)) return false;
      const other=fbRoute(absolute(link.href));
      return other && other.kind===route.kind && other.id===route.id;
    });
    const own=(root,node)=>{const nearest=node.closest(FB_ROOT);return !nearest || nearest===root || !root.contains(nearest);};
    const strong=roots.filter(identity);
    if(strong.length===1) {
      const scope=strong[0];
      const marked=messageNodes.filter(node=>scope.contains(node)&&own(scope,node));
      if(marked.length===1) return ownedExpansion(marked[0])
        ? {reason:'fb-expand-text-first',candidates:1}
        : {html:wrapped(marked[0],'fb'),reason:'fb-identified-message',candidates:1};
      const items=ranked(scope,node=>own(scope,node) && !node.closest(HEADING + ',a[href]'));
      if(items.length && items.filter(x=>x.score===items[0].score).length===1)
        return ownedExpansion(items[0].body) ? {reason:'fb-expand-text-first',candidates:items.length} :
          {html:wrapped(items[0].body,'fb'), review:items[0].score<3, reason:'fb-clickable-body',candidates:items.length};
      return {reason:'fb-ambiguous-body',candidates:items.length};
    }
    if(strong.length>1) return {reason:'fb-ambiguous-post',candidates:strong.length};
    // Opaque /share/p links sometimes have no self permalink in the visible
    // page. Prefill a single semantic body for review, never save it silently.
    const unique=messageNodes.filter(node=>!messageNodes.some(other=>other!==node&&other.contains(node)));
    if(unique.length===1 && meaningful(unique[0])) return ownedExpansion(unique[0])
      ? {reason:'fb-expand-text-first',candidates:1}
      : {html:wrapped(unique[0],'fb'),review:true,reason:'fb-review-unverified-body',candidates:1};
    if(unique.length>1) return {reason:'fb-ambiguous-body',candidates:unique.length};
    // The current lightweight Android Facebook story view has no article or
    // message marker: the text is a plain span in a directional mobile block,
    // between the card's header and footer. In the absence of an independently
    // verified self permalink, an exact single card only prefills Review.
    if (/^\/story\.php$/i.test(location.pathname)) {
      const story = Array.from(main.querySelectorAll(FB_RENDERED)).slice(0,80).filter(visible)
        .map(body=>{
          const parts=mobileTextParts(body);
          const text=meaningful(body);
          if(!parts.length || !text) return null;
          let branch=body;
          for(let i=0;i<7 && branch.parentElement && branch!==main;i++) {
            const card=branch.parentElement;
            const children=Array.from(card.children);
            const index=children.indexOf(branch);
            if(card.matches('.wbloks_1') && index>0 && index<children.length-1 &&
              children.every(x=>x.matches('.wbloks_1'))) return {body,card,parts};
            branch=card;
          }
          return null;
        }).filter(Boolean);
      const matching=story.filter(item=>identity(item.card));
      const mobileResult=(item,review,reason)=>ownedExpansion(item.body) || ownedExpansion(item.card.children[1])
        ? {reason:'fb-expand-text-first',candidates:story.length}
        : {html:wrapped(item.body,'fb',null,true),review,reason,candidates:story.length};
      if(matching.length===1) return mobileResult(matching[0],false,'fb-identified-mobile-body');
      if(matching.length===0 && story.length && route.kind==='post') {
        const exact=story.filter(item=>mobileDetailCard(item,main));
        if(exact.length===1 && painted(exact[0].body,exact[0].parts)) return mobileResult(exact[0],true,'fb-review-mobile-detail-body');
      }
      // A single unverified web-bloks body can still be a suggestion if
      // Facebook virtualized the story card. Never pass it to generic ranking.
      if(story.length) return {
        reason:story.length===1?'fb-unconfirmed-mobile-body':'fb-ambiguous-mobile-body',
        candidates:story.length
      };
    }
    if(main!==document.body) {
      const peerScopes=roots.filter(root=>!roots.some(other=>other!==root&&other.contains(root)));
      const scope=peerScopes.length===1 ? peerScopes[0] : !peerScopes.length ? main : null;
      const items=scope ? ranked(scope,node=>!node.closest(INTERACTIVE + ',' + HEADING)) : [];
      if(items.length && items[0].text.length>=24 && items.filter(x=>x.score===items[0].score).length===1) {
        return ownedExpansion(items[0].body) ? {reason:'fb-expand-text-first',candidates:items.length} :
          {html:wrapped(items[0].body,'fb'),review:true,reason:'fb-review-visible-body',candidates:items.length};
      }
    }
    return {reason:'fb-no-body',candidates:0};
  }
  function genericFallback() {
    if(REDDIT_HOST.test(location.hostname) || !/^\/.+/.test(location.pathname)) return null;
    const regions=Array.from(document.querySelectorAll('main,[role=main]')).filter(visible);
    if(regions.length!==1) return null;
    const scope=regions[0];
    const list=ranked(scope,node=>!node.closest(HEADING + ',a[href]'));
    if(!list.length || list[0].score<3 || list.filter(x=>x.score===list[0].score).length!==1) return null;
    return {html:wrapped(list[0].body,'generic'),review:true,reason:'generic-review-body',candidates:list.length};
  }
  let chosen='', recentSelection='', handler, selectionListener, rescan=false, staleSelection='';
  function stop() {
    if(handler) document.removeEventListener('click',handler,true);
    if(selectionListener) document.removeEventListener('selectionchange',selectionListener);
    document.getElementById('__postSelectStyle')?.remove();
    handler=selectionListener=null; chosen=recentSelection=staleSelection=''; rescan=false;
  }
  function walkSelection() {
    const s=String(window.getSelection() || '').trim();
    if(s && s.length<=MAX && (!rescan || s!==staleSelection)) {
      recentSelection=s; rescan=false; staleSelection='';
    }
  }
  function start() {
    stop();
    const css=document.createElement('style');
    css.id='__postSelectStyle';
    css.textContent='body * { -webkit-user-select:text !important; user-select:text !important; }';
    document.head?.append(css);
    selectionListener=walkSelection;
    document.addEventListener('selectionchange',selectionListener);
    handler=event=>{
      if(!event.isTrusted) return;
      const node=event.target instanceof Element ? event.target : event.target?.parentElement;
      if(!node) return;
      if((FB_HOST.test(location.hostname) || /^(?:www\.|mobile\.|m\.)?(?:x|twitter)\.com$/i.test(location.hostname)) &&
          (expansionControl(node) || (FB_HOST.test(location.hostname) && REEL_PATH.test(location.pathname) &&
             expansionControl(node,REEL_MORE)?.querySelector(FB_RENDERED)))) {
        staleSelection=String(window.getSelection() || '').trim();
        recentSelection=chosen=''; rescan=true;
        return; // The website still handles its own control.
      }
      if(node.closest('a[href],button,input,textarea,select,label,[contenteditable],nav,[role=navigation]')) return;
      const selected=String(window.getSelection() || '').trim();
      if(selected && (!rescan || selected!==staleSelection)) { walkSelection(); return; }
      const body=candidate(node);
      const text=body && (body.innerText || body.textContent || '').trim();
      if(!text || text.length>MAX) return;
      recentSelection=''; chosen=text; rescan=false; staleSelection='';
      // Leave ordinary navigation, account prompts and expansion controls to the browser.
    };
    document.addEventListener('click',handler,true);
  }
  function selection() {
    return {title:document.title.slice(0,240), rescan,
      text:rescan ? '' : (String(window.getSelection()||'').trim() || recentSelection || chosen).slice(0,MAX+1)};
  }
  window.__postTextSelection = {start,stop,selection,xFallback,xIdentityGap,fbFallback,genericFallback,fbHost:()=>FB_HOST.test(location.hostname)};
})();
