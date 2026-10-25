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
 * Sideloaded apps never hear about new versions, so on launch this asks
 * GitHub for the latest release and, if it's newer than the running build,
 * offers to install it: downloads the flavor's APK (fishtank-tv.apk /
 * mde-tv.apk -- the asset names the README's download links rely on too)
 * straight into a PackageInstaller session and hands off to Fire OS's own
 * install confirmation. Releases are signed with the same key, so it
 * installs over the top and the login survives.
 *
 * Fire OS requires a one-time "allow this app to install apps" grant; if
 * it's missing, the user is sent to that settings screen and the update
 * resumes when they come back (onResume).
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

    /** Set while waiting for the user to grant the install permission. */
    private var awaitingPermission: Release? = null
    private var progressDialog: AlertDialog? = null
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
                    toast("Update failed" + (msg?.let { ": $it" } ?: ""))
                }
            }
        }
    }

    fun checkInBackground() {
        Thread {
            val release = try { fetchNewerRelease() } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                null
            } ?: return@Thread
            activity.runOnUiThread { if (!activity.isFinishing) offer(release) }
        }.start()
    }

    /** Call from Activity.onResume, to carry on after the permission screen. */
    fun onResume() {
        val pending = awaitingPermission ?: return
        if (canInstall()) {
            awaitingPermission = null
            download(pending)
        }
    }

    fun onDestroy() {
        progressDialog?.dismiss()
        if (receiverRegistered) {
            try { activity.unregisterReceiver(statusReceiver) } catch (e: IllegalArgumentException) {}
            receiverRegistered = false
        }
    }

    private fun fetchNewerRelease(): Release? {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
            if (prefs.getString(PREF_SKIPPED_VERSION, null) == version) return null
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

    private fun offer(release: Release) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Update available")
            .setMessage("Version ${release.version} is out (you have ${BuildConfig.VERSION_NAME}).\n\n" +
                "Install it now? You'll stay signed in.\n\n" +
                // Fire OS's confirmation opens with CANCEL focused, so a
                // reflexive Select throws the update away.
                "When Fire TV asks, move right to INSTALL.")
            .setPositiveButton("Update") { _, _ -> startUpdate(release) }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip this version") { _, _ ->
                prefs.edit().putString(PREF_SKIPPED_VERSION, release.version).apply()
            }
            .show()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun startUpdate(release: Release) {
        if (canInstall()) {
            download(release)
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
            toast("Allow installs for this app under Settings > My Fire TV > Developer Options")
        }
    }

    private fun download(release: Release) {
        val progress = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Updating to ${release.version}")
            .setMessage("Downloading…")
            .setCancelable(false)
            .show()
        progressDialog = progress
        registerReceiver()

        Thread {
            try {
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        session.openWrite("update.apk", 0, total).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        activity.runOnUiThread { progress.setMessage("Downloading… $pct%") }
                                    }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    conn.disconnect()
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(activity.packageName)
                    val pending = PendingIntent.getBroadcast(activity, sessionId, statusIntent, flags)
                    session.commit(pending.intentSender)
                }
                activity.runOnUiThread { progress.dismiss() }
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                activity.runOnUiThread {
                    progress.dismiss()
                    toast("Couldn't download the update. Try again later.")
                }
            }
        }.start()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            activity, statusReceiver, IntentFilter(ACTION_INSTALL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
