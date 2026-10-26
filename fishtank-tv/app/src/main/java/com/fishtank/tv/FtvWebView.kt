package com.fishtank.tv

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView

/**
 * WebView that turns the Fire TV keyboard's action key into "submit".
 *
 * The Fire TV keyboard always shows Previous/Next, whatever the field asks
 * for, and in a page with a single text box (a search bar) Next does
 * nothing -- so there was no way to run a search from the keyboard. Any
 * editor action now submits the focused field's form (or clicks its submit
 * button) and closes the keyboard.
 */
class FtvWebView(context: Context) : WebView(context) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun performEditorAction(editorAction: Int): Boolean {
                post { submitFocusedField() }
                return true
            }
        }
    }

    private fun submitFocusedField() {
        evaluateJavascript(SUBMIT_JS, null)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    private companion object {
        const val SUBMIT_JS = """
(function(){
  var el = document.activeElement;
  if (!el || !/^(INPUT|TEXTAREA)$/.test(el.tagName)) return;
  var form = el.form;
  if (form) {
    if (form.requestSubmit) form.requestSubmit(); else form.submit();
    return;
  }
  // No form: press Enter in the field, then try a nearby submit/search button.
  ['keydown', 'keypress', 'keyup'].forEach(function(t){
    el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
  });
  for (var p = el.parentElement, i = 0; p && i < 3; p = p.parentElement, i++) {
    var b = p.querySelector('button[type="submit"], button[class*="ubmit"], button[aria-label*="earch"]');
    if (b) { b.click(); return; }
  }
})();
"""
    }
}
