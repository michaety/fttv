package com.fishtank.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sideloaded apps never hear about new versions, so this asks GitHub for the
 * latest release and, if it's newer than the running build, downloads the
 * flavor's APK (fishtank-tv.apk / mde-tv.apk -- the asset names the
 * README's download links rely on too) *before* asking, so "Install" goes
 * straight to Fire OS's own confirmation with no wait. Releases are signed
 * with the same key, so it installs over the top and the login survives.
 *
 * It hands the APK to Fire OS's installer screen rather than committing a
 * PackageInstaller session, because that screen ends with "Open". The update
 * kills the app, and nothing else can bring it back: Android 10+ blocks a
 * freshly started process from opening itself (tried MY_PACKAGE_REPLACED and
 * a session status PendingIntent -- both "Abort background activity start").
 *
 * Fully silent updates aren't possible here: Android only allows them from
 * 12 (API 31), and Fire OS 8 is Android 11 -- one INSTALL press is the
 * minimum.
 *
 * Fire OS also requires a one-time "allow this app to install apps" grant;
 * if it's missing at Install time, the user is sent to that settings screen
 * and the install carries on when they come back (onResume).
 */
class UpdateChecker(private val activity: Activity) {

    companion object {
        private const val TAG = "FishtankTV"
        private const val LATEST_URL = "https://api.github.com/repos/michaety/fttv/releases/latest"
        private const val RELEASES_URL = "https://api.github.com/repos/michaety/fttv/releases?per_page=30"
        private const val PREFS_NAME = "fishtank_tv_prefs"
        private const val PREF_SKIPPED_VERSION = "update_skipped_version"
        /** "Later" (or Back) holds off the launch prompt for a day. */
        private const val PREF_SNOOZED_UNTIL = "update_snoozed_until"
        private const val SNOOZE_MS = 24 * 60 * 60 * 1000L

        /** True if dotted version [a] (e.g. "1.2.0") is newer than [b]. */
        fun isNewer(a: String, b: String): Boolean {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }

    /** [notes]: what's new since the installed version, as HTML (see [fetchNotes]). */
    private data class Release(val version: String, val apkUrl: String, val notes: String = "")

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    /** Where the APK waits between download and install (res/xml/update_paths.xml). */
    private val apkFile get() = File(File(activity.cacheDir, "update"), "update.apk")

    @Volatile private var busy = false
    /** Downloaded, waiting on the install permission. */
    private var awaitingPermission: Release? = null

    /** On launch: quiet unless there's an update (and it isn't skipped). */
    fun checkInBackground() = check(manual = false)

    /** From the app menu: always reports back, and ignores "skip". */
    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) toast("Checking for updates…")
        Thread {
            try {
                // An update can land without this process being killed (seen on
                // Fire OS 8), leaving the old code running with its old
                // BuildConfig -- which would offer the same update again.
                // Restart into the installed version instead.
                if (installedVersionCode() > BuildConfig.VERSION_CODE) {
                    ui { restart() }
                    return@Thread
                }
                // Fire OS's installer doesn't tell us when it's done; by now it is.
                if (awaitingPermission == null) discard()
                val installed = installedVersionName()
                val release = fetchLatest()
                when {
                    release == null -> if (manual) ui { toast("Couldn't reach GitHub. Try again later.") }
                    !isNewer(release.version, installed) ->
                        if (manual) ui { toast("You're up to date ($installed).") }
                    !manual && prefs.getString(PREF_SKIPPED_VERSION, null) == release.version -> Unit
                    !manual && System.currentTimeMillis() < prefs.getLong(PREF_SNOOZED_UNTIL, 0) -> Unit
                    else -> {
                        if (manual) ui { toast("Downloading ${release.version}…") }
                        download(release)
                        val withNotes = release.copy(notes = fetchNotes(installed, release.version))
                        ui { offer(withNotes) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (manual) ui { toast("Update failed. Try again later.") }
            } finally {
                busy = false
            }
        }.start()
    }

    /** Call from Activity.onResume, to carry on after the permission screen. */
    fun onResume() {
        val release = awaitingPermission ?: return
        awaitingPermission = null
        if (canInstall()) {
            launchInstaller()
        } else {
            discard()
            toast("Update ${release.version} not installed: the install permission wasn't allowed.")
        }
    }

    @Suppress("DEPRECATION")
    private fun installedPackage() = activity.packageManager.getPackageInfo(activity.packageName, 0)

    private fun installedVersionName(): String =
        try { installedPackage().versionName } catch (e: Exception) { null } ?: BuildConfig.VERSION_NAME

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Int =
        try { installedPackage().versionCode } catch (e: Exception) { BuildConfig.VERSION_CODE }

    /** Relaunches into a fresh process, which loads the installed APK. */
    private fun restart() {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun fetchLatest(): Release? {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == BuildConfig.UPDATE_APK_NAME) {
                    return Release(version, asset.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The release notes of every version after [installed] up to [latest],
     * newest first, so skipping a few versions still shows everything that
     * changed. The notes are the GitHub release text (bold headlines, "- "
     * lists), turned into simple HTML. Empty if GitHub can't be reached.
     */
    private fun fetchNotes(installed: String, latest: String): String = try {
        val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) "" else {
                val list = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                val notes = (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                    .map { it.getString("tag_name").removePrefix("v") to it.optString("body") }
                    .filter { (v, _) -> isNewer(v, installed) && !isNewer(v, latest) }
                    .sortedWith { a, b -> if (isNewer(a.first, b.first)) -1 else if (isNewer(b.first, a.first)) 1 else 0 }
                notes.joinToString("") { (v, body) ->
                    (if (notes.size > 1) "<big><b>$v</b></big><br>" else "") + notesHtml(body)
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't fetch release notes", e)
        ""
    }

    /** GitHub's markdown, as far as the release notes use it, to HTML. */
    private fun notesHtml(body: String): String {
        val lines = body.replace("\r", "").split("\n").map { it.trim() }
            // The download line is for the GitHub page, not the TV.
            .filter { it.isNotEmpty() && !it.startsWith("Install:") }
        val bullet = { i: Int -> lines.getOrNull(i)?.startsWith("- ") == true }
        return lines.indices.joinToString("") { i ->
            val t = Html.escapeHtml(if (bullet(i)) lines[i].substring(2) else lines[i])
                .replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>").replace("`", "")
            // A blank line between paragraphs, but a list sits right under
            // its heading and its items under each other.
            (if (bullet(i)) "&#8226; $t" else t) + if (bullet(i + 1)) "<br>" else "<br><br>"
        }
    }

    /** Downloads the APK to [apkFile]. Off the UI thread. */
    private fun download(release: Release) {
        val tmp = File(apkFile.parentFile, "update.apk.part")
        tmp.parentFile?.mkdirs()
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            apkFile.delete()
            if (!tmp.renameTo(apkFile)) throw IOException("Couldn't save the update")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun offer(release: Release) {
        if (activity.isFinishing) { discard(); return }
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        val text = { sp: Float, s: CharSequence -> TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setLineSpacing(0f, 1.15f)
            setText(s)
        } }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(text(15f, "Version ${release.version} is downloaded (you have ${BuildConfig.VERSION_NAME}). " +
                "Install it now? You'll stay signed in.\n\n" +
                // Fire OS's confirmation opens with CANCEL focused, so a
                // reflexive Select throws the update away.
                "When Fire TV asks, move right to INSTALL, then choose OPEN."))
            if (release.notes.isNotEmpty()) {
                addView(text(17f, "What's new").apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(18), 0, dp(6))
                })
                @Suppress("DEPRECATION")
                addView(text(14f, Html.fromHtml(release.notes).trimEnd()))
            }
        }
        // Focusable, so Up from the buttons reaches it and Up/Down scroll
        // long notes (Down at the bottom goes back to the buttons).
        val scroll = ScrollView(activity).apply {
            isFocusable = true
            addView(body)
        }
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Update ready")
            .setView(scroll)
            .setPositiveButton("Install") { _, _ -> install(release) }
            .setNegativeButton("Later") { _, _ -> snooze() }
            .setNeutralButton("Skip this version") { _, _ ->
                prefs.edit().putString(PREF_SKIPPED_VERSION, release.version).apply()
                discard()
            }
            .setOnCancelListener { snooze() }
            .create().apply {
                // Start on Install, as before the notes (they'd take focus
                // first). Once shown, or the dialog's own first focus wins.
                setOnShowListener { scroll.post { getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() } }
                show()
            }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun install(release: Release) {
        if (canInstall()) {
            launchInstaller()
            return
        }
        awaitingPermission = release
        toast("Turn on \"Allow\" for ${activity.getString(R.string.app_name)}, then press BACK")
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) {
            awaitingPermission = null
            discard()
            toast("Allow installs for this app under Settings > My Fire TV > Developer Options")
        }
    }

    /** Fire OS's installer: confirm, install, then Done / Open. */
    private fun launchInstaller() {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update", apkFile)
            @Suppress("DEPRECATION")
            activity.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update install failed", e)
            discard()
            toast("Update failed. Try again from the Menu button.")
        }
    }

    private fun discard() {
        apkFile.delete()
    }

    /** Not now: no launch prompt for a day, however many releases come out
     *  meanwhile. The menu's "Check for updates" still offers it. */
    private fun snooze() {
        prefs.edit().putLong(PREF_SNOOZED_UNTIL, System.currentTimeMillis() + SNOOZE_MS).apply()
        discard()
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread { if (!activity.isFinishing) block() }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
