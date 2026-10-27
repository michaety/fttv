package com.fishtank.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Phone remote" for mde: a small web server on the TV's LAN address.
 *
 * The app menu shows a QR code for `http://<tv-ip>:8765/?k=<key>`. The page
 * (assets/remote.html) lists Continue watching, mde's New and Popular
 * playlists and search; tapping an episode opens it in the TV's WebView,
 * signed in, so progress and achievements count as normal. While something
 * plays, the page is a remote (play/pause, skip, seek bar).
 *
 * The phone can't call mde's API itself (CORS), so the server fetches it,
 * with the WebView's cookies so watched flags come through. Every request
 * needs the key from the QR code, which stays the same between launches so
 * a bookmark keeps working. Runs only while the app is in the foreground.
 */
class PhoneRemote(private val context: Context, private val host: Host) {

    /** What the server needs from the activity; called on the main thread. */
    interface Host {
        fun evalJs(code: String, result: (String?) -> Unit)
        fun openPath(path: String)
    }

    val key: String = context.getSharedPreferences("phone_remote", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("key", null) ?: newKey().also { prefs.edit().putString("key", it).apply() }
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(4)
    private var seriesTags: Map<String, String>? = null

    /** The address to put in the QR code, or null without a LAN address. */
    fun url(): String? = lanAddress()?.let { "http://$it:$PORT/?k=$key" }

    fun start() {
        if (server != null) return
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(4)
        val socket = try { ServerSocket(PORT) } catch (e: Exception) { Log.w(TAG, "can't listen on $PORT: $e"); return }
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                pool.execute { handle(client) }
            }
        }, "PhoneRemote").start()
    }

    fun stop() {
        try { server?.close() } catch (e: Exception) {}
        server = null
        pool.shutdownNow()
    }

    // ---- HTTP -----------------------------------------------------------

    private fun handle(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break }
                val target = requestLine.split(" ").getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val (status, type, body) = route(path, query)
                val bytes = body.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "request failed: $e")
            }
        }
    }

    private fun route(path: String, q: Map<String, String>): Triple<String, String, String> {
        if (q["k"] != key) return Triple("403 Forbidden", "text/plain", "Scan the QR code in the TV app's menu again.")
        return when (path) {
            "/" -> Triple("200 OK", "text/html; charset=utf-8",
                context.assets.open("remote.html").bufferedReader().use { it.readText() })
            "/api/home" -> json(home())
            "/api/search" -> json(search(q["q"].orEmpty()))
            "/api/status" -> json(JSONObject(evalOnMain("window.__ftvRemoteStatus ? window.__ftvRemoteStatus() : ''")
                ?.let { unquote(it) }?.takeIf { it.startsWith("{") } ?: "{}"))
            "/api/play" -> {
                val p = q["path"].orEmpty()
                if (!EPISODE_PATH.matches(p)) Triple("400 Bad Request", "text/plain", "bad path")
                else { main.post { host.openPath(p) }; json(JSONObject().put("ok", true)) }
            }
            "/api/cmd" -> {
                val code = when (q["c"]) {
                    "toggle" -> "window.__ftvTogglePlay && window.__ftvTogglePlay()"
                    "seek" -> q["v"]?.toIntOrNull()?.let { "window.__ftvSeek && window.__ftvSeek($it)" }
                    "seekto" -> q["v"]?.toDoubleOrNull()?.let { "window.__ftvRemoteSeekTo && window.__ftvRemoteSeekTo($it)" }
                    else -> null
                } ?: return Triple("400 Bad Request", "text/plain", "bad command")
                evalOnMain(code)
                json(JSONObject().put("ok", true))
            }
            else -> Triple("404 Not Found", "text/plain", "not found")
        }
    }

    private fun json(o: JSONObject) = Triple("200 OK", "application/json; charset=utf-8", o.toString())

    // ---- Data -------------------------------------------------------------

    private fun home(): JSONObject {
        val recent = JSONArray()
        evalOnMain("localStorage.getItem('__ftv_recent')")?.let { unquote(it) }?.let { raw ->
            val list = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            for (i in 0 until list.length()) {
                val x = list.optJSONObject(i) ?: continue
                val dur = x.optDouble("dur", 0.0)
                recent.put(JSONObject()
                    .put("path", x.optString("path"))
                    .put("title", x.optString("title"))
                    .put("series", x.optString("series"))
                    .put("thumb", x.optString("thumb"))
                    .put("next", x.optBoolean("next"))
                    .put("progress", if (dur > 0) x.optDouble("t", 0.0) / dur else 0.0))
            }
        }
        // The homepage's own source: unlike /playlists/<id>, it has watched flags.
        val lists = api("/site/landing")?.optJSONArray("playlists") ?: JSONArray()
        fun playlist(label: String): JSONArray {
            for (i in 0 until lists.length()) {
                val p = lists.optJSONObject(i) ?: continue
                if (p.optString("label").equals(label, ignoreCase = true)) {
                    return items(p.optJSONArray("items") ?: JSONArray()) { it.optString("series_tag") }
                }
            }
            return JSONArray()
        }
        return JSONObject()
            .put("recent", recent)
            .put("new", playlist("New"))
            .put("popular", playlist("Popular"))
    }

    private fun search(text: String): JSONObject {
        if (text.isBlank()) return JSONObject().put("results", JSONArray())
        val tags = seriesTags ?: (api("/series")?.optJSONArray("series")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .associate { it.optString("id") to it.optString("tag") }
        } ?: emptyMap()).also { if (it.isNotEmpty()) seriesTags = it }
        val results = api("/search?query=" + URLEncoder.encode(text, "UTF-8"))?.optJSONArray("results") ?: JSONArray()
        return JSONObject().put("results", items(results) { tags[it.optString("series")] ?: it.optString("series_tag") })
    }

    /** mde videos -> the page's cards; [seriesTag] finds each one's /series/<tag>. */
    private fun items(videos: JSONArray, seriesTag: (JSONObject) -> String): JSONArray {
        val out = JSONArray()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (v.optBoolean("hidden")) continue
            val tag = seriesTag(v)
            if (tag.isEmpty() || v.optString("tag").isEmpty()) continue
            out.put(JSONObject()
                .put("path", "/series/$tag/${v.optString("tag")}")
                .put("title", v.optString("title"))
                .put("series", v.optString("series_name"))
                .put("thumb", v.optString("small_thumbnail").ifEmpty { v.optString("thumbnail") })
                .put("dur", v.optInt("duration"))
                .put("watched", v.optBoolean("watched")))
        }
        return out
    }

    /** GET api.mde.tv/v1[path] with the WebView's cookies (watched flags need them). */
    private fun api(path: String): JSONObject? {
        val conn = URL("https://api.mde.tv/v1$path").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Origin", "https://www.mde.tv")
            conn.setRequestProperty("Referer", "https://www.mde.tv/")
            CookieManager.getInstance().getCookie("https://api.mde.tv")?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "api $path: $e")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ---- Helpers ----------------------------------------------------------

    /** Runs [code] in the WebView and waits (up to 3s) for its result. */
    private fun evalOnMain(code: String): String? {
        var result: String? = null
        val done = CountDownLatch(1)
        main.post { host.evalJs(code) { result = it; done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
        return result
    }

    /** evaluateJavascript hands back a JSON value; a string comes quoted. */
    private fun unquote(js: String): String? =
        if (js == "null") null else try { JSONArray("[$js]").getString(0) } catch (e: Exception) { null }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        val k = it.substringBefore('=')
        val v = it.substringAfter('=', "")
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun lanAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    private fun newKey(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        private const val TAG = "PhoneRemote"
        const val PORT = 8765
        private val EPISODE_PATH = Regex("^/series/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+$")
    }
}
