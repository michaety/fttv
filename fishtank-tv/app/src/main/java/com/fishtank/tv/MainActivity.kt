package com.fishtank.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
        private const val PREF_BLOCK_IMAGES = "block_images"

        /** Second BACK on the homepage within this window exits the app. */
        private const val EXIT_CONFIRM_MS = 2000L

        /**
         * 2s of black, 1280x720 @ 1fps H.264 -- see createOverlayHolder. It has
         * to be a "real" resolution: the WebView only hands its overlay to
         * videos above some size, and ignored a 128x72 version entirely. Black
         * frames at 1fps still cost next to nothing to decode.
         */
        private const val OVERLAY_HOLDER_MP4 = "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMZbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAB9AAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAkN0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAB9AAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAABQAAAALQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAfQAAAAAAABAAAAAAG7bWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAgABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABZm1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAASZzdGJsAAAAunN0c2QAAAAAAAAAAQAAAKphdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAABQAC0ABIAAAASAAAAAAAAAABFUxhdmM2Mi4yOC4xMDEgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAAMGF2Y0MBTUAf/+EAGGdNQB/cBQBbsBEAAAMAAQAAAwACDxgzgAEABWjuDyyAAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAEFAAAAAAAAAAGHN0dHMAAAAAAAAAAQAAAAIAAEAAAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAACAAAAAQAAABxzdHN6AAAAAAAAAAAAAAACAAADOwAAANkAAAAUc3RjbwAAAAAAAAABAAADSQAAAGJ1ZHRhAAAAWm1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALWlsc3QAAAAlqXRvbwAAAB1kYXRhAAAAAQAAAABMYXZmNjIuMTIuMTAxAAAACGZyZWUAAAQcbWRhdAAAAl8GBf//W9xF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjUgcjMyMjMgMDQ4MGNiMCAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0xIHJlZj0xIGRlYmxvY2s9MTowOjAgYW5hbHlzZT0weDE6MHgxMTEgbWU9aGV4IHN1Ym1lPTcgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MCBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTEgOHg4ZGN0PTAgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0xMiBsb29rYWhlYWRfdGhyZWFkcz0yIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0xIGtleWludF9taW49MSBzY2VuZWN1dD00MCBpbnRyYV9yZWZyZXNoPTAgcmM9Y3JmIG1idHJlZT0wIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAAA1GWIhAS//vet34FNwys1a7pXOLTLq5Q0PVH2lKZ4tkgAAAMAAAMAAAMAAAMAxia0ObURw4f6DwAAAwAAAwHyAAGMAAIqAAQsAA1AADJAAOIAA/gAEyAAhoAD7AAYoADrAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAAADAANnAAAA1WWIggF//vfUt8yy7gcitguo96KeJl9DdSUBm5bE7TqAAAADAAADAAADAAADAVNsHhpL9jWzTwkAAAMAAAMB9gABLgABxgAEMAAKuAAj4ACKgAJ0AAsgAEcAAXwAC8AARAACIAAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwAAAwB+wA=="

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
    private var blockImages: Boolean = false
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
                /* search button */
                div[class*="mobileSearch"] { display: none !important; }
                /* "want to be on one of the shows?" call-in promo (logged-in
                   only). Also a 942px animated GIF that never stops
                   decoding, so it costs CPU as well as space. */
                button[class*="_callInWidget"] { display: none !important; }
                /* notification bell next to the logo */
                button[class*="notification-center-module"], button[class*="_notificationCenter"] { display: none !important; }
                /* the account/login control's right-alignment depended on
                   the (now-hidden) search wrapper's margin-left:auto --
                   without that it collapsed to sit right next to the logo */
                div[class*="_topBar"] > div[class*="_user"] { margin-left: auto !important; }
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
     * by text). Idempotent — safe to call repeatedly. DPAD_JS re-invokes it
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
                  // MutationObserver tick in DPAD_JS.
                  document.querySelectorAll('button').forEach(function(b){
                    if (b.textContent.trim() === 'Merch') b.style.display = 'none';
                  });
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
     * Injects the site CSS, cleanup hook and DPAD_JS. Idempotent (the style
     * tag is id-guarded, DPAD_JS guards on window.__ftvReady), so it's run
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
        val dpadJs = DPAD_JS
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

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        blockImages = prefs.getBoolean(PREF_BLOCK_IMAGES, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        updateChecker.checkInBackground()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Builds (or, after a renderer crash, rebuilds) the WebView and inserts
     * it at the bottom of root, under the progress bar and error view.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = false
            useWideViewPort = false
            userAgentString = desktopUserAgent()
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
                exitCustomView()
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Lift the watch-page image block *before* the homepage
                // starts loading, not in onPageFinished: otherwise coming
                // BACK from a video loaded the whole homepage imageless and
                // only began fetching every thumbnail after it "finished".
                if (isHomePath(url)) view?.settings?.blockNetworkImage = blockImages
                showError(false)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                injectPageScripts(view, url)
            }

            // Fires for client-side (pushState) navigations too, which never
            // reach onPageStarted -- both sites are Next.js SPAs.
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (isHomePath(url)) view?.settings?.blockNetworkImage = blockImages
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

            override fun onPageFinished(view: WebView?, url: String?) {
                // A modest, honest mitigation, not a fix for video-decode CPU
                // itself: dumpsys cpuinfo during playback showed kswapd0 (the
                // kernel's memory-swap daemon) alone at ~24% CPU on this
                // device's 1.7GB RAM, so anything that trims memory pressure
                // during playback is worth doing even though it can't touch
                // the actual decode cost. The homepage's own images have no
                // reason to keep loading/decoding while a video page is
                // showing; restore the user's real preference once back on
                // the homepage.
                val isWatchPage = url != null && !isHomePath(url)
                view?.settings?.blockNetworkImage = isWatchPage || blockImages
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
     * it loads anything, plays a tiny black clip (720p but 1fps of solid
     * black -- next to no decode cost) full-screen on a loop, purely to occupy that one
     * overlay slot while the app is in the foreground. It lives outside the site's DOM
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
        root.addView(holder, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val html = "<!doctype html><body style='margin:0;background:#000'>" +
            "<video autoplay muted loop playsinline onplaying='FtvHolder.ready()' style='width:100vw;height:100vh' " +
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
     * Leaves a watch page for the homepage. Tries the site's own home link
     * first (window.__ftvGoHome in DPAD_JS) -- both sites are Next.js SPAs,
     * so that's a client-side route change that reuses the already-booted
     * app instead of re-downloading and re-parsing the whole bundle the way
     * loadUrl does, which was the multi-second black-screen on every BACK.
     * Falls back to a full load if the script isn't there or no link matched.
     */
    private fun goHome() {
        webView.evaluateJavascript("window.__ftvGoHome ? window.__ftvGoHome() : false") { result ->
            if (result != "true") webView.loadUrl(homeUrl)
        }
    }

    /**
     * Routes through window.__ftvSeek() in DPAD_JS rather than touching a
     * <video> element directly -- both sites' actual watch pages embed
     * their player as a cross-origin Bunny Stream iframe, not a same-origin
     * <video>, so seeking has to go through window.postMessage
     * (player.js protocol) there instead of a property write.
     */
    private fun seekVideo(deltaSeconds: Int) {
        js("window.__ftvSeek && window.__ftvSeek($deltaSeconds)")
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

            KeyEvent.KEYCODE_MENU -> { js("window.__ftvReset && window.__ftvReset()"); return true }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekVideo(step); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekVideo(-step); return true }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                toggleBlockImages()
                // It's persisted, so a stray press used to silently leave the
                // whole app imageless across restarts with no hint why.
                Toast.makeText(this, if (blockImages) "Images off" else "Images on", Toast.LENGTH_SHORT).show()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if ((event?.repeatCount ?: 0) > 0) return true
                if (customView != null) {
                    customViewCallback?.onCustomViewHidden()
                    exitCustomView()
                    return true
                }
                // Belt-and-suspenders alongside disablePictureInPicture in
                // DPAD_JS: a paused video can't trigger auto-PiP on navigate-away.
                // Covers both a same-origin <video> and (the common case) a
                // cross-origin Bunny iframe player, which only responds to
                // pause via postMessage, not document.querySelectorAll.
                js("window.__ftvPause && window.__ftvPause()")
                if (!isHomePath(webView.url)) {
                    // Always go to the actual homepage, never "back" through
                    // WebView history: each video pushes a history entry, so
                    // goBack() walked back through previously watched videos.
                    goHome()
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
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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

/**
 * Injected on every page load. Gives the site a synthetic focus cursor driven
 * by the remote: geometric nearest-neighbour search in the pressed direction,
 * amber ring on whatever is selected. This is the mouse you would otherwise
 * be plugging into the Firestick.
 */
private const val DPAD_JS = """
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
    }
  });

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
      if (s.zIndex !== 'auto' && s.position !== 'static') pinStyle(a, 'z-index', '2147483647');
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
    el.style.setProperty('z-index', '2147483647', 'important');
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
  setTimeout(checkPlayerFrame, 0);
  setTimeout(checkPlayerFrame, 100);
  setTimeout(checkPlayerFrame, 300);

  // Minimal on-screen feedback for seek/play-pause -- cheap now that it
  // targets the already-tracked video/iframe directly rather than the old
  // design's full-page video scan on every keypress (that scan, not the
  // overlay itself, was what got removed for performance earlier).
  var seekOverlayEl = null, seekOverlayTimeEl = null, seekOverlayFillEl = null, seekOverlayHideTimer = null;
  var seekOverlayVisible = false;

  function currentPlaybackInfo(){
    if (trackedVideo && document.contains(trackedVideo)) {
      return { time: trackedVideo.currentTime, duration: trackedVideo.duration || 0 };
    }
    return { time: playerState.time, duration: playerState.duration };
  }

  function fmtSeekTime(t){
    if (!isFinite(t) || t < 0) t = 0;
    var m = Math.floor(t / 60), s = Math.floor(t % 60);
    return m + ':' + (s < 10 ? '0' : '') + s;
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
      '#__ftv_seek_fill{height:100%;width:0%;background:__FTV_ACCENT_HEX__;}';
    (document.head || document.documentElement).appendChild(style);

    seekOverlayEl = document.createElement('div');
    seekOverlayEl.id = '__ftv_seek';
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
    seekOverlayTimeEl.textContent = fmtSeekTime(info.time) + ' / ' + fmtSeekTime(info.duration);
    seekOverlayFillEl.style.width = (info.duration ? (info.time / info.duration * 100) : 0) + '%';
  }

  function showSeekOverlay(){
    renderSeekOverlay();
    seekOverlayEl.classList.add('__ftv_show');
    seekOverlayVisible = true;
    if (seekOverlayHideTimer) clearTimeout(seekOverlayHideTimer);
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
  function pickDefault(list){
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
      if (st.overflowX !== 'visible') {
        L = Math.max(L, ar.left + a.clientLeft);
        R = Math.min(R, ar.left + a.clientLeft + a.clientWidth);
      }
      if (st.overflowY !== 'visible') {
        T = Math.max(T, ar.top + a.clientTop);
        B = Math.min(B, ar.top + a.clientTop + a.clientHeight);
      }
    }
    var z = Math.min(ZOOM, (R - L) / r.width, (B - T) / r.height);
    if (!(z >= 1.02)) z = 1;
    // Per axis: where the zoomed box would ideally sit (centred), pushed
    // back inside [lo, hi], expressed as the origin that produces it.
    function origin(pos, size, lo, hi){
      if (z === 1) return size / 2;
      var ns = size * z;
      var want = Math.max(lo, Math.min(pos - (ns - size) / 2, hi - ns));
      return Math.max(0, Math.min(size, (pos - want) / (z - 1)));
    }
    curZoom = z;
    curOx = origin(r.left, r.width, L, R);
    curOy = origin(r.top, r.height, T, B);
  }

  function zoomOn(el){
    savedScale = [el.style.getPropertyValue('scale'), el.style.getPropertyPriority('scale')];
    savedOrigin = [el.style.getPropertyValue('transform-origin'), el.style.getPropertyPriority('transform-origin')];
    el.classList.add('__ftv_focus');
    el.style.setProperty('transform-origin', curOx + 'px ' + curOy + 'px', 'important');
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
      if (dir === 'left') window.__ftvSeek(-(step || 10));
      else if (dir === 'right') window.__ftvSeek(step || 10);
      else showSeekOverlay();
      return;
    }
    if (silentCur) { mark(cur); return; }        // first press just reveals it
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
  window.__ftvGoHome = function(){
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
    if (playingVideo()) { window.__ftvTogglePlay(); return; }
    if (silentCur) { mark(cur); return; }        // first press just reveals it
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
"""
