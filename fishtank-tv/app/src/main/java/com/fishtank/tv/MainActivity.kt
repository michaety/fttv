package com.fishtank.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"

        /**
         * Desktop Chrome UA. The site serves the phone layout to a mobile UA,
         * which is the wrong shape for a 10-foot screen and gives fewer
         * focusable elements for the D-pad to land on.
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var homeUrl: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        homeUrl = intent.getStringExtra(EXTRA_URL) ?: LauncherActivity.FISHTANK_URL

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = DESKTOP_UA
            cacheMode = WebSettings.LOAD_DEFAULT
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
                view?.evaluateJavascript(DPAD_JS, null)
            }
        }

        setContentView(webView)
        hideSystemUi()
        webView.loadUrl(homeUrl)
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

            KeyEvent.KEYCODE_BACK -> {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Drop back to the site picker rather than killing the app.
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

  var style = document.createElement('style');
  style.textContent = '.__ftv_focus{outline:3px solid #FFB000 !important;' +
                      'outline-offset:2px !important;' +
                      'box-shadow:0 0 14px rgba(255,176,0,.85) !important;}';
  (document.head || document.documentElement).appendChild(style);

  var cur = null;

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

  function items(){
    var all = document.querySelectorAll(SEL);
    var out = [];
    for (var i = 0; i < all.length; i++) { if (visible(all[i])) out.push(all[i]); }
    return out;
  }

  function centre(el){
    var r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  function mark(el){
    if (cur) { cur.classList.remove('__ftv_focus'); }
    cur = el || null;
    if (!cur) return;
    cur.classList.add('__ftv_focus');
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

  window.__ftvNavigate = function(dir){
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

  // Single-page apps swap the DOM without a page load, so re-acquire focus
  // if the current target disappears.
  setInterval(function(){
    if (cur && (!document.contains(cur) || !visible(cur))) { window.__ftvReset(); }
  }, 1500);

  setTimeout(function(){ if (!cur) { window.__ftvReset(); } }, 1200);
})();
"""
