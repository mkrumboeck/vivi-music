package at.techtown.sonoscontroller

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class SonosDevice(val id: String, val name: String, val host: String)
internal data class TransportState(val playing: Boolean, val position: Int, val duration: Int, val uri: String, val status: SonosTransportStatus = if (playing) SonosTransportStatus.PLAYING else SonosTransportStatus.PAUSED)


internal object SonosXml {
    fun parse(xml: String): Document {
        // Android's DocumentBuilderFactory rejects the external-entity SAX feature flags.
        // Reject DTDs before parsing and deny entity resolution on both Android and the JVM.
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DTD nicht erlaubt" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("Externe XML-Entitäten nicht erlaubt") }
        }
        return builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }
    fun Document.value(tag: String): String =
        getElementsByTagNameNS("*", tag).item(0)?.textContent.orEmpty()
    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun envelope(service: String, action: String, args: Map<String, String>): String =
        """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">""" +
            args.entries.joinToString("") { "<${it.key}>${escape(it.value)}</${it.key}>" } +
            "</u:$action></s:Body></s:Envelope>"
    fun seconds(value: String): Int {
        val parts = value.split(":")
        if (parts.size != 3) return 0
        val h = parts[0].toIntOrNull() ?: return 0
        val m = parts[1].toIntOrNull() ?: return 0
        val s = parts[2].substringBefore('.').toIntOrNull() ?: return 0
        return (h * 3600 + m * 60 + s).coerceAtLeast(0)
    }
    fun timestamp(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
    }
    fun coordinator(xml: String, device: SonosDevice): SonosDevice {
        val groups = parse(xml).getElementsByTagNameNS("*", "ZoneGroup")
        for (i in 0 until groups.length) {
            val group = groups.item(i) as Element
            val members = group.getElementsByTagNameNS("*", "ZoneGroupMember")
            val inGroup = (0 until members.length).any {
                (members.item(it) as Element).getAttribute("UUID") == device.id
            }
            if (!inGroup) continue
            val coordinatorId = group.getAttribute("Coordinator")
            for (j in 0 until members.length) {
                val member = members.item(j) as Element
                if (member.getAttribute("UUID") == coordinatorId) {
                    val host = URL(member.getAttribute("Location")).host
                    return SonosDevice(coordinatorId, member.getAttribute("ZoneName"), host)
                }
            }
        }
        throw SonosException("Gruppenkoordinator nicht gefunden. Geräte erneut suchen.")
    }
}

internal interface SonosControl {
    fun device(host: String): SonosDevice
    fun coordinator(device: SonosDevice): SonosDevice
    fun state(device: SonosDevice): TransportState
    fun volume(device: SonosDevice): Int
    fun setVolume(device: SonosDevice, volume: Int)
    fun load(device: SonosDevice, uri: String, metadata: SonosMetadata? = null, mimeType: String = "audio/mpeg")
    fun play(device: SonosDevice)
    fun pause(device: SonosDevice)
    fun stop(device: SonosDevice)
    fun seek(device: SonosDevice, seconds: Int)
}

