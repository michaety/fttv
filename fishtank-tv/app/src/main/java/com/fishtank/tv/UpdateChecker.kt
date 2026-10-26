package com.fishtank.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sideloaded apps never hear about new versions, so this asks GitHub for the
 * latest release and, if it's newer than the running build, downloads the
 * flavor's APK (fishtank-tv.apk / mde-tv.apk -- the asset names the
 * README's download links rely on too) straight into a PackageInstaller
 * session *before* asking, so "Install" goes straight to Fire OS's own
 * confirmation with no wait. Releases are signed with the same key, so it
 * installs over the top and the login survives.
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
        private const val PREFS_NAME = "fishtank_tv_prefs"
        private const val PREF_SKIPPED_VERSION = "update_skipped_version"
        private const val ACTION_INSTALL_STATUS = "com.fishtank.tv.UPDATE_INSTALL_STATUS"

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

    private data class Release(val version: String, val apkUrl: String)

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val installer get() = activity.packageManager.packageInstaller

    @Volatile private var busy = false
    /** Downloaded, not yet committed; waiting on the install permission. */
    private var awaitingPermission: Pair<Release, Int>? = null
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Update install failed: $msg")
                    toast("Update not installed" + (msg?.let { ": $it" } ?: ""))
                }
            }
        }
    }

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
                val release = fetchLatest()
                when {
                    release == null -> if (manual) ui { toast("Couldn't reach GitHub. Try again later.") }
                    !isNewer(release.version, BuildConfig.VERSION_NAME) ->
                        if (manual) ui { toast("You're up to date (${BuildConfig.VERSION_NAME}).") }
                    !manual && prefs.getString(PREF_SKIPPED_VERSION, null) == release.version -> Unit
                    else -> {
                        if (manual) ui { toast("Downloading ${release.version}…") }
                        val session = download(release)
                        ui { offer(release, session) }
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
        val (release, session) = awaitingPermission ?: return
        if (canInstall()) {
            awaitingPermission = null
            commit(session)
        } else {
            awaitingPermission = null
            abandon(session)
            toast("Update ${release.version} not installed: the install permission wasn't allowed.")
        }
    }

    fun onDestroy() {
        if (receiverRegistered) {
            try { activity.unregisterReceiver(statusReceiver) } catch (e: IllegalArgumentException) {}
            receiverRegistered = false
        }
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

    /** Downloads the APK into a new, uncommitted install session. Off the UI thread. */
    private fun download(release: Release): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(activity.packageName)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                try {
                    conn.inputStream.use { input ->
                        session.openWrite("update.apk", 0, conn.contentLengthLong).use { out ->
                            input.copyTo(out, 64 * 1024)
                            session.fsync(out)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            abandon(sessionId)
            throw e
        }
        return sessionId
    }

    private fun offer(release: Release, sessionId: Int) {
        if (activity.isFinishing) { abandon(sessionId); return }
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Update ready")
            .setMessage("Version ${release.version} is downloaded (you have ${BuildConfig.VERSION_NAME}).\n\n" +
                "Install it now? You'll stay signed in.\n\n" +
                // Fire OS's confirmation opens with CANCEL focused, so a
                // reflexive Select throws the update away.
                "When Fire TV asks, move right to INSTALL.")
            .setPositiveButton("Install") { _, _ -> install(release, sessionId) }
            .setNegativeButton("Later") { _, _ -> abandon(sessionId) }
            .setNeutralButton("Skip this version") { _, _ ->
                prefs.edit().putString(PREF_SKIPPED_VERSION, release.version).apply()
                abandon(sessionId)
            }
            .setOnCancelListener { abandon(sessionId) }
            .show()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun install(release: Release, sessionId: Int) {
        if (canInstall()) {
            commit(sessionId)
            return
        }
        awaitingPermission = release to sessionId
        toast("Turn on \"Allow\" for ${activity.getString(R.string.app_name)}, then press BACK")
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) {
            awaitingPermission = null
            abandon(sessionId)
            toast("Allow installs for this app under Settings > My Fire TV > Developer Options")
        }
    }

    private fun commit(sessionId: Int) {
        try {
            registerReceiver()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(activity.packageName)
            val pending = PendingIntent.getBroadcast(activity, sessionId, statusIntent, flags)
            installer.openSession(sessionId).use { it.commit(pending.intentSender) }
        } catch (e: Exception) {
            Log.w(TAG, "Update commit failed", e)
            abandon(sessionId)
            toast("Update failed. Try again from the Menu button.")
        }
    }

    private fun abandon(sessionId: Int) {
        try { installer.abandonSession(sessionId) } catch (e: Exception) {}
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            activity, statusReceiver, IntentFilter(ACTION_INSTALL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread { if (!activity.isFinishing) block() }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
