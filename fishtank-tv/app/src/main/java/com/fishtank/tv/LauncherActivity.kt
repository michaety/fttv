package com.fishtank.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First screen on launch: pick a site.
 * Beats maintaining two separate APKs for two WebView shells.
 */
class LauncherActivity : Activity() {

    companion object {
        const val FISHTANK_URL = "https://www.fishtank.live"
        const val MDE_URL = "https://mde.tv"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(48), dp(48), dp(48), dp(48))
        }

        val heading = TextView(this).apply {
            text = "Choose a stream"
            setTextColor(Color.parseColor("#FFB000"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(40))
        }
        root.addView(heading)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val fishtank = siteButton("fishtank.live", FISHTANK_URL)
        val mde = siteButton("mde.tv", MDE_URL)

        row.addView(fishtank)
        row.addView(mde)
        root.addView(row)

        val hint = TextView(this).apply {
            text = "D-pad to move  \u2022  Select to open  \u2022  Back to return here"
            setTextColor(Color.parseColor("#777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, 0)
        }
        root.addView(hint)

        setContentView(root)
        fishtank.requestFocus()
    }

    private fun siteButton(label: String, url: String): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            isAllCaps = false
            background = buttonBackground()
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(140)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
            setOnClickListener { open(url) }
        }
    }

    private fun buttonBackground(): StateListDrawable {
        fun box(fill: String, stroke: String, strokeWidth: Int) = GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(strokeWidth), Color.parseColor(stroke))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box("#1E1E1E", "#FFB000", 4))
            addState(intArrayOf(android.R.attr.state_pressed), box("#2A2A2A", "#FFB000", 4))
            addState(intArrayOf(), box("#141414", "#333333", 2))
        }
    }

    private fun open(url: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_URL, url))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