internal class SonosClient(private val connection: (URL) -> HttpURLConnection) : SonosControl {
    private fun get(url: URL): String = connection(url).let { conn ->
        try {
            conn.connectTimeout = 4000; conn.readTimeout = 4000
            check(conn.responseCode == 200) { "Gerät antwortet mit HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
    override fun device(host: String): SonosDevice {
        val doc = SonosXml.parse(get(URL("http://$host:1400/xml/device_description.xml")))
        with(SonosXml) {
            if (!doc.value("manufacturer").contains("Sonos", true)) throw SonosException("Diese IP gehört zu keinem Sonos-Gerät.")
            return SonosDevice(doc.value("UDN").removePrefix("uuid:"),
                doc.value("roomName").ifBlank { doc.value("friendlyName") }, host)
        }
    }
    private fun action(device: SonosDevice, service: String, action: String,
                       args: Map<String, String> = mapOf("InstanceID" to "0")): Document {
        val path = when (service) {
            "AVTransport" -> "MediaRenderer/AVTransport"
            "RenderingControl" -> "MediaRenderer/RenderingControl"
            else -> "ZoneGroupTopology"
        }
        val conn = connection(URL("http://${device.host}:1400/$path/Control"))
        try {
            conn.connectTimeout = 4000; conn.readTimeout = 4000
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:$service:1#$action\"")
            conn.outputStream.use { it.write(SonosXml.envelope(service, action, args).toByteArray()) }
            val ok = conn.responseCode in 200..299
            val body = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) throw SonosException("Leere Sonos-Antwort (HTTP ${conn.responseCode}).")
            val doc = SonosXml.parse(body)
            with(SonosXml) {
                val code = doc.value("errorCode")
                if (!ok || code.isNotBlank()) throw SonosException(
                    when (code) {
                        "710", "711" -> SonosErrorCode.SEEK_UNSUPPORTED
                        else -> SonosErrorCode.PLAYBACK_REJECTED
                    }, when (code) {
                    "701" -> "Diese Aktion ist im aktuellen Wiedergabezustand nicht möglich."
                    "710", "711" -> "Sonos kann an diese Position nicht springen."
                    "714", "Illegal MIME-Type", "704" -> "Sonos unterstützt dieses Audioformat nicht."
                    else -> "Sonos lehnt $action ab (Code ${code.ifBlank { conn.responseCode.toString() }})."
                })
            }
            return doc
        } finally { conn.disconnect() }
    }
    override fun coordinator(device: SonosDevice): SonosDevice = with(SonosXml) {
        val doc = action(device, "ZoneGroupTopology", "GetZoneGroupState", emptyMap())
        coordinator(doc.value("ZoneGroupState"), device)
    }
    override fun state(device: SonosDevice): TransportState = with(SonosXml) {
        val transport = action(device, "AVTransport", "GetTransportInfo")
        val position = action(device, "AVTransport", "GetPositionInfo")
        val media = action(device, "AVTransport", "GetMediaInfo")
        // TrackURI can be empty or stale until the newly loaded transport starts.
        val sourceUri = media.value("CurrentURI").ifBlank { position.value("TrackURI") }
        TransportState(transport.value("CurrentTransportState") == "PLAYING",
            seconds(position.value("RelTime")), seconds(position.value("TrackDuration")), sourceUri,
            when (transport.value("CurrentTransportState")) {
                "PLAYING" -> SonosTransportStatus.PLAYING
                "PAUSED_PLAYBACK" -> SonosTransportStatus.PAUSED
                "STOPPED", "NO_MEDIA_PRESENT" -> SonosTransportStatus.STOPPED
                "TRANSITIONING" -> SonosTransportStatus.TRANSITIONING
                else -> SonosTransportStatus.UNKNOWN
            })
    }
    override fun volume(device: SonosDevice): Int = with(SonosXml) {
        action(device, "RenderingControl", "GetVolume", mapOf("InstanceID" to "0", "Channel" to "Master"))
            .value("CurrentVolume").toInt().coerceIn(0, 100)
    }
    override fun setVolume(device: SonosDevice, volume: Int) {
        action(device, "RenderingControl", "SetVolume", mapOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString()))
    }
    override fun load(device: SonosDevice, uri: String, metadata: SonosMetadata?, mimeType: String) {
        val title = SonosXml.escape(metadata?.title ?: "Audio")
        val artist = metadata?.artist?.let { "<dc:creator>" + SonosXml.escape(it) + "</dc:creator>" }.orEmpty()
        val cover = metadata?.coverUrl?.let { "<upnp:albumArtURI>" + SonosXml.escape(it) + "</upnp:albumArtURI>" }.orEmpty()
        val duration = metadata?.durationSeconds?.takeIf { it > 0 }?.let { " duration=\"" + SonosXml.timestamp(it) + "\"" }.orEmpty()
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="audio" parentID="-1" restricted="true"><dc:title>$title</dc:title>$artist$cover<upnp:class>object.item.audioItem.musicTrack</upnp:class><res$duration protocolInfo="http-get:*:${SonosXml.escape(mimeType)}:*">${SonosXml.escape(uri)}</res></item></DIDL-Lite>"""
        action(device, "AVTransport", "SetAVTransportURI", mapOf("InstanceID" to "0", "CurrentURI" to uri, "CurrentURIMetaData" to didl))
    }
    override fun play(device: SonosDevice) { action(device, "AVTransport", "Play", mapOf("InstanceID" to "0", "Speed" to "1")) }
    override fun pause(device: SonosDevice) { action(device, "AVTransport", "Pause") }
    override fun stop(device: SonosDevice) { action(device, "AVTransport", "Stop") }
    override fun seek(device: SonosDevice, seconds: Int) {
        action(device, "AVTransport", "Seek", mapOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to SonosXml.timestamp(seconds)))
    }
}
