const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const script = fs.readFileSync(path.join(__dirname, '../../main/assets/monitoring/live-learning.js'), 'utf8');
function page(html) {
  const dom = new JSDOM(html, { url: 'https://example.com/creator', runScripts: 'outside-only' });
  dom.window.eval(script); return dom;
}
test('learning a card keeps a selector that follows future broadcast addresses', () => {
  const d = page('<a class="broadcast" href="/live/one"><img></a>');
  const w = d.window; w.__customLive.pickLink();
  w.document.querySelector('img').click();
  const learned = w.__customLivePicked;
  assert.equal(learned.url, 'https://example.com/live/one');
  assert.ok(!learned.selector.includes('/live/one'));
  w.document.querySelector('a').href = '/live/two';
  assert.equal(w.document.querySelector(learned.selector).href, 'https://example.com/live/two');
  d.window.close();
});
test('JavaScript buttons can be learned without a permanent destination URL', () => {
  const d = page('<button class="go-live"><span>Watch</span></button>'); const w = d.window;
  let clicked = 0; w.document.querySelector('button').addEventListener('click', () => clicked++);
  w.__customLive.pickLink(); w.document.querySelector('span').click();
  assert.equal(clicked, 0); assert.equal(w.__customLivePicked.url, '');
  assert.equal(w.__customLive.follow(w.__customLivePicked.selector), 'clicked');
  assert.equal(clicked, 1); d.window.close();
});
test('an ambiguous rule never clicks the first live or uploaded-video card', () => {
  const d = page('<button class="watch">One</button><button class="watch">Two</button>'); let clicks = 0;
  d.window.document.querySelectorAll('button').forEach(b => b.onclick = () => clicks++);
  assert.equal(d.window.__customLive.follow('button.watch'), '2'); assert.equal(clicks, 0); d.window.close();
});
test('canceling selection leaves the normal page action available', () => {
  const d = page('<button>Watch</button>'); let clicks = 0; const w = d.window;
  w.document.querySelector('button').onclick = () => clicks++;
  w.__customLive.pickLink(); w.__customLive.cancel(); w.document.querySelector('button').click();
  assert.equal(clicks, 1); assert.equal(w.__customLivePicked, null); d.window.close();
});
test('playback evidence keeps ad and media-key signals separate from a media URL', () => {
  const d = page('<div class="ad-playing"></div><video src="https://cdn.example/live.m3u8"></video>'); const w = d.window;
  const v = w.document.querySelector('video'); Object.defineProperty(v, 'mediaKeys', { value: {} });
  v.play = () => Promise.resolve();
  const result = w.__customLive.capture(); const player = JSON.parse(result.player);
  assert.equal(player.ad, true); assert.equal(player.drm, true);
  assert.equal(player.playing, false); assert.equal(player.urls.length, 1); d.window.close();
});
test('the live helper never replaces existing upload discovery', () => {
  const d = page('<main>Uploads</main>'); const w = d.window;
  const original = { snapshot: () => '<uploads-preserved/>', cancelPick: () => {} };
  w.__channelLearning = original; w.eval(script);
  assert.equal(w.__channelLearning, original);
  assert.equal(w.__customLive.capture().html, '<uploads-preserved/>'); d.window.close();
});
