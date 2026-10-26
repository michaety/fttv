package com.fishtank.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * A minimal UPnP/DLNA MediaRenderer, so phone casting apps (BubbleUPnP, Web
 * Video Caster, ...) list this app as a target and can send it a video URL.
 *
 * Three pieces, all on background threads, alive only between start() and
 * stop() (i.e. while the app is in the foreground):
 *  - SSDP: answers multicast M-SEARCH discovery on 239.255.255.250:1900 and
 *    announces itself with NOTIFY alive/byebye.
 *  - HTTP: serves the device description and service descriptions (SCPD),
 *    and takes the SOAP control calls.
 *  - SOAP: AVTransport (SetAVTransportURI, Play, Pause, Stop, Seek,
 *    GetPositionInfo, GetTransportInfo, GetMediaInfo, ...), RenderingControl
 *    (volume/mute) and ConnectionManager (protocol info).
 *
 * Event subscriptions (GENA) are accepted but no events are sent -- control
 * points then fall back to polling GetPositionInfo/GetTransportInfo, which
 * all the common ones do.
 *
 * Playback itself is delegated to [Player], whose methods are called on
 * these background threads and must hand off to the UI thread themselves.
 */
class UpnpRenderer(
    private val context: Context,
    private val friendlyName: String,
    private val player: Player,
) {
    interface Player {
        fun load(uri: String, title: String?)
        fun play()
        fun pause()
        fun stop()
        fun seekTo(positionMs: Long)
        fun setVolume(percent: Int)
        fun setMuted(muted: Boolean)
        /** Snapshot, readable from any thread. */
        fun status(): Status
    }

    data class Status(
        val state: String,      // PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT
        val uri: String,
        val positionMs: Long,
        val durationMs: Long,
        val volume: Int,
        val muted: Boolean,
    )

    companion object {
        private const val TAG = "FishtankTV"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val HTTP_PORT = 49494
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private val SERVICES = listOf(
            Service("AVTransport", "urn:upnp-org:serviceId:AVTransport"),
            Service("RenderingControl", "urn:upnp-org:serviceId:RenderingControl"),
            Service("ConnectionManager", "urn:upnp-org:serviceId:ConnectionManager"),
        )
        private const val SINK_PROTOCOLS =
            "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*," +
                "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/webm:*," +
                "http-get:*:video/mpeg:*,http-get:*:video/*:*,http-get:*:audio/*:*"
    }

    private data class Service(val name: String, val id: String) {
        val type get() = "urn:schemas-upnp-org:service:$name:1"
    }

    @Volatile private var running = false
    private var httpServer: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var httpPort = HTTP_PORT
    private val udn: String = "uuid:" + context.getSharedPreferences("fishtank_tv_prefs", Context.MODE_PRIVATE).let { p ->
        p.getString("upnp_uuid", null) ?: UUID.randomUUID().toString().also { p.edit().putString("upnp_uuid", it).apply() }
    }

    fun start() {
        if (running) return
        running = true
        multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("fttv-upnp")?.apply { setReferenceCounted(false); acquire() }
        thread(name = "upnp-http", isDaemon = true) { runHttp() }
        thread(name = "upnp-ssdp", isDaemon = true) { runSsdp() }
        Log.i(TAG, "UPnP renderer '$friendlyName' starting")
    }

    fun stop() {
        if (!running) return
        running = false
        thread(isDaemon = true) { try { notify("ssdp:byebye") } catch (e: Exception) {} }
        try { httpServer?.close() } catch (e: Exception) {}
        try { ssdpSocket?.close() } catch (e: Exception) {}
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    /** "http://192.168.x.y:49494" -- for showing in the app menu. */
    fun address(): String? = localAddress()?.let { "http://${it.hostAddress}:$httpPort" }

    // ---- SSDP ------------------------------------------------------------

    private fun localAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        } catch (e: Exception) { null }
    }

    private fun location(): String = "http://${localAddress()?.hostAddress}:$httpPort/description.xml"

    private fun notificationTypes(): List<Pair<String, String>> {
        val list = mutableListOf(
            "upnp:rootdevice" to "$udn::upnp:rootdevice",
            udn to udn,
            DEVICE_TYPE to "$udn::$DEVICE_TYPE",
        )
        SERVICES.forEach { list.add(it.type to "$udn::${it.type}") }
        return list
    }

    private fun notify(nts: String) {
        val socket = ssdpSocket ?: return
        val group = InetAddress.getByName(SSDP_ADDR)
        for ((nt, usn) in notificationTypes()) {
            val msg = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("LOCATION: ${location()}\r\n")
                append("NT: $nt\r\n")
                append("NTS: $nts\r\n")
                append("SERVER: Android/1 UPnP/1.0 FireTV/1\r\n")
                append("USN: $usn\r\n\r\n")
            }.toByteArray()
            socket.send(DatagramPacket(msg, msg.size, group, SSDP_PORT))
        }
    }

    private fun runSsdp() {
        try {
            val socket = MulticastSocket(null).apply {
                reuseAddress = true   // other UPnP apps on the stick may hold :1900 too
                bind(InetSocketAddress(SSDP_PORT))
                joinGroup(InetAddress.getByName(SSDP_ADDR))
                soTimeout = 1000
            }
            ssdpSocket = socket
            var lastNotify = 0L
            val buf = ByteArray(2048)
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastNotify > 60_000) {
                    try { notify("ssdp:alive") } catch (e: Exception) { Log.w(TAG, "SSDP notify failed", e) }
                    lastNotify = now
                }
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: java.net.SocketTimeoutException) { continue }
                val text = String(packet.data, 0, packet.length)
                if (!text.startsWith("M-SEARCH")) continue
                val st = Regex("(?im)^ST:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1) ?: continue
                val matches = when (st) {
                    "ssdp:all" -> notificationTypes()
                    else -> notificationTypes().filter { it.first.equals(st, ignoreCase = true) }
                }
                for ((nt, usn) in matches) {
                    val reply = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("CACHE-CONTROL: max-age=1800\r\n")
                        append("EXT:\r\n")
                        append("LOCATION: ${location()}\r\n")
                        append("SERVER: Android/1 UPnP/1.0 FireTV/1\r\n")
                        append("ST: $nt\r\n")
                        append("USN: $usn\r\n\r\n")
                    }.toByteArray()
                    try { socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "SSDP stopped", e)
        }
    }

    // ---- HTTP + SOAP -----------------------------------------------------

    private fun runHttp() {
        val server = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(HTTP_PORT)) }
        } catch (e: Exception) {
            ServerSocket(0)
        }
        httpPort = server.localPort
        httpServer = server
        while (running) {
            val client = try { server.accept() } catch (e: Exception) { break }
            thread(isDaemon = true) { client.use { handle(it) } }
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input)
        if (requestLine.isEmpty()) return
        val (method, path) = requestLine.split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) String(ByteArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }) else ""

        when {
            method == "GET" && path == "/description.xml" -> respond(socket, 200, "text/xml", deviceDescription())
            method == "GET" && path.startsWith("/scpd/") -> {
                val name = path.removePrefix("/scpd/").removeSuffix(".xml")
                val xml = scpd(name)
                if (xml != null) respond(socket, 200, "text/xml", xml) else respond(socket, 404, "text/plain", "")
            }
            method == "POST" && path.startsWith("/control/") -> {
                val action = headers["soapaction"]?.trim('"')?.substringAfter('#') ?: ""
                val (status, xml) = control(path.removePrefix("/control/"), action, body)
                respond(socket, status, "text/xml; charset=\"utf-8\"", xml)
            }
            method == "SUBSCRIBE" -> respond(socket, 200, null, "", mapOf(
                "SID" to (headers["sid"] ?: "uuid:" + UUID.randomUUID()),
                "TIMEOUT" to "Second-1800",
            ))
            method == "UNSUBSCRIBE" -> respond(socket, 200, null, "")
            else -> respond(socket, 404, "text/plain", "")
        }
    }

    private fun respond(socket: Socket, status: Int, type: String?, body: String, extra: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${if (status == 200) "OK" else if (status == 500) "Internal Server Error" else "Not Found"}\r\n")
            if (type != null) append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Server: Android/1 UPnP/1.0 FireTV/1\r\n")
            append("Connection: close\r\n")
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    private fun arg(body: String, name: String): String =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?$name>").find(body)?.groupValues?.get(1)?.let(::unescape) ?: ""

    private fun control(service: String, action: String, body: String): Pair<Int, String> {
        val st = player.status()
        val type = "urn:schemas-upnp-org:service:$service:1"
        val out: Map<String, String> = when ("$service#$action") {
            "AVTransport#SetAVTransportURI" -> {
                val uri = arg(body, "CurrentURI")
                val meta = arg(body, "CurrentURIMetaData")
                val title = Regex("<dc:title>([\\s\\S]*?)</dc:title>").find(meta)?.groupValues?.get(1)?.let(::unescape)
                if (uri.isBlank()) return 500 to fault(714, "Illegal MIME-type")
                lastMetadata = meta
                player.load(uri, title)
                emptyMap()
            }
            "AVTransport#SetNextAVTransportURI" -> emptyMap()
            "AVTransport#Play" -> { player.play(); emptyMap() }
            "AVTransport#Pause" -> { player.pause(); emptyMap() }
            "AVTransport#Stop" -> { player.stop(); emptyMap() }
            "AVTransport#Seek" -> {
                val unit = arg(body, "Unit")
                val target = arg(body, "Target")
                if (unit == "REL_TIME" || unit == "ABS_TIME") player.seekTo(parseTime(target))
                emptyMap()
            }
            "AVTransport#GetTransportInfo" -> mapOf(
                "CurrentTransportState" to st.state,
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1",
            )
            "AVTransport#GetPositionInfo" -> mapOf(
                "Track" to if (st.uri.isEmpty()) "0" else "1",
                "TrackDuration" to fmtTime(st.durationMs),
                "TrackMetaData" to escape(lastMetadata),
                "TrackURI" to escape(st.uri),
                "RelTime" to fmtTime(st.positionMs),
                "AbsTime" to fmtTime(st.positionMs),
                "RelCount" to "2147483647",
                "AbsCount" to "2147483647",
            )
            "AVTransport#GetMediaInfo" -> mapOf(
                "NrTracks" to if (st.uri.isEmpty()) "0" else "1",
                "MediaDuration" to fmtTime(st.durationMs),
                "CurrentURI" to escape(st.uri),
                "CurrentURIMetaData" to escape(lastMetadata),
                "NextURI" to "", "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
            )
            "AVTransport#GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "AVTransport#GetDeviceCapabilities" -> mapOf(
                "PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED",
            )
            "AVTransport#GetCurrentTransportActions" -> mapOf("Actions" to "Play,Pause,Stop,Seek")
            "RenderingControl#GetVolume" -> mapOf("CurrentVolume" to st.volume.toString())
            "RenderingControl#SetVolume" -> { player.setVolume(arg(body, "DesiredVolume").toIntOrNull() ?: st.volume); emptyMap() }
            "RenderingControl#GetMute" -> mapOf("CurrentMute" to if (st.muted) "1" else "0")
            "RenderingControl#SetMute" -> { player.setMuted(arg(body, "DesiredMute").let { it == "1" || it.equals("true", true) }); emptyMap() }
            "ConnectionManager#GetProtocolInfo" -> mapOf("Source" to "", "Sink" to SINK_PROTOCOLS)
            "ConnectionManager#GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
            "ConnectionManager#GetCurrentConnectionInfo" -> mapOf(
                "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
            )
            else -> return 500 to fault(401, "Invalid Action")
        }
        val args = out.entries.joinToString("") { "<${it.key}>${it.value}</${it.key}>" }
        return 200 to soap("<u:${action}Response xmlns:u=\"$type\">$args</u:${action}Response>")
    }

    /** The DIDL-Lite the controller sent with the URI; some re-read it. */
    @Volatile private var lastMetadata = ""

    private fun soap(inner: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>$inner</s:Body></s:Envelope>"

    private fun fault(code: Int, desc: String) = soap(
        "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>$desc</errorDescription></UPnPError></detail></s:Fault>"
    )

    private fun parseTime(t: String): Long {
        val parts = t.split(":").map { it.toDoubleOrNull() ?: 0.0 }
        val secs = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> parts.getOrElse(0) { 0.0 }
        }
        return (secs * 1000).toLong()
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    // ---- descriptions ----------------------------------------------------

    private fun deviceDescription(): String {
        val services = SERVICES.joinToString("") {
            "<service><serviceType>${it.type}</serviceType><serviceId>${it.id}</serviceId>" +
                "<SCPDURL>/scpd/${it.name}.xml</SCPDURL><controlURL>/control/${it.name}</controlURL>" +
                "<eventSubURL>/event/${it.name}</eventSubURL></service>"
        }
        return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><device>" +
            "<deviceType>$DEVICE_TYPE</deviceType><friendlyName>${escape(friendlyName)}</friendlyName>" +
            "<manufacturer>fttv</manufacturer><modelName>${escape(friendlyName)}</modelName>" +
            "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><UDN>$udn</UDN>" +
            "<serviceList>$services</serviceList></device></root>"
    }

    private fun scpd(name: String): String? {
        fun action(n: String, vararg args: Triple<String, String, String>) =
            "<action><name>$n</name><argumentList>" + args.joinToString("") { (a, dir, v) ->
                "<argument><name>$a</name><direction>$dir</direction><relatedStateVariable>$v</relatedStateVariable></argument>"
            } + "</argumentList></action>"
        fun i(a: String, v: String) = Triple(a, "in", v)
        fun o(a: String, v: String) = Triple(a, "out", v)
        fun sv(n: String, type: String = "string", events: Boolean = false, allowed: List<String> = emptyList()) =
            "<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$n</name><dataType>$type</dataType>" +
                (if (allowed.isEmpty()) "" else "<allowedValueList>" + allowed.joinToString("") { "<allowedValue>$it</allowedValue>" } + "</allowedValueList>") +
                "</stateVariable>"
        val iid = i("InstanceID", "A_ARG_TYPE_InstanceID")
        val (actions, vars) = when (name) {
            "AVTransport" -> listOf(
                action("SetAVTransportURI", iid, i("CurrentURI", "AVTransportURI"), i("CurrentURIMetaData", "AVTransportURIMetaData")),
                action("SetNextAVTransportURI", iid, i("NextURI", "NextAVTransportURI"), i("NextURIMetaData", "NextAVTransportURIMetaData")),
                action("Play", iid, i("Speed", "TransportPlaySpeed")),
                action("Pause", iid),
                action("Stop", iid),
                action("Seek", iid, i("Unit", "A_ARG_TYPE_SeekMode"), i("Target", "A_ARG_TYPE_SeekTarget")),
                action("GetTransportInfo", iid, o("CurrentTransportState", "TransportState"), o("CurrentTransportStatus", "TransportStatus"), o("CurrentSpeed", "TransportPlaySpeed")),
                action("GetPositionInfo", iid, o("Track", "CurrentTrack"), o("TrackDuration", "CurrentTrackDuration"), o("TrackMetaData", "CurrentTrackMetaData"), o("TrackURI", "CurrentTrackURI"), o("RelTime", "RelativeTimePosition"), o("AbsTime", "AbsoluteTimePosition"), o("RelCount", "RelativeCounterPosition"), o("AbsCount", "AbsoluteCounterPosition")),
                action("GetMediaInfo", iid, o("NrTracks", "NumberOfTracks"), o("MediaDuration", "CurrentMediaDuration"), o("CurrentURI", "AVTransportURI"), o("CurrentURIMetaData", "AVTransportURIMetaData"), o("NextURI", "NextAVTransportURI"), o("NextURIMetaData", "NextAVTransportURIMetaData"), o("PlayMedium", "PlaybackStorageMedium"), o("RecordMedium", "RecordStorageMedium"), o("WriteStatus", "RecordMediumWriteStatus")),
                action("GetTransportSettings", iid, o("PlayMode", "CurrentPlayMode"), o("RecQualityMode", "CurrentRecordQualityMode")),
                action("GetDeviceCapabilities", iid, o("PlayMedia", "PossiblePlaybackStorageMedia"), o("RecMedia", "PossibleRecordStorageMedia"), o("RecQualityModes", "PossibleRecordQualityModes")),
                action("GetCurrentTransportActions", iid, o("Actions", "CurrentTransportActions")),
            ) to listOf(
                sv("A_ARG_TYPE_InstanceID", "ui4"), sv("AVTransportURI"), sv("AVTransportURIMetaData"), sv("NextAVTransportURI"),
                sv("NextAVTransportURIMetaData"), sv("TransportPlaySpeed", allowed = listOf("1")),
                sv("A_ARG_TYPE_SeekMode", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")), sv("A_ARG_TYPE_SeekTarget"),
                sv("TransportState", allowed = listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")),
                sv("TransportStatus", allowed = listOf("OK", "ERROR_OCCURRED")), sv("CurrentTrack", "ui4"), sv("CurrentTrackDuration"),
                sv("CurrentTrackMetaData"), sv("CurrentTrackURI"), sv("RelativeTimePosition"), sv("AbsoluteTimePosition"),
                sv("RelativeCounterPosition", "i4"), sv("AbsoluteCounterPosition", "i4"), sv("NumberOfTracks", "ui4"),
                sv("CurrentMediaDuration"), sv("PlaybackStorageMedium"), sv("RecordStorageMedium"), sv("RecordMediumWriteStatus"),
                sv("CurrentPlayMode", allowed = listOf("NORMAL")), sv("CurrentRecordQualityMode"), sv("PossiblePlaybackStorageMedia"),
                sv("PossibleRecordStorageMedia"), sv("PossibleRecordQualityModes"), sv("CurrentTransportActions"),
                sv("LastChange", events = true),
            )
            "RenderingControl" -> listOf(
                action("GetVolume", iid, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentVolume", "Volume")),
                action("SetVolume", iid, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredVolume", "Volume")),
                action("GetMute", iid, i("Channel", "A_ARG_TYPE_Channel"), o("CurrentMute", "Mute")),
                action("SetMute", iid, i("Channel", "A_ARG_TYPE_Channel"), i("DesiredMute", "Mute")),
            ) to listOf(
                sv("A_ARG_TYPE_InstanceID", "ui4"), sv("A_ARG_TYPE_Channel", allowed = listOf("Master")),
                sv("Volume", "ui2"), sv("Mute", "boolean"), sv("LastChange", events = true),
            )
            "ConnectionManager" -> listOf(
                action("GetProtocolInfo", o("Source", "SourceProtocolInfo"), o("Sink", "SinkProtocolInfo")),
                action("GetCurrentConnectionIDs", o("ConnectionIDs", "CurrentConnectionIDs")),
                action("GetCurrentConnectionInfo", i("ConnectionID", "A_ARG_TYPE_ConnectionID"), o("RcsID", "A_ARG_TYPE_RcsID"), o("AVTransportID", "A_ARG_TYPE_AVTransportID"), o("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), o("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"), o("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), o("Direction", "A_ARG_TYPE_Direction"), o("Status", "A_ARG_TYPE_ConnectionStatus")),
            ) to listOf(
                sv("SourceProtocolInfo", events = true), sv("SinkProtocolInfo", events = true), sv("CurrentConnectionIDs", events = true),
                sv("A_ARG_TYPE_ConnectionID", "i4"), sv("A_ARG_TYPE_RcsID", "i4"), sv("A_ARG_TYPE_AVTransportID", "i4"),
                sv("A_ARG_TYPE_ProtocolInfo"), sv("A_ARG_TYPE_ConnectionManager"),
                sv("A_ARG_TYPE_Direction", allowed = listOf("Input", "Output")),
                sv("A_ARG_TYPE_ConnectionStatus", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
            )
            else -> return null
        }
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>${actions.joinToString("")}</actionList>" +
            "<serviceStateTable>${vars.joinToString("")}</serviceStateTable></scpd>"
    }
}
