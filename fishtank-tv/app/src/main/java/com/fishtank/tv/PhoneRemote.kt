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
import java.net.InetSocketAddress
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
 * something plays, the page is a remote (play/pause, skip, seek bar), with a
 * full-screen player page: details, the rest of the series, and a queue of
 * episodes to play next (kept on the TV, see __ftvQueue in dpad.js).
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
        /** A phone opened the remote page (e.g. just scanned the QR code). */
        fun onPhoneConnected()
        /** Stop watching: back to the page the episode was opened from. */
        fun leave()
        /** The phone signed the TV in. */
        fun onSignedIn()
    }

    val key: String = context.getSharedPreferences("phone_remote", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("key", null) ?: newKey().also { prefs.edit().putString("key", it).apply() }
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    /** Between start() and stop(); the listening thread checks it. */
    @Volatile private var wanted = false
    private var pool = Executors.newFixedThreadPool(4)
    private var seriesTags: Map<String, String>? = null
    /** mde series tag -> (name, videos, when fetched); the player page asks often. */
    private val seriesCache = HashMap<String, Triple<String, List<JSONObject>, Long>>()
    private val fishtank = context.getString(R.string.home_url).contains("fishtank")
    /** fishtank's episodes (all ~140 come in one request); kept for search. */
    private var ftVideos: JSONArray? = null
    private var ftCameras: List<String> = emptyList()

    /** The address to put in the QR code, or null without a LAN address. */
    fun url(): String? = lanAddress()?.let { "http://$it:$PORT/?k=$key" }

    fun start() {
        if (wanted) return
        wanted = true
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(4)
        Thread({
            // Both apps use the same port, and switching from one to the other
            // runs this app's onStart before the other's onStop lets go of it,
            // so keep trying for a while rather than giving up on the first go.
            var socket: ServerSocket? = null
            for (attempt in 0 until 40) {
                if (!wanted) return@Thread
                socket = try {
                    ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                } catch (e: Exception) {
                    if (attempt == 0) Log.w(TAG, "port $PORT busy, retrying: $e")
                    null
                }
                if (socket != null) break
                Thread.sleep(250)
            }
            if (socket == null) { Log.w(TAG, "can't listen on $PORT"); return@Thread }
            synchronized(this) {
                if (!wanted) { socket.close(); return@Thread }
                server = socket
            }
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                pool.execute { handle(client) }
            }
        }, "PhoneRemote").start()
    }

    fun stop() {
        synchronized(this) {
            wanted = false
            try { server?.close() } catch (e: Exception) {}
            server = null
        }
        pool.shutdownNow()
    }

    // ---- HTTP -----------------------------------------------------------

    private fun handle(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                var length = 0
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true))
                        length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val method = requestLine.substringBefore(' ')
                val target = requestLine.split(" ").getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                // Only the sign-in form sends a body (so the password never sits in a URL).
                val body = if (method == "POST" && length in 1..MAX_BODY) readBody(reader, length) else ""
                // The home-screen icon is the one thing served without the key.
                val (status, type, bytes) = if (path == "/icon.png") Triple("200 OK", "image/png",
                    context.assets.open(if (fishtank) "remote-icon-fishtank.png" else "remote-icon-mde.png").use { it.readBytes() })
                else route(path, query, method, body).let { (st, ty, body) -> Triple(st, ty, body.toByteArray(Charsets.UTF_8)) }
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

    private fun route(path: String, q: Map<String, String>, method: String, body: String): Triple<String, String, String> {
        if (q["k"] != key) return Triple("403 Forbidden", "text/plain", "Scan the QR code in the TV app's menu again.")
        return when (path) {
            // Signing the TV in from the phone: email and password in the body.
            "/api/signin" -> if (method != "POST") Triple("405 Method Not Allowed", "text/plain", "POST only")
                else json(signIn(body))
            "/" -> {
                main.post { host.onPhoneConnected() }
                Triple("200 OK", "text/html; charset=utf-8",
                    context.assets.open("remote.html").bufferedReader().use { it.readText() }
                        // Each app's own look, matching its site (one file per site).
                        .replace("/*__FTV_CSS__*/", context.assets.open(if (fishtank) "remote-fishtank.css" else "remote-mde.css")
                            .bufferedReader().use { it.readText() })
                        .replace("__FTV_SITE__", if (fishtank) "fishtank" else "mde")
                        .replace("__FTV_KEY__", key))
            }
            // Lets "Add to Home screen" name the shortcut and open it full screen.
            "/manifest.webmanifest" -> Triple("200 OK", "application/manifest+json", JSONObject()
                .put("name", if (fishtank) "Fishtank TV Remote" else "MDE TV Remote")
                .put("short_name", if (fishtank) "Fishtank TV" else "MDE TV")
                .put("start_url", "/?k=$key")
                .put("scope", "/")
                .put("display", "standalone")
                .put("background_color", if (fishtank) "#56749f" else "#04080b")
                .put("theme_color", if (fishtank) "#56749f" else "#04080b")
                .put("icons", JSONArray()
                    .put(JSONObject().put("src", "/icon.png").put("sizes", "512x512").put("type", "image/png").put("purpose", "any"))
                    .put(JSONObject().put("src", "/icon.png").put("sizes", "512x512").put("type", "image/png").put("purpose", "maskable")))
                .toString())
            "/api/home" -> json(home())
            "/api/search" -> json(search(q["q"].orEmpty()))
            "/api/series" -> {
                val tag = q["tag"].orEmpty()
                if (fishtank || !SERIES_TAG.matches(tag)) Triple("400 Bad Request", "text/plain", "bad series")
                else json(mdeSeries(tag))
            }
            "/api/shuffle" -> {
                // mde: a random episode of the show on the TV, and autoplay keeps shuffling.
                val tag = q["tag"].orEmpty()
                if (fishtank || !SERIES_TAG.matches(tag)) Triple("400 Bad Request", "text/plain", "bad series")
                else {
                    evalOnMain("window.__ftvShuffle ? window.__ftvShuffle(" + JSONObject.quote(tag) + ") : 'none'")
                    json(JSONObject().put("ok", true))
                }
            }
            "/api/status" -> json(status())
            "/api/episode" -> {
                val p = q["path"].orEmpty()
                episode(p)?.let { json(it) } ?: Triple("400 Bad Request", "text/plain", "bad path")
            }
            "/api/queue" -> json(queue())
            // The account button: profile, (mde) calling cards and history, theme.
            "/api/account" -> json(account())
            "/api/history" -> if (fishtank) Triple("400 Bad Request", "text/plain", "mde only") else json(history())
            "/api/backgrounds" -> Triple("200 OK", "application/json; charset=utf-8",
                context.assets.open("mde-backgrounds.json").bufferedReader().use { it.readText() })
            "/api/loadout" -> {
                if (fishtank) return Triple("400 Bad Request", "text/plain", "mde only")
                val body = JSONObject()
                for (k in listOf("calling_card", "emblem")) q[k]?.let { v ->
                    if (v.isNotEmpty() && !SAFE_ID.matches(v)) return Triple("400 Bad Request", "text/plain", "bad id")
                    body.put(k, if (v.isEmpty()) JSONObject.NULL else v)
                }
                apiSend("PUT", "/rewards/me/loadout", body)?.let { json(account()) }
                    ?: Triple("502 Bad Gateway", "text/plain", "mde said no")
            }
            "/api/theme/set" -> {
                // Only the shapes each site's theme takes, nothing else.
                val t = try { JSONObject(q["v"].orEmpty()) } catch (e: Exception) { null }
                    ?: return Triple("400 Bad Request", "text/plain", "bad theme")
                val clean = cleanTheme(t) ?: return Triple("400 Bad Request", "text/plain", "bad theme")
                evalOnMain("window.__ftvThemeSet ? window.__ftvThemeSet(" + JSONObject.quote(clean.toString()) + ") : 'no'")
                json(JSONObject().put("ok", true))
            }
            // mde's like / dislike, comments and replies, as the TV's account.
            "/api/comments" -> {
                val id = q["id"].orEmpty()
                val page = q["page"]?.toIntOrNull()?.coerceIn(0, 1000) ?: 0
                if (!SAFE_ID.matches(id)) Triple("400 Bad Request", "text/plain", "bad id")
                else json(if (fishtank) ftComments(id, page, q["sort"].orEmpty()) else comments(id, page, q["sort"].orEmpty()))
            }
            "/api/like" -> {
                val id = q["id"].orEmpty()
                if (fishtank || !SAFE_ID.matches(id)) return Triple("400 Bad Request", "text/plain", "bad id")
                apiSend("POST", "/videos/$id/" + if (q["v"] == "dislike") "dislike" else "like")
                    ?.let { json(it) } ?: Triple("502 Bad Gateway", "text/plain", "mde said no")
            }
            "/api/comment/post" -> {
                val id = q["id"].orEmpty()
                val reply = q["reply"] == "1"
                val msg = q["message"].orEmpty().trim()
                if (fishtank) {
                    // fishtank: the episode it's on, and (a reply) the comment it answers.
                    val ep = if (reply) q["ep"].orEmpty() else id
                    if (!SAFE_ID.matches(id) || !SAFE_ID.matches(ep) || msg.length < 2 || msg.length > 500)
                        return Triple("400 Bad Request", "text/plain", "bad comment")
                    val body = JSONObject().put("message", msg).put("relatedId", ep).put("type", "episode")
                        .put("reply", if (reply) id else JSONObject.NULL)
                    return ftSend("POST", "/comments", body)?.let { json(JSONObject().put("ok", true)) }
                        ?: Triple("502 Bad Gateway", "text/plain", "fishtank said no")
                }
                if (!SAFE_ID.matches(id) || msg.isEmpty() || msg.length > 2000) return Triple("400 Bad Request", "text/plain", "bad comment")
                val r = apiSend("POST", if (reply) "/videos/comments/$id/reply" else "/videos/$id/comments", JSONObject().put("message", msg))
                r?.let { json(JSONObject().put("ok", true)) } ?: Triple("502 Bad Gateway", "text/plain", "mde said no")
            }
            "/api/comment/vote" -> {
                val id = q["id"].orEmpty()
                if (!SAFE_ID.matches(id)) return Triple("400 Bad Request", "text/plain", "bad id")
                // fishtank: one endpoint for comments and replies, as its site uses.
                if (fishtank) return ftSend("POST", "/comments/$id/" + if (q["v"] == "dislike") "dislike" else "like")
                    ?.let { json(it) } ?: Triple("502 Bad Gateway", "text/plain", "fishtank said no")
                val kind = if (q["reply"] == "1") "replies" else "comments"
                apiSend("PUT", "/videos/$kind/$id/" + if (q["v"] == "dislike") "dislike" else "like")
                    ?.let { json(it) } ?: Triple("502 Bad Gateway", "text/plain", "mde said no")
            }
            "/api/recent/remove" -> {
                val p = q["path"].orEmpty()
                if (!validPath(p)) return Triple("400 Bad Request", "text/plain", "bad path")
                evalOnMain("window.__ftvRecentRemove && window.__ftvRecentRemove(" + JSONObject.quote(p) + ")")
                json(JSONObject().put("recent", recent()))
            }
            "/api/queue/move" -> {
                val p = q["path"].orEmpty()
                val to = q["to"]?.toIntOrNull() ?: return Triple("400 Bad Request", "text/plain", "bad position")
                if (!validPath(p)) return Triple("400 Bad Request", "text/plain", "bad path")
                evalOnMain("window.__ftvQueue && window.__ftvQueue('move', " + JSONObject().put("path", p).put("to", to) + ")")
                json(queue())
            }
            "/api/queue/unshuffle" -> {
                evalOnMain("window.__ftvQueue && window.__ftvQueue('unshuffle')")
                json(queue())
            }
            "/api/queue/clear" -> {
                evalOnMain("window.__ftvQueue && window.__ftvQueue('clear')")
                json(queue())
            }
            "/api/queue/add", "/api/queue/remove", "/api/queue/top" -> {
                val p = q["path"].orEmpty()
                if (!validPath(p)) return Triple("400 Bad Request", "text/plain", "bad path")
                val arg = if (path == "/api/queue/add") queueItem(p)?.toString()
                    ?: return Triple("404 Not Found", "text/plain", "no such episode")
                    else JSONObject.quote(p)
                val op = when (path) {
                    "/api/queue/add" -> if (q["at"] == "next") "next" else "add"
                    "/api/queue/remove" -> "remove"
                    else -> "top"
                }
                evalOnMain("window.__ftvQueue && window.__ftvQueue('$op', $arg)")
                json(queue())
            }
            "/api/play" -> {
                val p = q["path"].orEmpty()
                when {
                    fishtank && FT_EPISODE_PATH.matches(p) -> {
                        // Its season, in order, so autoplay carries on through it.
                        val row = JSONArray()
                        ftSeasonOf(p.substringAfterLast('/'))?.second?.forEach {
                            row.put(JSONObject().put("id", it.optString("id")).put("title", it.optString("title")))
                        }
                        evalOnMain("window.__ftvRemoteEpisode && window.__ftvRemoteEpisode(" +
                            JSONObject.quote(p.substringAfterLast('/')) + "," + JSONObject.quote(q["title"].orEmpty()) + "," + row +
                            "," + JSONObject.quote(thumbParam(q)) + ")")
                        json(JSONObject().put("ok", true))
                    }
                    !fishtank && EPISODE_PATH.matches(p) -> {
                        // Through the page, so its loading screen goes up first;
                        // straight to the URL if the page isn't ours to script.
                        val meta = JSONObject().put("title", q["title"].orEmpty()).put("series", q["series"].orEmpty())
                            .put("thumb", thumbParam(q))
                        val went = evalOnMain("window.__ftvGo ? (window.__ftvGo(" + JSONObject.quote(p) + "," + meta + "), 1) : 0")
                        if (went != "1") main.post { host.openPath(p) }
                        json(JSONObject().put("ok", true))
                    }
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
                    "restart" -> "window.__ftvRemoteSeekTo && window.__ftvRemoteSeekTo(0)"
                    "captions" -> "window.__ftvCaptions && window.__ftvCaptions('toggle')"
                    "stop" -> { main.post { host.leave() }; return json(JSONObject().put("ok", true)) }
                    else -> null
                } ?: return Triple("400 Bad Request", "text/plain", "bad command")
                evalOnMain(code)
                json(JSONObject().put("ok", true))
            }
            else -> Triple("404 Not Found", "text/plain", "not found")
        }
    }

    private fun json(o: JSONObject) = Triple("200 OK", "application/json; charset=utf-8", o.toString())

    /** The episode picture the phone sent for the TV's loading screen: an https URL or nothing. */
    private fun thumbParam(q: Map<String, String>) = q["thumb"].orEmpty().takeIf { it.startsWith("https://") }.orEmpty()

    // ---- Data -------------------------------------------------------------

    private fun home(): JSONObject {
        val out = if (fishtank) fishtankHome() else mdeHome()
        val recent = recent()
        // mde's Continue watching saves the show as the TV page's breadcrumb
        // ("HW", "KSTV"); the series list has its proper name.
        out.optJSONArray("series")?.let { all ->
            val names = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.associate { it.optString("tag") to it.optString("title") }
            for (i in 0 until recent.length()) {
                val r = recent.optJSONObject(i) ?: continue
                names[r.optString("path").split('/').getOrNull(2)]?.takeIf { it.isNotEmpty() }?.let { r.put("series", it) }
            }
        }
        return out.put("recent", recent)
    }

    /** Continue watching, from the app's own list in the WebView's localStorage. */
    private fun recent(): JSONArray {
        val recent = JSONArray()
        // fishtank entries can carry a guessed thumbnail (see ftThumb in dpad.js).
        val ftThumbs = if (!fishtank) emptyMap() else ftVideoList().let { v ->
            (0 until v.length()).mapNotNull { v.optJSONObject(it) }.associate { it.optString("id") to it.optString("thumbnail") }
        }
        evalOnMain("localStorage.getItem('__ftv_recent')")?.let { unquote(it) }?.let { raw ->
            val list = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            for (i in 0 until list.length()) {
                val x = list.optJSONObject(i) ?: continue
                val dur = x.optDouble("dur", 0.0)
                val path = x.optString("path")
                recent.put(JSONObject()
                    .put("path", path)
                    .put("title", x.optString("title"))
                    .put("series", x.optString("series"))
                    .put("thumb", ftThumbs[path.substringAfterLast('/')]?.takeIf { it.isNotEmpty() } ?: x.optString("thumb"))
                    .put("next", x.optBoolean("next"))
                    .put("progress", if (dur > 0) x.optDouble("t", 0.0) / dur else 0.0))
            }
        }
        return recent
    }

    /**
     * The homepage's rows (New, Popular, Free...), its Spotlight (the
     * "Featured" playlist) and its series, all from the homepage's own
     * source: unlike /playlists/<id>, it has watched flags.
     */
    private fun mdeHome(): JSONObject {
        val landing = api("/site/landing") ?: JSONObject()
        val lists = landing.optJSONArray("playlists") ?: JSONArray()
        val sections = JSONArray()
        var spotlight = JSONArray()
        for (i in 0 until lists.length()) {
            val p = lists.optJSONObject(i) ?: continue
            val label = p.optString("label")
            val videos = p.optJSONArray("items") ?: JSONArray()
            if (label.equals("Featured", ignoreCase = true)) spotlight = items(videos, big = true) { it.optString("series_tag") }
            // The site stopped showing "Free" (Nov 2026).
            else if (!label.equals("Free", ignoreCase = true))
                sections.put(JSONObject().put("title", label).put("items", items(videos) { it.optString("series_tag") }))
        }
        // Then its hand-picked rows (Directors from Beyond, Rabbit Hole, Car Guys), as the site shows them.
        val curated = landing.optJSONArray("curatedPlaylists") ?: JSONArray()
        for (i in 0 until curated.length()) {
            val p = curated.optJSONObject(i) ?: continue
            val videos = p.optJSONArray("items") ?: continue
            if (videos.length() > 0)
                sections.put(JSONObject().put("title", p.optString("label")).put("items", items(videos) { it.optString("series_tag") }))
        }
        val series = JSONArray()
        val all = landing.optJSONArray("series") ?: JSONArray()
        (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .sortedBy { it.optInt("sort_order") }
            .forEach { s ->
                series.put(JSONObject()
                    .put("tag", s.optString("tag"))
                    .put("title", s.optString("name"))
                    .put("thumb", s.optString("medium_thumbnail").ifEmpty { s.optString("thumbnail") })
                    .put("episodes", s.optInt("episodes"))
                    // How many you've finished (signed in), for the site's progress bar.
                    .put("watched", s.optInt("watched")))
            }
        return JSONObject().put("sections", sections).put("spotlight", spotlight).put("series", series)
    }

    /** Every episode of one mde series, newest first (as the site lists them). */
    private fun mdeSeries(tag: String): JSONObject {
        val (name, videos) = mdeSeriesVideos(tag)
        return JSONObject().put("title", name).put("items", items(JSONArray(videos)) { tag })
    }

    /** One mde series' name and videos (newest first), cached for a few minutes. */
    private fun mdeSeriesVideos(tag: String): Pair<String, List<JSONObject>> {
        synchronized(seriesCache) {
            seriesCache[tag]?.let { if (System.currentTimeMillis() - it.third < 5 * 60_000) return it.first to it.second }
        }
        val videos = mutableListOf<JSONObject>()
        var name = ""
        for (page in 0 until 10) {
            val res = api("/series/$tag/videos?page=$page&pageSize=200") ?: break
            if (name.isEmpty()) name = res.optJSONObject("series")?.optString("name").orEmpty()
            val list = res.optJSONArray("videos") ?: break
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { if (!it.optBoolean("hidden")) videos.add(it) }
            if (list.length() < 200 || videos.size >= res.optInt("total")) break
        }
        if (videos.isNotEmpty()) synchronized(seriesCache) { seriesCache[tag] = Triple(name, videos, System.currentTimeMillis()) }
        return name to videos
    }

    /**
     * The episodes [video] belongs with, in watching order, and what to call
     * them: the series from episode 1, or for Pause Snack Continue just the
     * same game's episodes (as autoplay does, see pscNext in dpad.js).
     */
    private fun mdeRun(tag: String, video: JSONObject): Pair<String, List<JSONObject>> {
        val (name, all) = mdeSeriesVideos(tag)
        val ordered = all.sortedWith(compareBy<JSONObject> { it.optInt("episode") }.thenBy { it.optString("created_at") })
        if (tag != "pause-snack-continue") return name to ordered
        val game = pscGame(video.optString("title")) ?: return name to emptyList()
        return game to ordered.filter { pscGame(it.optString("title")) == game }
            .sortedWith(compareBy<JSONObject> { pscEp(it.optString("title")) }.thenBy { it.optInt("episode") })
    }

    /** fishtank: whether any camera is live right now (null: couldn't tell). Off the main thread. */
    fun fishtankLive(): Boolean? {
        val streams = ftApi("/live-streams") ?: return null
        val status = streams.optJSONObject("liveStreamStatus") ?: return null
        // Only the listed cameras: off-season an unlisted "grid" stream still shows as online.
        val list = streams.optJSONArray("liveStreams") ?: return false
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .any { !it.optBoolean("hidden") && status.optString(it.optString("id")) == "online" }
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

        ftApi("/videos")?.optJSONArray("videos")?.let { ftVideos = it }
        val sections = JSONArray()
        ftCollections().forEach { c ->
            sections.put(JSONObject().put("title", c.label).put("items", JSONArray(c.shown.map { ftItem(it, c.label) })))
        }
        return JSONObject().put("cameras", JSONArray(cams)).put("sections", sections)
    }

    /** A fishtank collection: as the page lists it, and in watching order. */
    private class FtCollection(val label: String, val shown: List<JSONObject>, val ordered: List<JSONObject>)

    private fun ftVideoList(): JSONArray = ftVideos ?: ftApi("/videos")?.optJSONArray("videos")?.also { ftVideos = it } ?: JSONArray()

    /**
     * fishtank's episodes by collection: Seasons 1-3, then a collection we
     * don't know yet (a new season), newest first, then digests, shorts and
     * the like.
     */
    private fun ftCollections(): List<FtCollection> {
        val videos = ftVideoList()
        val byCollection = linkedMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            byCollection.getOrPut(v.optString("collection").ifEmpty { "Other" }) { mutableListOf() }.add(v)
        }
        val rank = { name: String ->
            val top = COLLECTIONS_TOP.indexOf(name)
            if (top >= 0) top
            else COLLECTIONS_BOTTOM.indexOf(name).let { if (it >= 0) COLLECTIONS_TOP.size + 1 + it else COLLECTIONS_TOP.size }
        }
        return byCollection.entries
            .sortedWith(compareBy<Map.Entry<String, MutableList<JSONObject>>> { rank(it.key) }
                .thenByDescending { e -> e.value.maxOf { it.optLong("createdAt") } })
            .map { (name, vs) ->
                // Seasons run from episode 1 ("01 - ...", "S3E1 - ..."): upload
                // dates don't follow the numbering. The rest are listed newest
                // first and watched oldest first.
                val num = { v: JSONObject -> NUMBERED.find(v.optString("title"))?.groupValues?.get(1)?.toIntOrNull() }
                val numbered = vs.count { num(it) != null } > vs.size / 2
                val label = if (name == "Digest") "Season 4 Digest" else name
                // An episode's "(Extras)" come after it.
                if (numbered) vs.sortedWith(compareBy<JSONObject>({ num(it) ?: Int.MAX_VALUE },
                    { if (EXTRAS.containsMatchIn(it.optString("title"))) 1 else 0 })).let { FtCollection(label, it, it) }
                else vs.sortedByDescending { it.optLong("createdAt") }.let { FtCollection(label, it, it.reversed()) }
            }
    }

    /** The collection a fishtank episode is in, and its episodes in watching order. */
    private fun ftSeasonOf(id: String): Pair<String, List<JSONObject>>? =
        ftCollections().firstOrNull { c -> c.ordered.any { it.optString("id") == id } }?.let { it.label to it.ordered }

    // ---- Player page and queue ---------------------------------------------

    private fun status(): JSONObject = JSONObject(evalOnMain("window.__ftvRemoteStatus ? window.__ftvRemoteStatus() : ''")
        ?.let { unquote(it) }?.takeIf { it.startsWith("{") } ?: "{}").put("device", deviceName)

    /** "Playing on <this>": the name set for the Fire TV in its settings. */
    private val deviceName: String by lazy {
        (try { android.provider.Settings.Global.getString(context.contentResolver, "device_name") } catch (e: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
    }

    private fun validPath(p: String) = if (fishtank) FT_EPISODE_PATH.matches(p) else EPISODE_PATH.matches(p)

    /** mde: the API's object for the video at /series/<tag>/<video>. */
    private fun mdeVideo(p: String): JSONObject? = api("/videos/" + p.substringAfterLast('/'))?.optJSONObject("video")

    /**
     * What the player page shows for the episode at [p]: its details and the
     * episodes it belongs with, in watching order ("list", titled "listTitle").
     */
    private fun episode(p: String): JSONObject? {
        if (!validPath(p)) return null
        if (fishtank) {
            val id = p.substringAfterLast('/')
            val (label, run) = ftSeasonOf(id) ?: return null
            val v = run.first { it.optString("id") == id }
            return ftItem(v, label).put("description", v.optString("description"))
                .put("listTitle", label).put("list", JSONArray(run.map { ftItem(it, label) }))
        }
        val tag = p.split('/')[2]
        val full = api("/videos/" + p.substringAfterLast('/')) ?: return null
        val v = full.optJSONObject("video") ?: return null
        val (listTitle, run) = mdeRun(tag, v)
        val d = full.optJSONObject("details") ?: JSONObject()
        return JSONObject()
            .put("path", p)
            .put("id", v.optString("id"))
            // Likes and comments act on the account the TV is signed in with.
            .put("likes", d.optInt("likes"))
            .put("dislikes", d.optInt("dislikes"))
            .put("myLike", if (d.isNull("myLike")) JSONObject.NULL else d.optBoolean("myLike"))
            .put("comments", d.optInt("totalComments"))
            .put("chapters", marks(full.optJSONArray("chapters")))
            .put("moments", marks(full.optJSONArray("moments")))
            .put("title", v.optString("title").replace(SPACES, " ").trim())
            .put("series", v.optString("series_name"))
            .put("seriesTag", tag)
            .put("thumb", v.optString("medium_thumbnail").ifEmpty { v.optString("thumbnail") })
            .put("dur", v.optInt("duration"))
            .put("description", v.optString("description").trim())
            .put("listTitle", listTitle)
            .put("list", items(JSONArray(run)) { tag })
    }

    /** One episode as a queue entry (mde's also carries the API object autoplay needs). */
    private fun queueItem(p: String): JSONObject? {
        if (fishtank) {
            val id = p.substringAfterLast('/')
            val (label, run) = ftSeasonOf(id) ?: return null
            // Its season too, so autoplay carries on from there once it's played.
            val row = JSONArray()
            run.forEach { row.put(JSONObject().put("id", it.optString("id")).put("title", it.optString("title"))) }
            return ftItem(run.first { it.optString("id") == id }, label).put("row", row)
        }
        val v = mdeVideo(p) ?: return null
        val tag = v.optString("series_tag").ifEmpty { p.split('/')[2] }
        val item = items(JSONArray().put(v)) { tag }.optJSONObject(0) ?: return null
        return item.put("video", v)
    }

    /**
     * The queue (what was queued from the phone) and "auto": what autoplay
     * would play after that, for the episode on the TV now.
     */
    private fun queue(): JSONObject {
        val raw = evalOnMain("window.__ftvQueue ? window.__ftvQueue('list') : ''")?.let { unquote(it) }
        val q = try { JSONObject(raw ?: "{}") } catch (e: Exception) { JSONObject() }
        val list = q.optJSONArray("queue") ?: JSONArray()
        var auto = q.optJSONArray("auto")
        val shuffle = q.optJSONObject("shuffle")
        if (shuffle != null) auto = JSONArray()   // random picks: nothing to list
        if (auto == null && !fishtank) {
            auto = JSONArray()
            // Autoplay carries on from whatever played last: the end of the
            // queue if there is one, else the episode on the TV now.
            val lastQueued = if (list.length() > 0) list.optJSONObject(list.length() - 1)?.optString("path").orEmpty() else ""
            val p = lastQueued.ifEmpty { status().optString("path") }
            val tag = p.split('/').getOrNull(2).orEmpty()
            if (EPISODE_PATH.matches(p) && tag !in NO_NEXT_UP) {
                mdeVideo(p)?.let { v ->
                    val run = mdeRun(tag, v).second
                    val at = run.indexOfFirst { it.optString("id") == v.optString("id") }
                    if (at >= 0) auto = items(JSONArray(run.drop(at + 1).take(60))) { tag }
                }
            }
        }
        // fishtank's TV only has ids and titles for these; the episode list
        // has the real thumbnails (not always <id>/thumbnail.jpg) and lengths.
        if (fishtank && auto != null && auto!!.length() > 0) {
            val known = HashMap<String, JSONObject>()
            ftCollections().forEach { c -> c.ordered.forEach { known["/episodes/" + it.optString("id")] = ftItem(it, c.label) } }
            val a = auto!!
            auto = JSONArray((0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { known[it.optString("path")] ?: it })
        }
        // Anything queued already plays first.
        val queued = (0 until list.length()).map { list.optJSONObject(it)?.optString("path") }.toSet()
        val autoOut = JSONArray()
        auto?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("path") !in queued) autoOut.put(it) } }
        return JSONObject().put("queue", list).put("auto", autoOut).put("shuffle", shuffle ?: JSONObject.NULL)
    }

    private fun ftItem(v: JSONObject, collection: String): JSONObject = JSONObject()
        .put("id", v.optString("id"))
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

    /**
     * mde videos -> the page's cards; [seriesTag] finds each one's /series/<tag>.
     * [big] uses the medium thumbnail (the Spotlight's slides fill the phone's width).
     */
    private fun items(videos: JSONArray, big: Boolean = false, seriesTag: (JSONObject) -> String): JSONArray {
        val out = JSONArray()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (v.optBoolean("hidden")) continue
            val tag = seriesTag(v)
            if (tag.isEmpty() || v.optString("tag").isEmpty()) continue
            out.put(JSONObject()
                .put("path", "/series/$tag/${v.optString("tag")}")
                .put("title", v.optString("title").replace(SPACES, " ").trim())
                .put("series", v.optString("series_name"))
                .put("thumb", v.optString(if (big) "medium_thumbnail" else "small_thumbnail").ifEmpty { v.optString("thumbnail") })
                .put("dur", v.optInt("duration"))
                .put("watched", v.optBoolean("watched")))
        }
        return out
    }

    /**
     * mde chapters / moments as { title, t } (seconds), soonest first. Few
     * episodes have any, so the field names are read loosely.
     */
    private fun marks(list: JSONArray?): JSONArray {
        val out = ArrayList<JSONObject>()
        for (i in 0 until (list?.length() ?: 0)) {
            val m = list!!.optJSONObject(i) ?: continue
            val t = listOf("start", "time", "timestamp", "startTime", "start_time", "seconds")
                .firstNotNullOfOrNull { k -> m.opt(k)?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() } } ?: continue
            val title = listOf("title", "name", "label", "text").map { m.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            out.add(JSONObject().put("title", title).put("t", t))
        }
        return JSONArray(out.sortedBy { it.optDouble("t") })
    }

    /**
     * The account button's page: who's signed in on the TV, their (mde)
     * calling cards and level, and the site theme the TV is using.
     */
    private fun account(): JSONObject {
        val out = JSONObject().put("site", if (fishtank) "fishtank" else "mde")
        val theme = evalOnMain("JSON.stringify(window.__ftvTheme || null)")?.let { unquote(it) }
        out.put("theme", theme?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: JSONObject.NULL)
        if (fishtank) {
            // /auth also carries the session's tokens: only the id is used.
            val id = ftApi("/auth")?.optJSONObject("session")?.optJSONObject("user")?.optString("id").orEmpty()
            val p = (if (SAFE_ID.matches(id)) ftApi("/profile/$id")?.optJSONObject("profile") else null)
                ?: return out.put("signedIn", false)
            return out.put("signedIn", true).put("profile", JSONObject()
                .put("name", p.optString("displayName"))
                .put("color", p.optString("color"))
                .put("photo", p.optString("photo"))
                .put("xp", p.optInt("xp"))
                .put("tokens", p.optInt("tokens"))
                .put("clan", p.optJSONObject("clan")?.optString("tag")?.ifEmpty { null } ?: p.optString("clan").takeIf { it != "null" }.orEmpty())
                .put("bio", p.optString("bio").takeIf { it != "null" }.orEmpty())
                .put("pass", when {
                    p.optBoolean("seasonPassXL") -> "Season Pass XL"
                    p.optBoolean("seasonPass") -> "Season Pass"
                    else -> ""
                }))
        }
        val user = api("/auth")?.optJSONObject("user") ?: return out.put("signedIn", false)
        val silver = api("/silver/me")
        val rewards = api("/rewards/me")
        val loadout = rewards?.optJSONObject("loadout")
        val cards = JSONArray()
        rewards?.optJSONArray("rewards")?.let { list ->
            for (i in 0 until list.length()) {
                val r = list.optJSONObject(i) ?: continue
                if (!r.optBoolean("enabled", true)) continue
                cards.put(JSONObject()
                    .put("id", r.optString("id"))
                    .put("title", r.optString("title"))
                    .put("description", r.optString("description"))
                    .put("category", r.optString("category"))
                    .put("rarity", r.optString("rarity_label"))
                    .put("rarityRank", r.optInt("rarity_rank"))
                    .put("goal", r.optInt("goal"))
                    .put("progress", r.optInt("progress"))
                    .put("unlocked", r.optBoolean("unlocked"))
                    .put("card", r.optJSONObject("card")?.optString("url").orEmpty())
                    .put("emblem", r.optJSONObject("emblem")?.optString("url").orEmpty()))
            }
        }
        val equipped = { k: String -> loadout?.optString(k)?.takeIf { it.isNotEmpty() && it != "null" } }
        return out.put("signedIn", true).put("profile", JSONObject()
            .put("name", user.optString("display_name"))
            .put("color", user.optString("color"))
            .put("photo", user.optString("photo"))
            .put("level", silver?.optInt("level") ?: 0)
            .put("prestige", silver?.optInt("prestige") ?: 0)
            .put("silver", silver?.optInt("balance") ?: 0)
            .put("callingCard", equipped("calling_card") ?: JSONObject.NULL)
            .put("emblem", equipped("emblem") ?: JSONObject.NULL))
            .put("cards", cards)
    }

    /** mde's History list (every episode opened, newest first), as cards. */
    private fun history(): JSONObject {
        val id = api("/auth")?.optJSONObject("user")?.optString("id").orEmpty()
        if (!SAFE_ID.matches(id)) return JSONObject().put("items", JSONArray())
        val videos = api("/playlists/history-$id")?.optJSONObject("playlist")?.optJSONArray("videos") ?: JSONArray()
        return JSONObject().put("items", items(videos) { it.optString("series_tag") })
    }

    /**
     * A theme from the phone, rebuilt from only the fields each site uses:
     * mde {colors: six #hex values, background: a name or null}; fishtank
     * {settings: its *Color keys as #hex, or null for its default}.
     */
    private fun cleanTheme(t: JSONObject): JSONObject? {
        val hex = Regex("^#[0-9a-fA-F]{6}$")
        val out = JSONObject()
        if (fishtank) {
            val s = t.optJSONObject("settings") ?: return out.put("settings", JSONObject.NULL)
            val clean = JSONObject()
            for (k in FT_THEME_COLORS) s.optString(k).takeIf { hex.matches(it) }?.let { clean.put(k, it) }
            // Textures, fonts, corners, spacing, animations: only the site's own options.
            val texture = Regex("^none$|^url\\(https://cdn\\.fishtank\\.live/images/patterns/[a-z0-9-]+\\.png\\)$")
            for (k in FT_THEME_TEXTURES) s.optString(k).takeIf { texture.matches(it) }?.let { clean.put(k, it) }
            for (k in listOf("primaryFont", "secondaryFont")) s.optString(k).takeIf { it in FT_THEME_FONTS }?.let { clean.put(k, it) }
            s.optString("borderRadius").takeIf { it in listOf("none", "sm", "md", "lg") }?.let { clean.put("borderRadius", it) }
            s.optString("spacing").takeIf { it in listOf("sm", "md", "lg") }?.let { clean.put("spacing", it) }
            s.optString("animations").takeIf { it == "true" || it == "false" }?.let { clean.put("animations", it) }
            return out.put("settings", clean)
        }
        if (t.has("colors")) {
            val c = t.optJSONObject("colors") ?: return null
            val clean = JSONObject()
            for (k in MDE_THEME_COLORS) clean.put(k, c.optString(k).takeIf { hex.matches(it) } ?: return null)
            out.put("colors", clean)
        }
        if (t.has("background")) {
            val b = t.opt("background")
            out.put("background", if (b is String && SERIES_TAG.matches(b)) b else JSONObject.NULL)
        }
        return out
    }

    /** One page of an episode's comments, in one of mde's four orders. */
    private fun comments(id: String, page: Int, sort: String): JSONObject {
        val (by, order) = when (sort) {
            "oldest" -> "created_at" to "asc"
            "best" -> "likes" to "desc"
            "worst" -> "dislikes" to "desc"
            else -> "created_at" to "desc"
        }
        val r = api("/videos/$id/comments?page=$page&sort_by=$by&sort_order=$order") ?: return JSONObject().put("error", true)
        val one = { c: JSONObject -> JSONObject()
            .put("id", c.optString("id"))
            .put("name", c.optJSONObject("user")?.optString("display_name").orEmpty())
            .put("photo", c.optJSONObject("user")?.optString("photo").orEmpty())
            .put("message", c.optString("message"))
            .put("gif", c.optString("gif").takeIf { it.startsWith("https://") }.orEmpty())
            .put("at", c.optString("created_at"))
            .put("likes", c.optInt("likes"))
            .put("dislikes", c.optInt("dislikes"))
            .put("iLike", c.optBoolean("i_like"))
            .put("iDislike", c.optBoolean("i_dislike")) }
        val list = { a: JSONArray? -> JSONArray((0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.map { c ->
            one(c).also { o -> c.optJSONArray("replies")?.let { rs ->
                o.put("replies", JSONArray((0 until rs.length()).mapNotNull { rs.optJSONObject(it) }.map(one)))
            } }
        }) }
        return JSONObject()
            .put("comments", list(r.optJSONArray("comments")))
            // Pinned ones are sent with the first page only.
            .put("pinned", if (page == 0) list(r.optJSONArray("pinnedComments")) else JSONArray())
            .put("total", r.optInt("total"))
    }

    /** GET api.mde.tv/v1[path] with the WebView's cookies (watched flags need them). */
    private fun api(path: String): JSONObject? = get("https://api.mde.tv/v1$path", "https://www.mde.tv")

    /**
     * A change on the account the TV is signed in with (a like, a comment):
     * [method] api.mde.tv/v1[path], with [body] as JSON if given.
     */
    private fun apiSend(method: String, path: String, body: JSONObject? = null): JSONObject? {
        val url = "https://api.mde.tv/v1$path"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Origin", "https://www.mde.tv")
            conn.setRequestProperty("Referer", "https://www.mde.tv/")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) { Log.w(TAG, "$method $path: $code"); null }
            else conn.inputStream.bufferedReader().use { it.readText() }.let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "$method $url: $e")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun ftApi(path: String): JSONObject? = get("https://api.fishtank.live/v1$path", "https://www.fishtank.live")

    /**
     * fishtank: an episode's comments, as the site lists them under the
     * player (GET /comments/<episode>, 10 a page from 0), in the same shape
     * as mde's for the phone.
     */
    private fun ftComments(id: String, page: Int, sort: String): JSONObject {
        val s = if (sort in listOf("newest", "oldest", "best", "worst")) sort else "newest"
        val r = ftApi("/comments/$id?page=$page&sort=$s") ?: return JSONObject().put("error", true)
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val one = { c: JSONObject -> JSONObject()
            .put("id", c.optString("id"))
            .put("name", c.optJSONObject("profile")?.optString("displayName").orEmpty())
            .put("photo", c.optJSONObject("profile")?.optString("photo").orEmpty())
            .put("message", c.optString("message"))
            .put("at", iso.format(java.util.Date(c.optLong("createdAt"))))
            .put("likes", c.optInt("likes"))
            .put("dislikes", c.optInt("dislikes"))
            .put("iLike", c.optBoolean("iLike"))
            .put("iDislike", c.optBoolean("iDislike")) }
        val list = r.optJSONArray("comments")
        return JSONObject()
            .put("comments", JSONArray((0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }.map { c ->
                one(c).also { o -> c.optJSONArray("replies")?.let { rs ->
                    o.put("replies", JSONArray((0 until rs.length()).mapNotNull { rs.optJSONObject(it) }
                        .sortedBy { it.optLong("createdAt") }.map(one)))
                } }
            }))
            .put("pinned", JSONArray())
            .put("total", r.optInt("total"))
    }

    /**
     * fishtank: a change on the TV's account (a comment, a vote). Its site's
     * API client sends the session's access token as well as the cookies, so
     * this asks /auth for it at the time and uses it for this one request;
     * it's never kept or logged.
     */
    private fun ftSend(method: String, path: String, body: JSONObject? = null): JSONObject? {
        val token = ftApi("/auth")?.optJSONObject("session")?.optString("access_token").orEmpty()
        val url = "https://api.fishtank.live/v1$path"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Origin", "https://www.fishtank.live")
            conn.setRequestProperty("Referer", "https://www.fishtank.live/")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) { Log.w(TAG, "$method $path: $code"); null }
            else conn.inputStream.bufferedReader().use { it.readText() }.let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "$method $path: ${e.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

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

    // ---- Signing in from the phone ---------------------------------------

    /**
     * Whether the TV is signed in to its site: true, false, or null when the
     * site couldn't be reached (so the app doesn't ask to sign in offline).
     * Runs off the main thread.
     */
    fun signedIn(): Boolean? {
        val (url, origin) = if (fishtank) "https://api.fishtank.live/v1/auth" to "https://www.fishtank.live"
            else "https://api.mde.tv/v1/auth" to "https://www.mde.tv"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Origin", origin)
            conn.setRequestProperty("Referer", "$origin/")
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            val code = conn.responseCode
            when {
                code == 401 || code == 403 -> false
                code != 200 -> null
                else -> {
                    // fishtank's /auth also carries the session's tokens: only whether there's a user is used.
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val user = if (fishtank) o.optJSONObject("session")?.optJSONObject("user") else o.optJSONObject("user")
                    user != null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "auth check: $e")
            null
        } finally {
            conn.disconnect()
        }
    }

    @Volatile private var signingIn = false

    /**
     * The phone's sign-in form: the TV's page fills in the site's own login
     * form with [body]'s email and password and submits it (__ftvSignIn in
     * dpad.js), then this waits for the account to show as signed in. The
     * password is only passed through, never kept or logged.
     */
    private fun signIn(body: String): JSONObject {
        val fail = { msg: String -> JSONObject().put("ok", false).put("error", msg) }
        val o = try { JSONObject(body) } catch (e: Exception) { return fail("Enter your email and password.") }
        val email = o.optString("email").trim()
        val pw = o.optString("password")
        if (email.isEmpty() || pw.isEmpty() || email.length > 254 || pw.length > 256) return fail("Enter your email and password.")
        if (signedIn() == true) { main.post { host.onSignedIn() }; return JSONObject().put("ok", true) }
        synchronized(this) {
            if (signingIn) return fail("Already signing in. Give it a moment.")
            signingIn = true
        }
        try {
            val start = "window.__ftvSignIn ? window.__ftvSignIn(" + JSONObject.quote(email) + "," + JSONObject.quote(pw) + ") : 'noform'"
            var started = evalOnMain(start)?.let { unquote(it) }
            if (started == "started") { Thread.sleep(600); if (state()?.optString("state") == "noform") started = "noform" }
            // Not on a page with the site's Log In button (or the page script
            // isn't in yet): the homepage has one.
            if (started != "started") {
                main.post { host.openPath("/") }
                Thread.sleep(7_000)
                started = evalOnMain(start)?.let { unquote(it) }
            }
            if (started != "started") return fail("The TV isn't ready. Try again in a moment.")
            val until = System.currentTimeMillis() + 25_000
            var checked = 0L
            while (System.currentTimeMillis() < until) {
                Thread.sleep(500)
                val st = state()
                when (st?.optString("state")) {
                    "error" -> return fail(st.optString("msg").ifBlank { "That didn't work. Check your email and password." })
                    "noform" -> return fail("Couldn't find the sign-in form on the TV. Sign in with the remote instead.")
                }
                // The site may reload its page once signed in, taking the state
                // with it, so check the account itself every few seconds too.
                val submitted = st?.optString("state") == "submitted"
                if (submitted || System.currentTimeMillis() - checked > 3_000) {
                    checked = System.currentTimeMillis()
                    if (signedIn() == true) { main.post { host.onSignedIn() }; return JSONObject().put("ok", true) }
                }
            }
            return fail("That didn't work. Check your email and password.")
        } finally {
            signingIn = false
        }
    }

    private fun state(): JSONObject? = evalOnMain("JSON.stringify(window.__ftvSignInState || null)")
        ?.let { unquote(it) }?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    // ---- Helpers ----------------------------------------------------------

    /** [bytes] bytes of UTF-8 body from [reader], which has already read the headers. */
    private fun readBody(reader: BufferedReader, bytes: Int): String {
        val sb = StringBuilder()
        var n = 0
        while (n < bytes) {
            val c = reader.read()
            if (c < 0) break
            sb.append(c.toChar())
            n += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDBFF -> 4   // a surrogate pair: 4 bytes for both halves
                c in 0xDC00..0xDFFF -> 0
                else -> 3
            }
        }
        return sb.toString()
    }

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
        private const val MAX_BODY = 8 * 1024
        private val EPISODE_PATH = Regex("^/series/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+$")
        private val FT_EPISODE_PATH = Regex("^/episodes/[0-9a-f-]{36}$")
        private val NUMBERED = Regex("^\\s*(?:S\\d+E)?(\\d+)\\b", RegexOption.IGNORE_CASE)
        /** fishtank's full seasons go first, in order; the rest after them. */
        private val COLLECTIONS_TOP = listOf("Season 1", "Season 2", "Season 3")
        private val COLLECTIONS_BOTTOM = listOf("Season 5 Digests", "Off-Season", "Digest", "Shorts", "BTS")
        private val SERIES_TAG = Regex("^[A-Za-z0-9_-]+$")
        /** The theme editors' colours: mde's six; Fishtank's main ones (its site has more). */
        private val MDE_THEME_COLORS = listOf("primary", "secondary", "tertiary", "text", "links", "terminal")
        private val FT_THEME_COLORS = listOf("primaryColor", "secondaryColor", "tertiaryColor", "lightColor", "darkColor",
            "linkColor", "dangerColor", "infoColor", "backgroundColor", "lightTextColor", "darkTextColor")
        private val FT_THEME_TEXTURES = listOf("backgroundTexture", "panelTexture", "innerPanelTexture", "darkPanelTexture", "metalTexture")
        /** fishtank's THEME_FONTS values (Sofia Pro, Highway Gothic, Share Tech Mono, JetBrains Mono, DM Sans, Winky Sans). */
        private val FT_THEME_FONTS = listOf("\"sofia-pro-variable\", sans-serif", "\"highway-gothic\", sans-serif",
            "\"Share Tech Mono\", monospace", "\"JetBrains Mono\", monospace", "\"DM Sans\", sans-serif", "\"Winky Sans\", sans-serif")
        /** A video, comment or reply id from the phone (mde uses UUIDs; kept loose). */
        private val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")
        private val SPACES = Regex("\\s+")
        private val EXTRAS = Regex("\\(extras\\)", RegexOption.IGNORE_CASE)
        /** mde collections that aren't watched in order: no autoplay list. Same as NO_NEXT_UP in dpad.js. */
        private val NO_NEXT_UP = setOf("sst", "films", "sequence-master", "vlt", "bntm", "mde", "music", "xp-sound",
            "tubi-poops", "patina-research", "worldcore", "fleshsim")
        private val PSC_TITLE = Regex("^(.*?)\\s*[-–—]\\s*Ep\\.?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val PSC_PREFIX = Regex("^\\s*(Scary Mondays|PSC Wildcard Wednesday)\\s*:\\s*", RegexOption.IGNORE_CASE)
        /** A Pause Snack Continue title's game ("<Game> - Ep. N"), as pscGame in dpad.js. */
        private fun pscGame(title: String) = PSC_TITLE.find(title.replace(PSC_PREFIX, ""))?.groupValues?.get(1)?.trim()
        private fun pscEp(title: String) = PSC_TITLE.find(title.replace(PSC_PREFIX, ""))?.groupValues?.get(2)?.toIntOrNull() ?: 0
        private val VIDEO_EXT = Regex("\\.(mp4|mov|mkv|webm)$", RegexOption.IGNORE_CASE)
        /** name -> thumbnail of each camera tile on fishtank's live page. */
        private const val CAMERA_THUMBS_JS = "(function(){var o={};" +
            "document.querySelectorAll('button[aria-label^=\"Watch \"]').forEach(function(b){" +
            "var i=b.querySelector('img');if(i&&i.src)o[b.getAttribute('aria-label').slice(6)]=i.src;});" +
            "return JSON.stringify(o);})()"
    }
}
