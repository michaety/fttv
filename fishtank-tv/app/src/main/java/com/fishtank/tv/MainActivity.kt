package com.fishtank.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject

/**
 * Substrings matched (case-insensitively) against every outgoing request
 * URL; a hit short-circuits the request with an empty response instead of
 * hitting the network. Edit this list, not the intercept logic in
 * MainActivity — and never add anything that could match a video
 * stream/manifest/segment URL or an auth/session endpoint.
 */
private val BLOCKED_URL_SUBSTRINGS = listOf(
    // analytics / telemetry
    "google-analytics.com",
    "googletagmanager.com",
    "doubleclick.net",
    "connect.facebook.net",
    "facebook.net",
    "sentry.io",
    "segment.io",
    "segment.com",
    "amplitude.com",
    "mixpanel.com",
    "hotjar.com",
    "fullstory.com",
    // ads
    "googlesyndication.com",
    "adservice.google.com",
    "adnxs.com",
    // site-specific: decorative chat emoji pack, not needed on a TV shell
    "cdn.fishtank.live/emojis",
)

class MainActivity : Activity() {

    companion object {
        private const val TAG = "FishtankTV"

        /**
         * Desktop Chrome UA. The site serves the phone layout to a mobile UA,
         * which is the wrong shape for a 10-foot screen and gives fewer
         * focusable elements for the D-pad to land on.
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val PREFS_NAME = "fishtank_tv_prefs"
        private const val PREF_ZOOM = "zoom_pct"
        private const val ZOOM_DEFAULT = 100
        private const val PREF_BLOCK_IMAGES = "block_images"
    }

    private lateinit var webView: WebView
    private lateinit var homeUrl: String
    private var zoomPct: Int = ZOOM_DEFAULT
    private var blockImages: Boolean = false

    /** Set once we've handed a URL off to a Custom Tab / external browser, so
     *  onResume knows to reload and pick up a session the OAuth flow left
     *  behind. Cleared as soon as that reload fires. */
    private var launchedCustomTab: Boolean = false

    /** The view Chromium hands us for an HTML5 fullscreen request (video or
     *  otherwise). Tracked so onHideCustomView can tear it back down. */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /**
     * Per-site selectors to hide before DPAD_JS builds its focus list, so
     * hidden elements never become focusable in the first place.
     *
     * Selectors are matched against Tailwind/CSS-module utility classes
     * rather than IDs because neither site exposes stable hooks for these
     * elements; re-check against the live site if a future redesign stops
     * matching. Anything that re-renders often (chat) is removed from the
     * DOM in siteCleanupJs() instead of just hidden here.
     */
    private fun siteCss(url: String?): String {
        if (url == null) return ""
        return when {
            url.contains("fishtank.live") -> """
                /* "Get Fishbucks" purchase CTA (top-right) */
                div.fixed.top-0.right-0 { display: none !important; }
                /* season-pass upsell toast */
                div.fixed.z-50.left-4.right-4 { display: none !important; }
                /* bottom nav bar (mobile-layout icon row -- shown because the
                   WebView viewport falls under Tailwind's lg breakpoint) */
                div.fixed.bottom-0.left-0.w-full.bg-light.z-6 { display: none !important; }
            """.trimIndent()
            url.contains("mde.tv") -> """
                /* bottom nav bar (Videos/Audio/Screeds/Chat/Producer/Shop) */
                div[class*="mobileNavigation"] { display: none !important; }
                /* docked audio player */
                div[class*="left-bar-module"][class*="__music"] { display: none !important; }
                /* search button and whatever sits next to it in the top bar
                   (login/signup normally, a different control when signed in) */
                div[class*="mobileSearch"], div[class*="mobileSearch"] + div { display: none !important; }
                /* "want to be on one of the shows?" call-in promo (logged-in
                   only, which is why this wasn't found until checked while
                   actually signed in) */
                button[class*="__callInWidget"] { display: none !important; }
                /* notification bell next to the logo */
                button[class*="notification-center-module"] { display: none !important; }
            """.trimIndent()
            else -> ""
        }
    }

    /**
     * Defines window.__ftvSiteCleanup, which physically removes rendering
     * hotspots (rather than display:none) so the SPA stops re-rendering
     * into them. Idempotent — safe to call repeatedly. DPAD_JS re-invokes it
     * on every debounced MutationObserver tick, since the SPA may re-mount
     * the node on route/tab changes.
     */
    private fun siteCleanupJs(url: String?): String {
        if (url == null) return ""
        return when {
            url.contains("fishtank.live") -> """
                window.__ftvSiteCleanup = function(){
                  var panel = document.querySelector('div.fixed.bottom-0.right-0.z-2');
                  if (panel) panel.remove();
                };
            """.trimIndent()
            else -> ""
        }
    }

    /**
     * Google refuses to complete OAuth inside an embedded WebView ("this
     * browser or app may not be secure"), so any navigation to
     * accounts.google.com is handed off to a Custom Tab instead of loading
     * in-app. Returns true if the navigation was handed off (caller should
     * not load it in the WebView), false if it should load normally.
     */
    private fun handleUrlOverride(url: String?): Boolean {
        if (url == null) return false
        val uri = Uri.parse(url)
        if (uri.host != "accounts.google.com") return false

        // Cookie sharing between the Custom Tab and this WebView is not
        // guaranteed on Fire OS -- they may not share a cookie jar at all,
        // so flushing here just ensures whatever session state the WebView
        // already has is durable before we leave it.
        CookieManager.getInstance().flush()

        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
            launchedCustomTab = true
            Log.i(TAG, "OAuth: opened $url in a Custom Tab")
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                launchedCustomTab = true
                Log.i(TAG, "OAuth: no Custom Tabs provider for $url, fell back to ACTION_VIEW")
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "OAuth: no browser available for $url, loading in WebView instead")
                return false
            }
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        homeUrl = getString(R.string.home_url)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        zoomPct = prefs.getInt(PREF_ZOOM, ZOOM_DEFAULT)
        blockImages = prefs.getBoolean(PREF_BLOCK_IMAGES, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = false
            useWideViewPort = false
            userAgentString = DESKTOP_UA
            cacheMode = WebSettings.LOAD_DEFAULT
            blockNetworkImage = blockImages
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        }

