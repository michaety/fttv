package com.fishtank.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import io.nayuki.qrcodegen.QrCode
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
    "posthog.com",           // fishtank: product analytics / session capture
    "cloudflareinsights.com", // both: Cloudflare Web Analytics beacon
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


        /** Second BACK on the homepage within this window exits the app. */
        private const val EXIT_CONFIRM_MS = 2000L

        /**
         * 2s of black, 1280x720 @ 1fps H.264 -- see createOverlayHolder. It has
         * to be a "real" resolution: the WebView only hands its overlay to
         * videos above some size, and ignored a 128x72 version entirely. Black
         * frames at 1fps still cost next to nothing to decode.
         */
        private const val OVERLAY_HOLDER_MP4 = "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"

        /** Gap between releasing Select and opening the keyboard. */
        private const val TAP_DELAY_MS = 250L
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var overlayHolder: WebView? = null
    private var holderReady = false
    /** Main WebView's onResume, held back until the holder has the slot. */
    private var resumeWhenHolderReady = false
    private lateinit var updateChecker: UpdateChecker
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var homeUrl: String
    private val thumbCache by lazy { ThumbCache(cacheDir) }
    /** mde only: browse and control from a phone (see PhoneRemote). */
    private var phoneRemote: PhoneRemote? = null
    private var lastHomeBackPress = 0L

    /** Set when the main-frame load failed and errorView is showing; Select
     *  then retries instead of being forwarded to the (error) page. */
    private var errorShown = false

    /** Set once we've handed a URL off to a Custom Tab / external browser, so
     *  onResume knows to reload and pick up a session the OAuth flow left
     *  behind. Cleared as soon as that reload fires. */
    private var launchedCustomTab: Boolean = false

    /** The view Chromium hands us for an HTML5 fullscreen request (video or
     *  otherwise). Tracked so onHideCustomView can tear it back down. */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /**
     * Per-site selectors to hide before dpad.js builds its focus list, so
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
                /* the 3 circular quick-action buttons docked bottom-right
                   (inventory/anchor/chat) */
                div[class*="ease-spring"][class*="translate-y-[-2px]"] { display: none !important; }
                /* bottom-right docked panel (logged-in only). Hidden, not
                   removed: this used to be node.remove()d, but it belongs
                   to React, and pulling it out from under React made its
                   next update throw removeChild/insertBefore "not a child
                   of this node" -- which crashed the whole site to "This
                   page couldn't load" right after logging in. */
                div.fixed.bottom-0.right-0.z-2 { display: none !important; }
                /* Episodes page: the big "Latest Episode" banner at the top
                   -- a third of the screen for one episode, pushing every
                   row below it out of view. */
                div.flex.flex-col.gap-5.mb-5 > a.flex.flex-col[href^="/episodes/"] { display: none !important; }
                /* ...and the Season 1/2/3 row's oversized 400x332 cards
                   brought down to the 180px / 4:3 size every other row
                   uses, then the whole episodes area zoomed to 0.8 -- two
                   full rows of ~6 episodes on screen instead of one row of
                   2.5. */
                a[href^="/episodes/"][class*="md:w-[400px]"] { width: 180px !important; height: auto !important; }
                a[href^="/episodes/"][class*="md:w-[400px]"] > img { width: 180px !important; height: 135px !important; }
                div.flex.flex-col.gap-5.mb-5:has(a[href^="/episodes/"]) { zoom: 0.8; }
                /* achievement / mission toasts (Sonner): over the fullscreen
                   video (which sits just below the top layer for this), at
                   the bottom of the screen */
                [data-sonner-toaster] {
                  z-index: 2147483647 !important;
                  top: auto !important;
                  bottom: 32px !important;
                }
                /* a toaster configured for the top hangs its toasts down
                   from the anchor -- moved to the bottom, they'd drop off
                   the screen, so hang them upward instead */
                [data-sonner-toaster] [data-sonner-toast][data-y-position="top"] {
                  top: auto !important;
                  bottom: 0 !important;
                }
                /* homepage cam grid: 4 columns, as the site itself does from
                   its lg breakpoint, which this viewport falls just short of
                   -- all 8 tiles on one screen instead of 4 */
                div.grid[class*="min-h-[calc(100vh-160px)]"] {
                  grid-template-columns: repeat(4, minmax(0, 1fr)) !important;
                  gap: 16px !important;
                  min-height: 0 !important;
                }
                /* "Log In With Google" and the "Or" divider under it --
                   Google refuses OAuth inside an embedded WebView, so it
                   can only fail. Matched by its icon, not its Tailwind
                   classes, which are generic button styling. */
                div:has(> button img[src*="/icons/google"]) { display: none !important; }
                div:has(> button img[src*="/icons/google"]) + div { display: none !important; }
            """.trimIndent()
            url.contains("mde.tv") -> """
                /* mde's CSS-module class names changed format in a site
                   rebuild -- from "left-bar-module__music" to hashed
                   "5R0ZAa_music" -- which silently broke most of these.
                   Each selector now matches on the "_name" part, which
                   both formats share, and never on the hash. */
                /* bottom nav bar (Videos/Audio/Screeds/Chat/Producer/Shop) */
                div[class*="mobileNavigation"] { display: none !important; }
                /* docked audio player (has a visualizer that repaints) */
                div[class*="_musicPanel"], div[class*="_music "], div[class$="_music"] { display: none !important; }
                /* live chat bar -- a real performance cost, not just clutter:
                   it renders full chat message history (with animated GIF
                   emotes -- confirmed 90 of them on a fresh homepage load,
                   all continuously decoding/repainting) into a 0-size
                   collapsed panel that was never visible in this layout to
                   begin with. display:none stops the browser from animating
                   images it isn't rendering. (Not present in the current
                   build, kept in case it returns.) */
                div[class*="_chatBar"] { display: none !important; }
                /* "want to be on one of the shows?" call-in promo (logged-in
                   only). Also a 942px animated GIF that never stops
                   decoding, so it costs CPU as well as space. */
                button[class*="_callInWidget"] { display: none !important; }
                /* search: a permanent box in the header between the logo and
                   the profile, instead of the icon that pops a full-width
                   bar (the site's narrow-screen layout, which this viewport
                   falls into). Select on it opens the Fire TV keyboard. */
                div[class*="_topBar"] > div[class*="_mobileSearch"] { display: none !important; }
                /* (*= not $=: the site adds "_clearable" once there's text) */
                div[class*="_topBar"] > div[class*="_search"] {
                  display: flex !important;
                  position: static !important;
                  flex: 1 1 auto !important;
                  min-width: 0 !important;
                  height: 32px !important;
                  margin: 0 24px !important;
                }
                div[class*="_topBar"] [class*="_mobileBack"] { display: none !important; }
                /* notification bell next to the logo */
                button[class*="notification-center-module"], button[class*="_notificationCenter"] { display: none !important; }
                /* the account/login control's right-alignment depended on
                   the (now-hidden) search wrapper's margin-left:auto --
                   without that it collapsed to sit right next to the logo */
                div[class*="_topBar"] > div[class*="_user"] { margin-left: auto !important; }
                /* reward / badge toasts (react-hot-toast): over the
                   fullscreen video and at the bottom of the screen. The
                   library stacks toasts down from the top, so the container
                   is flipped vertically and each toast flipped back upright
                   -- with 'scale', which composes with the transforms the
                   toasts animate with instead of replacing them. */
                [data-rht-toaster] { z-index: 2147483647 !important; scale: 1 -1; }
                [data-rht-toaster] > div > * { scale: 1 -1; }
                /* the player's own "Up next in N" card: above the fullscreen
                   video (it was underneath, only showing once the video had
                   ended), as a TV-sized card bottom-right. Same look as the
                   app's card on fishtank (#__ftv_next). Back presses its
                   close button (see __ftvScrubCancel). */
                div[class*="_nextVideo"] {
                  position: fixed !important; inset: 0 !important;
                  z-index: 2147483001 !important; background: transparent !important;
                }
                div[class*="_nextVideo"] > div[class*="_inner"] {
                  position: absolute !important; right: 4% !important; bottom: 12% !important;
                  left: auto !important; top: auto !important; transform: none !important;
                  width: 340px !important; padding: 14px !important; border-radius: 10px !important;
                  background: rgba(0,0,0,.85) !important; box-shadow: 0 4px 18px rgba(0,0,0,.6) !important;
                }
                div[class*="_nextVideo"] [class*="_label"] { font-size: 16px !important; margin-bottom: 8px !important; }
                div[class*="_nextVideo"] [class*="_close"] { display: none !important; }
                div[class*="_nextVideo"] [class*="_label"]::after { content: " \00b7  Back to cancel"; opacity: .7; }
                div[class*="_nextVideo"] [class*="_thumbnail"] img { width: 100% !important; height: auto !important; border-radius: 6px !important; }
                div[class*="_nextVideo"] [class*="_title"] { font-size: 18px !important; margin-top: 8px !important; }
                /* homepage HYDEFORCE ad banner: the site advertising its own
                   merch (the same reason fishtank's merch prompts are
                   hidden), and a looping video ad besides. Unconditional:
                   keying it on its contents (:has(video)) made it flicker
                   in and out as the site re-rendered it. */
                a[class*="_banner"] { display: none !important; }
                /* homepage "Series" grid -- 4 columns instead of 2, so more
                   fits on screen without scrolling as far */
                div[class*="_seriesGrid"] { grid-template-columns: repeat(4, 1fr) !important; }
            """.trimIndent()
            else -> ""
        }
    }

    /**
     * D-pad cursor accent color (hex, and its "r,g,b" form for rgba()),
     * sampled from each site's own design tokens rather than picked
     * arbitrarily: fishtank's --color-primary and mde's --color-green,
     * read live via getComputedStyle(document.documentElement) against
     * the real sites.
     */
    private fun accentColor(url: String?): Pair<String, String> = when {
        url != null && url.contains("mde.tv") -> "#25FF06" to "37,255,6"
        else -> "#DF4E1E" to "223,78,30"
    }

    /**
     * Defines window.__ftvSiteCleanup, for clutter CSS can't target (matched
     * by text). Idempotent — safe to call repeatedly. dpad.js re-invokes it
     * on every debounced MutationObserver tick, since the SPA may re-mount
     * the node on route/tab changes. Only ever hide here, never remove():
     * these nodes belong to React, and removing them crashes the site.
     */
    private fun siteCleanupJs(url: String?): String {
        if (url == null) return ""
        return when {
            url.contains("fishtank.live") -> """
                window.__ftvSiteCleanup = function(){
                  // Never before the page has finished loading: React is
                  // still hydrating the server HTML then, and any change to
                  // it fails hydration (React error #418).
                  if (document.readyState !== 'complete') return;
                  // The Archives/Episodes/Clips/Merch menu only exists in the
                  // DOM while open, so this can't be a one-time CSS rule --
                  // it relies on siteCleanupJs being re-run on the debounced
                  // MutationObserver tick in dpad.js.
                  document.querySelectorAll('button').forEach(function(b){
                    if (b.textContent.trim() === 'Merch') b.style.display = 'none';
                  });
                  // Episodes page: numbered episodes ("10 - Finale" ...
                  // "01 - ...", or "S3E22 - ..." with unnumbered BTS
                  // extras at the end) shown oldest first. Flex 'order'
                  // only, so React's nodes stay where it put them;
                  // re-applied each tick, which also covers switching
                  // season tabs.
                  document.querySelectorAll('ul[role="group"]').forEach(function(ul){
                    var lis = ul.children;
                    if (lis.length < 2) return;
                    var nums = [].map.call(lis, function(li){
                      var m = /^\s*(?:S\d+E)?(\d+)\b/i.exec(li.textContent);
                      return m ? +m[1] : NaN;
                    }).filter(function(n){ return !isNaN(n); });
                    if (nums.length < lis.length / 2 || nums[0] <= nums[nums.length - 1]) return;
                    for (var i = 0; i < lis.length; i++) {
                      var o = String(lis.length - i);
                      if (lis[i].style.order !== o) lis[i].style.order = o;
                    }
                  });
                  // Episodes page: Off-Season row moved below the seasons
                  // and digests. Flex 'order' only -- the node stays where
                  // React put it. Matched by its heading, not position.
                  var rows = document.querySelector('div.flex.flex-col.gap-5.mb-5');
                  if (rows) {
                    for (var i = 0; i < rows.children.length; i++) {
                      var row = rows.children[i];
                      if (row.textContent.trim().indexOf('Off-Season') === 0 && row.style.order !== '1') {
                        row.style.order = '1';
                      }
                    }
                  }
                  // Episodes page: the "Digest" tab is season 4's digests
                  // (next to "Season 5 Digests"), so say so. Only the text
                  // node changes; re-applied each tick if React resets it.
                  var tabs = document.querySelectorAll('div.flex.flex-col.gap-5.mb-5 button');
                  for (var i = 0; i < tabs.length; i++) {
                    if (tabs[i].textContent.trim() !== 'Digest') continue;
                    var tw = document.createTreeWalker(tabs[i], NodeFilter.SHOW_TEXT);
                    for (var tn = tw.nextNode(); tn; tn = tw.nextNode()) {
                      if (tn.nodeValue.trim() === 'Digest') tn.nodeValue = 'Season 4 Digest';
                    }
                  }
                  // Episodes page: season 3's two BTS videos belong with the
                  // other BTS videos, in the row below. Hidden (not removed)
                  // from season 3, and copies added to the BTS tab while it's
                  // the one showing. Copies are kept in localStorage so the
                  // BTS tab has them even before season 3 has been shown.
                  var btsExtra = window.__ftvBtsExtra;
                  if (!btsExtra) {
                    try { btsExtra = JSON.parse(localStorage.getItem('__ftv_bts_extra') || '{}'); } catch (e) { btsExtra = {}; }
                    window.__ftvBtsExtra = btsExtra;
                  }
                  document.querySelectorAll('ul[role="group"] > li').forEach(function(li){
                    if (li.hasAttribute('data-ftv-bts') || !/^\s*BTS\b/.test(li.textContent)) return;
                    if (!/S3E\d/i.test(li.parentElement.textContent)) return;
                    var a = li.querySelector('a[href^="/episodes/"]');
                    if (!a) return;
                    var h = a.getAttribute('href');
                    if (!btsExtra[h]) {
                      var c = li.cloneNode(true);
                      c.removeAttribute('style');
                      c.querySelectorAll('[data-ftv]').forEach(function(n){ n.removeAttribute('data-ftv'); });
                      btsExtra[h] = c.outerHTML;
                      try { localStorage.setItem('__ftv_bts_extra', JSON.stringify(btsExtra)); } catch (e) {}
                    }
                    if (li.style.display !== 'none') li.style.display = 'none';
                  });
                  var btsTab = [].filter.call(tabs, function(b){ return b.textContent.trim() === 'BTS'; })[0];
                  var btsRow = btsTab && btsTab.closest('div.flex.flex-col.gap-5.mb-5 > *');
                  var btsUl = btsRow && btsRow.querySelector('ul[role="group"]');
                  if (btsUl) {
                    var showing = btsTab.classList.contains('shadow-panel');
                    btsUl.querySelectorAll('li[data-ftv-bts]').forEach(function(li){ if (!showing) li.remove(); });
                    if (showing) {
                      Object.keys(btsExtra).forEach(function(h){
                        if (btsUl.querySelector('a[href="' + h + '"]')) return;
                        var box = document.createElement('ul');
                        box.innerHTML = btsExtra[h];
                        var li = box.firstElementChild;
                        if (!li) return;
                        li.setAttribute('data-ftv-bts', h);
                        btsUl.appendChild(li);
                      });
                    }
                  }
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

    /**
     * Desktop UA carrying the WebView's *real* Chrome version instead of a
     * hardcoded one, so feature detection server-side matches what the
     * engine can actually do. Falls back to DESKTOP_UA if the default UA
     * doesn't parse.
     */
    private fun desktopUserAgent(): String {
        val version = try {
            Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(this))?.groupValues?.get(1)
        } catch (e: Exception) { null } ?: return DESKTOP_UA
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    private fun isHomePath(url: String?): Boolean {
        val path = try { Uri.parse(url).path } catch (e: Exception) { null }
        return path.isNullOrEmpty() || path == "/"
    }

    /**
     * Injects the site CSS, cleanup hook and dpad.js. Idempotent (the style
     * tag is id-guarded, dpad.js guards on window.__ftvReady), so it's run
     * from onPageCommitVisible -- as soon as the new page first paints, so
     * the remote works and clutter is hidden without waiting for every
     * image/iframe/ad to finish -- and again from onPageFinished as a
     * fallback in case body didn't exist yet at commit time.
     */
    private fun injectPageScripts(view: WebView?, url: String?) {
        val css = siteCss(url)
        if (css.isNotEmpty()) {
            val js = "(function(){if(document.getElementById('__ftv_site_css'))return;" +
                "var s=document.createElement('style');s.id='__ftv_site_css';" +
                "s.textContent=${JSONObject.quote(css)};" +
                "(document.head||document.documentElement).appendChild(s);})();"
            view?.evaluateJavascript(js, null)
        }
        val cleanup = siteCleanupJs(url)
        if (cleanup.isNotEmpty()) {
            view?.evaluateJavascript(cleanup, null)
            view?.evaluateJavascript("window.__ftvSiteCleanup && window.__ftvSiteCleanup();", null)
        }
        val (accentHex, accentRgb) = accentColor(url)
        val dpadJs = dpadTemplate
            .replace("__FTV_ACCENT_HEX__", accentHex)
            .replace("__FTV_ACCENT_RGB__", accentRgb)
            .replace("__FTV_IGNORE_MUTATIONS__", JSONObject.quote(ignoredMutationSelector(url)))
            .replace("__FTV_PAUSE_HOME_VIDEOS__", pauseHomeVideos(url).toString())
        view?.evaluateJavascript(dpadJs, null)
    }

    /**
     * Subtrees whose DOM churn should not invalidate the D-pad candidate
     * cache: they're hidden by siteCss but the SPA keeps rendering into
     * them (live chat), which otherwise marks the cache dirty every few
     * hundred ms and makes every keypress pay for a full rebuild.
     */
    private fun ignoredMutationSelector(url: String?): String = when {
        url != null && url.contains("mde.tv") ->
            "[class*=\"_chatBar\"], [class*=\"_musicPanel\"]"
        else -> ""
    }

    /**
     * Whether autoplaying videos on the homepage (mde's Spotlight clips and
     * banner ad) get paused. Measured on a Fire TV Stick 4K: total CPU ~226%
     * of 400% with the Spotlight clip playing vs ~116% paused -- over a full
     * core spent decoding a preview nobody pressed play on, on a device
     * already deep in swap. Not on fishtank, whose homepage is the
     * live cams themselves.
     */
    private fun pauseHomeVideos(url: String?): Boolean = url != null && url.contains("mde.tv")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        homeUrl = getString(R.string.home_url)
        if (homeUrl.contains("mde.tv")) phoneRemote = PhoneRemote(this, object : PhoneRemote.Host {
            override fun evalJs(code: String, result: (String?) -> Unit) = webView.evaluateJavascript(code) { result(it) }
            override fun openPath(path: String) {
                showError(false)
                webView.loadUrl("https://www.mde.tv$path")
            }
        })


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // Thin load indicator: a full page load on the Firestick takes
        // seconds, and without this a Select that triggered one looks like it
        // did nothing.
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                progressTintList = ColorStateList.valueOf(Color.parseColor(accentColor(homeUrl).first))
            }
        }

        errorView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            text = "Couldn't reach the site.\n\nPress Select to retry."
            visibility = View.GONE
        }

        createWebView()
        createOverlayHolder()   // (onStart then sees it's already there)
        root.addView(errorView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(progressBar, FrameLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { gravity = Gravity.TOP })

        setContentView(root)
        hideSystemUi()
        webView.loadUrl(homeUrl)

        updateChecker = UpdateChecker(this)
        // Not during startup: the homepage load already has the Firestick's
        // CPU and network to itself for long enough.
        root.postDelayed({ if (!isFinishing) updateChecker.checkInBackground() }, 10_000)
    }

    /** The injected D-pad/player script, from assets/dpad.js (too big for a
     *  Kotlin string constant: the JVM caps those at 64KB). */
    private val dpadTemplate by lazy { assets.open("dpad.js").bufferedReader().use { it.readText() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Builds (or, after a renderer crash, rebuilds) the WebView and inserts
     * it at the bottom of root, under the progress bar and error view.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        webView = FtvWebView(this)
        webView.setBackgroundColor(Color.BLACK)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = false
            useWideViewPort = false
            userAgentString = desktopUserAgent()
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        }

        // Keep the login across restarts.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            // Without this, Element.requestFullscreen() (what the
            // auto-fullscreen hook in dpad.js calls) is a silent no-op --
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
                exitCustomView()
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                showError(false)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                injectPageScripts(view, url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showError(true)
            }

            /**
             * The renderer is a separate process, and on a ~2GB Firestick the
             * low-memory killer does take it out under load. Without this
             * override Android's default is to kill the app process along with
             * it -- the "app just closed itself" crash. Rebuild the WebView and
             * reload where we were instead.
             */
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "Renderer gone (crashed=${detail?.didCrash()}), rebuilding WebView")
                val lastUrl = webView.url ?: homeUrl
                if (customView != null) exitCustomView()
                root.removeView(webView)
                webView.destroy()
                createWebView()
                webView.loadUrl(if (isHomePath(lastUrl)) homeUrl else lastUrl)
                return true
            }

            // No more blocking images on non-home pages to save memory while
            // watching: it was decided per full page load, so any page loaded
            // directly (fishtank's /episodes, say) lost all its thumbnails,
            // while the client-side navigations it was meant for never
            // triggered it at all.
            override fun onPageFinished(view: WebView?, url: String?) {
                injectPageScripts(view, url)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null && BLOCKED_URL_SUBSTRINGS.any { url.contains(it, ignoreCase = true) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                request?.let { thumbCache.intercept(it) }?.let { return it }
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

        // Above the overlay holder (index 0) whenever it exists.
        val index = if (overlayHolder?.parent == root) 1 else 0
        root.addView(webView, index, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /**
     * Works around Amazon WebView's broken video overlay on Fire TV.
     *
     * The Amazon WebView hands the first large playing video in the app a
     * hardware overlay layer (a SurfaceView behind the window) -- one per
     * app ("AWV Unable to create overlay; MAX_OVERLAYS limit hit" for any
     * other). On current Fire OS that overlay is never actually connected
     * to the decoder ("SurfaceView has 0x0 dimensions after addView" /
     * "configureVideo - Not using SurfaceView from MediaCodecBridge"), so
     * whichever video gets it plays -- audio, clock advancing -- with no
     * picture: black, or a grey hole once the page is transparent. Every
     * *other* video falls back to normal compositing and draws fine. mde's
     * episode player was usually first in line; fishtank's only got lucky.
     *
     * So this hidden WebView, stacked under the real one and started before
     * it loads anything, starts a tiny black clip (720p, 1fps of solid
     * black) and pauses it, purely to occupy that one overlay slot while
     * the app is in the foreground. It lives outside the site's DOM
     * on purpose: an element injected into the page itself gets thrown
     * away whenever the site's React app re-renders.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createOverlayHolder() {
        holderReady = false
        val holder = WebView(this)
        holder.setBackgroundColor(Color.BLACK)
        holder.settings.mediaPlaybackRequiresUserGesture = false
        holder.settings.javaScriptEnabled = true
        holder.isFocusable = false
        // Tells us once the clip is actually playing, i.e. holds the slot.
        holder.addJavascriptInterface(object {
            @JavascriptInterface
            fun ready() = runOnUiThread { onHolderReady() }
        }, "FtvHolder")
        holder.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                root.removeView(holder)
                holder.destroy()
                overlayHolder = null
                createOverlayHolder()
                return true
            }
        }
        overlayHolder = holder
        // 8px square and paused as soon as it starts. Both measured on the
        // Firestick: the slot is decided by the clip's resolution, not how
        // big it's drawn, and is kept while paused (checked after 4 minutes
        // idle) -- but a full-screen holder left playing slowed every page
        // load by ~3.5s (BACK to mde's homepage: 4.6s paused vs 6.6-7.9s).
        root.addView(holder, 0, FrameLayout.LayoutParams(8, 8))
        val html = "<!doctype html><body style='margin:0;background:#000'>" +
            "<video autoplay muted playsinline " +
            "onplaying='FtvHolder.ready();var v=this;setTimeout(function(){v.pause()},300)' " +
            "style='width:100vw;height:100vh' " +
            "src='data:video/mp4;base64,$OVERLAY_HOLDER_MP4'></video></body>"
        holder.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun destroyOverlayHolder() {
        overlayHolder?.let {
            root.removeView(it)
            it.destroy()
        }
        overlayHolder = null
        holderReady = false
    }

    private fun onHolderReady() {
        if (holderReady) return
        holderReady = true
        if (resumeWhenHolderReady) {
            resumeWhenHolderReady = false
            webView.onResume()
        }
    }

    /** A genuine touch at (x, y) in WebView pixels -- see __ftvActivate. */
    private fun tap(x: Float, y: Float) {
        webView.requestFocus()
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(t, t, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    /**
     * Screen stays on only while a video is actually playing, so the Fire
     * TV can dim and start its screensaver when the app sits on a menu or a
     * paused episode (it used to hold the screen on the whole time).
     * Polled: screensaver timeouts are minutes, so 15s is plenty.
     */
    private val screenPoll = object : Runnable {
        override fun run() {
            webView.evaluateJavascript("window.__ftvIsPlaying ? window.__ftvIsPlaying() : false") { r ->
                if (r == "true") window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            root.postDelayed(this, 15_000)
        }
    }

    /** The remote's Menu (☰) button: app-level actions. */
    private fun showAppMenu() {
        // Subtitles entry only when the playing video has a subtitle track.
        webView.evaluateJavascript("window.__ftvCaptions ? window.__ftvCaptions('state') : 'none'") { r ->
            val captions = r?.trim('"') ?: "none"
            val items = mutableListOf("Check for updates", "Reload page", "Reset cursor")
            if (phoneRemote != null) items.add(0, "Use your phone as a remote")
            if (captions != "none") items.add(0, if (captions == "on") "Subtitles: turn off" else "Subtitles: turn on")
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}")
                .setItems(items.toTypedArray()) { _, which ->
                    when (items[which]) {
                        "Check for updates" -> updateChecker.checkNow()
                        "Reload page" -> webView.reload()
                        "Reset cursor" -> js("window.__ftvReset && window.__ftvReset()")
                        "Use your phone as a remote" -> showPhoneRemote()
                        else -> js("window.__ftvCaptions && window.__ftvCaptions('toggle')")
                    }
                }
                .show()
        }
    }

    /** The QR code for the phone remote page, with its address underneath. */
    private fun showPhoneRemote() {
        val remote = phoneRemote ?: return
        val url = remote.url()
        if (url == null) {
            Toast.makeText(this, "Not connected to Wi-Fi", Toast.LENGTH_LONG).show()
            return
        }
        val qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM)
        val scale = 10
        val border = 2
        val px = (qr.size + border * 2) * scale
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)
        bmp.eraseColor(Color.WHITE)
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (!qr.getModule(x, y)) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                bmp.setPixel((x + border) * scale + dx, (y + border) * scale + dy, Color.BLACK)
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 30, 40, 10)
            addView(ImageView(this@MainActivity).apply { setImageBitmap(bmp) },
                LinearLayout.LayoutParams(px, px))
            addView(TextView(this@MainActivity).apply {
                text = "Scan with your phone's camera. Your phone must be on the same Wi-Fi as the TV.\n\n" +
                    url.substringBefore("/?")
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            })
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Use your phone as a remote")
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showError(show: Boolean) {
        errorShown = show
        errorView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun exitCustomView() {
        (window.decorView as? ViewGroup)?.removeView(customView)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        hideSystemUi()
    }

    /**
     * Leaves a watch page for the homepage.
     *
     * fishtank: through the page (window.__ftvGoHome in dpad.js) -- its
     * pages are client-side routes of one Next.js app, so going back that
     * way reuses the booted app.
     *
     * mde: a plain reload. Opening an mde episode is a full page load, so
     * the homepage is gone either way, and measured on the Firestick the
     * reload (~4.3-5.2s) beat both a client-side route via its home link
     * (~7.3s: the server re-renders the homepage first) and history.back
     * (~4.5s). Its homepage doesn't qualify for the WebView's back/forward
     * cache either (tried: it reloads anyway).
     */
    private fun goHome() {
        if (homeUrl.contains("mde.tv")) {
            webView.loadUrl(homeUrl)
            return
        }
        webView.evaluateJavascript("window.__ftvGoHome ? window.__ftvGoHome() : false") { result ->
            if (result != "true") webView.loadUrl(homeUrl)
        }
    }

    /**
     * Routes through window.__ftvSeek() in dpad.js rather than touching a
     * <video> element directly -- both sites' actual watch pages embed
     * their player as a cross-origin Bunny Stream iframe, not a same-origin
     * <video>, so seeking has to go through window.postMessage
     * (player.js protocol) there instead of a property write.
     */
    private fun seekVideo(deltaSeconds: Int) {
        js("window.__ftvScrub && window.__ftvScrub($deltaSeconds)")
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
        KeyEvent.KEYCODE_BACK -> true
        else -> false
    }

    private var selectHeld = false
    private var pendingTap: Pair<Float, Float>? = null

    private fun isSelectKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isSelectKey(event.keyCode)) {
            selectHeld = event.action == KeyEvent.ACTION_DOWN
            if (event.action == KeyEvent.ACTION_UP) {
                pendingTap?.let { (x, y) -> webView.postDelayed({ tap(x, y) }, TAP_DELAY_MS) }
                pendingTap = null
            }
        }
        if (isHandledKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                return onKeyDown(event.keyCode, event)
            }
            return true // swallow ACTION_UP too, so the WebView never sees any part of it
        }
        return super.dispatchKeyEvent(event)
    }

    /** Seek step grows the longer FF/RW (or left/right on a watch page) is
     *  held, so skipping through an hour-long episode isn't 360 presses. */
    private fun seekStep(event: KeyEvent?): Int {
        val repeat = event?.repeatCount ?: 0
        return when {
            repeat < 6 -> 10
            repeat < 20 -> 30
            else -> 60
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (errorShown) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    showError(false)
                    webView.reload()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
            }
            return true
        }
        val step = seekStep(event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { js("window.__ftvNavigate && window.__ftvNavigate('up', $step)"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { js("window.__ftvNavigate && window.__ftvNavigate('down', $step)"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { js("window.__ftvNavigate && window.__ftvNavigate('left', $step)"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { js("window.__ftvNavigate && window.__ftvNavigate('right', $step)"); return true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                webView.evaluateJavascript("window.__ftvActivate && window.__ftvActivate()") { result ->
                    Regex("""tap:(\d+),(\d+)""").find(result ?: "")?.let {
                        val x = it.groupValues[1].toFloat()
                        val y = it.groupValues[2].toFloat()
                        // Open the keyboard only once Select is released:
                        // otherwise the key-up lands on the just-opened
                        // keyboard, which "presses" its highlighted key and
                        // types a stray "a" into every field.
                        if (selectHeld) pendingTap = x to y else webView.postDelayed({ tap(x, y) }, TAP_DELAY_MS)
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { js("window.__ftvTogglePlay && window.__ftvTogglePlay()"); return true }

            KeyEvent.KEYCODE_MENU -> { showAppMenu(); return true }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekVideo(step); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekVideo(-step); return true }

            KeyEvent.KEYCODE_BACK -> {
                if ((event?.repeatCount ?: 0) > 0) return true
                if (customView != null) {
                    customViewCallback?.onCustomViewHidden()
                    exitCustomView()
                    return true
                }
                // A scrub in progress: BACK cancels it, and only that.
                webView.evaluateJavascript("window.__ftvScrubCancel ? window.__ftvScrubCancel() : false") { r ->
                    if (r != "true") leavePage()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun leavePage() {
                // Belt-and-suspenders alongside disablePictureInPicture in
                // dpad.js: a paused video can't trigger auto-PiP on navigate-away.
                // Covers both a same-origin <video> and (the common case) a
                // cross-origin Bunny iframe player, which only responds to
                // pause via postMessage, not document.querySelectorAll.
                js("window.__ftvPause && window.__ftvPause()")
                if (!isHomePath(webView.url)) {
                    // From an episode, to the page it was opened from (the
                    // PSC page, a show, fishtank's Episodes page -- see
                    // window.__ftvBack in dpad.js); from anywhere else, the
                    // actual homepage. Never "back" through WebView history:
                    // each video pushes a history entry, so goBack() walked
                    // back through previously watched videos.
                    webView.evaluateJavascript("window.__ftvBack ? window.__ftvBack() : false") { r ->
                        if (r != "true") goHome()
                    }
                } else {
                    // Never goBack() from the homepage either: after watching
                    // anything, history is [home, video, home], so that went
                    // straight back into the last video and BACK could never
                    // exit the app. Double-press to exit instead.
                    val now = SystemClock.uptimeMillis()
                    if (now - lastHomeBackPress < EXIT_CONFIRM_MS) {
                        finish()
                    } else {
                        lastHomeBackPress = now
                        Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
    }

    override fun onPause() {
        super.onPause()
        // Pressing Home left the video playing (audio and decoder) in the
        // background, since WebView.onPause doesn't pause media.
        js("window.__ftvPause && window.__ftvPause()")
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onStart() {
        super.onStart()
        if (overlayHolder == null) createOverlayHolder()
        root.post(screenPoll)
        phoneRemote?.start()
    }

    /**
     * Gives the overlay holder's decoder back while we're in the background
     * -- the Firestick only has a few hardware decoders, and a holder still
     * looping behind another app (a casting receiver, say) can leave that
     * app without one. onStart brings it back.
     */
    override fun onStop() {
        super.onStop()
        destroyOverlayHolder()
        root.removeCallbacks(screenPoll)
        phoneRemote?.stop()
    }

    override fun onResume() {
        super.onResume()
        // Resume the page only once the holder is playing again: a video
        // that resumes first would grab the broken overlay slot (see
        // createOverlayHolder) and play black. Falls back after 3s rather
        // than leave the page frozen if the holder never reports in.
        if (holderReady) {
            webView.onResume()
        } else {
            resumeWhenHolderReady = true
            root.postDelayed({
                if (resumeWhenHolderReady) {
                    resumeWhenHolderReady = false
                    webView.onResume()
                }
            }, 3000)
        }
        hideSystemUi()
        updateChecker.onResume()
        if (launchedCustomTab) {
            launchedCustomTab = false
            webView.reload()
        }
    }

    override fun onDestroy() {
        updateChecker.onDestroy()
        destroyOverlayHolder()
        webView.destroy()
        super.onDestroy()
    }
}
