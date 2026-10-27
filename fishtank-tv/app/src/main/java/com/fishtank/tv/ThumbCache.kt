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
        if (request.method != "GET" || url.host?.endsWith(".b-cdn.net") != true) return null
        val id = Regex("^/([0-9a-f-]{36})/thumbnail\\.jpg$").find(url.path ?: "")?.groupValues?.get(1) ?: return null
        if (request.requestHeaders["Referer"]?.contains("mediadelivery.net") == true) return null
        return try {
            val file = File(dir, "$id.jpg")
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
                if (!shrink(url.toString(), request.requestHeaders, file)) return null
            }
            WebResourceResponse("image/jpeg", null, 200, "OK",
                mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"),
                FileInputStream(file))
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail $id: $e")
            null
        }
    }

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