        // Keep the login across restarts.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            // Without this, Element.requestFullscreen() (what the
            // auto-fullscreen hook in DPAD_JS calls) is a silent no-op --
            // Android WebView routes any HTML5 fullscreen request through
            // these two callbacks rather than handling it internally, and
            // the base WebChromeClient doesn't implement them.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                (window.decorView as? ViewGroup)?.addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                webView.visibility = View.GONE
                hideSystemUi()
            }

            override fun onHideCustomView() {
                (window.decorView as? ViewGroup)?.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                webView.visibility = View.VISIBLE
                hideSystemUi()
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val css = siteCss(url)
                if (css.isNotEmpty()) {
                    val js = "(function(){var s=document.createElement('style');" +
                        "s.textContent=${JSONObject.quote(css)};" +
                        "(document.head||document.documentElement).appendChild(s);})();"
                    view?.evaluateJavascript(js, null)
                }
                val cleanup = siteCleanupJs(url)
                if (cleanup.isNotEmpty()) {
                    view?.evaluateJavascript(cleanup, null)
                    view?.evaluateJavascript("window.__ftvSiteCleanup && window.__ftvSiteCleanup();", null)
                }
                view?.evaluateJavascript(DPAD_JS, null)
                view?.evaluateJavascript(zoomJs(zoomPct), null)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null && BLOCKED_URL_SUBSTRINGS.any { url.contains(it, ignoreCase = true) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return handleUrlOverride(request?.url?.toString())
            }

            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrlOverride(url)
            }
        }

        setContentView(webView)
        hideSystemUi()
        webView.loadUrl(homeUrl)
    }

    private fun zoomJs(pct: Int): String =
        "document.documentElement.style.zoom = '$pct%';"

    /**
     * Direct mapping from the remote's fast-forward/rewind buttons to the
     * video DPAD_JS has actually seen play (window.__ftvVideo(), tracked via
     * a cheap 'play' listener) rather than document.querySelector('video')
     * -- the first video in DOM order was sometimes a decorative/background
     * one, not the real player. No overlay, no acceleration: a single
     * property write is cheap enough to not be a performance concern.
     */
    private fun seekVideo(deltaSeconds: Int) {
        js(
            "(function(){var v=window.__ftvVideo?window.__ftvVideo():document.querySelector('video');" +
                "if(!v)return;" +
                "v.disablePictureInPicture=true;" +
                "v.currentTime=Math.max(0,v.currentTime+($deltaSeconds));})();"
        )
    }

    private fun toggleBlockImages() {
        blockImages = !blockImages
        webView.settings.blockNetworkImage = blockImages
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_BLOCK_IMAGES, blockImages)
            .apply()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun js(code: String) = webView.evaluateJavascript(code, null)

    /**
     * A focused WebView handles D-pad keys itself before Activity.onKeyDown
     * ever sees them -- it has its own built-in link-navigation/scrolling
     * behavior for arrow keys, which is exactly what was swallowing every
     * D-pad press on real hardware: onKeyDown's logic was correct (confirmed
     * by driving the same JS functions directly over the WebView's remote
     * debugger) but never actually ran, so the synthetic cursor could never
     * move past wherever it happened to land on load. Intercepting in
     * dispatchKeyEvent runs before the WebView gets a chance at the event at
     * all, for both the initial press and the auto-repeat while held.
     */
    private fun isHandledKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_BACK -> true
        else -> false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isHandledKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                return onKeyDown(event.keyCode, event)
            }
            return true // swallow ACTION_UP too, so the WebView never sees any part of it
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { js("window.__ftvNavigate && window.__ftvNavigate('up')"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { js("window.__ftvNavigate && window.__ftvNavigate('down')"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { js("window.__ftvNavigate && window.__ftvNavigate('left')"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { js("window.__ftvNavigate && window.__ftvNavigate('right')"); return true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> { js("window.__ftvActivate && window.__ftvActivate()"); return true }

            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { js(TOGGLE_PLAY_JS); return true }

            KeyEvent.KEYCODE_MENU -> { js("window.__ftvReset && window.__ftvReset()"); return true }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekVideo(10); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekVideo(-10); return true }

            KeyEvent.KEYCODE_MEDIA_NEXT -> { toggleBlockImages(); return true }

            KeyEvent.KEYCODE_BACK -> {
                // Belt-and-suspenders alongside disablePictureInPicture in
                // DPAD_JS: a paused video can't trigger auto-PiP on navigate-away.
                js("document.querySelectorAll('video').forEach(function(v){ v.pause(); })")
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemUi()
        if (launchedCustomTab) {
            launchedCustomTab = false
            webView.reload()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}

private const val TOGGLE_PLAY_JS = """
(function(){
  var v = window.__ftvVideo ? window.__ftvVideo() : document.querySelector('video');
  if (!v) return;
  v.disablePictureInPicture = true;
  if (v.paused) { v.play(); } else { v.pause(); }
})();
"""

/**
 * Injected on every page load. Gives the site a synthetic focus cursor driven
 * by the remote: geometric nearest-neighbour search in the pressed direction,
 * amber ring on whatever is selected. This is the mouse you would otherwise
 * be plugging into the Firestick.
 */
private const val DPAD_JS = """
(function(){
  if (window.__ftvReady) return;
  window.__ftvReady = true;

  // Deliberately excludes 'video': the dedicated video-control module below
  // drives play/pause/seek directly against the active <video> regardless of
  // cursor position. Letting the D-pad cursor land ON the video used to give
  // it the loud focus treatment -- 1.08 scale, 6px outline, full-screen dim
  // -- while actually watching, which is exactly backwards.
  var SEL = 'a[href], button, input:not([type=hidden]), select, textarea, ' +
            '[tabindex]:not([tabindex="-1"]), [role="button"], [role="link"], [onclick]';

  // Focus ring, scale-up and screen dim are transform/opacity/box-shadow
  // only -- paint/composite work, no layout -- so they stay cheap even
  // though they're louder than a plain outline.
  var style = document.createElement('style');
  style.textContent =
    '.__ftv_focus{' +
      'outline:6px solid #FFB000 !important;' +
      'outline-offset:4px !important;' +
      'box-shadow:0 0 40px 14px rgba(255,176,0,.9) !important;' +
      'isolation:isolate !important;' +
      'z-index:2147483647 !important;' +
      'transform:scale(1.08) !important;' +
      'transition:transform 120ms ease-out !important;' +
      'will-change:transform;' +
    '}' +
    '@keyframes __ftv_pulse{0%{transform:scale(1.2);}100%{transform:scale(1.08);}}' +
    '.__ftv_focus.__ftv_pulse{animation:__ftv_pulse 220ms ease-out;}' +
    '#__ftv_dim{' +
      'position:fixed;inset:0;background:rgba(0,0,0,.35);' +
      'z-index:2147483646;pointer-events:none;opacity:0;' +
      'transition:opacity 150ms ease-out;' +
    '}' +
    '#__ftv_dim.__ftv_dim_on{opacity:1;}';
  (document.head || document.documentElement).appendChild(style);

  var dim = document.createElement('div');
  dim.id = '__ftv_dim';
  (document.body || document.documentElement).appendChild(dim);

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

    var onscreen = [];
    for (var i = 0; i < n; i++) {
      var r = rects[i];
      if (r.width < 8 || r.height < 8) continue;
      if (r.bottom < 0 || r.top > window.innerHeight) continue;
      if (r.right < 0 || r.left > window.innerWidth) continue;
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

  // Elements that match SEL but should never be reachable by the D-pad at
  // all, not just skipped as a default landing spot.
  function isExcluded(el){
    return !!(el.matches && el.matches('a[class*="__logo"]'));
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
      if (window.__ftvSiteCleanup) {
        try { window.__ftvSiteCleanup(); } catch (e) {}
      }
    }, 250);
  }
  new MutationObserver(scheduleInvalidate).observe(document.body, { childList: true, subtree: true });
  // Capture phase on document, not a plain window listener: scroll events
  // don't bubble, so a listener on window only ever sees the page itself
  // scrolling. Capture-phase delegation from document catches any nested
  // scrollable container too (a horizontally-scrolling carousel row).
  document.addEventListener('scroll', scheduleInvalidate, { passive: true, capture: true });

  // Track the actively-playing video via event delegation -- cheap, since it
  // only fires on a real play/pause transition rather than scanning the
  // page. This is ONLY used to target play/pause/seek key presses at the
  // right <video> (window.__ftvVideo() below); it does NOT gate D-pad
  // navigation. An earlier version suppressed all navigation/focus while
  // any large-enough video was playing, meant to stop D-pad presses from
  // interfering with a fullscreen player -- but some pages (mde's homepage
  // "Spotlight" hero banner) autoplay a video large enough to pass that
  // same size check while just browsing, which permanently froze
  // navigation on those pages. Removed rather than chasing a size/fullscreen
  // heuristic that can't reliably tell "the dedicated player" apart from
  // "a large autoplaying homepage banner".
  var trackedVideo = null;
  document.addEventListener('play', function(e){
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    v.disablePictureInPicture = true;
    var r = v.getBoundingClientRect();
    // Only a real, mostly-viewport-filling video is worth tracking as "the"
    // video for play/pause/seek purposes -- a homepage grid thumbnail
    // hover-preview (commonly 150-300px) is a mouse-era autoplay pattern
    // that means nothing on a D-pad interface, and each one spins up its
    // own hardware video decoder. On this device (~1.7GB RAM, ~36MB free
    // under normal load) enough of those playing concurrently was enough
    // for Android's low-memory-killer to kill the *shared*
    // com.amazon.webview.chromium process outright, taking the whole app
    // down with it -- confirmed via logcat. Stop it outright rather than
    // merely ignoring it.
    if (r.width < window.innerWidth * 0.5 || r.height < window.innerHeight * 0.3) {
      try { v.pause(); } catch (err) {}
      return;
    }
    trackedVideo = v;
  }, true);

  // MainActivity's play/pause/seek key handlers call this (via
  // evaluateJavascript) instead of document.querySelector('video') directly,
  // so they target the video DPAD_JS has actually seen play rather than
  // whichever <video> happens to be first in DOM order (which was landing on
  // decorative/background videos instead of the real player).
  window.__ftvVideo = function(){
    if (trackedVideo && document.contains(trackedVideo)) return trackedVideo;
    return document.querySelector('video');
  };

  // Header/branding chrome (logo, search, login) shouldn't be where the
  // synthetic cursor parks by default -- it's still reachable by navigating
  // up into it, just not where a fresh page load or reset lands.
  function isChrome(el){
    return !!el.closest('[class*="top-bar-module"], [class*="search-module"]');
  }
  function pickDefault(list){
    for (var i = 0; i < list.length; i++) {
      if (!isChrome(list[i])) return list[i];
    }
    return list[0] || null;
  }

  function centre(el){
    var r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  function mark(el){
    if (cur) { cur.classList.remove('__ftv_focus', '__ftv_pulse'); }
    cur = el || null;
    if (!cur) { dim.classList.remove('__ftv_dim_on'); return; }
    cur.classList.add('__ftv_focus');
    dim.classList.add('__ftv_dim_on');
    // Double rAF so the browser paints the pulse class removed before it's
    // re-added, restarting the CSS animation without forcing a sync layout.
    requestAnimationFrame(function(){
      requestAnimationFrame(function(){ if (cur) cur.classList.add('__ftv_pulse'); });
    });
    try { cur.focus({ preventScroll: true }); } catch (e) {}
    var r = cur.getBoundingClientRect();
    if (r.top < 60 || r.bottom > window.innerHeight - 60) {
      try { cur.scrollIntoView({ block: 'center' }); } catch (e) { cur.scrollIntoView(); }
    }
  }

  window.__ftvReset = function(){
    if (cur) { cur.classList.remove('__ftv_focus'); }
    cur = null;
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

  function nearest(list, dir){
    var c = centre(cur);
    var best = null;
    var bestScore = Infinity;

    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === cur) continue;
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
      if (score < bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function doNavigate(dir){
    var list = items();
    if (!list.length) return;

    if (!cur || !document.contains(cur) || !visible(cur)) { mark(pickDefault(list)); return; }

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
  window.__ftvNavigate = function(dir){
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

  window.__ftvActivate = function(){
    if (!cur) { window.__ftvReset(); return; }
    var tag = cur.tagName.toLowerCase();
    if (tag === 'input' || tag === 'textarea') {
      cur.focus();                            // brings up the Fire TV keyboard
      return;
    }
    cur.click();
  };

  setTimeout(function(){ if (!cur) { window.__ftvReset(); } }, 1200);
})();
"""
