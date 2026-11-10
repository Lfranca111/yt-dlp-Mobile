(function () {
  'use strict';
  function visible(e) {
    if (!e || e.closest('[hidden],[aria-hidden=true]')) return false;
    for (let p=e.parentElement, n=0; p && n<20; p=p.parentElement,n++) { const s=getComputedStyle(p); if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false; }
    const s = getComputedStyle(e), r = e.getBoundingClientRect();
    return s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0' && r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  }
  function kind(text) {
    text = String(text || '').toLowerCase();
    if (/verify (that )?you('|’)re human|verify you are human|just a moment|security verification|checking your browser/.test(text)) return 'verification';
    if (/(confirm|verify|yes|i am|i'm|i’m|over|under).{0,35}(18|21|age)|age.{0,25}(verification|confirmation|restricted)/.test(text)) return 'age';
    if (/(i agree|accept|agree to|consent).{0,90}(terms|agreement|privacy|cookies)|terms.{0,55}(accept|agree)|consent required/.test(text)) return 'agreement';
    if (/sign.?in to continue|log.?in to continue|authentication required|you must (log|sign)/.test(text)) return 'login';
    return '';
  }
  const titleGate = kind(document.title);
  if (titleGate === 'verification') return titleGate;
  if (/(^|\/)(login|signin|sign-in)(\/|$)/i.test(location.pathname) && Array.from(document.querySelectorAll('input[type=password]')).some(visible)) return 'login';
  if (Array.from(document.querySelectorAll('input[type=password]')).some(visible) && /sign.?in to continue|log.?in to continue|authentication required/i.test(document.body ? document.body.innerText || document.body.textContent : '')) return 'login';
  const selectors = '[aria-modal=true],[role=dialog],dialog[open],[class*=age-],[id*=age-],[class*=consent],[id*=consent],[class*=agreement],[id*=agreement]';
  for (const e of Array.from(document.querySelectorAll(selectors)).slice(0,100)) {
    if (!visible(e)) continue;
    const r=e.getBoundingClientRect(), style=getComputedStyle(e);
    const blocking=e.getAttribute('aria-modal') === 'true' || (e.tagName === 'DIALOG' && e.open) ||
      ((style.position === 'fixed' || style.position === 'absolute') && r.width*r.height >= innerWidth*innerHeight*.16 && r.top < innerHeight/2 && r.bottom > innerHeight/2);
    if (!blocking || style.pointerEvents === 'none') continue;
    const found=kind((e.innerText || e.textContent || '').slice(0,12000));
    if (found) return found;
    if (Array.from(e.querySelectorAll('input[type=password]')).some(visible)) return 'login';
  }
  return '';
})();
