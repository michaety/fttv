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
    // Moved with transform (the compositor slides it without repainting);
    // only a change of size re-lays out and repaints its blurred shadow.
    '#__ftv_veil{position:fixed;left:0;top:0;width:0;height:0;pointer-events:none;' +
      'z-index:2147483646;border-radius:6px;opacity:0;will-change:transform;' +
      'box-shadow:0 0 0 1px rgba(255,255,255,.55),0 10px 28px 6px rgba(0,0,0,.6);' +
      'transition:opacity 160ms ease-out,transform 160ms ease-out,' +
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
  // alone before any survivor pays for a getComputedStyle call. Whether an
  // item is covered is NOT checked here: hit-testing every card cost ~30ms
  // a rebuild on the Firestick, so only the items the cursor is about to
  // land on are tested (isCovered, below).
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
    // (mde's Spotlight 1-7 buttons: LEFT/RIGHT on the slide work them instead, see stepSpotlight)
    return !!(el.matches && el.matches('a[class*="_logo"], [class*="_spotlight"] [class*="_selector"] button'));
  }

  // covered(), remembered until the list is next rebuilt.
  var coveredMemo = new Map();
  function isCovered(el){
    if (!coveredMemo.has(el)) coveredMemo.set(el, covered(el));
    return coveredMemo.get(el);
  }

  // changeCount: real changes to the page (mutations, big scrolls) so far;
  // builtAt: its value when the list was built. A press that finds nothing
  // that way only rebuilds to retry if something changed since.
  var changeCount = 0, builtAt = -1;
  function items(){
    if (dirty || !cache) {
      cache = computeItems(); coveredMemo = new Map(); dirty = false;
      builtAt = changeCount;
      if (typeof scrolledFrom !== 'undefined') scrolledFrom = new Map();
    }
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
    changeCount++;
    invalidateTimer = setTimeout(function(){
      invalidateTimer = null;
      changeCount++;
      dirty = true;
      // The selected item was redrawn (a row rebuilt, React re-rendering):
      // move the cursor onto its replacement -- same link, else whatever is
      // where it was (refind) -- or let go of it if there's none.
      if (cur && !document.contains(cur)) {
        var again = silentCur || playingVideo() ? null : refind(items());
        if (again) mark(again);
        else { hideVeil(); cur = null; trackFor = null; }
      }
      // Let go of a page's elements once they're gone, or they (and the
      // whole page around them) stay in memory: during playback the cursor
      // never moves, so the previous page's list kept all of it alive, and
      // an old episode's <video> survived into the next one (renderer
      // crashes happen exactly when switching episodes).
      if (cache && cache.length && !document.contains(cache[0])) { cache = null; coveredMemo = new Map(); }
      if (trackedVideo && !document.contains(trackedVideo)) trackedVideo = null;
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
      try { syncAccount(); } catch (e) {}
      try { renderPscRows(); } catch (e) {}
      restoreReturnFocus();
    }, 250);
  }
  // Mutations entirely inside an ignored subtree (hidden live chat that the
  // SPA keeps rendering into) are skipped, or they'd dirty the cache every
  // few hundred ms forever and every keypress would pay a full rebuild.
  // So are mutations inside the selected item: mde swaps a card's thumbnail
  // when it takes focus, which made every key press rebuild the list.
  var IGNORE = __FTV_IGNORE_MUTATIONS__;
  new MutationObserver(function(records){
    for (var i = 0; i < records.length; i++) {
      var t = records[i].target;
      if (cur && cur !== t && cur.contains(t)) continue;
      if (IGNORE && t.closest && t.closest(IGNORE)) continue;
      scheduleInvalidate();
      return;
    }
  }).observe(document.body, { childList: true, subtree: true });
  // Capture phase on document, not a plain window listener: scroll events
  // don't bubble, so a listener on window only ever sees the page itself
  // scrolling. Capture-phase delegation from document catches any nested
  // scrollable container too (a horizontally-scrolling carousel row).
  //
  // A scroll moves things but doesn't add or remove any, and the list
  // already reaches a screen past each edge, so it's only rebuilt once
  // something has scrolled more than half a screen since it was built.
  // (It used to be rebuilt after every scroll, and nearly every DOWN press
  // scrolls, so most presses paid for a full rebuild.)
  var scrolledFrom = new Map();   // scroller -> [left, top] when the list was built
  function scrollPos(t){
    return t === document || t === document.documentElement || t === document.body
      ? [window.scrollX, window.scrollY] : [t.scrollLeft, t.scrollTop];
  }
  document.addEventListener('scroll', function(e){
    var t = e.target, p = scrollPos(t), from = scrolledFrom.get(t);
    if (!from) { scrolledFrom.set(t, p); return; }
    if (Math.abs(p[0] - from[0]) > window.innerWidth / 2 || Math.abs(p[1] - from[1]) > window.innerHeight / 2) {
      scrolledFrom.set(t, p);
      scheduleInvalidate();
    }
  }, { passive: true, capture: true });

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
    else if (data.event === 'play') { playerState.playing = true; playbackStarted(); osdPlayStateChanged(); }
    else if (data.event === 'pause') { playerState.playing = false; osdPlayStateChanged(); }
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
      if (playerState.time > 0) playbackStarted();
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
                   thumb: ftThumb(id), t: t, dur: dur });
    if (dur > 60 && t > dur - 30) queueFishtankNextUp();
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
    // Something queued from the phone remote goes first.
    var queued = loadPlayQueue()[0];
    var qm = queued && /^\/episodes\/([0-9a-f-]{36})$/.exec(queued.path);
    if (qm && queued.path !== location.pathname) return { id: qm[1], title: queued.title || 'Episode', row: queued.row };
    var id = episodeId(), q = [];
    try { q = JSON.parse(sessionStorage.getItem('__ftv_queue') || '[]'); } catch (e) {}
    for (var i = 0; i < q.length - 1; i++) if (q[i].id === id) return q[i + 1];
    return null;
  }
  // Continue watching's "Up next": the next episode in the same row.
  // Once per episode (the page stays loaded between episodes here).
  var nextUpFor = null;
  function queueFishtankNextUp(){
    var id = episodeId(), next = nextEpisode();
    if (!next || nextUpFor === id) return;
    nextUpFor = id;
    try { localStorage.setItem('__ftv_title_' + next.id, cleanTitle(next.title)); } catch (e) {}
    recordNextUp({ path: '/episodes/' + next.id, title: cleanTitle(next.title),
                   thumb: ftThumb(next.id) });
  }
  function onEpisodeEnded(){
    var id = episodeId();
    try { if (id) localStorage.removeItem('__ftv_pos_' + id); } catch (e) {}
    if (id) removeRecent('/episodes/' + id);
    queueFishtankNextUp();
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
      img.src = ftThumb(next.id);
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
        // A queued episode brings its own season, for autoplay after it.
        if (next.row && next.row.length > 1) {
          try { sessionStorage.setItem('__ftv_queue', JSON.stringify(next.row)); } catch (e) {}
        }
        window.__ftvGo('/episodes/' + next.id, { title: cleanTitle(next.title), thumb: ftThumb(next.id) });
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

  // ---- Queue (both sites, from the phone remote) -----------------------
  // Episodes queued on the phone play next, ahead of autoplay's own pick:
  // fishtank's countdown takes the first one (nextEpisode), and mde's site
  // is handed it as the answer to its /videos/next request (below), so its
  // own up-next card plays it. An entry comes off when its page loads,
  // however it got there. entry: { path, title, series, thumb, dur, video? }
  // (video: mde's API object for it, which its up-next card needs).
  var PLAYQ_KEY = '__ftv_playqueue';
  function loadPlayQueue(){
    try { return JSON.parse(localStorage.getItem(PLAYQ_KEY) || '[]') || []; } catch (e) { return []; }
  }
  function savePlayQueue(q){
    try { localStorage.setItem(PLAYQ_KEY, JSON.stringify(q.slice(0, 50))); } catch (e) {}
  }
  (function(){
    var q = loadPlayQueue(), left = q.filter(function(x){ return x.path !== location.pathname; });
    if (left.length !== q.length) savePlayQueue(left);
  })();
  // What fishtank will autoplay after this episode: the rest of the row it
  // was opened from (see rememberQueue).
  function fishtankAutoNext(){
    var id = episodeId(), q = [], out = [];
    if (!id) return out;
    try { q = JSON.parse(sessionStorage.getItem('__ftv_queue') || '[]'); } catch (e) {}
    for (var i = 0; i < q.length; i++) {
      if (q[i].id !== id) continue;
      for (var j = i + 1; j < q.length && out.length < 5; j++) {
        out.push({ path: '/episodes/' + q[j].id, title: cleanTitle(q[j].title),
                   thumb: ftThumb(q[j].id) });
      }
      break;
    }
    return out;
  }
  // op: 'list' | 'add' (to the end) | 'next' (to the front) | 'remove' | 'top'
  window.__ftvQueue = function(op, arg){
    var q = loadPlayQueue();
    if (op === 'add' || op === 'next') {
      if (!arg || !arg.path) return 'false';
      q = q.filter(function(x){ return x.path !== arg.path; });
      if (op === 'add') q.push(arg); else q.unshift(arg);
      savePlayQueue(q);
    } else if (op === 'remove') {
      savePlayQueue(q.filter(function(x){ return x.path !== arg; }));
    } else if (op === 'top') {
      var hit = q.filter(function(x){ return x.path === arg; });
      savePlayQueue(hit.concat(q.filter(function(x){ return x.path !== arg; })));
    }
    var list = loadPlayQueue().map(function(x){
      return { path: x.path, title: x.title, series: x.series, thumb: x.thumb, dur: x.dur };
    });
    return JSON.stringify({ queue: list, auto: location.host.indexOf('mde.tv') >= 0 ? null : fishtankAutoNext() });
  };

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
  // Removing an entry by hand: from the TV's Menu (the card the cursor is
  // on, see __ftvFocusedRecent) or from the phone remote.
  window.__ftvRecentRemove = function(path){
    removeRecent(path);
    dismiss(path);   // and never offer it as "On another device"
    recentSig = '';
    try { renderRecent(); } catch (e) {}
    return 'ok';
  };
  window.__ftvFocusedRecent = function(){
    var a = cur && cur.closest && cur.closest('#__ftv_recent_row a[href]');
    return a ? a.getAttribute('href') : '';
  };
  // entry: { path, title, thumb, t, dur } (seconds)
  function recordRecent(entry){
    if (!entry || !entry.path || !(entry.dur > 60)) return;
    if (entry.t > entry.dur - 30) { removeRecent(entry.path); return; }   // finished
    if (entry.t < 15) return;                                           // barely started
    var prev = null;
    var list = loadRecent().filter(function(x){ if (x.path === entry.path) prev = x; return x.path !== entry.path; });
    entry.ts = Date.now();
    if (prev && prev.wasWatched !== undefined) entry.wasWatched = prev.wasWatched;
    list.unshift(entry);
    saveRecent(list);
    if (entry.wasWatched === undefined) noteWasWatched(entry.path);
  }
  function cleanTitle(t){
    return String(t || '').replace(/\s+/g, ' ').trim().replace(/\.(mp4|mov|mkv|webm)$/i, '');
  }

  // Finishing an episode puts the next one at the front of the row, marked
  // "Up next" instead of a progress bar; once it's started it's an ordinary
  // part-watched entry. entry: { path, title, thumb, series } -- no times.
  function recordNextUp(entry){
    if (!entry || !entry.path) return;
    var list = loadRecent().filter(function(x){ return x.path !== entry.path; });
    entry.next = true;
    entry.t = 0;
    entry.ts = Date.now();
    list.unshift(entry);
    saveRecent(list);
    noteWasWatched(entry.path);
  }

  // ---- Watched on another device (mde) --------------------------------
  // mde keeps two things on the account that every device shares: a watched
  // flag on each episode (set near its end; from /series/<show>/videos) and
  // the History list (every episode page opened, newest first -- no times or
  // positions; positions stay in each browser). On the homepage they're used
  // to
  //  - drop an entry finished somewhere else since the TV saved it. Its
  //    flag is noted when the TV first saves it (wasWatched), so rewatching
  //    an episode finished long ago doesn't make it vanish;
  //  - show the last episode opened anywhere, if that was on another device
  //    and it's unfinished, as one card marked "On another device"
  //    (elsewhere). It only lasts while it's the latest thing opened, so it
  //    never piles up, and it starts from the beginning.
  var LAST_OPENED_KEY = '__ftv_last_opened', DISMISSED_KEY = '__ftv_dismissed';
  function epOf(path){
    var m = /^\/series\/([^/]+)\/([^/]+)/.exec(path || '');
    return m && location.host.indexOf('mde.tv') >= 0 ? { show: m[1], tag: m[2] } : null;
  }
  // The episode this TV opened last, to tell its own History entries apart.
  function noteOpened(){
    if (epOf(location.pathname)) try { localStorage.setItem(LAST_OPENED_KEY, location.pathname); } catch (e) {}
  }
  noteOpened();
  function dismissed(){
    try { return JSON.parse(localStorage.getItem(DISMISSED_KEY) || '[]') || []; } catch (e) { return []; }
  }
  function dismiss(path){
    var l = dismissed().filter(function(p){ return p !== path; });
    l.unshift(path);
    try { localStorage.setItem(DISMISSED_KEY, JSON.stringify(l.slice(0, 30))); } catch (e) {}
  }
  function mdeApi(u){
    return fetch('https://api.mde.tv/v1/' + u, { credentials: 'include' })
      .then(function(r){ if (!r.ok) throw new Error(r.status); return r.json(); });
  }
  var showWatched = {};   // show -> promise of { episode tag: watched }
  function watchedIn(show){
    if (!showWatched[show]) showWatched[show] = mdeApi('series/' + show + '/videos').then(function(j){
      var m = {};
      (j.videos || []).forEach(function(v){ m[v.tag] = !!v.watched; });
      return m;
    });
    return showWatched[show];
  }
  function noteWasWatched(path){
    var e = epOf(path);
    if (!e) return;
    watchedIn(e.show).then(function(m){
      if (!(e.tag in m)) return;
      var list = loadRecent(), hit = false;
      list.forEach(function(x){ if (x.path === path && x.wasWatched === undefined) { x.wasWatched = m[e.tag]; hit = true; } });
      if (hit) saveRecent(list);
    }).catch(function(){});
  }
  var accountSynced = 0;
  function syncAccount(){
    if (location.host.indexOf('mde.tv') < 0 || location.pathname !== '/' || Date.now() - accountSynced < 60000) return;
    accountSynced = Date.now();
    showWatched = {};   // fresh flags
    var last = null, lastPath = '';
    mdeApi('auth').then(function(j){
      return mdeApi('playlists/history-' + j.user.id);
    }).then(function(j){
      last = j.playlist && j.playlist.videos && j.playlist.videos[0];
      if (last && last.series_tag && last.tag) lastPath = '/series/' + last.series_tag + '/' + last.tag;
      var shows = {};
      loadRecent().concat([{ path: lastPath }]).forEach(function(x){ var e = epOf(x.path); if (e) shows[e.show] = 1; });
      var names = Object.keys(shows);
      return Promise.all(names.map(function(s){ return watchedIn(s).catch(function(){ return null; }); })).then(function(maps){
        var byShow = {};
        names.forEach(function(s, i){ byShow[s] = maps[i]; });
        return function(path){ var e = epOf(path), m = e && byShow[e.show]; return m && e.tag in m ? m[e.tag] : undefined; };
      });
    }).then(function(watched){
      var list = loadRecent(), before = JSON.stringify(list);
      var mine = '';
      try { mine = localStorage.getItem(LAST_OPENED_KEY) || ''; } catch (e) {}
      list = list.filter(function(x){
        var w = watched(x.path);
        if (x.elsewhere && (x.path !== lastPath || x.path === mine)) return false;
        if (w === undefined) return true;
        if (x.elsewhere) return !w;
        if (x.wasWatched === undefined) { x.wasWatched = w; return true; }
        return !(w && !x.wasWatched);   // finished somewhere else since
      });
      if (lastPath && lastPath !== mine && watched(lastPath) === false && dismissed().indexOf(lastPath) < 0 &&
          !list.some(function(x){ return x.path === lastPath; })) {
        list.unshift({ path: lastPath, title: cleanTitle(last.title), series: last.series_name || '',
                       thumb: last.small_thumbnail || last.thumbnail || '', t: 0, dur: last.duration || 0,
                       elsewhere: true, wasWatched: false, ts: Date.now() });
      }
      if (JSON.stringify(list) !== before) {
        saveRecent(list);
        recentSig = '';
        renderRecent();
      }
    }).catch(function(){});
  }

  // mde: the next episode of the same show, from the API the site's own
  // up-next card uses (for PSC, the same game's -- see pscNext). Not for
  // collections that aren't watched in order.
  var NO_NEXT_UP = ['sst', 'films', 'sequence-master', 'vlt', 'bntm', 'mde', 'music', 'xp-sound',
                    'tubi-poops', 'patina-research', 'worldcore', 'fleshsim'];
  var nextUpQueued = false;
  function queueMdeNextUp(){
    var m = /^\/series\/([^/]+)\/([^/]+)/.exec(location.pathname);
    if (nextUpQueued || !m || NO_NEXT_UP.indexOf(m[1]) >= 0) return;
    nextUpQueued = true;
    var api = 'https://api.mde.tv/v1/videos/';
    var get = function(u){ return fetch(api + u, { credentials: 'include' }).then(function(r){ return r.json(); }); };
    get(m[2]).then(function(j){
      var id = j.video && j.video.id;
      if (!id) return null;
      if (m[1] === 'pause-snack-continue') return pscNext(id);
      return get('next/' + id).then(function(n){ return n.video; });
    }).then(function(v){
      if (!v || v.series_tag !== m[1]) return;   // last episode of the show
      recordNextUp({ path: '/series/' + v.series_tag + '/' + v.tag, title: cleanTitle(v.title),
                     series: v.series_name || '', thumb: v.small_thumbnail || v.thumbnail || '' });
    }).catch(function(){});
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
    if (trackedVideo.duration > 60 && trackedVideo.currentTime > trackedVideo.duration - 30) queueMdeNextUp();
  }, true);
  document.addEventListener('ended', function(e){
    if (location.host.indexOf('mde.tv') < 0 || e.target !== trackedVideo) return;
    removeRecent(location.pathname);
    queueMdeNextUp();
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

  // Along the bottom of a card's thumbnail: how far in, "Up next", or
  // "On another device".
  function cardStrip(x){
    if (!x.next && !x.elsewhere) return progressBar(x.t / x.dur);
    var tag = document.createElement('div');
    tag.textContent = x.next ? 'UP NEXT' : 'ON ANOTHER DEVICE';
    tag.style.cssText = 'position:relative;z-index:2;height:22px;margin-top:-22px;width:100%;box-sizing:border-box;' +
      'padding:0 8px;background:rgba(0,0,0,.75);color:__FTV_ACCENT_HEX__;font:700 13px/22px sans-serif;' +
      'letter-spacing:1px;border-radius:0 0 6px 6px;';
    return tag;
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
      if (img) img.insertAdjacentElement('afterend', cardStrip(x));
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
      if (img) img.insertAdjacentElement('afterend', cardStrip(x));
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
      a.appendChild(cardStrip(x));
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
    // Entries saved with a guessed fishtank thumbnail get the real one.
    list.forEach(function(x){
      var m = ftThumbs && /^\/episodes\/([0-9a-f-]{36})$/.exec(x.path);
      if (m && ftThumbs[m[1]]) x.thumb = ftThumbs[m[1]];
    });
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
      if ((!row || (row.__ftvPlain && sig !== recentSig)) && document.readyState === 'complete' && slot.children.length > 2) {
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

  function loadPsc(){
    if (pscVideos || pscLoading) return;
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
  }

  // Games newest first (Specials last), each with its episodes Ep. 1 on.
  function pscGames(){
    var games = {}, order = [];
    pscVideos.forEach(function(v){
      var g = pscGame(v.title);
      if (!games[g.game]) { games[g.game] = { name: g.game, eps: [], latest: 0 }; order.push(games[g.game]); }
      games[g.game].eps.push({ v: v, ep: g.ep });
      games[g.game].latest = Math.max(games[g.game].latest, v.episode);
    });
    order.forEach(function(g){
      g.eps.sort(function(a, b){ return (a.ep - b.ep) || (a.v.episode - b.v.episode); });
    });
    return order.sort(function(a, b){
      if ((a.name === 'Specials') !== (b.name === 'Specials')) return a.name === 'Specials' ? 1 : -1;
      return b.latest - a.latest;
    });
  }

  // Up next stays in the same game. mde's site picks the next video by
  // asking GET /videos/next/<id>, which goes by upload order, so an
  // Oblivion episode could be followed by Outlast. The request still goes
  // out, but for a PSC episode its answer is swapped for the next episode
  // of the same game; mde's own countdown card then plays that. After a
  // game's last episode, or before the list has loaded, mde's answer is
  // left alone.
  function pscNext(id){
    if (!pscVideos) return null;
    var games = pscGames();
    for (var i = 0; i < games.length; i++) {
      if (games[i].name === 'Specials') continue;
      var eps = games[i].eps;
      for (var j = 0; j < eps.length - 1; j++) if (eps[j].v.id === id) return eps[j + 1].v;
    }
    return null;
  }
  if (location.host.indexOf('mde.tv') >= 0) {
    if (location.pathname.indexOf(PSC_PATH + '/') === 0) loadPsc();
    var xhrOpen = XMLHttpRequest.prototype.open, xhrSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url){
      var m = /\/videos\/next\/([0-9a-f-]{36})/.exec(String(url));
      this.__ftvNextOf = m ? m[1] : null;
      return xhrOpen.apply(this, arguments);
    };
    // The site asks just before it shows its up-next card, so an episode
    // queued from the phone mid-episode still makes it.
    var queuedNext = function(){
      var q = loadPlayQueue()[0];
      return q && q.video && q.path !== location.pathname ? q.video : null;
    };
    XMLHttpRequest.prototype.send = function(){
      var xhr = this, next = xhr.__ftvNextOf && (queuedNext() || pscNext(xhr.__ftvNextOf));
      if (next) {
        var body = JSON.stringify({ video: next });
        Object.defineProperty(xhr, 'responseText', { get: function(){ return body; } });
        Object.defineProperty(xhr, 'response', { get: function(){ return xhr.responseType === 'json' ? JSON.parse(body) : body; } });
      }
      // Tell the app what the card will play, for if the page crashes
      // while switching to it (see onRenderProcessGone).
      if (xhr.__ftvNextOf && window.FtvApp) {
        var from = location.pathname;
        xhr.addEventListener('load', function(){
          try {
            var v = (xhr.responseType === 'json' ? xhr.response : JSON.parse(xhr.responseText)).video;
            if (v && v.series_tag && v.tag) window.FtvApp.upNext(from, '/series/' + v.series_tag + '/' + v.tag);
          } catch (e) {}
        });
      }
      return xhrSend.apply(this, arguments);
    };
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
    if (!pscVideos) { loadPsc(); return; }
    if (!pscVideos.length) return;
    if (box && box.nextElementSibling === wrapper && pscSig) { wrapper.style.display = 'none'; return; }
    var tpl = list.querySelector('a[class*="_video"]');
    if (!tpl) return;

    var order = pscGames();

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
    osdPlayStateChanged();
  }, true);
  document.addEventListener('pause', function(e){
    if (e.target === trackedVideo) osdPlayStateChanged();
  }, true);
  // Actually showing frames (not just asked to play): the loading screen can go.
  document.addEventListener('playing', function(e){
    if (e.target === trackedVideo) playbackStarted();
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

  // ---- TV player controls ---------------------------------------------
  // While something plays, any key brings up a full-screen layer like a TV
  // app's: the title along the top; a full-width scrub bar with the time
  // either side and the seek preview riding above the scrub point; and a
  // row of buttons (Restart, -10, play/pause, +30, Next, Subtitles).
  // Left/right scrub; Down moves to the buttons and Up back to the bar;
  // Select presses; Back hides it (see __ftvScrubCancel). It hides after a
  // few seconds while playing, and stays up over a dimmed picture while
  // paused. (Named "seek overlay" from before it grew; the timeupdate and
  // key handlers call it by those names.)
  var osdSeriesEl = null, osdTitleEl = null, osdNoteEl = null, osdRightEl = null, osdKnobEl = null,
      osdFlashEl = null, osdScrubEl = null, osdStimeEl = null, osdBtnEls = [], osdFocus = -1, osdNoteTimer = null,
      osdFlashTimer = null;   // osdFocus: -1 is the bar, else a button index
  var OSD_REPLAY = 'M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z';
  var OSD_NUM = '<text x="12" y="16.3" font-size="6.6" font-weight="700" text-anchor="middle" font-family="sans-serif">';
  var OSD_ICONS = {
    play: '<path d="M8 5v14l11-7z"/>',
    pause: '<path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>',
    restart: '<path d="' + OSD_REPLAY + '"/>',
    back10: '<path d="' + OSD_REPLAY + '"/>' + OSD_NUM + '10</text>',
    fwd30: '<path transform="matrix(-1 0 0 1 24 0)" d="' + OSD_REPLAY + '"/>' + OSD_NUM + '30</text>',
    next: '<path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/>',
    cc: '<path d="M19 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm-8 7H9.5v-.5h-2v3h2V13H11v1a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1z"/>'
  };
  var OSD_BUTTONS = [['restart', 'Restart'], ['back10', 'Back 10s'], ['play', 'Pause'],
                     ['fwd30', 'Forward 30s'], ['next', 'Next episode'], ['cc', 'Subtitles']];
  var OSD_PLAY = 2;
  function osdIcon(name){ return '<svg viewBox="0 0 24 24" aria-hidden="true">' + OSD_ICONS[name] + '</svg>'; }

  function ensureSeekOverlay(){
    if (seekOverlayEl) return;
    var A = '__FTV_ACCENT_HEX__';
    var style = document.createElement('style');
    style.textContent =
      '#__ftv_seek{position:fixed;inset:0;z-index:2147483646;pointer-events:none;opacity:0;' +
        'transition:opacity 200ms ease-out,background-color 200ms;color:#fff;font-family:Roboto,"Amazon Ember",sans-serif;}' +
      '#__ftv_seek.__ftv_show{opacity:1;}' +
      '#__ftv_seek.__ftv_paused{background-color:rgba(0,0,0,.35);}' +
      '#__ftv_seek .__ftv_top{position:absolute;left:0;right:0;top:0;padding:2.6vw 4vw 7vw;' +
        'background:linear-gradient(rgba(0,0,0,.8),transparent);will-change:transform;}' +
      '#__ftv_seek .__ftv_series{font-size:1.2vw;letter-spacing:.12vw;text-transform:uppercase;opacity:.75;}' +
      '#__ftv_seek .__ftv_title{font-size:2.3vw;font-weight:600;margin-top:.3vw;text-shadow:0 .1vw .4vw rgba(0,0,0,.6);}' +
      '#__ftv_seek .__ftv_note{font-size:1.3vw;margin-top:.6vw;color:' + A + ';min-height:1.6vw;}' +
      '#__ftv_seek .__ftv_bottom{position:absolute;left:0;right:0;bottom:0;padding:9vw 4vw 2.6vw;' +
        'background:linear-gradient(transparent,rgba(0,0,0,.88));will-change:transform;}' +
      '#__ftv_seek .__ftv_scrub{position:relative;height:2vw;display:flex;align-items:center;}' +
      '#__ftv_seek_bar{position:relative;width:100%;height:.35vw;border-radius:.2vw;background:rgba(255,255,255,.3);' +
        'transition:height 150ms;}' +
      '#__ftv_seek.__ftv_barfocus #__ftv_seek_bar{height:.6vw;}' +
      '#__ftv_seek_fill{height:100%;width:100%;border-radius:inherit;background:' + A + ';' +
        'transform-origin:0 50%;transform:scaleX(0);will-change:transform;}' +
      '#__ftv_seek .__ftv_knob{position:absolute;top:50%;left:0;width:1.4vw;height:1.4vw;margin:-.7vw 0 0 -.7vw;' +
        'border-radius:50%;background:#fff;box-shadow:0 0 0 .22vw ' + A + ';opacity:0;transition:opacity 150ms;will-change:transform;}' +
      '#__ftv_seek.__ftv_barfocus .__ftv_knob{opacity:1;}' +
      '#__ftv_seek_thumb{position:absolute;bottom:2.4vw;left:0;display:none;border-radius:.45vw;background-color:#000;will-change:transform;' +
        'background-repeat:no-repeat;border:.18vw solid #fff;box-shadow:0 .4vw 1.6vw rgba(0,0,0,.7);}' +
      '#__ftv_seek.__ftv_scrubbing #__ftv_seek_thumb.__ftv_has{display:block;}' +
      '#__ftv_seek .__ftv_stime{position:absolute;left:0;right:0;bottom:-2.3vw;text-align:center;font-size:1.25vw;' +
        'font-weight:600;text-shadow:0 .1vw .4vw #000;}' +
      '#__ftv_seek .__ftv_times{display:flex;justify-content:space-between;margin-top:.7vw;font-size:1.3vw;' +
        'font-variant-numeric:tabular-nums;opacity:.9;}' +
      '#__ftv_seek .__ftv_btns{display:flex;justify-content:center;gap:1.6vw;margin-top:1.3vw;}' +
      '#__ftv_seek .__ftv_btn{display:flex;flex-direction:column;align-items:center;gap:.45vw;min-width:6.4vw;' +
        'padding:.8vw 1.1vw .7vw;border-radius:.9vw;font-size:1vw;opacity:.8;transition:background 120ms,opacity 120ms;}' +
      '#__ftv_seek .__ftv_btn svg{width:2.5vw;height:2.5vw;fill:currentColor;}' +
      '#__ftv_seek .__ftv_btn.__ftv_on{background:#fff;color:#000;opacity:1;}' +
      '#__ftv_seek .__ftv_btn.__ftv_cc_on svg{fill:' + A + ';}' +
      '#__ftv_seek .__ftv_btn[hidden]{display:none;}' +
      '#__ftv_seek .__ftv_flash{position:absolute;left:50%;top:50%;width:8vw;height:8vw;margin:-4vw 0 0 -4vw;' +
        'border-radius:50%;background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;' +
        'opacity:0;transform:scale(.85);transition:opacity 220ms,transform 220ms;}' +
      '#__ftv_seek .__ftv_flash.__ftv_go{opacity:1;transform:scale(1);}' +
      '#__ftv_seek .__ftv_flash svg{width:4vw;height:4vw;fill:#fff;}';
    (document.head || document.documentElement).appendChild(style);

    var div = function(cls){ var d = document.createElement('div'); if (cls) d.className = cls; return d; };
    seekOverlayEl = div();
    seekOverlayEl.id = '__ftv_seek';
    var top = div('__ftv_top');
    osdSeriesEl = div('__ftv_series'); osdTitleEl = div('__ftv_title'); osdNoteEl = div('__ftv_note');
    top.appendChild(osdSeriesEl); top.appendChild(osdTitleEl); top.appendChild(osdNoteEl);
    osdFlashEl = div('__ftv_flash');
    var bottom = div('__ftv_bottom');
    osdScrubEl = div('__ftv_scrub');
    seekThumbEl = div(); seekThumbEl.id = '__ftv_seek_thumb';
    osdStimeEl = div('__ftv_stime'); seekThumbEl.appendChild(osdStimeEl);
    var bar = div(); bar.id = '__ftv_seek_bar';
    seekOverlayFillEl = div(); seekOverlayFillEl.id = '__ftv_seek_fill';
    osdKnobEl = div('__ftv_knob');
    bar.appendChild(seekOverlayFillEl); bar.appendChild(osdKnobEl);
    osdScrubEl.appendChild(seekThumbEl); osdScrubEl.appendChild(bar);
    var times = div('__ftv_times');
    seekOverlayTimeEl = document.createElement('span'); osdRightEl = document.createElement('span');
    times.appendChild(seekOverlayTimeEl); times.appendChild(osdRightEl);
    var btns = div('__ftv_btns');
    osdBtnEls = OSD_BUTTONS.map(function(b){
      var e = div('__ftv_btn');
      e.innerHTML = osdIcon(b[0]) + '<span></span>';
      e.lastChild.textContent = b[1];
      btns.appendChild(e);
      return e;
    });
    bottom.appendChild(osdScrubEl); bottom.appendChild(times); bottom.appendChild(btns);
    seekOverlayEl.appendChild(top); seekOverlayEl.appendChild(osdFlashEl); seekOverlayEl.appendChild(bottom);
    (document.body || document.documentElement).appendChild(seekOverlayEl);
  }

  // Title and show of what's playing, as the phone remote shows them.
  function playbackMeta(){
    var m = { title: '', series: '', thumb: '' };
    if (location.host.indexOf('mde.tv') >= 0) {
      var og = function(k){ var e = document.querySelector('meta[property="og:' + k + '"]'); return e ? e.content : ''; };
      var crumb = document.querySelector('a[href="/series/' + location.pathname.split('/')[2] + '"]');
      m.title = cleanTitle((og('title') || document.title).replace(/\s*\|\s*MDE\.tv\s*$/i, ''));
      m.series = crumb ? cleanTitle(crumb.textContent) : '';
      m.thumb = og('image');
    } else {
      var id = episodeId() || (/\/episodes\/([0-9a-f-]{36})/.exec(location.pathname) || [])[1];
      try { m.title = (id && localStorage.getItem('__ftv_title_' + id)) || ''; } catch (e) {}
      m.thumb = id ? ftThumb(id) : '';
    }
    return m;
  }

  function osdButtonVisible(i){ return !osdBtnEls[i].hidden; }
  function renderSeekOverlay(){
    ensureSeekOverlay();
    var info = currentPlaybackInfo();
    var t = scrubTime !== null ? scrubTime : info.time;
    var d = info.duration || 0;
    var hours = isFinite(d) && d >= 3600;
    seekOverlayTimeEl.textContent = fmtSeekTime(t, hours);
    osdRightEl.textContent = d ? '-' + fmtSeekTime(Math.max(0, d - t), hours) : '';
    var pct = d ? Math.max(0, Math.min(100, t / d * 100)) : 0;
    // Transforms, not width/left: moving them then repaints nothing.
    seekOverlayFillEl.style.transform = 'scaleX(' + (pct / 100) + ')';
    osdKnobEl.style.transform = 'translateX(' + Math.round(osdScrubEl.clientWidth * pct / 100) + 'px)';
    var playing = window.__ftvIsPlaying();
    var playBtn = osdBtnEls[OSD_PLAY];
    var want = playing ? 'pause' : 'play';
    if (playBtn.__icon !== want) {
      playBtn.__icon = want;
      playBtn.innerHTML = osdIcon(want) + '<span></span>';
      playBtn.lastChild.textContent = playing ? 'Pause' : 'Play';
    }
    var cc = window.__ftvCaptions ? window.__ftvCaptions('state') : 'none';
    osdBtnEls[5].hidden = cc === 'none';
    osdBtnEls[5].classList.toggle('__ftv_cc_on', cc === 'on');
    if (osdFocus >= 0 && !osdButtonVisible(osdFocus)) osdFocus = OSD_PLAY;
    seekOverlayEl.classList.toggle('__ftv_scrubbing', scrubTime !== null);
    seekOverlayEl.classList.toggle('__ftv_barfocus', osdFocus < 0);
    seekOverlayEl.classList.toggle('__ftv_paused', !playing && scrubTime === null);
    for (var i = 0; i < osdBtnEls.length; i++) osdBtnEls[i].classList.toggle('__ftv_on', i === osdFocus);
    if (scrubTime !== null) renderThumb(scrubTime, pct);
  }

  // Preview thumbnail for time t. Both sites' players are Bunny Stream,
  // whose seek previews are sprite sheets of 6x6 tiles, one every 2s:
  //  - mde's own <video> carries them as a 'thumbnails' metadata track
  //    (cue text "<sprite url>#xywh=x,y,w,h"), signed URLs included;
  //  - fishtank's player is a cross-origin iframe, but its sprites are
  //    public at <cdn>/<video id>/seek/_N.jpg (1800x1008, tiles 300x168).
  // No preview (just the bar) if neither is available.
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
  // A fishtank episode's thumbnail. Not always <id>/thumbnail.jpg (Season 2's
  // are thumbnail_<hash>.jpg), so the real ones come from the public episode
  // list, fetched once per page; the guess is only for before that arrives.
  var ftThumbs = null;
  function ftThumb(id){
    return (ftThumbs && ftThumbs[id]) || 'https://' + bunnyHost() + '/' + id + '/thumbnail.jpg';
  }
  if (location.host.indexOf('fishtank') >= 0) {
    fetch('https://api.fishtank.live/v1/videos').then(function(r){ return r.json(); }).then(function(j){
      var map = {};
      (j.videos || []).forEach(function(v){ if (v.id && v.thumbnail) map[v.id] = v.thumbnail; });
      ftThumbs = map;
      recentSig = '';   // redraw Continue watching with them
      scheduleInvalidate();
    }).catch(function(){});
  }

  // Sprite sheets already loaded (url -> true) or loading (url -> Image).
  // A tile is only shown once its sheet has loaded, so a fast scrub into
  // a new sheet keeps the last good tile instead of flashing black; the
  // following sheet is preloaded so moving forward rarely waits at all.
  var sprites = {};
  // `then` runs only when a sheet that wasn't loaded yet finishes loading.
  // (Calling it for one already loaded made the caller redraw, which
  // called this again, forever: the scrub froze the page.)
  // Loaded sheets are kept as their (decoded) Image, to draw tiles from.
  function loadSprite(url, then){
    if (sprites[url] && sprites[url].__ready) return true;
    if (!sprites[url]) {
      var im = new Image();
      im.decoding = 'async';
      var done = function(){ im.__ready = true; if (im.__then) im.__then(); };
      im.onload = function(){
        // Decoded off the main thread where the WebView can, before it's drawn.
        if (im.decode) im.decode().then(done, done); else done();
      };
      im.src = url;
      sprites[url] = im;
      // Keep the last few sheets only: each is a decoded 1800x1008 image,
      // and on fishtank (one page app) they piled up episode after episode.
      var keys = Object.keys(sprites);
      if (keys.length > 4) delete sprites[keys[0]];
    }
    if (then) sprites[url].__then = then;
    return false;
  }

  // The preview sits above the bar at the scrub point (kept on screen at
  // the ends), with the time it would jump to under it.
  var lastThumbT = 0;
  function renderThumb(t, pct){
    osdStimeEl.textContent = fmtSeekTime(t, currentPlaybackInfo().duration >= 3600);
    var th = null;
    try { th = thumbAt(t); } catch (e) {}
    var w = Math.round(window.innerWidth * 0.16);
    var place = function(h){
      var barW = osdScrubEl.clientWidth;
      var x = Math.max(0, Math.min(barW - w, barW * (pct || 0) / 100 - w / 2));
      seekThumbEl.style.transform = 'translateX(' + Math.round(x) + 'px)';
      seekThumbEl.style.width = w + 'px';
      if (h) seekThumbEl.style.height = h + 'px';
    };
    if (!th) { seekThumbEl.classList.remove('__ftv_has'); return; }
    // Each sheet covers 72s: load the next one the way the scrub is heading.
    // (Only one: decoding several 1800x1008 sheets at once stalled the TV.)
    var dir = t >= lastThumbT ? 1 : -1;
    lastThumbT = t;
    try { var ahead = thumbAt(t + dir * 72); if (ahead) loadSprite(ahead.url); } catch (e) {}
    if (!loadSprite(th.url, function(){ if (scrubTime !== null) renderSeekOverlay(); })) {
      if (thumbDrawn) { place(); seekThumbEl.classList.add('__ftv_has'); }   // keep the last tile
      return;
    }
    // Just the one tile, drawn into a small canvas: scaling the whole
    // 1800x1008 sheet with CSS on every step was what made scrubbing lag.
    var h = Math.round(th.h * w / th.w), d = window.devicePixelRatio || 1;
    if (!thumbCanvas) {
      thumbCanvas = document.createElement('canvas');
      thumbCanvas.style.cssText = 'display:block;width:100%;height:100%;border-radius:inherit;';
      seekThumbEl.insertBefore(thumbCanvas, seekThumbEl.firstChild);
    }
    if (thumbCanvas.width !== Math.round(w * d)) { thumbCanvas.width = Math.round(w * d); thumbCanvas.height = Math.round(h * d); }
    var key = th.url + '#' + th.x + ',' + th.y;
    if (key !== thumbDrawn) {
      thumbCanvas.getContext('2d').drawImage(sprites[th.url], th.x, th.y, th.w, th.h, 0, 0, thumbCanvas.width, thumbCanvas.height);
      thumbDrawn = key;
    }
    place(h);
    seekThumbEl.classList.add('__ftv_has');
  }
  var thumbCanvas = null, thumbDrawn = '';

  // Shows the controls, or keeps them up: they hide a few seconds after the
  // last key while playing, and stay while paused or scrubbing. Drawn at most
  // once a frame: a held remote button repeats faster than the TV can draw.
  var osdRaf = 0;
  function showSeekOverlay(){
    ensureSeekOverlay();
    if (!seekOverlayVisible) {
      var m = playbackMeta();
      osdTitleEl.textContent = m.title || 'Now playing';
      osdSeriesEl.textContent = m.series;
    }
    if (!osdRaf) osdRaf = requestAnimationFrame(function(){ osdRaf = 0; renderSeekOverlay(); });
    seekOverlayEl.classList.add('__ftv_show');
    seekOverlayVisible = true;
    armSeekOverlayHide();
  }
  function armSeekOverlayHide(){
    if (seekOverlayHideTimer) clearTimeout(seekOverlayHideTimer);
    seekOverlayHideTimer = null;
    if (scrubTime !== null || !window.__ftvIsPlaying()) return;
    seekOverlayHideTimer = setTimeout(hideSeekOverlay, 4000);
  }
  function hideSeekOverlay(){
    if (!seekOverlayEl) return;
    seekOverlayEl.classList.remove('__ftv_show');
    seekOverlayVisible = false;
    osdFocus = -1;
  }
  // Play state changed (any source, the phone remote included): keep the
  // controls up while paused, and let them time out again once playing.
  function osdPlayStateChanged(){
    if (!playingVideo()) return;
    // Finished: leave the screen to the up-next card.
    if (trackedVideo && document.contains(trackedVideo) && trackedVideo.ended) { hideSeekOverlay(); return; }
    if (!window.__ftvIsPlaying()) showSeekOverlay();
    else if (seekOverlayVisible) { renderSeekOverlay(); armSeekOverlayHide(); }
  }
  // The big play/pause icon in the middle, briefly.
  function osdFlash(name){
    ensureSeekOverlay();
    osdFlashEl.innerHTML = osdIcon(name);
    osdFlashEl.classList.add('__ftv_go');
    if (osdFlashTimer) clearTimeout(osdFlashTimer);
    osdFlashTimer = setTimeout(function(){ osdFlashEl.classList.remove('__ftv_go'); }, 650);
  }
  function osdNote(text){
    ensureSeekOverlay();
    osdNoteEl.textContent = text;
    if (osdNoteTimer) clearTimeout(osdNoteTimer);
    osdNoteTimer = setTimeout(function(){ osdNoteEl.textContent = ''; }, 2500);
    showSeekOverlay();
  }
  // Arrow keys while the controls are up (see __ftvNavigate).
  function osdMove(dir){
    if (dir === 'down') { if (osdFocus < 0 && scrubTime === null) osdFocus = OSD_PLAY; }
    else if (dir === 'up') osdFocus = -1;
    else if (osdFocus >= 0) {
      var i = osdFocus, d = dir === 'left' ? -1 : 1;
      do { i += d; } while (i >= 0 && i < osdBtnEls.length && !osdButtonVisible(i));
      if (i >= 0 && i < osdBtnEls.length) osdFocus = i;
    }
    showSeekOverlay();
  }
  function osdPress(){
    var what = OSD_BUTTONS[osdFocus] && OSD_BUTTONS[osdFocus][0];
    if (what === 'play') { window.__ftvTogglePlay(); return; }
    if (what === 'restart') window.__ftvRemoteSeekTo(0);
    else if (what === 'back10') window.__ftvSeek(-10);
    else if (what === 'fwd30') window.__ftvSeek(30);
    else if (what === 'cc') window.__ftvCaptions('toggle');
    else if (what === 'next') { window.__ftvPlayNext(); return; }
    showSeekOverlay();
  }

  // MainActivity's play/pause/seek key handlers go through these instead of
  // manipulating a <video> element directly, so the same physical buttons
  // work whether the page has a same-origin video or (as turned out to be
  // the common case) a cross-origin Bunny iframe reachable only via
  // postMessage.
  window.__ftvTogglePlay = function(){
    // Not on a watch page (Play/Pause pressed while browsing): nothing to
    // control. It used to bring up the player controls anyway, which then
    // never went away and blocked BACK.
    if (!playingVideo()) return;
    if (trackedVideo && document.contains(trackedVideo)) {
      if (trackedVideo.paused) { trackedVideo.play(); osdFlash('play'); } else { trackedVideo.pause(); osdFlash('pause'); }
      showSeekOverlay();
      return;
    }
    sendPlayerCommand(playerState.playing ? 'pause' : 'play');
    playerState.playing = !playerState.playing; // optimistic; corrected by the real play/pause event if wrong
    osdFlash(playerState.playing ? 'play' : 'pause');
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

  // For the phone remote (PhoneRemote.kt): what's on the TV, as JSON, and
  // an absolute seek for its progress bar.
  window.__ftvRemoteStatus = function(){
    var p = currentPlaybackInfo();
    var video = !!((trackedVideo && document.contains(trackedVideo)) || findPlayerFrame());
    var s = { path: location.pathname, video: video, title: '', series: '', thumb: '',
              playing: window.__ftvIsPlaying(), t: p.time || 0, dur: p.duration || 0,
              captions: window.__ftvCaptions ? window.__ftvCaptions('state') : 'none' };
    if (video && location.host.indexOf('mde.tv') >= 0) {
      var og = function(k){ var m = document.querySelector('meta[property="og:' + k + '"]'); return m ? m.content : ''; };
      var crumb = document.querySelector('a[href="/series/' + location.pathname.split('/')[2] + '"]');
      s.title = cleanTitle((og('title') || document.title).replace(/\s*\|\s*MDE\.tv\s*$/i, ''));
      s.series = crumb ? cleanTitle(crumb.textContent) : '';
      s.thumb = og('image');
    } else if (video) {
      var id = episodeId();
      try { s.title = localStorage.getItem('__ftv_title_' + id) || ''; } catch (e) {}
      s.thumb = id ? ftThumb(id) : '';
    }
    return JSON.stringify(s);
  };
  window.__ftvRemoteSeekTo = function(t){
    window.__ftvSeek(t - (currentPlaybackInfo().time || 0));
  };
  // fishtank: open an episode from the phone, keeping its title for
  // Continue watching (the episode page itself doesn't say). row: the
  // episodes of its season in order, [{id, title}], so autoplay carries on
  // through the season the way it does when opened from the TV's row.
  window.__ftvRemoteEpisode = function(id, title, row, thumb){
    try {
      localStorage.setItem('__ftv_title_' + id, cleanTitle(title));
      if (row && row.length > 1) sessionStorage.setItem('__ftv_queue', JSON.stringify(row));
    } catch (e) {}
    window.__ftvGo('/episodes/' + id, { title: cleanTitle(title), thumb: thumb || ftThumb(id) });
  };
  // fishtank: switch to a live camera. Cameras are picked on the live page
  // (/) by its "Watch <name>" tile buttons; there's no URL for one. If
  // we're elsewhere, go there first and wait for the tile.
  window.__ftvRemoteCamera = function(name){
    var label = 'Watch ' + name, tries = 0;
    if (location.pathname !== '/') {
      try { sessionStorage.setItem('__ftv_cam', name); } catch (e) {}
      undoFullscreen();
      location.assign('/');
      return;
    }
    (function attempt(){
      var b = [].filter.call(document.querySelectorAll('button[aria-label]'), function(x){
        return x.getAttribute('aria-label') === label;
      })[0];
      if (b) { b.click(); return; }
      if (++tries < 40) setTimeout(attempt, 250);
    })();
  };
  // A camera asked for while the live page was still loading (above).
  try {
    var pendingCam = sessionStorage.getItem('__ftv_cam');
    sessionStorage.removeItem('__ftv_cam');
    if (pendingCam && location.pathname === '/') setTimeout(function(){ window.__ftvRemoteCamera(pendingCam); }, 500);
  } catch (e) {}

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
    if (scrubTime !== null) {
      scrubTime = null;
      if (scrubTimer) { clearTimeout(scrubTimer); scrubTimer = null; }
      showSeekOverlay();
      return true;
    }
    // The player controls: BACK hides them first, and leaves only after.
    if (seekOverlayVisible && playingVideo()) { hideSeekOverlay(); return true; }
    return false;
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
  // Part of the site's top bar that stays on screen as the page scrolls
  // (its search, logo, account): stuck there rather than just near the top.
  function inTopBar(el, r){
    if (el.closest('[class*="top-bar-module"], [class*="search-module"], [class*="_topBar"], header')) return true;
    if (r.top > 90) return false;
    for (var a = el; a && a !== document.body; a = a.parentElement) {
      var p = getComputedStyle(a).position;
      if (p === 'fixed' || p === 'sticky') return true;
    }
    return false;
  }
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
    if (rc && list.indexOf(rc) >= 0 && !isCovered(rc)) return rc;
    if (location.host.indexOf('fishtank') >= 0) {
      for (var i = 0; i < list.length; i++) {
        if (list[i].tagName === 'BUTTON' && list[i].textContent.trim() === 'Episodes' && inViewport(list[i]) && !isCovered(list[i])) return list[i];
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
      if (!inViewport(list[i]) || isCovered(list[i])) continue;
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
  var curRadius = null;        // cur's corner rounding, for the lift
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
    var w = Math.round(r.width * curZoom) + 'px', h = Math.round(r.height * curZoom) + 'px';
    veil.style.transform = 'translate(' + (r.left + curOx * (1 - curZoom)) + 'px,' + (r.top + curOy * (1 - curZoom)) + 'px)';
    if (veil.style.width !== w) veil.style.width = w;
    if (veil.style.height !== h) veil.style.height = h;
    if (curRadius && veil.style.borderRadius !== curRadius) veil.style.borderRadius = curRadius;
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
      // A whole rect (right/bottom too): navigation measures from it.
      var l = curRect.left + dx, t = curRect.top + dy;
      curRect = { left: l, top: t, width: curRect.width, height: curRect.height,
                  right: l + curRect.width, bottom: t + curRect.height };
      curRef = { left: pr.left, top: pr.top };
      placeVeil(true);
    });
  }, { passive: true, capture: true });

  // The selected item's link, to find it again if the site replaces it
  // (see doNavigate); failing that, whatever covers where it was (curRect,
  // which outlives it).
  var lostHref = null;
  function refind(list){
    var i, lostRect = curRect;
    if (lostHref) for (i = 0; i < list.length; i++) if (list[i].getAttribute('href') === lostHref) return list[i];
    if (!lostRect) return null;
    var cx = lostRect.left + lostRect.width / 2, cy = lostRect.top + lostRect.height / 2, best = null, bd = 1e9;
    for (i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      if (cx < r.left || cx > r.right || cy < r.top || cy > r.bottom) continue;   // must cover the old centre
      var d = Math.abs(r.width - lostRect.width) + Math.abs(r.height - lostRect.height);
      if (d < bd) { bd = d; best = list[i]; }
    }
    return best;
  }

  function mark(el, silent){
    if (cur) { zoomOff(cur); }
    cur = el || null;
    curRect = curRef = null;
    lostHref = cur && cur.getAttribute && cur.getAttribute('href');
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
    // Read before zoomOn's writes, so it doesn't force a second style pass.
    curRadius = getComputedStyle(cur).borderRadius || '6px';
    fitZoom(cur, curRect);
    zoomOn(cur);
    placeVeil(false);
    watchSpotlight();
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

  // The scrolling row the cursor is in (or null), looked up once per item
  // rather than on every LEFT/RIGHT (it walks every ancestor's style).
  var trackFor = null, trackOfCur = null;
  function curTrack(){
    if (trackFor !== cur) { trackFor = cur; trackOfCur = cur && scrollableAncestor(cur, 'x'); }
    return trackOfCur;
  }

  // The last press's best few candidates, for checking navigation over
  // devtools (window.__ftvLastScores()).
  var lastScored = [];
  window.__ftvLastScores = function(){
    return lastScored.map(function(s){
      var e = s.el, r = e.getBoundingClientRect();
      return Math.round(s.score) + ' ' + (e.getAttribute('href') || e.tagName) + ' @' + Math.round(r.top) + (isCovered(e) ? ' covered' : '');
    }).join(' | ');
  };

  // a: the cursor's rect, b: a candidate's.
  function inBeam(a, b, dir){
    if (dir === 'left' || dir === 'right') return b.bottom > a.top + 2 && b.top < a.bottom - 2;
    return b.right > a.left + 2 && b.left < a.right - 2;
  }

  function nearest(list, dir){
    // The cursor's own box unzoomed (curRect, kept up to date on scroll):
    // measured live it's up to 10% bigger and off-centre, or mid-animation
    // on a quick press, so the same press could pick differently.
    var a = curRect || cur.getBoundingClientRect();
    var c = { x: a.left + a.width / 2, y: a.top + a.height / 2 };
    var scored = [];

    // Left/right inside a horizontally-scrolling carousel must stay locked
    // to that row -- it should only ever continue along the row or stop at
    // its end, never drift into a different row/section just because
    // something there scored better than nothing. Without this, reaching
    // the last card in a row could jump the cursor down into the next
    // section entirely (this got a lot more likely to bite once the Series
    // grid went from 2 columns to 4 -- more rows means more diagonal
    // neighbours close enough to out-score a genuinely-empty "next" slot).
    var track = (dir === 'left' || dir === 'right') ? curTrack() : null;
    var wideW = window.innerWidth * 0.6;
    var curInTopBar = dir === 'up' && inTopBar(cur, a);

    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === cur) continue;
      if (track && !track.contains(el)) continue;
      // Each candidate measured once, and nothing written in between, so
      // these reads share one layout.
      var b = el.getBoundingClientRect();
      var p = { x: b.left + b.width / 2, y: b.top + b.height / 2 };
      var dx = p.x - c.x;
      var dy = p.y - c.y;
      var along, across;

      if (dir === 'left')       { along = -dx; across = Math.abs(dy); }
      else if (dir === 'right') { along =  dx; across = Math.abs(dy); }
      else if (dir === 'up')    { along = -dy; across = Math.abs(dx); }
      else                      { along =  dy; across = Math.abs(dx); }

      if (along <= 4) continue;              // wrong side of the cursor
      // A full-width item (mde's Spotlight) measured centre to centre is
      // "far" from everything beside its middle, so DOWN from Continue
      // watching skipped it for a card further down, and UP from it went
      // to the search box. Measure edge to edge when either side is that wide.
      if (dir !== 'left' && dir !== 'right' && (a.width > wideW || b.width > wideW)) {
        along = Math.max(5, dir === 'up' ? a.top - b.bottom : b.top - a.bottom);
        across = Math.max(0, b.left - a.right, a.left - b.right);
      }
      var score = along + across * 2.5;      // prefer straight ahead over diagonal
      // Anything in line with the cursor (overlapping it across the
      // direction of travel) beats everything that isn't, however close --
      // as Android's own focus search does. Without this, RIGHT from a
      // section's tabs went down into the nearby cards and that section's
      // far-right "View All" link could never be reached.
      if (!inBeam(a, b, dir)) score += 1e6;
      // UP through the page doesn't stop at the top bar (it stays on screen,
      // so it was nearly always "closest" and every other press landed on
      // the search box). It's reached when nothing else is above.
      if (dir === 'up' && !curInTopBar && inTopBar(el, b)) score += 1e7;
      scored.push({ el: el, score: score });
    }
    // Best first, skipping anything hidden under something else.
    scored.sort(function(a, b){ return a.score - b.score; });
    lastScored = scored.slice(0, 6);
    for (var j = 0; j < scored.length; j++) {
      if (!isCovered(scored[j].el)) return scored[j].el;
    }
    return null;
  }

  // ---- mde's Spotlight carousel ----------------------------------------
  // One slide link at a time, switched by the 1-7 buttons above it, and it
  // rotates on its own. The cursor follows the slide that's showing (or a
  // rotation would leave it on a hidden link, and Select would open that),
  // and LEFT/RIGHT on it press the previous/next button.
  function spotlightOf(el){
    var car = el && el.closest && el.closest('[class*="_spotlight"] [class*="_carousel"]');
    return car && el.closest('[class*="_slide"]') ? car : null;
  }
  function syncSpotlight(){
    var car = spotlightOf(cur);
    if (!car || cur.closest('[class*="_current"]')) return;
    var a = car.querySelector('[class*="_current"] a[href]');
    if (a) { dirty = true; mark(a); }
  }
  // Follow the carousel's own rotation too, so the ring never sits on a
  // hidden slide -- checked only while a slide has the cursor (mark()
  // starts and stops it), not all the time.
  var spotTimer = null;
  function watchSpotlight(){
    var on = !!(cur && !silentCur && spotlightOf(cur));
    if (on && !spotTimer) spotTimer = setInterval(function(){
      if (!cur || !spotlightOf(cur)) { clearInterval(spotTimer); spotTimer = null; return; }
      syncSpotlight();
    }, 500);
    else if (!on && spotTimer) { clearInterval(spotTimer); spotTimer = null; }
  }
  function stepSpotlight(dir){
    var car = spotlightOf(cur);
    if (!car) return false;
    var btns = car.querySelectorAll('[class*="_selector"] button');
    if (btns.length < 2) return false;
    var i = 0;
    for (var j = 0; j < btns.length; j++) if (/_selected/.test(btns[j].className)) i = j;
    btns[(i + (dir === 'right' ? 1 : btns.length - 1)) % btns.length].click();
    setTimeout(syncSpotlight, 60);
    return true;
  }

  function doNavigate(dir){
    var list = items();
    if (!list.length) return;
    syncSpotlight();
    if ((dir === 'left' || dir === 'right') && stepSpotlight(dir)) return;

    // Only reset to the default when the cursor is really lost (removed,
    // hidden, or more than a screen away). Merely scrolled off the edge --
    // e.g. by the scroll fallback below -- it keeps navigating from where
    // it is, instead of jumping back up to the top-left item.
    if (cur && document.contains(cur) && list.indexOf(cur) < 0 && !dirty) { dirty = true; list = items(); }
    if (!cur || !document.contains(cur)) {
      // The site redrew the selected card (React re-rendering a row,
      // Continue watching rebuilt): carry on from its replacement -- the
      // same link, else whatever now sits where it was -- rather than
      // jumping back to the start of the page.
      if (lostHref || curRect) { dirty = true; list = items(); }
      var again = refind(list);
      if (!again) { mark(pickDefault(list)); return; }
      mark(again);
    }
    if (list.indexOf(cur) < 0 || isCovered(cur)) { mark(pickDefault(list)); return; }

    var best = nearest(list, dir);

    // A cached list that's gone stale (a row hydrated but no mutation/
    // scroll happened to invalidate it yet) is a far more common reason to
    // come up empty than "there's truly nothing that way", so force one
    // fresh read and retry before concluding that and falling back to
    // scrolling -- but only if the page has changed since the list was
    // built, or holding LEFT at the start of a row rebuilt it every repeat.
    if (!best && !dirty && changeCount !== builtAt) {
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
      var track = cur && curTrack();
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
      // Left/right scrub, unless the buttons have the focus; up/down move
      // between the bar and the buttons (the first press just shows them).
      var buttons = seekOverlayVisible && osdFocus >= 0;
      if ((dir === 'left' || dir === 'right') && !buttons) window.__ftvScrub(dir === 'left' ? -(step || 10) : (step || 10));
      else if (!seekOverlayVisible) showSeekOverlay();
      else osdMove(dir);
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
      if (!d) return;
      var t0 = performance.now();
      doNavigate(d);
      // How long each press took, for measuring (window.__ftvNavStats).
      navStats.push(Math.round(performance.now() - t0));
      if (navStats.length > 50) navStats.shift();
    });
  };
  var navStats = window.__ftvNavStats = [];

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
      noteBrowsePage();
      noteOpened();
      window.__ftvQueue('remove', location.pathname);   // a queued episode opened on the TV
      if (!isEpisodePath(location.pathname)) hideLoader();
      return r;
    };
    window.addEventListener('popstate', function(){ prevPath = null; noteBrowsePage(); if (!isEpisodePath(location.pathname)) hideLoader(); });
  })();

  // ---- BACK from an episode (both sites) -------------------------------
  // goes to the page it was opened from -- the PSC page, a show, fishtank's
  // Episodes page -- with the cursor back on that episode, and BACK again
  // goes home. Only the last such page is kept (in sessionStorage, as mde
  // episodes are full page loads), not the whole history, so a run of
  // autoplayed episodes still goes straight back to where it started. The
  // homepage clears it: from there BACK goes home as before.
  var BROWSE_KEY = '__ftv_browse', RETURN_KEY = '__ftv_return';
  function isEpisodePath(p){
    return location.host.indexOf('mde.tv') >= 0 ? /^\/series\/[^/]+\/[^/]+/.test(p) : /^\/episodes\/[^/]+/.test(p);
  }
  function noteBrowsePage(){
    var p = location.pathname;
    try {
      if (p === '/') sessionStorage.removeItem(BROWSE_KEY);
      else if (!isEpisodePath(p)) sessionStorage.setItem(BROWSE_KEY, p + location.search);
    } catch (e) {}
  }
  noteBrowsePage();

  // ---- Loading screen (both sites) -------------------------------------
  // Opening an episode shows a dark screen with its picture and title until
  // it's actually playing, rather than the page drawing and the player
  // popping up over it. Put up before navigating when we start the episode
  // (a card on the TV, the phone remote, Next, fishtank's countdown), and
  // carried over a full page load in sessionStorage.
  var LOADING_KEY = '__ftv_loading';
  var loaderEl = null, loaderTimer = null;
  function showLoader(meta){
    meta = meta || {};
    if (!loaderEl) {
      var st = document.createElement('style');
      st.textContent =
        '#__ftv_loader{position:fixed;inset:0;z-index:2147483647;background:#000;display:flex;flex-direction:column;' +
          'align-items:center;justify-content:center;color:#fff;font-family:Roboto,"Amazon Ember",sans-serif;' +
          'opacity:1;transition:opacity 400ms ease-out;}' +
        '#__ftv_loader.__ftv_out{opacity:0;}' +
        '#__ftv_loader .__ftv_bg{position:absolute;inset:-6%;background:center/cover no-repeat;' +
          'filter:blur(2.4vw) brightness(.4);}' +
        '#__ftv_loader .__ftv_card{position:relative;width:34vw;aspect-ratio:16/9;border-radius:.8vw;' +
          'background:#111 center/cover no-repeat;box-shadow:0 1.2vw 3.6vw rgba(0,0,0,.7);}' +
        '#__ftv_loader .__ftv_s{position:relative;margin-top:2vw;font-size:1.2vw;letter-spacing:.12vw;' +
          'text-transform:uppercase;opacity:.75;}' +
        '#__ftv_loader .__ftv_t{position:relative;margin-top:.4vw;max-width:70vw;font-size:2.3vw;font-weight:600;' +
          'text-align:center;}' +
        '#__ftv_loader .__ftv_spin{position:relative;margin-top:1.8vw;width:2.6vw;height:2.6vw;border-radius:50%;' +
          'border:.28vw solid rgba(255,255,255,.25);border-top-color:__FTV_ACCENT_HEX__;animation:__ftv_spin .9s linear infinite;}' +
        '@keyframes __ftv_spin{to{transform:rotate(360deg);}}';
      (document.head || document.documentElement).appendChild(st);
      loaderEl = document.createElement('div');
      loaderEl.id = '__ftv_loader';
      loaderEl.innerHTML = '<div class="__ftv_bg"></div><div class="__ftv_card"></div>' +
        '<div class="__ftv_s"></div><div class="__ftv_t"></div><div class="__ftv_spin"></div>';
    }
    var url = meta.thumb ? 'url("' + String(meta.thumb).replace(/"/g, '%22') + '")' : '';
    loaderEl.children[0].style.backgroundImage = url;
    loaderEl.children[1].style.backgroundImage = url;
    loaderEl.children[1].style.display = url ? '' : 'none';
    loaderEl.children[2].textContent = meta.series || '';
    loaderEl.children[3].textContent = meta.title || '';
    loaderEl.classList.remove('__ftv_out');
    (document.body || document.documentElement).appendChild(loaderEl);
    if (loaderTimer) clearTimeout(loaderTimer);
    loaderTimer = setTimeout(hideLoader, 15000);   // never stuck up there
  }
  function hideLoader(){
    try { sessionStorage.removeItem(LOADING_KEY); } catch (e) {}
    if (loaderTimer) { clearTimeout(loaderTimer); loaderTimer = null; }
    if (!loaderEl || !loaderEl.parentNode || loaderEl.classList.contains('__ftv_out')) return;
    var el = loaderEl;
    el.classList.add('__ftv_out');
    setTimeout(function(){ if (el.classList.contains('__ftv_out') && el.parentNode) el.parentNode.removeChild(el); }, 450);
  }
  function playbackStarted(){ hideLoader(); }
  function rememberLoading(path, meta){
    try { sessionStorage.setItem(LOADING_KEY, JSON.stringify({ path: path, meta: meta || {}, at: Date.now() })); } catch (e) {}
  }
  // Start an episode: the loading screen first, then go. meta: { title, series, thumb }
  window.__ftvGo = function(path, meta){
    showLoader(meta);
    rememberLoading(path, meta);
    location.assign(path);
  };
  // What a card on the page says about its episode, for the loading screen.
  function cardMeta(a){
    var q = function(sel){ var e = a.querySelector(sel); return e ? cleanTitle(e.textContent) : ''; };
    var im = a.querySelector('img');
    return { thumb: im ? (im.currentSrc || im.src) : '',
             title: q('[class*="_title"]') || cleanTitle(a.textContent),
             series: q('[class*="_seriesName"]') };
  }
  // A frame actually on screen (the player may have started before this
  // script was injected, so its play events were missed).
  document.addEventListener('timeupdate', function(e){
    // Only while the loading screen is up (this fires ~4x a second all episode).
    if (!loaderEl || !loaderEl.parentNode || loaderEl.classList.contains('__ftv_out')) return;
    var v = e.target;
    if (v && v.tagName === 'VIDEO' && v.currentTime > 0 && isEpisodePath(location.pathname) &&
        v.getBoundingClientRect().width >= window.innerWidth * 0.5) playbackStarted();
  }, true);
  // An episode page: the screen from the page that started it, or a new one.
  (function(){
    if (!isEpisodePath(location.pathname)) return;
    var saved = null;
    try { saved = JSON.parse(sessionStorage.getItem(LOADING_KEY) || 'null'); } catch (e) {}
    if (saved && (saved.path !== location.pathname || Date.now() - saved.at > 30000)) saved = null;
    var v = document.querySelector('video');
    if (v && !v.paused && v.currentTime > 0) return;   // already playing
    var m = playbackMeta();
    if (saved && saved.meta) { m.title = m.title || saved.meta.title; m.thumb = m.thumb || saved.meta.thumb; m.series = m.series || saved.meta.series; }
    showLoader(m);
  })();

  // Next episode now (the player's Next button): what's queued, else what
  // autoplay would play.
  window.__ftvPlayNext = function(){
    if (location.host.indexOf('mde.tv') < 0) {
      var n = nextEpisode();
      if (!n) { osdNote('No next episode in this row'); return; }
      try {
        localStorage.setItem('__ftv_title_' + n.id, cleanTitle(n.title));
        if (n.row && n.row.length > 1) sessionStorage.setItem('__ftv_queue', JSON.stringify(n.row));
      } catch (e) {}
      window.__ftvGo('/episodes/' + n.id, { title: cleanTitle(n.title), thumb: ftThumb(n.id) });
      return;
    }
    var queued = loadPlayQueue().filter(function(x){ return x.path !== location.pathname; })[0];
    if (queued) { window.__ftvGo(queued.path, { title: queued.title, series: queued.series, thumb: queued.thumb }); return; }
    var m = /^\/series\/([^/]+)\/([^/]+)/.exec(location.pathname);
    if (!m) return;
    osdNote('Finding the next episode…');
    var api = 'https://api.mde.tv/v1/videos/';
    var get = function(u){ return fetch(api + u, { credentials: 'include' }).then(function(r){ return r.json(); }); };
    get(m[2]).then(function(j){
      var id = j.video && j.video.id;
      if (!id) return null;
      if (m[1] === 'pause-snack-continue' && pscNext(id)) return pscNext(id);
      return get('next/' + id).then(function(n){ return n.video; });
    }).then(function(v){
      if (!v) { osdNote('No next episode'); return; }
      window.__ftvGo('/series/' + v.series_tag + '/' + v.tag,
        { title: cleanTitle(v.title), series: v.series_name || '', thumb: v.medium_thumbnail || v.thumbnail || '' });
    }).catch(function(){ osdNote('Couldn’t find the next episode'); });
  };
  window.__ftvBack = function(){
    if (!isEpisodePath(location.pathname)) return false;
    var to = null;
    try { to = sessionStorage.getItem(BROWSE_KEY); sessionStorage.setItem(RETURN_KEY, location.pathname); } catch (e) {}
    if (!to) return false;
    undoFullscreen();
    if (cur) { zoomOff(cur); cur = null; }
    hideVeil();
    // Opened by a client-side route from that page (fishtank): going back
    // reuses the page from Next.js's cache, scroll position and all, and
    // this script keeps running, so it picks up the cursor itself.
    if (prevPath && prevPath === to.split('?')[0]) {
      prevPath = null;
      returnTo = location.pathname;
      returnUntil = Date.now() + 10000;
      try { sessionStorage.removeItem(RETURN_KEY); } catch (e) {}
      history.back();
    }
    else location.assign(to);
    return true;
  };
  // On the page BACK returned to, the episode just left gets the cursor
  // once its card exists (PSC's rows, say, are built a moment after load).
  var returnTo = null, returnUntil = 0;
  try { returnTo = sessionStorage.getItem(RETURN_KEY); sessionStorage.removeItem(RETURN_KEY); } catch (e) {}
  if (returnTo) returnUntil = Date.now() + 10000;
  function restoreReturnFocus(){
    if (!returnTo) return;
    if (Date.now() > returnUntil) { returnTo = null; return; }
    // Rendered, though not necessarily on screen yet: mark() scrolls to it.
    var a = document.querySelector('a[href="' + returnTo + '"]');
    if (!a || a.getBoundingClientRect().width < 8) return;
    returnTo = null;
    dirty = true;
    mark(a);
  }

  // The homepage puts the cursor back on the page being left (a show's
  // tile, a playlist), the way BACK from an episode already does.
  function noteReturn(){
    if (location.pathname === '/') return;
    try { sessionStorage.setItem(RETURN_KEY, location.pathname); } catch (e) {}
    returnTo = location.pathname;
    returnUntil = Date.now() + 10000;
  }
  // Came straight from the homepage by a client-side route: go back to it.
  // Next.js restores a back navigation from its client cache (and keeps the
  // scroll position) -- measured ~0.5s, where clicking the home link waits
  // on the server to render the homepage again (~4.4s on mde, no better
  // than a full reload). False if that isn't how we got here.
  window.__ftvHomeBack = function(){
    if (prevPath !== '/') return false;
    noteReturn();
    undoFullscreen();
    if (cur) { zoomOff(cur); cur = null; }
    hideVeil();
    prevPath = null;
    history.back();
    return true;
  };
  window.__ftvGoHome = function(){
    if (window.__ftvHomeBack()) return true;
    noteReturn();
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

  // BACK first closes whatever is open over the page (fishtank's login
  // dialog or menu, a site modal), as on any TV app, instead of leaving the
  // page behind it. True if there was one.
  function shown(e){
    var r = e.getBoundingClientRect(), s = getComputedStyle(e);
    return r.width > 40 && r.height > 40 && s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity) > 0.1;
  }
  window.__ftvDismiss = function(){
    var i, j, list;
    // fishtank's pop-ups aren't marked as dialogs. A drop-down (the profile
    // menu): a small fixed panel of buttons, closed by the button that
    // opened it, beside it.
    list = document.querySelectorAll('div.fixed');
    for (i = 0; i < list.length; i++) {
      var d = list[i], dr = d.getBoundingClientRect();
      // (Hanging from the top-right, where menus open -- not, say, a chat panel.)
      if (dr.top < 30 || dr.top > window.innerHeight * 0.4 || dr.right < window.innerWidth * 0.6 ||
          dr.width >= window.innerWidth * 0.5 || !shown(d) || d.querySelectorAll('button, a[href]').length < 3) continue;
      var opener = null, sib = d.parentElement ? d.parentElement.querySelectorAll('button') : [];
      for (j = 0; j < sib.length; j++) if (!d.contains(sib[j])) opener = sib[j];
      if (opener) { opener.click(); dirty = true; return true; }
    }
    // A menu or panel over the whole screen (its start menu): a full-screen
    // fixed layer holding a smaller panel, whose close button sits in the
    // panel's top-right corner.
    for (i = 0; i < list.length; i++) {
      var L = list[i], lr = L.getBoundingClientRect();
      if (lr.width < window.innerWidth * 0.9 || lr.height < window.innerHeight * 0.9 || !shown(L)) continue;
      var btns = L.querySelectorAll('button');
      for (j = 0; j < btns.length; j++) {
        var b = btns[j], br = b.getBoundingClientRect(), p = b.parentElement && b.parentElement.closest('div'), pr = p && p.getBoundingClientRect();
        if (!pr || pr.width > window.innerWidth * 0.8 || pr.height > window.innerHeight * 0.9) continue;
        if (br.width < 60 && pr.right - br.right < 40 && br.top - pr.top < 40) { b.click(); dirty = true; return true; }
      }
    }
    var open = null;
    list = document.querySelectorAll('[role="dialog"], [aria-modal="true"], dialog[open]');
    for (i = 0; i < list.length; i++) if (shown(list[i])) { open = list[i]; break; }
    if (!open) return false;
    var close = open.querySelector('[aria-label*="close" i], button[class*="close" i], [data-dismiss], [class*="_close"]');
    if (close) close.click();
    else {
      var esc = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
      (document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', esc));
      document.dispatchEvent(new KeyboardEvent('keydown', esc));
    }
    dirty = true;
    return true;
  };

  window.__ftvActivate = function(){
    if (playingVideo()) {
      if (seekOverlayVisible && osdFocus >= 0) { osdPress(); return; }
      if (!window.__ftvScrubCommit()) window.__ftvTogglePlay();
      return;
    }
    if (silentCur) { revealStart(); return; }    // first press just reveals it
    if (!cur) { window.__ftvReset(); return; }
    syncSpotlight();
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
    var card = cur.closest && cur.closest('a[href]');
    if (card && isEpisodePath(card.getAttribute('href') || '')) {
      var cm = cardMeta(card);
      showLoader(cm);
      rememberLoading(card.getAttribute('href'), cm);
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

  // The cursor shows as soon as the page has something to land on, so the
  // first press moves it. (It used to be placed invisibly, which made the
  // first press on every page do nothing but reveal it; that was to avoid
  // dimming the screen, and the dimming is long gone.) Waits a little for
  // the preferred start -- Continue watching renders after the rest -- then
  // settles for the default. Silent on a watch page, where the player owns
  // the keys.
  var placeTries = 0;
  (function place(){
    if (cur && !silentCur) return;   // the user got there first
    // (Injected at commit, the page is often still empty: keep trying.)
    dirty = true;
    var list = items(), pref = list.length ? preferredStart(list) : null;
    placeTries++;
    if (!pref && (placeTries < 4 || (!list.length && placeTries < 12))) { setTimeout(place, 600); return; }
    if (list.length) mark(pref || pickDefault(list), playingVideo());
  })();
})();
