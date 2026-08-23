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
        private const val ZOOM_MIN = 60
        private const val ZOOM_MAX = 200
        private const val ZOOM_STEP = 10
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

        webView.webChromeClient = WebChromeClient()
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

    private fun applyZoom(delta: Int) {
        zoomPct = (zoomPct + delta).coerceIn(ZOOM_MIN, ZOOM_MAX)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_ZOOM, zoomPct)
            .apply()
        js(zoomJs(zoomPct))
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

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { applyZoom(ZOOM_STEP); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { applyZoom(-ZOOM_STEP); return true }

            KeyEvent.KEYCODE_MEDIA_NEXT -> { toggleBlockImages(); return true }

            KeyEvent.KEYCODE_BACK -> {
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
  var v = document.querySelector('video');
  if (!v) return;
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

  var SEL = 'a[href], button, input:not([type=hidden]), select, textarea, video, ' +
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
      var s = window.getComputedStyle(el);
      if (s.visibility === 'hidden' || s.display === 'none') continue;
      if (parseFloat(s.opacity) < 0.1) continue;
      if (el.disabled) continue;
      out.push(el);
    }
    return out;
  }

  function items(){
    if (dirty || !cache) { cache = computeItems(); dirty = false; }
    return cache;
  }

  // DOM mutations (SPA route/content swaps) and scrolling both change which
  // elements are on-screen, so both invalidate the cache. Debounced so a
  // burst of changes -- or a site cleanup hook re-running below -- triggers
  // one rebuild instead of one per mutation record.
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
  window.addEventListener('scroll', scheduleInvalidate, { passive: true });

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
    if (list.length) { mark(list[0]); }
  };

  function doNavigate(dir){
    var list = items();
    if (!list.length) return;

    if (!cur || !document.contains(cur) || !visible(cur)) { mark(list[0]); return; }

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

    if (best) {
      mark(best);
    } else {
      // Nothing focusable that way -- scroll the page instead so long
      // pages are not a dead end.
      if (dir === 'down') window.scrollBy(0, Math.round(window.innerHeight * 0.6));
      if (dir === 'up')   window.scrollBy(0, -Math.round(window.innerHeight * 0.6));
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
    if (tag === 'video') {
      if (cur.paused) { cur.play(); } else { cur.pause(); }
      return;
    }
    cur.click();
  };

  setTimeout(function(){ if (!cur) { window.__ftvReset(); } }, 1200);
})();
"""
