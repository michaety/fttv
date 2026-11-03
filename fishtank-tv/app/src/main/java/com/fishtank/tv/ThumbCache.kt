package com.fishtank.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small copies of fishtank's episode thumbnails.
 *
 * Its Episodes page shows ~60 Bunny thumbnails, and every one is the full
 * 1920x1080 frame (Bunny ignores ?width there). Even straight from the
 * WebView's cache each took ~1.25s to appear on the Firestick -- reading and
 * decoding 60 full-HD JPEGs is the slow part, not the network. So the first
 * time one is requested it's downloaded, shrunk to 480px wide and kept on
 * disk, and from then on the page gets the ~20KB copy.
 *
 * Only `<id>/thumbnail.jpg` on Bunny's CDN, and not inside the Bunny player
 * iframe, whose poster is shown full screen. Copies older than a week are
 * fetched again in case the episode's thumbnail changed.
 */
class ThumbCache(cacheRoot: File) {
    private val dir = File(cacheRoot, "thumbs").apply { mkdirs() }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (request.method != "GET") return null
        // Season 3's newer pictures are on fishtank's own CDN, at full size
        // (1536-1600px): shrunk and kept the same way.
        if (url.host == "cdn.fishtank.live") {
            val f = Regex("^/episodes/([0-9a-f-]{36}-\\d{10,16})\\.jpg$").find(url.path ?: "")?.groupValues?.get(1) ?: return null
            return serve(File(dir, "cdn-$f.jpg"), url.toString(), request.requestHeaders)
        }
        if (url.host?.endsWith(".b-cdn.net") != true) return null
        // Also the bare `<id>/thumbnail` the site's cards ask for first: Bunny
        // 404s that and the card falls back to thumbnail.jpg, so every redraw
        // of a card blanked its picture for a moment (the flashing).
        // ...and Season 2's thumbnail_<hash>.jpg, which used to skip the cache
        // (each a full-HD frame, ~1.8s on the Firestick).
        val m = Regex("^/([0-9a-f-]{36})/(thumbnail(?:_[0-9a-f]{4,16})?)(\\.jpg)?$").find(url.path ?: "") ?: return null
        val id = m.groupValues[1]
        val name = m.groupValues[2]
        if (request.requestHeaders["Referer"]?.contains("mediadelivery.net") == true) return null
        return serve(File(dir, if (name == "thumbnail") "$id.jpg" else "$id-$name.jpg"),
            "https://${url.host}/$id/$name.jpg", request.requestHeaders)
    }

    /** [file], made from [source] first if it's missing or a week old; null leaves it to the WebView. */
    private fun serve(file: File, source: String, headers: Map<String, String>): WebResourceResponse? = try {
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
            if (!shrink(source, headers, file)) null else open(file)
        } else open(file)
    } catch (e: Exception) {
        Log.w(TAG, "thumbnail ${file.name}: $e")
        null
    }

    private fun open(file: File) = WebResourceResponse("image/jpeg", null, 200, "OK",
        mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"), FileInputStream(file))

    /**
     * Downloads [url] and writes a 480px-wide copy to [out]; false leaves the
     * request to the WebView. The page's own request headers go along: Bunny
     * refuses (403) a thumbnail without fishtank's Referer.
     */
    private fun shrink(url: String, headers: Map<String, String>, out: File): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (conn.responseCode != 200) { Log.w(TAG, "$url: HTTP ${conn.responseCode}"); return false }
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            bmp.recycle()
            return tmp.renameTo(out)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "ThumbCache"
        private const val TARGET_WIDTH = 480
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * fishtank's episode list (GET api.fishtank.live/v1/videos), kept on disk.
 *
 * The Episodes page can't draw a card until that list arrives, and the API
 * took 2-4s to send it on the Firestick (with ~20 other requests going at
 * once), so the thumbnails only started loading ~6s in. The site gets the
 * last copy straight away instead, and a fresh one is fetched in the
 * background for next time (an episode added since shows up a launch later).
 * It's the public list, the same for everyone; nothing of the account's.
 */
class ListCache(cacheRoot: File) {
    private val file = File(cacheRoot, "ft-videos.json")
    @Volatile private var refreshing = false

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (request.method != "GET" || url.host != "api.fishtank.live" || url.path != "/v1/videos" || url.query != null) return null
        val origin = request.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.value
        if (!file.exists() || file.length() < 100) { refresh(request.requestHeaders); return null }
        if (System.currentTimeMillis() - file.lastModified() > FRESH_MS) refresh(request.requestHeaders)
        val headers = mutableMapOf("Cache-Control" to "no-store")
        // The site asks with credentials, which needs its own origin echoed (not *).
        if (origin == "https://www.fishtank.live") {
            headers["Access-Control-Allow-Origin"] = origin
            headers["Access-Control-Allow-Credentials"] = "true"
        } else headers["Access-Control-Allow-Origin"] = "*"
        return try {
            WebResourceResponse("application/json", "utf-8", 200, "OK", headers, FileInputStream(file))
        } catch (e: Exception) { null }
    }

    private fun refresh(headers: Map<String, String>) {
        if (refreshing) return
        refreshing = true
        Thread {
            try {
                val conn = URL("https://api.fishtank.live/v1/videos").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Origin", "https://www.fishtank.live")
                conn.setRequestProperty("Referer", "https://www.fishtank.live/")
                headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { conn.setRequestProperty("User-Agent", it.value) }
                try {
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.use { it.readBytes() }
                        // Only a real list replaces the copy.
                        if (String(body, 0, minOf(body.size, 200), Charsets.UTF_8).contains("\"videos\"")) {
                            val tmp = File(file.path + ".tmp")
                            tmp.writeBytes(body)
                            tmp.renameTo(file)
                        }
                    }
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                Log.w("ListCache", "refresh: $e")
            } finally {
                refreshing = false
            }
        }.start()
    }

    companion object {
        /** A copy younger than this is used as is, without fetching again. */
        private const val FRESH_MS = 5L * 60 * 1000
    }
}
