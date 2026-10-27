// Injected on every page load (MainActivity.injectPageScripts). Gives the
// site a synthetic focus cursor driven by the remote: geometric
// nearest-neighbour search in the pressed direction, plus the watch-page
// player controls (scrub, up next, resume, continue watching).
//
// Placeholders filled in at injection: __FTV_ACCENT_HEX__, __FTV_ACCENT_RGB__,
// __FTV_IGNORE_MUTATIONS__, __FTV_PAUSE_HOME_VIDEOS__.
(function(){
  // Injected from onPageCommitVisible as well as onPageFinished -- bail
  // (without claiming ready) if the body isn't there yet, so the later call
  // still gets to run.
  if (window.__ftvReady || !document.body) return;
  window.__ftvReady = true;

  // Deliberately excludes 'video': the dedicated video-control module below
  // drives play/pause/seek directly against the active <video> regardless of
  // cursor position. Letting the D-pad cursor land ON the video used to give
  // it the loud focus treatment -- 1.08 scale, 6px outline, full-screen dim
  // -- while actually watching, which is exactly backwards.
  var SEL = 'a[href], button, input:not([type=hidden]), select, textarea, ' +
            '[tabindex]:not([tabindex="-1"]), [role="button"], [role="link"], [onclick]';

  // Focus treatment: the selected item zooms in, and a separate fixed
  // "lift" element sits exactly over it (at its zoomed size) casting a
  // soft drop shadow with a faint light edge -- the edge is what makes it
  // read on mde's near-black page, where a shadow alone vanishes. Being
  // its own fixed element on top, it can't be covered by the card's image
  // or clipped by a carousel's overflow, unlike the inset coloured ring
  // this replaced (a full-bleed card image painted straight over that).
  // Both are compositor-only: 'scale' (the standalone property, so it
  // composes with any transform the site already uses) and position/
  // opacity on one small layer.
  var ZOOM = 1.1;
  var style = document.createElement('style');
  style.textContent =
    // The zoom amount and anchor point are set inline per item (see
    // fitZoom); the class only carries the animation.
    '.__ftv_focus{outline:none !important;transition:scale 160ms ease-out !important;}' +
    '#__ftv_veil{position:fixed;left:0;top:0;width:0;height:0;pointer-events:none;' +
      'z-index:2147483646;border-radius:6px;opacity:0;' +
      'box-shadow:0 0 0 1px rgba(255,255,255,.55),0 10px 28px 6px rgba(0,0,0,.6);' +
      'transition:opacity 160ms ease-out,left 160ms ease-out,top 160ms ease-out,' +
        'width 160ms ease-out,height 160ms ease-out;}' +
    '#__ftv_veil.__ftv_on{opacity:1;}' +
    '#__ftv_veil.__ftv_snap{transition:opacity 160ms ease-out;}';
  (document.head || document.documentElement).appendChild(style);

  var veil = document.createElement('div');
  veil.id = '__ftv_veil';
  document.body.appendChild(veil);

  var cur = null;
  var cache = null;
  var dirty = true;

  // Single-element check used only for validating `cur` (not a hot loop).
  function visible(el){
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return false;
    if (r.bottom < 0 || r.top > window.innerHeight) return false;
    if (r.right < 0 || r.left > window.innerWidth) return false;
    var s = window.getComputedStyle(el);
    if (s.visibility === 'hidden' || s.display === 'none') return false;
    if (parseFloat(s.opacity) < 0.1) return false;
    if (el.disabled) return false;
    return true;
  }

  // Rebuilds the candidate list. Rects are read in one batch pass up front
  // (interleaving getBoundingClientRect with getComputedStyle forces a
  // layout per element) and off-screen elements are dropped by geometry
  // alone before any survivor pays for a getComputedStyle call.
  function computeItems(){
    var all = document.querySelectorAll(SEL);
    var n = all.length;
    var rects = new Array(n);
    for (var i = 0; i < n; i++) { rects[i] = all[i].getBoundingClientRect(); }

    // Up to one screen beyond each edge, not just the visible viewport: with
    // a viewport-only list, the next row/card just past the edge was
    // unreachable, so DOWN scrolled the page without moving the cursor,
    // the cursor scrolled off, and the next press reset it to the top-left
    // item. Now the cursor moves straight to it and mark() scrolls it in.
    // Horizontally, only inside a scrolling carousel row: anything else
    // past the left/right edge is far more likely an off-canvas drawer
    // than content. Memoised per parent since a row's cards share one.
    var mx = window.innerWidth, my = window.innerHeight;
    var trackOf = new Map();
    var onscreen = [];
    for (var i = 0; i < n; i++) {
      var r = rects[i];
      if (r.width < 8 || r.height < 8) continue;
      if (r.bottom < -my || r.top > window.innerHeight + my) continue;
      if (r.right < 0 || r.left > window.innerWidth) {
        if (r.right < -mx || r.left > window.innerWidth + mx) continue;
        var p = all[i].parentElement;
        if (!trackOf.has(p)) trackOf.set(p, scrollableAncestor(all[i], 'x'));
        if (!trackOf.get(p)) continue;
      }
      onscreen.push(all[i]);
    }

    var out = [];
    for (var i = 0; i < onscreen.length; i++) {
      var el = onscreen[i];
      if (isExcluded(el)) continue;
      var s = window.getComputedStyle(el);
      if (s.visibility === 'hidden' || s.display === 'none') continue;
      if (parseFloat(s.opacity) < 0.1) continue;
      if (el.disabled) continue;
      if (covered(el)) continue;
      out.push(el);
    }
    return out;
  }

  // True if something else is painted on top of el at every probe point --
  // a modal over the page (fishtank's menu stayed reachable underneath its
  // login dialog), or el clipped away by an overflow:hidden ancestor (the
  // login dialog's off-to-the-side "forgot password" pane, whose buttons
  // the cursor kept landing on). Hit-testing is the one check that answers
  // "can the user actually see this" for both. Probes the centre and four
  // inset points so a small badge over one part of a card doesn't count;
  // points outside the viewport can't be hit-tested and are skipped, and
  // an element with no testable point at all is given the benefit of the
  // doubt (it's in the off-screen margin).
  var PROBES = [[.5,.5],[.25,.25],[.75,.25],[.25,.75],[.75,.75]];
  function covered(el){
    var r = el.getBoundingClientRect();
    var tested = false;
    for (var i = 0; i < PROBES.length; i++) {
      var x = r.left + r.width * PROBES[i][0], y = r.top + r.height * PROBES[i][1];
      if (x < 0 || y < 0 || x >= window.innerWidth || y >= window.innerHeight) continue;
      tested = true;
      var hit = document.elementFromPoint(x, y);
      if (!hit || el === hit || el.contains(hit)) return false;
    }
    return tested;
  }

  // Elements that match SEL but should never be reachable by the D-pad at
  // all, not just skipped as a default landing spot.
  function isExcluded(el){
    return !!(el.matches && el.matches('a[class*="_logo"]'));
  }

  function items(){
    if (dirty || !cache) { cache = computeItems(); dirty = false; }
    return cache;
  }

  // DOM mutations (SPA route/content swaps) and scrolling both change which
  // elements are on-screen, so both invalidate the cache. Debounced so a
  // burst of changes -- or a site cleanup hook re-running below -- triggers
  // one rebuild instead of one per mutation record.
  //
  // This deliberately does NOT also watch attributes (class/style): on a
  // page that updates inline styles continuously -- a scrubber position, a
  // carousel transform, chat timestamps -- via requestAnimationFrame, an
  // attribute-observing MutationObserver queues a record for every single
  // one of those before our debounce ever gets to run, which was enough to
  // choke mde's page to a blank screen on real hardware. doNavigate's
  // force-refresh-before-giving-up below covers the case this would have
  // caught (a lazily-hydrated row) without paying that cost continuously.
  var invalidateTimer = null;
  function scheduleInvalidate(){
    if (invalidateTimer) return;
    invalidateTimer = setTimeout(function(){
      invalidateTimer = null;
      dirty = true;
      if (cur && !document.contains(cur)) hideVeil();
      if (window.__ftvSiteCleanup) {
        try { window.__ftvSiteCleanup(); } catch (e) {}
      }
      // Picks up the Bunny player iframe as soon as it mounts (it doesn't
      // fire a same-origin 'play' event we could listen for instead), so
      // auto-fullscreen and event subscription don't have to wait for the
      // user to press a key first.
      checkPlayerFrame();
      pauseHomeVideos();
      try { renderRecent(); } catch (e) {}
      try { renderPscRows(); } catch (e) {}
    }, 250);
  }
  // Mutations entirely inside an ignored subtree (hidden live chat that the
  // SPA keeps rendering into) are skipped, or they'd dirty the cache every
  // few hundred ms forever and every keypress would pay a full rebuild.
  var IGNORE = __FTV_IGNORE_MUTATIONS__;
  new MutationObserver(function(records){
    if (!IGNORE) { scheduleInvalidate(); return; }
    for (var i = 0; i < records.length; i++) {
      var t = records[i].target;
      if (!(t.closest && t.closest(IGNORE))) { scheduleInvalidate(); return; }
    }
  }).observe(document.body, { childList: true, subtree: true });
  // Capture phase on document, not a plain window listener: scroll events
  // don't bubble, so a listener on window only ever sees the page itself
  // scrolling. Capture-phase delegation from document catches any nested
  // scrollable container too (a horizontally-scrolling carousel row).
  document.addEventListener('scroll', scheduleInvalidate, { passive: true, capture: true });

  // ---- Video / player control ------------------------------------------
  // Both sites' actual watch pages turned out to embed their player as a
  // cross-origin <iframe> (Bunny Stream, player.mediadelivery.net) rather
  // than a same-origin <video> -- confirmed by inspecting the live DOM:
  // document.querySelectorAll('video') only ever finds small decorative
  // videos, never the real player, because the browser's same-origin
  // policy blocks JS in this frame from reaching into a cross-origin
  // iframe's DOM at all. That's the real reason play/pause/seek and
  // auto-fullscreen kept not working. Bunny's embed implements the
  // player.js postMessage protocol (github.com/embedly/player.js), so
  // control has to go through window.postMessage instead of element
  // properties.
  var trackedVideo = null;       // same-origin <video>, if a page ever has one
  var playerFrame = null;        // cross-origin Bunny iframe, if present
  // timeKnown: whether playerState.time has actually been reported by the
  // player yet. Seeking off the default 0 sent FF to 0:10 -- i.e. restarted
  // the episode -- whenever the event subscription hadn't landed.
  var playerState = { playing: false, time: 0, duration: 0, timeKnown: false };
  var fullscreenEl = null;       // element currently pinned to the viewport
  var fullscreenUndo = [];       // [element, cssText] pairs to restore

  function findPlayerFrame(){
    if (playerFrame && document.contains(playerFrame)) return playerFrame;
    var f = document.querySelector('iframe[src*="mediadelivery.net"]');
    if (f !== playerFrame) {
      // A different (or no) player than last time -- SPA route change or
      // next episode. Everything learned about the old one is stale, and
      // the new one needs its own subscription and fullscreen.
      playerFrame = f || null;
      playerSubscribed = false;
      playerState = { playing: false, time: 0, duration: 0, timeKnown: false };
      resumeChecked = false;
    }
    return playerFrame;
  }

  function sendPlayerCommand(method, value, listener){
    var f = findPlayerFrame();
    if (!f || !f.contentWindow) return false;
    var msg = { context: 'player.js', version: '0.0.11', method: method };
    if (value !== undefined) msg.value = value;
    if (listener !== undefined) msg.listener = listener;
    try { f.contentWindow.postMessage(JSON.stringify(msg), '*'); } catch (e) {}
    return true;
  }

  var playerSubscribed = false;
  function subscribePlayerEvents(){
    if (playerSubscribed || !findPlayerFrame()) return;
    playerSubscribed = true;
    // 'addEventListener' with a listener id, not 'on': Bunny's player.js
    // receiver answers 'on' with {event:'error', msg:'Invalid Method "on"'},
    // so none of these subscriptions had ever been live -- the real
    // reason play/pause state and seek positions were unreliable.
    sendPlayerCommand('addEventListener', 'play', 'ftvPlay');
    sendPlayerCommand('addEventListener', 'pause', 'ftvPause');
    sendPlayerCommand('addEventListener', 'timeupdate', 'ftvTime');
    sendPlayerCommand('addEventListener', 'ended', 'ftvEnded');
    // Autoplay starts (and often finishes its first play/pause transition)
    // before we ever get a chance to subscribe to those events, so
    // playerState.playing's default of false is wrong from the start on
    // most page loads -- toggling play/pause against that stale guess sent
    // 'play' to an already-playing video, which does nothing and looks
    // exactly like "the button stopped working". Query the real state
    // directly instead of only waiting for a future transition we may have
    // already missed.
    sendPlayerCommand('getPaused', undefined, 'ftvInit');
    sendPlayerCommand('getCurrentTime', undefined, 'ftvInit');
    sendPlayerCommand('getDuration', undefined, 'ftvInit');
  }

  // Seek requested before the player has reported a position: accumulated
  // here and applied once a getCurrentTime reply arrives.
  var pendingSeek = null;

  window.addEventListener('message', function(e){
    var data = e.data;
    if (typeof data === 'string') {
      try { data = JSON.parse(data); } catch (err) { return; }
    }
    if (!data || data.context !== 'player.js') return;
    var f = findPlayerFrame();
    if (f && e.source !== f.contentWindow) return;  // a stale/other player
    // player.js receivers announce 'ready' once they can accept commands;
    // anything sent before that (which is when the iframe element first
    // appears in the DOM) is silently dropped, so subscribe again here.
    if (data.event === 'ready') { playerSubscribed = false; subscribePlayerEvents(); }
    else if (data.event === 'play') { playerState.playing = true; }
    else if (data.event === 'pause') { playerState.playing = false; }
    else if (data.event === 'getPaused') { playerState.playing = !data.value; }
    else if (data.event === 'getCurrentTime') {
      playerState.time = data.value || 0;
      playerState.timeKnown = true;
      if (pendingSeek !== null) { var d = pendingSeek; pendingSeek = null; window.__ftvSeek(d); }
    }
    else if (data.event === 'getDuration') { playerState.duration = data.value || 0; }
    else if (data.event === 'timeupdate' && data.value) {
      playerState.time = data.value.seconds || 0;
      playerState.duration = data.value.duration || 0;
      playerState.timeKnown = true;
      if (seekOverlayVisible) { renderSeekOverlay(); }
      rememberPosition(playerState.time, playerState.duration);
    }
    else if (data.event === 'ended') { onEpisodeEnded(); }
  });

  // ---- Resume (fishtank) ---------------------------------------------
  // mde's site saves and restores watch position itself; fishtank's
  // doesn't, so its episodes always restarted from 0. Positions are kept
  // per episode in the site's localStorage, saved every ~5s while playing,
  // restored once when that episode's player first reports a time, and
  // dropped near the end so a finished episode starts over next time.
  var resumeChecked = false, lastSaved = 0;
  function episodeId(){
    var f = playerFrame;
    var m = f && /\/embed\/\d+\/([0-9a-f-]{36})/.exec(f.src);
    return m ? m[1] : null;
  }
  function rememberPosition(t, dur){
    var id = episodeId();
    if (!id) return;
    if (!resumeChecked) {
      resumeChecked = true;
      var saved = 0;
      try { saved = parseFloat(localStorage.getItem('__ftv_pos_' + id)) || 0; } catch (e) {}
      if (saved > 20 && t < 15 && (!dur || saved < dur - 30)) {
        playerState.time = saved;
        sendPlayerCommand('setCurrentTime', saved);
        return;
      }
    }
    if (Math.abs(t - lastSaved) < 5) return;
    lastSaved = t;
    try {
      if (dur && t > dur - 30) localStorage.removeItem('__ftv_pos_' + id);
      else if (t > 20) localStorage.setItem('__ftv_pos_' + id, String(Math.floor(t)));
    } catch (e) {}
    var title = '';
    try { title = localStorage.getItem('__ftv_title_' + id) || ''; } catch (e) {}
    recordRecent({ path: '/episodes/' + id, title: title || 'Episode',
                   thumb: 'https://' + bunnyHost() + '/' + id + '/thumbnail.jpg', t: t, dur: dur });
  }

  // ---- Up next (fishtank) --------------------------------------------
  // fishtank's player just stops at the end. When an episode is opened
  // from a row on the Episodes page, that row is remembered (oldest
  // first) and, at the end, the next episode in it plays after a short
  // countdown that Back cancels. mde does this itself.
  var nextTimer = null, nextOverlay = null;
  function rememberQueue(link){
    var row = link.closest('div.w-full') || link.parentElement;
    if (!row) return;
    var seen = {}, items = [];
    row.querySelectorAll('a[href^="/episodes/"]').forEach(function(a){
      var m = /\/episodes\/([0-9a-f-]{36})/.exec(a.getAttribute('href'));
      if (m && !seen[m[1]]) { seen[m[1]] = 1; items.push({ id: m[1], title: a.textContent.trim() }); }
    });
    if (items.length < 2) return;
    // Rows list newest first ("10 - Finale", "09 - ..."); play oldest->newest.
    var first = parseInt(items[0].title, 10), last = parseInt(items[items.length - 1].title, 10);
    if (!(first < last)) items.reverse();
    try { sessionStorage.setItem('__ftv_queue', JSON.stringify(items)); } catch (e) {}
  }
  function nextEpisode(){
    var id = episodeId(), q = [];
    try { q = JSON.parse(sessionStorage.getItem('__ftv_queue') || '[]'); } catch (e) {}
    for (var i = 0; i < q.length - 1; i++) if (q[i].id === id) return q[i + 1];
    return null;
  }
  function onEpisodeEnded(){
    var id = episodeId();
    try { if (id) localStorage.removeItem('__ftv_pos_' + id); } catch (e) {}
    if (id) removeRecent('/episodes/' + id);
    var next = nextEpisode();
    if (!next || nextTimer) return;
    if (!nextOverlay) {
      // Same look as mde's own card (restyled in siteCss).
      nextOverlay = document.createElement('div');
      nextOverlay.id = '__ftv_next';
      nextOverlay.style.cssText = 'position:fixed;right:4%;bottom:16%;z-index:2147483647;width:340px;' +
        'padding:14px;border-radius:10px;background:rgba(0,0,0,.85);box-shadow:0 4px 18px rgba(0,0,0,.6);' +
        'color:#fff;font:600 16px/1.3 sans-serif;';
      var label = document.createElement('div');
      label.style.cssText = 'margin-bottom:8px;';
      var img = document.createElement('img');
      img.src = 'https://' + bunnyHost() + '/' + next.id + '/thumbnail.jpg';
      img.style.cssText = 'display:block;width:100%;aspect-ratio:16/9;object-fit:cover;border-radius:6px;background:#111;';
      img.onerror = function(){ img.style.display = 'none'; };
      var title = document.createElement('div');
      title.style.cssText = 'margin-top:8px;font-size:18px;';
      title.textContent = next.title.replace(/\.(mp4|mov|mkv|webm)$/i, '');   // some titles carry the file extension
      nextOverlay.appendChild(label);
      nextOverlay.appendChild(img);
      nextOverlay.appendChild(title);
      document.body.appendChild(nextOverlay);
      nextOverlay.__label = label;
    }
    var n = 10;
    var tick = function(){
      nextOverlay.__label.innerHTML = 'Up next in ' + n + ' <span style="opacity:.7">\u00b7 Back to cancel</span>';
      if (n-- <= 0) {
        cancelNext();
        try { localStorage.setItem('__ftv_title_' + next.id, cleanTitle(next.title)); } catch (e) {}
        location.assign('/episodes/' + next.id);
        return;
      }
      nextTimer = setTimeout(tick, 1000);
    };
    tick();
  }
  function cancelNext(){
    if (!nextTimer && !nextOverlay) return false;
    if (nextTimer) { clearTimeout(nextTimer); nextTimer = null; }
    if (nextOverlay) { nextOverlay.remove(); nextOverlay = null; }
    return true;
  }

  // ---- Continue watching (both sites) ---------------------------------
  // Part-watched episodes, most recent first, as a row at the top of the
  // page you land on -- mde's homepage, fishtank's Episodes page -- so
  // something started earlier is one press away. Kept in the site's
  // localStorage on this TV; an episode drops off once finished.
  var RECENT_KEY = '__ftv_recent', RECENT_MAX = 12;
  function loadRecent(){
    try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]') || []; } catch (e) { return []; }
  }
  function saveRecent(list){
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, RECENT_MAX))); } catch (e) {}
  }
  function removeRecent(path){
    saveRecent(loadRecent().filter(function(x){ return x.path !== path; }));
  }
  // entry: { path, title, thumb, t, dur } (seconds)
  function recordRecent(entry){
    if (!entry || !entry.path || !(entry.dur > 60)) return;
    if (entry.t > entry.dur - 30) { removeRecent(entry.path); return; }   // finished
    if (entry.t < 15) return;                                           // barely started
    var list = loadRecent().filter(function(x){ return x.path !== entry.path; });
    entry.ts = Date.now();
    list.unshift(entry);
    saveRecent(list);
  }
  function cleanTitle(t){
    return String(t || '').replace(/\s+/g, ' ').trim().replace(/\.(mp4|mov|mkv|webm)$/i, '');
  }

  // mde: its player is the page's own <video>; title and thumbnail come
  // from the page's og: tags (episode pages are full page loads).
  var lastRecentSave = 0;
  document.addEventListener('timeupdate', function(e){
    if (location.host.indexOf('mde.tv') < 0 || e.target !== trackedVideo || location.pathname === '/') return;
    var now = Date.now();
    if (now - lastRecentSave < 5000) return;
    lastRecentSave = now;
    var og = function(k){ var m = document.querySelector('meta[property="og:' + k + '"]'); return m ? m.content : ''; };
    var crumb = document.querySelector('a[href="/series/' + location.pathname.split('/')[2] + '"]');
    recordRecent({ path: location.pathname,
                   series: crumb ? cleanTitle(crumb.textContent) : '',
                   title: cleanTitle((og('title') || document.title).replace(/\s*\|\s*MDE\.tv\s*$/i, '')),
                   thumb: og('image') || trackedVideo.poster || '',
                   t: trackedVideo.currentTime, dur: trackedVideo.duration });
  }, true);
  document.addEventListener('ended', function(e){
    if (location.host.indexOf('mde.tv') >= 0 && e.target === trackedVideo) removeRecent(location.pathname);
  }, true);

  // Where the row goes on the current page, or null.
  function recentSlot(){
    if (location.host.indexOf('mde.tv') >= 0) {
      return location.pathname === '/' ? document.querySelector('[class*="_landing"]') : null;
    }
    if (location.pathname === '/episodes' && !location.search) {
      return document.querySelector('div.flex.flex-col.gap-5.mb-5');
    }
    return null;
  }

  // The row is built by cloning one of the site's own rows and cards as a
  // template (mde: its "New" row; fishtank: a single-tab row like
  // Off-Season), so it has each site's own fonts, colours and card style
  // and keeps up with their redesigns. Falls back to a plain row if no
  // template is found.
  var recentSig = '';

  function progressBar(frac){
    var bar = document.createElement('div');
    bar.style.cssText = 'position:relative;z-index:2;height:4px;margin-top:-4px;width:100%;' +
      'background:rgba(255,255,255,.35);border-radius:0 0 6px 6px;overflow:hidden;';
    var fill = document.createElement('div');
    fill.style.cssText = 'height:100%;background:__FTV_ACCENT_HEX__;width:' +
      Math.max(2, Math.min(100, frac * 100)) + '%;';
    bar.appendChild(fill);
    return bar;
  }

  function buildMdeRow(slot, list){
    var tpl = null, secs = slot.children;
    for (var i = 0; i < secs.length; i++) {
      var lab = secs[i].querySelector('[class*="_label"]');
      if (lab && /^\s*new\s*$/i.test(lab.textContent) && secs[i].querySelector('a[class*="_video"]')) { tpl = secs[i]; break; }
    }
    if (!tpl) return null;
    // The section holds several rows (New, Popular): copy the section's
    // shell but only the row whose label is "New".
    var scroll = null, kids = tpl.children;
    for (var j = 0; j < kids.length; j++) {
      var kl = kids[j].querySelector('[class*="_label"]');
      if (kl && /^\s*new\s*$/i.test(kl.textContent)) { scroll = kids[j]; break; }
    }
    var row = tpl.cloneNode(false);
    row.appendChild((scroll || tpl.firstElementChild).cloneNode(true));
    var label = row.querySelector('[class*="_label"]');
    (label.querySelector('span') || label).textContent = 'Continue Watching';
    var card = row.querySelector('a[class*="_video"]');
    var box = card.parentElement;
    while (box.firstChild) box.removeChild(box.firstChild);
    box.style.justifyContent = 'flex-start';   // site spreads a full row edge to edge
    box.style.gap = '16px';
    list.forEach(function(x){
      var c = card.cloneNode(true);
      c.href = x.path;
      c.removeAttribute('data-ftv');
      var img = c.querySelector('img');
      if (img) { img.src = x.thumb || ''; img.removeAttribute('srcset'); img.loading = 'eager'; }
      var series = c.querySelector('[class*="_seriesName"]');
      if (series) { if (x.series) series.textContent = x.series; else series.remove(); }
      var title = c.querySelector('[class*="_title"]');
      if (title) title.textContent = x.title || 'Untitled';
      c.querySelectorAll('[class*="_watched"]').forEach(function(w){ w.remove(); });
      if (img) img.insertAdjacentElement('afterend', progressBar(x.t / x.dur));
      box.appendChild(c);
    });
    return row;
  }

  function buildFishtankRow(slot, list){
    var tpl = null, rows = slot.children;
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      if (r.id === '__ftv_recent_row' || getComputedStyle(r).display === 'none') continue;
      var tabs = r.firstElementChild && r.firstElementChild.children;
      var card = r.querySelector('li a[href^="/episodes/"]');
      if (card && !/400px/.test(card.className) && tabs && tabs.length <= 2) { tpl = r; break; }
    }
    if (!tpl) return null;
    var row = tpl.cloneNode(true);
    row.style.order = '-1';
    var head = row.firstElementChild;
    var tab = head && head.firstElementChild;
    if (tab) tab.textContent = 'Continue Watching';
    if (head) head.querySelectorAll('a').forEach(function(a){ a.remove(); });
    var li = row.querySelector('li');
    var ul = li.parentElement;
    // The site's carousel is driven by its own script (which isn't attached
    // to this copy): show the list and let it scroll natively instead.
    ul.style.opacity = '1';
    ul.style.transform = 'none';
    ul.style.width = 'max-content';
    ul.style.maxWidth = 'none';
    var region = ul.parentElement;
    region.style.overflowX = 'auto';
    region.style.scrollbarWidth = 'none';
    while (ul.firstChild) ul.removeChild(ul.firstChild);
    list.forEach(function(x, n){
      var c = li.cloneNode(true);
      c.setAttribute('aria-posinset', String(n + 1));
      c.setAttribute('aria-setsize', String(list.length));
      var a = c.querySelector('a');
      a.href = x.path;
      var img = a.querySelector('img');
      if (img) { img.src = x.thumb || ''; img.removeAttribute('srcset'); img.loading = 'eager'; }
      var leaves = [].filter.call(a.querySelectorAll('div'), function(d){ return !d.children.length && d.textContent.trim(); });
      if (leaves.length) leaves[0].textContent = x.title || 'Episode';
      for (var k = 1; k < leaves.length; k++) leaves[k].remove();
      if (img) img.insertAdjacentElement('afterend', progressBar(x.t / x.dur));
      ul.appendChild(c);
    });
    return row;
  }

  function buildPlainRow(list){
    var row = document.createElement('div');
    row.style.cssText = 'order:-1;margin:0 0 18px;';
    var head = document.createElement('div');
    head.style.cssText = 'color:#fff;font-weight:700;font-size:20px;margin:0 0 10px 2px;';
    head.textContent = 'Continue watching';
    var track = document.createElement('div');
    track.style.cssText = 'display:flex;gap:14px;overflow-x:auto;padding:4px 2px 8px;scrollbar-width:none;';
    list.forEach(function(x){
      var a = document.createElement('a');
      a.href = x.path;
      a.style.cssText = 'flex:0 0 220px;display:block;color:#fff;text-decoration:none;';
      var img = document.createElement('div');
      img.style.cssText = 'width:220px;aspect-ratio:16/9;border-radius:8px;background:#111 center/cover no-repeat;' +
        (x.thumb ? 'background-image:url("' + x.thumb + '");' : '');
      var title = document.createElement('div');
      title.style.cssText = 'margin-top:6px;font-size:14px;';
      title.textContent = x.title || 'Untitled';
      a.appendChild(img);
      a.appendChild(progressBar(x.t / x.dur));
      a.appendChild(title);
      track.appendChild(a);
    });
    row.appendChild(head);
    row.appendChild(track);
    return row;
  }

  function renderRecent(){
    var slot = recentSlot();
    var row = document.getElementById('__ftv_recent_row');
    var list = slot ? loadRecent() : [];
    if (!slot || !list.length) { if (row) row.remove(); recentSig = ''; return; }
    var sig = JSON.stringify(list.map(function(x){ return [x.path, Math.round(x.t / 30)]; }));
    // Up to date -- unless it's the plain fallback, which is swapped for the
    // themed version as soon as the site's own rows have rendered.
    if (row && row.parentElement === slot && sig === recentSig && !row.__ftvPlain) return;
    var built = null;
    try {
      built = location.host.indexOf('mde.tv') >= 0 ? buildMdeRow(slot, list) : buildFishtankRow(slot, list);
    } catch (e) { built = null; }
    if (!built) {
      // Template not rendered yet: try again on a later tick rather than
      // flash a plain row that gets replaced a moment later.
      if (!row && document.readyState === 'complete' && slot.children.length > 2) {
        built = buildPlainRow(list);
        built.__ftvPlain = true;
      } else return;
    }
    if (row) row.remove();
    recentSig = sig;
    built.id = '__ftv_recent_row';
    slot.insertBefore(built, slot.firstElementChild);
  }

  // ---- Pause Snack Continue by game (mde) -----------------------------
  // PSC is one long list of 130+ episodes across a dozen games. On its
  // series page the site's list is hidden and replaced with one row per
  // game (newest game first, episodes Ep. 1 onwards), built from the
  // series API in a single request and from clones of the site's own
  // card. Titles that aren't "<Game> - Ep. N" go in a Specials row.
  var PSC_PATH = '/series/pause-snack-continue';
  var pscVideos = null, pscLoading = false, pscSig = '';

  function pscGame(title){
    var t = String(title).replace(/^\s*(Scary Mondays|PSC Wildcard Wednesday)\s*:\s*/i, '');
    var m = /^(.*?)\s*[-–—]\s*Ep\.?\s*(\d+)/i.exec(t);
    return m ? { game: m[1].trim(), ep: +m[2] } : { game: 'Specials', ep: 0 };
  }
  function fmtDuration(s){
    var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60);
    return (h ? h + 'h ' : '') + m + 'm';
  }

  function renderPscRows(){
    var box = document.getElementById('__ftv_psc');
    var list = null;
    if (location.host.indexOf('mde.tv') >= 0 && location.pathname === PSC_PATH) {
      // Our rows copy the list's class too, so skip those.
      var lists = document.querySelectorAll('[class*="_seriesVideoList"]');
      for (var i = 0; i < lists.length; i++) if (!lists[i].closest('#__ftv_psc')) { list = lists[i]; break; }
    }
    if (!list) { if (box) box.remove(); pscSig = ''; return; }
    var wrapper = list.parentElement;
    if (!pscVideos) {
      if (pscLoading) return;
      // The API caps pageSize at 200 (pages count from 0).
      pscLoading = true;
      var got = [];
      var page = function(p){
        return fetch('https://api.mde.tv/v1/series/pause-snack-continue/videos?page=' + p + '&pageSize=200', { credentials: 'include' })
          .then(function(r){ return r.json(); })
          .then(function(j){
            got = got.concat(j.videos || []);
            if (j.videos && j.videos.length && got.length < j.total && p < 10) return page(p + 1);
          });
      };
      page(0)
        .then(function(){ pscVideos = got.filter(function(v){ return !v.hidden; }); scheduleInvalidate(); })
        .catch(function(){})
        .then(function(){ pscLoading = false; });
      return;
    }
    if (!pscVideos.length) return;
    if (box && box.nextElementSibling === wrapper && pscSig) { wrapper.style.display = 'none'; return; }
    var tpl = list.querySelector('a[class*="_video"]');
    if (!tpl) return;

    var games = {}, order = [];
    pscVideos.forEach(function(v){
      var g = pscGame(v.title);
      if (!games[g.game]) { games[g.game] = { name: g.game, eps: [], latest: 0 }; order.push(games[g.game]); }
      games[g.game].eps.push({ v: v, ep: g.ep });
      games[g.game].latest = Math.max(games[g.game].latest, v.episode);
    });
    order.sort(function(a, b){
      if ((a.name === 'Specials') !== (b.name === 'Specials')) return a.name === 'Specials' ? 1 : -1;
      return b.latest - a.latest;
    });

    // The card styles are scoped to the list, so each row is a copy of the
    // list element itself, turned into a single scrolling line.
    var cardW = Math.round(tpl.getBoundingClientRect().width) || renderPscRows.cardW || 290;
    renderPscRows.cardW = cardW;
    var head = document.querySelector('[class*="_subtitle"]');
    var hs = head ? getComputedStyle(head) : null;
    var out = document.createElement('div');
    out.id = '__ftv_psc';
    out.style.cssText = 'display:flex;flex-direction:column;gap:22px;margin:8px 0 24px;';
    order.forEach(function(g){
      g.eps.sort(function(a, b){ return (a.ep - b.ep) || (a.v.episode - b.v.episode); });
      var row = document.createElement('div');
      var label = document.createElement('div');
      label.textContent = g.name + '  ·  ' + g.eps.length + (g.eps.length === 1 ? ' episode' : ' episodes');
      label.style.cssText = 'margin:0 0 10px 2px;' + (hs ? 'font-family:' + hs.fontFamily + ';color:' + hs.color +
        ';font-size:' + hs.fontSize + ';letter-spacing:' + hs.letterSpacing + ';text-shadow:' + hs.textShadow + ';' : '');
      var track = list.cloneNode(false);
      track.style.cssText = 'display:flex;flex-wrap:nowrap;gap:16px;overflow-x:auto;scrollbar-width:none;' +
        'padding:4px 2px 8px;grid-template-columns:none;';
      g.eps.forEach(function(e, n){
        var v = e.v, c = tpl.cloneNode(true);
        c.id = v.id;
        c.href = PSC_PATH + '/' + v.tag;
        c.removeAttribute('data-ftv');
        c.style.flex = '0 0 ' + cardW + 'px';
        var set = function(sel, text){ var el = c.querySelector('[class*="' + sel + '"]'); if (el) el.textContent = text; };
        set('_episode', '#' + (e.ep || n + 1));
        // The row already names the game: show "Ep. 3: Title".
        set('_title', g.name === 'Specials' ? v.title : v.title.replace(/^.*?(Ep\.?\s*\d+)\s*:?\s*/i, '$1: '));
        set('_views', v.views + ' Views');
        set('_duration', fmtDuration(v.duration || 0));
        set('_date', String(v.published_at || v.created_at || '').slice(0, 10));
        var img = c.querySelector('img');
        if (img) { img.src = v.small_thumbnail || v.thumbnail || ''; img.removeAttribute('srcset'); img.loading = n < 6 ? 'eager' : 'lazy'; }
        if (v.watched && img) {
          var tick = document.createElement('div');
          tick.textContent = '✓ WATCHED';
          tick.style.cssText = 'position:absolute;top:8px;right:8px;z-index:2;padding:2px 8px;border-radius:4px;' +
            'background:rgba(0,0,0,.8);color:__FTV_ACCENT_HEX__;font:700 13px/1.4 sans-serif;';
          img.parentElement.style.position = 'relative';
          img.parentElement.appendChild(tick);
        }
        track.appendChild(c);
      });
      row.appendChild(label);
      row.appendChild(track);
      out.appendChild(row);
    });
    if (box) box.remove();
    wrapper.parentElement.insertBefore(out, wrapper);
    wrapper.style.display = 'none';
    pscSig = 'built';
  }

  // Auto-fullscreen the player once per page -- gated to "not the
  // homepage" rather than "large enough", because both sites' homepages
  // autoplay a similarly-sized hero banner video that would otherwise
  // trigger this while just browsing.
  //
  // This is plain CSS, not the HTML5 Fullscreen API: mde's Bunny iframe is
  // missing "fullscreen" from its allow attribute, so every
  // requestFullscreen() call -- on the iframe, and (since Permissions
  // Policy governs the iframe's whole document, not how a request is
  // triggered) on anything inside it including Bunny's own fullscreen
  // button, had one been reachable -- rejects with "Permissions check
  // failed" regardless of gesture, confirmed live. But the WebView is
  // already fullscreen/immersive at the OS level; the only reason the
  // player doesn't look fullscreen is that the surrounding page (header,
  // breadcrumbs) shares the layout with it. Pinning the player element
  // itself to cover the viewport achieves the same visual result without
  // ever calling the blocked API.
  // The fullscreen player stays just under the very top layer, which is
  // left for the sites' achievement/reward toasts (see siteCss) and the
  // app's own seek overlay.
  var FULLSCREEN_Z = 2147483000;

  function pinStyle(el, prop, value){
    fullscreenUndo.push([el, el.style.cssText]);
    el.style.setProperty(prop, value, 'important');
  }

  // Puts back every inline style maybeAutoFullscreen changed. Run when the
  // route goes back to the homepage, in case the SPA keeps the player's
  // container mounted in a shared layout.
  function undoFullscreen(){
    for (var i = fullscreenUndo.length - 1; i >= 0; i--) {
      try { fullscreenUndo[i][0].style.cssText = fullscreenUndo[i][1]; } catch (e) {}
    }
    fullscreenUndo = [];
    fullscreenEl = null;
  }

  function maybeAutoFullscreen(el){
    if (el === fullscreenEl || location.pathname === '/') return;
    undoFullscreen();
    fullscreenEl = el;
    // position:fixed is relative to the viewport UNLESS an ancestor has a
    // transform/filter/perspective/will-change/contain, which creates its
    // own containing block instead (as does container-type) -- confirmed live: computed position was
    // "fixed" but the rendered rect was still boxed inside a transformed
    // ancestor's layout. This used to escape that by reparenting the
    // element onto <body>, but moving an <iframe> in the DOM makes the
    // browser discard and reload it (HTML spec; only the new moveBefore()
    // avoids that) -- so every video loaded twice, and the player.js
    // subscription sent just before went to the discarded frame. It also
    // left a node React no longer knew the position of. Neutralise the
    // offending ancestors in place instead.
    for (var a = el.parentElement; a && a !== document.documentElement; a = a.parentElement) {
      var s = getComputedStyle(a);
      if (s.transform !== 'none') pinStyle(a, 'transform', 'none');
      // The standalone properties count too (mde's player wrapper sets
      // translate:0px, which does nothing visually but still traps it).
      if (s.translate && s.translate !== 'none') pinStyle(a, 'translate', 'none');
      if (s.scale && s.scale !== 'none') pinStyle(a, 'scale', 'none');
      if (s.rotate && s.rotate !== 'none') pinStyle(a, 'rotate', 'none');
      if (s.filter && s.filter !== 'none') pinStyle(a, 'filter', 'none');
      if (s.perspective && s.perspective !== 'none') pinStyle(a, 'perspective', 'none');
      if (s.willChange && s.willChange !== 'auto') pinStyle(a, 'will-change', 'auto');
      if (s.contain && s.contain !== 'none') pinStyle(a, 'contain', 'none');
      if (s.backdropFilter && s.backdropFilter !== 'none') pinStyle(a, 'backdrop-filter', 'none');
      // Container queries imply layout containment, which also captures
      // fixed descendants -- mde's current player skin sets this.
      if (s.containerType && s.containerType !== 'normal') pinStyle(a, 'container-type', 'normal');
      // Staying in place also means staying inside any ancestor stacking
      // context -- lift those to the top too, or a fixed site header with
      // a higher z-index would paint over the video.
      if (s.zIndex !== 'auto' && s.position !== 'static') pinStyle(a, 'z-index', String(FULLSCREEN_Z));
    }
    fullscreenUndo.push([el, el.style.cssText]);
    el.style.setProperty('position', 'fixed', 'important');
    el.style.setProperty('top', '0', 'important');
    el.style.setProperty('left', '0', 'important');
    el.style.setProperty('margin', '0', 'important');
    // Explicit pixel dimensions from innerWidth/innerHeight, not 100vw/100vh
    // -- vw/vh can be wider than the actual visible viewport (they can
    // include scrollbar-gutter space depending on the engine), which with
    // left:0 pushes the excess off the right edge only, not both sides
    // evenly. That's exactly the reported symptom: more cropped on the
    // right than the left.
    el.style.setProperty('width', window.innerWidth + 'px', 'important');
    el.style.setProperty('height', window.innerHeight + 'px', 'important');
    el.style.setProperty('z-index', String(FULLSCREEN_Z), 'important');
    el.style.setProperty('background', '#000', 'important');
    hideVeil();
  }

  var PAUSE_HOME_VIDEOS = __FTV_PAUSE_HOME_VIDEOS__;
  // With a poster: autoplay off and load() back to the poster, which also
  // releases the hardware decoder. Without one: pause once a frame is on
  // screen -- pausing on the 'play' event itself left an empty black box,
  // and the Spotlight clips open on black frames anyway.
  function stopAfterFirstFrame(v){
    if (v.poster) {
      v.autoplay = false;
      try { v.pause(); v.load(); } catch (e) {}
      return;
    }
    if (v.readyState >= 2 && v.currentTime > 0) { try { v.pause(); } catch (e) {} return; }
    if (v.__ftvStopping) return;
    v.__ftvStopping = true;
    v.addEventListener('timeupdate', function(){
      v.__ftvStopping = false;
      if (location.pathname === '/') { try { v.pause(); } catch (e) {} }
    }, { once: true });
  }
  function pauseHomeVideos(){
    if (!PAUSE_HOME_VIDEOS || location.pathname !== '/') return;
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) { if (!vs[i].paused) stopAfterFirstFrame(vs[i]); }
  }

  document.addEventListener('play', function(e){
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    v.disablePictureInPicture = true;
    // Homepage previews (see pauseHomeVideos in MainActivity): stopped as
    // they start, including each new clip the Spotlight carousel rotates to.
    if (PAUSE_HOME_VIDEOS && location.pathname === '/') {
      stopAfterFirstFrame(v);
      return;
    }
    var r = v.getBoundingClientRect();
    // Only a real, mostly-viewport-filling video is worth tracking -- a
    // homepage grid thumbnail hover-preview (commonly 150-300px) is a
    // mouse-era autoplay pattern that means nothing on a D-pad interface,
    // and each one spins up its own hardware video decoder. On this device
    // (~1.7GB RAM, ~36MB free under normal load) enough of those playing
    // concurrently was enough for Android's low-memory-killer to kill the
    // *shared* com.amazon.webview.chromium process outright, taking the
    // whole app down with it -- confirmed via logcat. Stop it outright
    // rather than merely ignoring it.
    if (r.width < window.innerWidth * 0.5 || r.height < window.innerHeight * 0.3) {
      try { v.pause(); } catch (err) {}
      return;
    }
    trackedVideo = v;
    maybeAutoFullscreen(v);
  }, true);

  // Bunny's iframe doesn't fire a same-origin 'play' event we can catch, so
  // it's picked up via the ordinary DOM-mutation invalidation path instead
  // -- cheap, re-checked only on the existing debounced tick, not polled.
  function checkPlayerFrame(){
    if (location.pathname === '/' && fullscreenEl) undoFullscreen();
    var f = findPlayerFrame();
    if (!f) return;
    subscribePlayerEvents();
    maybeAutoFullscreen(f);
  }
  // The Fullscreen API only honours a request made close to the user
  // gesture that (indirectly, via the click that navigated here)
  // triggered it -- the 250ms-debounced mutation tick above is too late by
  // the time it fires. A few immediate/near-immediate checks right as this
  // script starts running catch the iframe (if it's already in the initial
  // HTML) as early as possible instead of waiting for that debounce.
  checkPlayerFrame();
  pauseHomeVideos();   // anything that started before this script was injected
  setTimeout(function(){ try { renderRecent(); } catch (e) {} }, 800);
  setTimeout(checkPlayerFrame, 0);
  setTimeout(checkPlayerFrame, 100);
  setTimeout(checkPlayerFrame, 300);

  // Minimal on-screen feedback for seek/play-pause -- cheap now that it
  // targets the already-tracked video/iframe directly rather than the old
  // design's full-page video scan on every keypress (that scan, not the
  // overlay itself, was what got removed for performance earlier).
  var seekOverlayEl = null, seekOverlayTimeEl = null, seekOverlayFillEl = null, seekOverlayHideTimer = null;
  var seekOverlayVisible = false;
  var seekThumbEl = null;
  // Scrubbing: left/right and FF/RW move this target without touching the
  // player; one seek happens on Select, or after SCRUB_COMMIT_MS without a
  // key press. Back cancels. null when not scrubbing.
  var scrubTime = null, scrubTimer = null;
  var SCRUB_COMMIT_MS = 1500;

  function currentPlaybackInfo(){
    if (trackedVideo && document.contains(trackedVideo)) {
      return { time: trackedVideo.currentTime, duration: trackedVideo.duration || 0 };
    }
    return { time: playerState.time, duration: playerState.duration };
  }

  // h:mm:ss when the video is an hour or longer (both sides of the
  // "position / duration" readout, so they line up), else m:ss.
  function fmtSeekTime(t, hours){
    if (!isFinite(t) || t < 0) t = 0;
    var h = Math.floor(t / 3600), m = Math.floor(t % 3600 / 60), s = Math.floor(t % 60);
    var ss = (s < 10 ? '0' : '') + s;
    if (hours) return h + ':' + (m < 10 ? '0' : '') + m + ':' + ss;
    return Math.floor(t / 60) + ':' + ss;
  }

  function ensureSeekOverlay(){
    if (seekOverlayEl) return;
    var style = document.createElement('style');
    style.textContent =
      '#__ftv_seek{position:fixed;left:50%;bottom:8%;transform:translateX(-50%);' +
        'min-width:260px;padding:12px 22px;border-radius:10px;background:rgba(0,0,0,.72);' +
        'z-index:2147483647;opacity:0;transition:opacity 150ms ease-out;pointer-events:none;}' +
      '#__ftv_seek.__ftv_show{opacity:1;}' +
      '#__ftv_seek_time{color:#fff;font:600 18px/1.2 sans-serif;text-align:center;margin-bottom:6px;' +
        'text-shadow:0 1px 3px rgba(0,0,0,.8);}' +
      '#__ftv_seek_bar{width:100%;height:5px;border-radius:3px;background:rgba(255,255,255,.3);overflow:hidden;}' +
      '#__ftv_seek_fill{height:100%;width:0%;background:__FTV_ACCENT_HEX__;}' +
      '#__ftv_seek.__ftv_scrub{min-width:520px;}' +
      '#__ftv_seek_thumb{display:none;margin:0 auto 8px;border-radius:6px;background-color:#000;' +
        'background-repeat:no-repeat;box-shadow:0 2px 10px rgba(0,0,0,.6);}' +
      '#__ftv_seek.__ftv_scrub #__ftv_seek_thumb.__ftv_has{display:block;}';
    (document.head || document.documentElement).appendChild(style);

    seekOverlayEl = document.createElement('div');
    seekOverlayEl.id = '__ftv_seek';
    seekThumbEl = document.createElement('div');
    seekThumbEl.id = '__ftv_seek_thumb';
    seekOverlayEl.appendChild(seekThumbEl);
    seekOverlayTimeEl = document.createElement('div');
    seekOverlayTimeEl.id = '__ftv_seek_time';
    var bar = document.createElement('div');
    bar.id = '__ftv_seek_bar';
    seekOverlayFillEl = document.createElement('div');
    seekOverlayFillEl.id = '__ftv_seek_fill';
    bar.appendChild(seekOverlayFillEl);
    seekOverlayEl.appendChild(seekOverlayTimeEl);
    seekOverlayEl.appendChild(bar);
    (document.body || document.documentElement).appendChild(seekOverlayEl);
  }

  function renderSeekOverlay(){
    ensureSeekOverlay();
    var info = currentPlaybackInfo();
    var t = scrubTime !== null ? scrubTime : info.time;
    var hours = isFinite(info.duration) && info.duration >= 3600;
    seekOverlayTimeEl.textContent = fmtSeekTime(t, hours) + ' / ' + fmtSeekTime(info.duration, hours);
    seekOverlayFillEl.style.width = (info.duration ? (t / info.duration * 100) : 0) + '%';
    seekOverlayEl.classList.toggle('__ftv_scrub', scrubTime !== null);
    if (scrubTime !== null) renderThumb(scrubTime);
  }

  // Preview thumbnail for time t. Both sites' players are Bunny Stream,
  // whose seek previews are sprite sheets of 6x6 tiles, one every 2s:
  //  - mde's own <video> carries them as a 'thumbnails' metadata track
  //    (cue text "<sprite url>#xywh=x,y,w,h"), signed URLs included;
  //  - fishtank's player is a cross-origin iframe, but its sprites are
  //    public at <cdn>/<video id>/seek/_N.jpg (1800x1008, tiles 300x168).
  // No preview (just the bar) if neither is available.
  var THUMB_W = 240;
  function thumbAt(t){
    var v = trackedVideo && document.contains(trackedVideo) ? trackedVideo : null;
    if (v) {
      var track = null;
      for (var i = 0; i < v.textTracks.length; i++) {
        var tt = v.textTracks[i];
        if (tt.kind === 'metadata' && /thumb/i.test(tt.label)) { track = tt; break; }
      }
      var cues = track && track.cues;
      if (!cues || !cues.length) return null;
      var lo = 0, hi = cues.length - 1;
      while (lo < hi) { var mid = (lo + hi + 1) >> 1; if (cues[mid].startTime <= t) lo = mid; else hi = mid - 1; }
      var m = /^(\S+?)#xywh=(\d+),(\d+),(\d+),(\d+)/.exec(String(cues[lo].text).trim());
      if (!m) return null;
      return { url: m[1], x: +m[2], y: +m[3], w: +m[4], h: +m[5] };
    }
    var f = findPlayerFrame();
    var id = f && /\/embed\/\d+\/([0-9a-f-]{36})/.exec(f.src);
    if (!id) return null;
    var host = bunnyHost();
    var n = Math.floor(Math.max(0, t) / 2), k = n % 36;
    return { url: 'https://' + host + '/' + id[1] + '/seek/_' + Math.floor(n / 36) + '.jpg',
             x: (k % 6) * 300, y: Math.floor(k / 6) * 168, w: 300, h: 168 };
  }

  // fishtank's Bunny CDN host, read off any of its images on the page.
  function bunnyHost(){
    var img = document.querySelector('img[src*=".b-cdn.net/"]');
    var hm = img && /\/\/(vz-[^/]+\.b-cdn\.net)\//.exec(img.src);
    return hm ? hm[1] : 'vz-a2fe5bfa-400.b-cdn.net';
  }

  // Sprite sheets already loaded (url -> true) or loading (url -> Image).
  // A tile is only shown once its sheet has loaded, so a fast scrub into
  // a new sheet keeps the last good tile instead of flashing black; the
  // following sheet is preloaded so moving forward rarely waits at all.
  var sprites = {};
  function loadSprite(url, then){
    if (sprites[url] === true) { if (then) then(); return true; }
    if (!sprites[url]) {
      var im = new Image();
      im.onload = function(){ sprites[url] = true; if (im.__then) im.__then(); };
      im.src = url;
      sprites[url] = im;
    }
    if (then) sprites[url].__then = then;
    return false;
  }

  function renderThumb(t){
    var th = null;
    try { th = thumbAt(t); } catch (e) {}
    if (!th) { seekThumbEl.classList.remove('__ftv_has'); return; }
    try { var ahead = thumbAt(t + 72); if (ahead) loadSprite(ahead.url); } catch (e) {}
    if (!loadSprite(th.url, function(){ if (scrubTime !== null) renderThumb(scrubTime); })) {
      if (seekThumbEl.style.backgroundImage) seekThumbEl.classList.add('__ftv_has');
      return;
    }
    var k = THUMB_W / th.w;
    seekThumbEl.style.width = THUMB_W + 'px';
    seekThumbEl.style.height = Math.round(th.h * k) + 'px';
    seekThumbEl.style.backgroundImage = 'url("' + th.url + '")';
    seekThumbEl.style.backgroundSize = (th.w * 6 * k) + 'px ' + (th.h * 6 * k) + 'px';
    seekThumbEl.style.backgroundPosition = (-th.x * k) + 'px ' + (-th.y * k) + 'px';
    seekThumbEl.classList.add('__ftv_has');
  }

  function showSeekOverlay(){
    renderSeekOverlay();
    seekOverlayEl.classList.add('__ftv_show');
    seekOverlayVisible = true;
    if (seekOverlayHideTimer) clearTimeout(seekOverlayHideTimer);
    if (scrubTime !== null) return;   // stays up until the scrub ends
    seekOverlayHideTimer = setTimeout(function(){
      seekOverlayEl.classList.remove('__ftv_show');
      seekOverlayVisible = false;
    }, 2000);
  }

  // MainActivity's play/pause/seek key handlers go through these instead of
  // manipulating a <video> element directly, so the same physical buttons
  // work whether the page has a same-origin video or (as turned out to be
  // the common case) a cross-origin Bunny iframe reachable only via
  // postMessage.
  window.__ftvTogglePlay = function(){
    if (trackedVideo && document.contains(trackedVideo)) {
      if (trackedVideo.paused) { trackedVideo.play(); } else { trackedVideo.pause(); }
      showSeekOverlay();
      return;
    }
    sendPlayerCommand(playerState.playing ? 'pause' : 'play');
    playerState.playing = !playerState.playing; // optimistic; corrected by the real play/pause event if wrong
    showSeekOverlay();
  };

  // Unconditional pause (unlike __ftvTogglePlay) for BACK's "make sure
  // nothing keeps playing off-screen" belt-and-suspenders -- toggling could
  // have started playback instead if it was already paused.
  window.__ftvPause = function(){
    if (trackedVideo && document.contains(trackedVideo)) { trackedVideo.pause(); }
    sendPlayerCommand('pause');
    playerState.playing = false;
  };

  window.__ftvSeek = function(deltaSeconds){
    if (trackedVideo && document.contains(trackedVideo)) {
      trackedVideo.currentTime = Math.max(0, trackedVideo.currentTime + deltaSeconds);
      showSeekOverlay();
      return;
    }
    if (!findPlayerFrame()) return;
    // Position not reported yet: ask for it and seek from the answer,
    // rather than seeking relative to a made-up 0.
    if (!playerState.timeKnown) {
      pendingSeek = (pendingSeek || 0) + deltaSeconds;
      subscribePlayerEvents();
      sendPlayerCommand('getCurrentTime', undefined, 'ftvSeek');
      return;
    }
    // No getCurrentTime round trip: timeupdate keeps playerState.time fresh
    // while playing, and this optimistically advances it so back-to-back
    // seeks accumulate correctly even before the next timeupdate confirms.
    var next = Math.max(0, playerState.time + deltaSeconds);
    if (playerState.duration) { next = Math.min(next, playerState.duration); }
    playerState.time = next;
    sendPlayerCommand('setCurrentTime', next);
    showSeekOverlay();
  };

  window.__ftvScrub = function(deltaSeconds){
    if (scrubTime === null) {
      // Position not known yet (fishtank, before the player reports in):
      // fall back to a plain relative seek rather than scrub from 0.
      if (!(trackedVideo && document.contains(trackedVideo)) && !playerState.timeKnown) {
        window.__ftvSeek(deltaSeconds);
        return;
      }
      scrubTime = currentPlaybackInfo().time;
    }
    var dur = currentPlaybackInfo().duration;
    scrubTime = Math.max(0, scrubTime + deltaSeconds);
    if (dur) scrubTime = Math.min(scrubTime, Math.max(0, dur - 1));
    if (scrubTimer) clearTimeout(scrubTimer);
    scrubTimer = setTimeout(window.__ftvScrubCommit, SCRUB_COMMIT_MS);
    showSeekOverlay();
  };

  // Seek to the scrub target. Returns true if a scrub was in progress.
  // Whether a video is actually playing -- MainActivity keeps the screen
  // on only while this is true.
  window.__ftvIsPlaying = function(){
    if (trackedVideo && document.contains(trackedVideo)) return !trackedVideo.paused && !trackedVideo.ended;
    return !!(findPlayerFrame() && playerState.playing);
  };

  // Subtitles on the page's own <video> (mde): 'state' -> "on" | "off" |
  // "none"; 'toggle' flips them. fishtank's cross-origin player exposes no
  // caption control, so it reports "none".
  window.__ftvCaptions = function(op){
    var v = trackedVideo && document.contains(trackedVideo) ? trackedVideo : null;
    if (!v) return 'none';
    var subs = [];
    for (var i = 0; i < v.textTracks.length; i++) {
      var t = v.textTracks[i];
      if (t.kind === 'subtitles' || t.kind === 'captions') subs.push(t);
    }
    if (!subs.length) return 'none';
    var on = subs.some(function(t){ return t.mode === 'showing'; });
    if (op === 'toggle') {
      subs.forEach(function(t, i){ t.mode = (!on && i === 0) ? 'showing' : 'disabled'; });
      on = !on;
    }
    return on ? 'on' : 'off';
  };

  window.__ftvScrubCommit = function(){
    if (scrubTime === null) return false;
    var target = scrubTime;
    scrubTime = null;
    if (scrubTimer) { clearTimeout(scrubTimer); scrubTimer = null; }
    if (trackedVideo && document.contains(trackedVideo)) {
      trackedVideo.currentTime = target;
    } else {
      playerState.time = target;
      sendPlayerCommand('setCurrentTime', target);
    }
    showSeekOverlay();
    return true;
  };

  // Drop the scrub without seeking. Returns true if one was in progress.
  window.__ftvScrubCancel = function(){
    if (cancelNext()) return true;
    // mde's own up-next card: press its close button.
    var close = document.querySelector('[class*="_nextVideo"] [class*="_close"]');
    if (close) { close.click(); return true; }
    if (scrubTime === null) return false;
    scrubTime = null;
    if (scrubTimer) { clearTimeout(scrubTimer); scrubTimer = null; }
    showSeekOverlay();
    return true;
  };

  // True on any dedicated watch page (never the homepage -- see the
  // hero-banner note above). D-pad navigation/activation and the focus
  // ring no-op while this is true: arrows were navigating the underlying
  // page (a "sort" button getting focused and glowing on top of the
  // video), and center was clicking whatever the cursor still happened to
  // be resting on. The physical play/pause/seek buttons are the only
  // control surface while actually watching. Deliberately not conditioned
  // on "is it actually playing" -- the cross-origin iframe's play state
  // only reaches us asynchronously via postMessage, which isn't a signal
  // worth gating on when "not the homepage" already answers the question
  // this exists to answer.
  function playingVideo(){
    if (location.pathname === '/') return null;
    checkPlayerFrame();
    return (trackedVideo && document.contains(trackedVideo)) || findPlayerFrame();
  }

  // Header/branding chrome (logo, search, account/login) shouldn't be where
  // the synthetic cursor parks by default -- it's still reachable by
  // navigating up into it, just not where a fresh page load or reset lands.
  // Content is the sensible default regardless of sign-in state: landing on
  // an account button when already logged in makes no sense, but neither
  // does needing per-site logged-in/out detection to fix just that when
  // simply never defaulting into the header (top ~70px, where both sites'
  // headers live) solves it either way -- fishtank has no equivalent to
  // mde's class-based check at all, so this also fixes fishtank always
  // defaulting onto its logo.
  function isChrome(el){
    if (el.closest('[class*="top-bar-module"], [class*="search-module"], [class*="_topBar"]')) return true;
    return el.getBoundingClientRect().top < 70;
  }
  function inViewport(el){
    var r = el.getBoundingClientRect();
    return r.bottom > 0 && r.top < window.innerHeight && r.right > 0 && r.left < window.innerWidth;
  }
  // The candidate list now reaches a screen past each edge, so the default
  // landing spot has to be chosen from what's actually on screen.
  // Where the cursor starts: the first Continue watching card if that row
  // is showing, else fishtank's "Episodes" menu button (the menu it opens
  // on launch), else the first on-screen content item.
  function preferredStart(list){
    var rc = document.querySelector('#__ftv_recent_row a');
    if (rc && list.indexOf(rc) >= 0) return rc;
    if (location.host.indexOf('fishtank') >= 0) {
      for (var i = 0; i < list.length; i++) {
        if (list[i].tagName === 'BUTTON' && list[i].textContent.trim() === 'Episodes' && inViewport(list[i])) return list[i];
      }
    }
    return null;
  }

  // First key press after load: show the cursor, re-choosing its start in
  // case the preferred target (Continue watching, fishtank's menu) only
  // appeared after the cursor was quietly placed.
  function revealStart(){
    dirty = true;
    var pref = preferredStart(items());
    mark(pref || cur);
  }

  function pickDefault(list){
    var pref = preferredStart(list);
    if (pref) return pref;
    var firstVisible = null;
    for (var i = 0; i < list.length; i++) {
      if (!inViewport(list[i])) continue;
      if (!firstVisible) firstVisible = list[i];
      if (!isChrome(list[i])) return list[i];
    }
    return firstVisible || list[0] || null;
  }

  function centre(el){
    var r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  // Set when the cursor was placed by the passive auto-focus timer below
  // rather than a real key press, so the very first press can reveal it
  // (dim + ring) instead of the screen dimming the instant the page loads,
  // before the user has touched the remote at all.
  var silentCur = false;

  function isEditable(el){
    if (!el) return false;
    var tag = el.tagName;
    if (tag === 'TEXTAREA' || el.isContentEditable) return true;
    return tag === 'INPUT' && !/^(button|submit|reset|checkbox|radio|range|color|file|image)$/i.test(el.type);
  }

  // The selected item's zoom: how much (curZoom) and about which point
  // (curOx/curOy, px within the item). Chosen per item by fitZoom so the
  // zoomed item stays inside both the screen and every ancestor that
  // clips its overflow -- zooming from the centre pushed edge cards off
  // the screen, and cards in tight scrolling rows got their titles cut
  // off by the row. Where there's no room at all it zooms less (or not
  // at all; the lift edge still marks it) rather than getting clipped.
  var curZoom = 1, curOx = 0, curOy = 0;
  var curRect = null;          // cur's unzoomed rect
  var curRef = null;           // cur's parent rect when curRect was taken
  var savedScale = null, savedOrigin = null;

  function fitZoom(el, r){
    var m = 6;
    var L = m, T = m, R = window.innerWidth - m, B = window.innerHeight - m;
    for (var a = el.parentElement; a && a !== document.body && a !== document.documentElement; a = a.parentElement) {
      var st = getComputedStyle(a);
      if (st.overflowX === 'visible' && st.overflowY === 'visible') continue;
      var ar = a.getBoundingClientRect();
      // Screen-space rect edges only: clientWidth etc. are in the
      // element's own units, which differ under an ancestor's CSS zoom.
      if (st.overflowX !== 'visible') { L = Math.max(L, ar.left); R = Math.min(R, ar.right); }
      if (st.overflowY !== 'visible') { T = Math.max(T, ar.top); B = Math.min(B, ar.bottom); }
    }
    // Growth allowed on each side: what a centred zoom would use there
    // (half the extra), but no more than the room to the edge/clip box.
    // So a card against an edge grows inward only as much as a middle card
    // would on that side -- zooming less overall rather than spilling
    // twice as far onto its neighbour.
    // ...and no further than the gap to the nearest neighbouring item
    // that way, less a couple of px, so it never paints over the next card.
    var nL = L, nR = R, nT = T, nB = B, list = cache || [];
    for (var i = 0; i < list.length; i++) {
      var o = list[i];
      if (o === el || el.contains(o) || o.contains(el)) continue;
      var q = o.getBoundingClientRect();
      if (q.bottom > r.top && q.top < r.bottom) {          // same row
        if (q.left >= r.right) nR = Math.min(nR, q.left - 2);
        if (q.right <= r.left) nL = Math.max(nL, q.right + 2);
      }
      if (q.right > r.left && q.left < r.right) {          // same column
        if (q.top >= r.bottom) nB = Math.min(nB, q.top - 2);
        if (q.bottom <= r.top) nT = Math.max(nT, q.bottom + 2);
      }
    }
    function rooms(pos, size, lo, hi){
      var half = (ZOOM - 1) * size / 2;
      return [Math.max(0, Math.min(half, pos - lo)), Math.max(0, Math.min(half, hi - (pos + size)))];
    }
    var rx = rooms(r.left, r.width, nL, nR), ry = rooms(r.top, r.height, nT, nB);
    var z = Math.min(1 + (rx[0] + rx[1]) / r.width, 1 + (ry[0] + ry[1]) / r.height);
    if (!(z >= 1.02)) z = 1;
    // Origin that splits the growth in proportion to the room each side.
    function origin(size, room){
      if (z === 1 || room[0] + room[1] === 0) return size / 2;
      return size * room[0] / (room[0] + room[1]);
    }
    curZoom = z;
    curOx = origin(r.width, rx);
    curOy = origin(r.height, ry);
  }

  function zoomOn(el){
    savedScale = [el.style.getPropertyValue('scale'), el.style.getPropertyPriority('scale')];
    savedOrigin = [el.style.getPropertyValue('transform-origin'), el.style.getPropertyPriority('transform-origin')];
    el.classList.add('__ftv_focus');
    // As percentages, not px: inside an ancestor with CSS zoom (fishtank's
    // episodes page) local px are scaled, while curOx/curOy were measured
    // in screen px -- the zoom would then anchor slightly off the lift.
    var r = curRect || el.getBoundingClientRect();
    var px = r.width ? curOx / r.width * 100 : 50, py = r.height ? curOy / r.height * 100 : 50;
    el.style.setProperty('transform-origin', px + '% ' + py + '%', 'important');
    el.style.setProperty('scale', String(curZoom), 'important');
  }
  function zoomOff(el){
    el.classList.remove('__ftv_focus');
    if (savedScale) el.style.setProperty('scale', savedScale[0], savedScale[1]);
    if (savedOrigin) el.style.setProperty('transform-origin', savedOrigin[0], savedOrigin[1]);
    savedScale = savedOrigin = null;
  }

  // Moves the lift element onto the selected item at its final zoomed
  // box -- computed from the unzoomed rect, so it lands where the zoom
  // animation ends rather than wherever it is mid-transition. 'snap' skips
  // the slide animation (used while scrolling, where sliding would trail).
  function placeVeil(snap){
    if (!cur || silentCur || !document.contains(cur) || playingVideo()) {
      veil.classList.remove('__ftv_on');
      return;
    }
    // The site's React app can rebuild <body> and drop nodes it didn't
    // create, this one included -- put it back rather than lose the lift.
    if (!veil.isConnected) document.body.appendChild(veil);
    var r = curRect || cur.getBoundingClientRect();
    veil.classList.toggle('__ftv_snap', !!snap);
    veil.style.left = (r.left + curOx * (1 - curZoom)) + 'px';
    veil.style.top = (r.top + curOy * (1 - curZoom)) + 'px';
    veil.style.width = (r.width * curZoom) + 'px';
    veil.style.height = (r.height * curZoom) + 'px';
    veil.style.borderRadius = getComputedStyle(cur).borderRadius || '6px';
    veil.classList.add('__ftv_on');
  }
  function hideVeil(){ veil.classList.remove('__ftv_on'); }

  // Scrolling moves the selected item under the fixed lift element, so
  // follow it -- once per frame, snapped. Tracked via the (unzoomed)
  // parent's movement, since cur's own rect may be mid-zoom-animation.
  var veilRaf = false;
  document.addEventListener('scroll', function(){
    if (veilRaf || !cur || !curRect || !curRef) return;
    veilRaf = true;
    requestAnimationFrame(function(){
      veilRaf = false;
      if (!cur || !curRect || !curRef || !cur.parentElement) return;
      var pr = cur.parentElement.getBoundingClientRect();
      var dx = pr.left - curRef.left, dy = pr.top - curRef.top;
      curRect = { left: curRect.left + dx, top: curRect.top + dy, width: curRect.width, height: curRect.height };
      curRef = { left: pr.left, top: pr.top };
      placeVeil(true);
    });
  }, { passive: true, capture: true });

  function mark(el, silent){
    if (cur) { zoomOff(cur); }
    cur = el || null;
    curRect = curRef = null;
    if (!cur) { silentCur = false; hideVeil(); return; }
    if (silent) { silentCur = true; return; }
    silentCur = false;
    // Never focus a text field just because the cursor passed over it, and
    // let go of one the cursor has left: a focused field connects the
    // system keyboard, which then receives the remote's keys *before* the
    // app does even while hidden -- so the next Select "pressed" the
    // keyboard's highlighted key and typed a stray "a". Text fields only
    // get focus from __ftvActivate, i.e. an actual Select on them.
    var ae = document.activeElement;
    if (ae && ae !== cur && isEditable(ae)) { try { ae.blur(); } catch (e) {} }
    if (!isEditable(cur)) { try { cur.focus({ preventScroll: true }); } catch (e) {} }
    // Targets can now be off-screen in either axis (next row below, next
    // card along a carousel), so scroll whichever axis needs it.
    var r = cur.getBoundingClientRect();
    var needY = r.top < 60 || r.bottom > window.innerHeight - 60;
    var needX = r.left < 0 || r.right > window.innerWidth;
    if (needY || needX) {
      try {
        cur.scrollIntoView({ block: needY ? 'center' : 'nearest', inline: needX ? 'center' : 'nearest', behavior: 'auto' });
      } catch (e) { cur.scrollIntoView(); }
    }
    // Measure (unzoomed) after any scroll, then zoom and move the lift.
    curRect = cur.getBoundingClientRect();
    var pr = cur.parentElement && cur.parentElement.getBoundingClientRect();
    curRef = pr ? { left: pr.left, top: pr.top } : null;
    fitZoom(cur, curRect);
    zoomOn(cur);
    placeVeil(false);
  }

  window.__ftvReset = function(){
    if (playingVideo()) return;
    if (cur) { zoomOff(cur); }
    cur = null;
    hideVeil();
    var list = items();
    if (list.length) { mark(pickDefault(list)); }
  };

  // Nearest scrollable ancestor along one axis -- used to scroll a
  // horizontally-scrolling carousel row when the D-pad reaches its last
  // on-screen card, the same way vertical scrolling already falls back to
  // window.scrollBy when nothing more is focusable that way.
  function scrollableAncestor(el, axis){
    var node = el && el.parentElement;
    while (node && node !== document.body) {
      var s = getComputedStyle(node);
      if (axis === 'x') {
        if ((s.overflowX === 'auto' || s.overflowX === 'scroll') && node.scrollWidth > node.clientWidth + 4) return node;
      } else {
        if ((s.overflowY === 'auto' || s.overflowY === 'scroll') && node.scrollHeight > node.clientHeight + 4) return node;
      }
      node = node.parentElement;
    }
    return null;
  }

  function inBeam(el, dir){
    var a = cur.getBoundingClientRect(), b = el.getBoundingClientRect();
    if (dir === 'left' || dir === 'right') return b.bottom > a.top + 2 && b.top < a.bottom - 2;
    return b.right > a.left + 2 && b.left < a.right - 2;
  }

  function nearest(list, dir){
    var c = centre(cur);
    var best = null;
    var bestScore = Infinity;

    // Left/right inside a horizontally-scrolling carousel must stay locked
    // to that row -- it should only ever continue along the row or stop at
    // its end, never drift into a different row/section just because
    // something there scored better than nothing. Without this, reaching
    // the last card in a row could jump the cursor down into the next
    // section entirely (this got a lot more likely to bite once the Series
    // grid went from 2 columns to 4 -- more rows means more diagonal
    // neighbours close enough to out-score a genuinely-empty "next" slot).
    var track = (dir === 'left' || dir === 'right') ? scrollableAncestor(cur, 'x') : null;

    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === cur) continue;
      if (track && !track.contains(el)) continue;
      var p = centre(el);
      var dx = p.x - c.x;
      var dy = p.y - c.y;
      var along, across;

      if (dir === 'left')       { along = -dx; across = Math.abs(dy); }
      else if (dir === 'right') { along =  dx; across = Math.abs(dy); }
      else if (dir === 'up')    { along = -dy; across = Math.abs(dx); }
      else                      { along =  dy; across = Math.abs(dx); }

      if (along <= 4) continue;              // wrong side of the cursor
      var score = along + across * 2.5;      // prefer straight ahead over diagonal
      // Anything in line with the cursor (overlapping it across the
      // direction of travel) beats everything that isn't, however close --
      // as Android's own focus search does. Without this, RIGHT from a
      // section's tabs went down into the nearby cards and that section's
      // far-right "View All" link could never be reached.
      if (!inBeam(el, dir)) score += 1e6;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function doNavigate(dir){
    var list = items();
    if (!list.length) return;

    // Only reset to the default when the cursor is really lost (removed,
    // hidden, or more than a screen away). Merely scrolled off the edge --
    // e.g. by the scroll fallback below -- it keeps navigating from where
    // it is, instead of jumping back up to the top-left item.
    if (cur && document.contains(cur) && list.indexOf(cur) < 0 && !dirty) { dirty = true; list = items(); }
    if (!cur || !document.contains(cur) || list.indexOf(cur) < 0) { mark(pickDefault(list)); return; }

    var best = nearest(list, dir);

    // A cached list that's gone stale (a row hydrated but no mutation/
    // scroll happened to invalidate it yet) is a far more common reason to
    // come up empty than "there's truly nothing that way", so force one
    // fresh read and retry before concluding that and falling back to
    // scrolling.
    if (!best && !dirty) {
      dirty = true;
      list = items();
      best = nearest(list, dir);
    }

    if (best) {
      mark(best);
    } else if (dir === 'down') {
      window.scrollBy(0, Math.round(window.innerHeight * 0.6));
    } else if (dir === 'up') {
      window.scrollBy(0, -Math.round(window.innerHeight * 0.6));
    } else {
      // Nothing focusable further that way in the viewport -- if cur sits
      // inside a horizontally-scrolling carousel, scroll that container to
      // reveal more instead of dead-ending; the retry-fresh-recompute above
      // already covers rediscovering the newly-visible cards on the next
      // press once the scroll settles.
      var track = cur && scrollableAncestor(cur, 'x');
      if (track) {
        var amount = Math.round(track.clientWidth * 0.8);
        track.scrollBy({ left: dir === 'right' ? amount : -amount, behavior: 'auto' });
      }
    }
  }

  // Fire TV remotes auto-repeat while a direction is held, firing one JS
  // eval per repeat. Collapse a whole run of those into a single navigate
  // call per animation frame instead of one full candidate search each.
  var pendingDir = null;
  var rafScheduled = false;
  // On a watch page the arrows don't navigate the page under the player
  // (see playingVideo); instead left/right seek -- by a step that grows
  // while held, computed natively from the key repeat count -- and up/down
  // just bring up the position overlay.
  window.__ftvNavigate = function(dir, step){
    if (playingVideo()) {
      if (dir === 'left') window.__ftvScrub(-(step || 10));
      else if (dir === 'right') window.__ftvScrub(step || 10);
      else showSeekOverlay();
      return;
    }
    if (silentCur) { revealStart(); return; }    // first press just reveals it
    pendingDir = dir;
    if (rafScheduled) return;
    rafScheduled = true;
    requestAnimationFrame(function(){
      rafScheduled = false;
      var d = pendingDir;
      pendingDir = null;
      if (d) doNavigate(d);
    });
  };

  // Client-side route to the homepage via the site's own home link (a
  // Next.js <Link>, which intercepts the click), instead of a full reload.
  // Returns false if there's no such link, so the caller can fall back.
  // The path we navigated *from* on the last client-side route change, so
  // BACK can tell whether the page before this one was the homepage. Both
  // sites route with history.pushState (Next.js); a popstate means we no
  // longer know, so it resets.
  var prevPath = null;
  (function(){
    var push = history.pushState;
    history.pushState = function(){
      var from = location.pathname;
      var r = push.apply(this, arguments);
      if (location.pathname !== from) prevPath = from;
      return r;
    };
    window.addEventListener('popstate', function(){ prevPath = null; });
  })();

  window.__ftvGoHome = function(){
    // Came straight from the homepage: go back to it. Next.js restores a
    // back navigation from its client cache (and keeps the scroll position)
    // -- measured ~0.5s, where clicking the home link waits on the server to
    // render the homepage again (~4.4s on mde, no better than a full reload).
    if (prevPath === '/') {
      undoFullscreen();
      if (cur) { zoomOff(cur); cur = null; }
      hideVeil();
      prevPath = null;
      history.back();
      return true;
    }
    var link = document.querySelector('a[href="/"]') ||
               document.querySelector('a[href="' + location.origin + '/"]') ||
               document.querySelector('a[href="' + location.origin + '"]');
    if (!link) return false;
    undoFullscreen();
    if (cur) { zoomOff(cur); cur = null; }
    hideVeil();
    link.click();
    return true;
  };

  window.__ftvActivate = function(){
    if (playingVideo()) { if (!window.__ftvScrubCommit()) window.__ftvTogglePlay(); return; }
    if (silentCur) { revealStart(); return; }    // first press just reveals it
    if (!cur) { window.__ftvReset(); return; }
    var tag = cur.tagName.toLowerCase();
    if (tag === 'input' || tag === 'textarea') {
      // focus() alone never brings up the Fire TV keyboard: a field
      // focused from script isn't user input, so Chromium never connects
      // it to the IME and even a native showSoftInput() is ignored. Hand
      // back the field's centre in device pixels instead, and MainActivity
      // taps it with a real touch event, which opens the keyboard the same
      // way a finger would.
      cur.focus();
      var r = cur.getBoundingClientRect(), d = window.devicePixelRatio || 1;
      return 'tap:' + Math.round((r.left + r.width / 2) * d) + ',' + Math.round((r.top + r.height / 2) * d);
    }
    var ep = cur.closest && cur.closest('a[href^="/episodes/"]');
    if (ep) {
      var em = /\/episodes\/([0-9a-f-]{36})/.exec(ep.getAttribute('href'));
      if (em && !ep.closest('#__ftv_recent_row')) {
        try { localStorage.setItem('__ftv_title_' + em[1], cleanTitle(ep.textContent)); } catch (e) {}
        rememberQueue(ep);
      }
    }
    cur.click();
  };

  // Silent: this establishes a starting point for navigation without
  // dimming the screen before the user has touched the remote at all --
  // __ftvNavigate/__ftvActivate reveal it (full visual mark) on first press.
  setTimeout(function(){
    if (cur) return;
    var list = items();
    if (list.length) { mark(pickDefault(list), true); }
  }, 1200);
})();
