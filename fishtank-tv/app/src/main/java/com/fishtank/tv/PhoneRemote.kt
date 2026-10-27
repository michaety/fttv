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
 * "Phone remote": a small web server on the TV's LAN address.
 *
 * The app menu shows a QR code for `http://<tv-ip>:8765/?k=<key>`. The page
 * (assets/remote.html) lists Continue watching and search, plus mde's New and
 * Popular playlists, or fishtank's live cameras and its episodes by season.
 * Tapping an episode opens it in the TV's WebView, signed in, so progress and
 * achievements count as normal; tapping a camera switches the TV to it. While
 * something plays, the page is a remote (play/pause, skip, seek bar).
 *
 * The phone can't call the sites' APIs itself (CORS), so the server fetches
 * them, with the WebView's cookies (mde's watched flags need them). Every request
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
    private val fishtank = context.getString(R.string.home_url).contains("fishtank")
    /** fishtank's episodes (all ~140 come in one request); kept for search. */
    private var ftVideos: JSONArray? = null
    private var ftCameras: List<String> = emptyList()

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
                context.assets.open("remote.html").bufferedReader().use { it.readText() }
                    .replace("__FTV_SITE__", if (fishtank) "fishtank" else "mde"))
            "/api/home" -> json(home())
            "/api/search" -> json(search(q["q"].orEmpty()))
            "/api/status" -> json(JSONObject(evalOnMain("window.__ftvRemoteStatus ? window.__ftvRemoteStatus() : ''")
                ?.let { unquote(it) }?.takeIf { it.startsWith("{") } ?: "{}"))
            "/api/play" -> {
                val p = q["path"].orEmpty()
                when {
                    fishtank && FT_EPISODE_PATH.matches(p) -> {
                        evalOnMain("window.__ftvRemoteEpisode && window.__ftvRemoteEpisode(" +
                            JSONObject.quote(p.substringAfterLast('/')) + "," + JSONObject.quote(q["title"].orEmpty()) + ")")
                        json(JSONObject().put("ok", true))
                    }
                    !fishtank && EPISODE_PATH.matches(p) -> { main.post { host.openPath(p) }; json(JSONObject().put("ok", true)) }
                    else -> Triple("400 Bad Request", "text/plain", "bad path")
                }
            }
            "/api/camera" -> {
                val name = q["name"].orEmpty()
                if (!fishtank || name !in ftCameras) Triple("400 Bad Request", "text/plain", "bad camera")
                else {
                    evalOnMain("window.__ftvRemoteCamera && window.__ftvRemoteCamera(" + JSONObject.quote(name) + ")")
                    json(JSONObject().put("ok", true))
                }
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
        val out = if (fishtank) fishtankHome() else mdeHome()
        return out.put("recent", recent())
    }

    /** Continue watching, from the app's own list in the WebView's localStorage. */
    private fun recent(): JSONArray {
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
        return recent
    }

    private fun mdeHome(): JSONObject {
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
        return JSONObject().put("sections", JSONArray()
            .put(JSONObject().put("title", "New").put("items", playlist("New")))
            .put(JSONObject().put("title", "Popular").put("items", playlist("Popular"))))
    }

    /** fishtank: live cameras (online first), then episodes by collection. */
    private fun fishtankHome(): JSONObject {
        val streams = ftApi("/live-streams")
        val status = streams?.optJSONObject("liveStreamStatus") ?: JSONObject()
        // Thumbnails only exist on the TV's live page, in the camera tiles.
        val thumbs = evalOnMain(CAMERA_THUMBS_JS)?.let { unquote(it) }
            ?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: JSONObject()
        val cams = mutableListOf<JSONObject>()
        val list = streams?.optJSONArray("liveStreams") ?: JSONArray()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            if (c.optBoolean("hidden")) continue
            val name = c.optString("name")
            cams.add(JSONObject().put("name", name).put("thumb", thumbs.optString(name))
                .put("online", status.optString(c.optString("id")) == "online"))
        }
        ftCameras = cams.map { it.optString("name") }
        cams.sortBy { if (it.optBoolean("online")) 0 else 1 }

        val videos = ftApi("/videos")?.optJSONArray("videos")?.also { ftVideos = it } ?: ftVideos ?: JSONArray()
        val byCollection = linkedMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            byCollection.getOrPut(v.optString("collection").ifEmpty { "Other" }) { mutableListOf() }.add(v)
        }
        val sections = JSONArray()
        // Collections we know in a sensible order; a new one (next season) goes on top.
        byCollection.entries
            .sortedWith(compareBy<Map.Entry<String, MutableList<JSONObject>>> { COLLECTION_ORDER.indexOf(it.key) }
                .thenByDescending { e -> e.value.maxOf { it.optLong("createdAt") } })
            .forEach { (name, vs) ->
                // Seasons run from episode 1 ("01 - ...", "S3E1 - ..."): upload
                // dates don't follow the numbering. The rest newest first.
                val num = { v: JSONObject -> NUMBERED.find(v.optString("title"))?.groupValues?.get(1)?.toIntOrNull() }
                val numbered = vs.count { num(it) != null } > vs.size / 2
                val sorted = if (numbered) vs.sortedBy { num(it) ?: Int.MAX_VALUE } else vs.sortedByDescending { it.optLong("createdAt") }
                val label = if (name == "Digest") "Season 4 Digest" else name
                sections.put(JSONObject().put("title", label).put("items", JSONArray(sorted.map { ftItem(it, label) })))
            }
        return JSONObject().put("cameras", JSONArray(cams)).put("sections", sections)
    }

    private fun ftItem(v: JSONObject, collection: String): JSONObject = JSONObject()
        .put("path", "/episodes/${v.optString("id")}")
        .put("title", v.optString("title").replace(VIDEO_EXT, "").trim())
        .put("series", collection)
        .put("thumb", v.optString("thumbnail"))
        .put("dur", v.optInt("duration"))

    private fun search(text: String): JSONObject {
        if (text.isBlank()) return JSONObject().put("results", JSONArray())
        if (fishtank) {
            // No search API: match titles in the episode list.
            val videos = ftVideos ?: ftApi("/videos")?.optJSONArray("videos")?.also { ftVideos = it } ?: JSONArray()
            val words = text.lowercase().split(' ').filter { it.isNotBlank() }
            val out = JSONArray()
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val t = v.optString("title").lowercase()
                if (words.all { t.contains(it) }) out.put(ftItem(v, v.optString("collection")))
            }
            return JSONObject().put("results", out)
        }
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
    private fun api(path: String): JSONObject? = get("https://api.mde.tv/v1$path", "https://www.mde.tv")

    private fun ftApi(path: String): JSONObject? = get("https://api.fishtank.live/v1$path", "https://www.fishtank.live")

    private fun get(url: String, origin: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Origin", origin)
            conn.setRequestProperty("Referer", "$origin/")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "GET $url: $e")
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
        private val FT_EPISODE_PATH = Regex("^/episodes/[0-9a-f-]{36}$")
        private val NUMBERED = Regex("^\\s*(?:S\\d+E)?(\\d+)\\b", RegexOption.IGNORE_CASE)
        private val COLLECTION_ORDER = listOf("Season 5 Digests", "Off-Season", "Digest", "Season 3", "Season 2",
            "Season 1", "Shorts", "BTS")
        private val VIDEO_EXT = Regex("\\.(mp4|mov|mkv|webm)$", RegexOption.IGNORE_CASE)
        /** name -> thumbnail of each camera tile on fishtank's live page. */
        private const val CAMERA_THUMBS_JS = "(function(){var o={};" +
            "document.querySelectorAll('button[aria-label^=\"Watch \"]').forEach(function(b){" +
            "var i=b.querySelector('img');if(i&&i.src)o[b.getAttribute('aria-label').slice(6)]=i.src;});" +
            "return JSON.stringify(o);})()"
    }
}
