package at.techtown.sonoscontroller

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class SonosProtocolTest {
    private val room = SonosDevice("BED", "Schlafzimmer", "192.168.1.21")
    private class Response(url: URL, private val xml: String, private val status: Int = 200) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream() = body
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(xml.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(xml.toByteArray())
    }
    @Test fun coordinatorUsesGroupContainingSelectedDevice() {
        val xml = """<ZoneGroups><ZoneGroup Coordinator="OTHER"><ZoneGroupMember UUID="OTHER" Location="http://192.168.1.99:1400/xml/device_description.xml" /></ZoneGroup><ZoneGroup Coordinator="LIVING"><ZoneGroupMember UUID="BED" ZoneName="Schlafzimmer" Location="http://192.168.1.21:1400/xml/device_description.xml"/><ZoneGroupMember UUID="LIVING" ZoneName="Wohnzimmer" Location="http://192.168.1.22:1400/xml/device_description.xml"/></ZoneGroup></ZoneGroups>"""
        assertEquals(SonosDevice("LIVING", "Wohnzimmer", "192.168.1.22"), SonosXml.coordinator(xml, room))
    }
    @Test(expected = SonosException::class) fun missingCoordinatorDoesNotSilentlyUseWrongSpeaker() {
        SonosXml.coordinator("<ZoneGroups/>", room)
    }
    @Test fun soapEscapesUrlsAndMetadata() {
        val request = SonosXml.envelope("AVTransport", "SetAVTransportURI", mapOf("CurrentURI" to "http://x/?a=1&b=2", "CurrentURIMetaData" to "<title>Test</title>"))
        val doc = SonosXml.parse(request)
        with(SonosXml) {
            assertEquals("http://x/?a=1&b=2", doc.value("CurrentURI"))
            assertEquals("<title>Test</title>", doc.value("CurrentURIMetaData"))
        }
    }
    @Test fun timestampsAndUnknownPositions() {
        assertEquals(3723, SonosXml.seconds("1:02:03.500"))
        assertEquals(0, SonosXml.seconds("NOT_IMPLEMENTED"))
        assertEquals("01:02:03", SonosXml.timestamp(3723))
        assertEquals("00:00:00", SonosXml.timestamp(-10))
    }
    @Test fun transportUsesNamespacedResponses() {
        val responses = ArrayDeque(listOf(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>""",
            """<root><RelTime>0:00:12</RelTime><TrackDuration>0:00:30</TrackDuration><TrackURI>http://phone/sample.m4a</TrackURI></root>""",
            "<root><CurrentURI>http://phone/sample.m4a</CurrentURI></root>"
        ))
        val client = SonosClient { Response(it, responses.removeFirst()) }
        assertEquals(TransportState(true, 12, 30, "http://phone/sample.m4a"), client.state(room))
    }
    @Test fun loadedTransportIsOwnedBeforeTheFirstPlayEvenWhenTrackUriIsEmptyOrStale() {
        listOf("", "http://other/old-track").forEach { trackUri ->
            val responses = ArrayDeque(listOf(
                "<root><CurrentTransportState>STOPPED</CurrentTransportState></root>",
                "<root><RelTime>0:00:00</RelTime><TrackDuration>0:00:00</TrackDuration><TrackURI>$trackUri</TrackURI></root>",
                "<root><CurrentURI>http://phone/new-track.m4a</CurrentURI></root>",
            ))
            val client = SonosClient { Response(it, responses.removeFirst()) }
            assertEquals("http://phone/new-track.m4a", client.state(room).uri)
        }
    }
    @Test fun volumeUsesSelectedRoomAndMasterChannel() {
        lateinit var response: Response
        val client = SonosClient { Response(it, "<root/>").also { conn -> response = conn } }
        client.setVolume(room, 150)
        assertEquals("192.168.1.21", response.url.host)
        assertEquals("/MediaRenderer/RenderingControl/Control", response.url.path)
        with(SonosXml) {
            val doc = parse(response.body.toString("UTF-8"))
            assertEquals("100", doc.value("DesiredVolume"))
            assertEquals("Master", doc.value("Channel"))
        }
    }
    @Test fun soapFaultGivesActionableError() {
        val client = SonosClient { Response(it, "<root><errorCode>711</errorCode></root>", 500) }
        try { client.seek(room, 10); fail("Expected fault") }
        catch (e: SonosException) { assertTrue(e.message!!.contains("springen")) }
    }
    @Test fun loadUsesPlayableAudioMetadata() {
        lateinit var response: Response
        val client = SonosClient { Response(it, "<root/>").also { conn -> response = conn } }
        client.load(room, "http://phone/test.m4a")
        with(SonosXml) {
            val doc = parse(response.body.toString("UTF-8"))
            val metadata = parse(doc.value("CurrentURIMetaData"))
            assertEquals("http://phone/test.m4a", metadata.value("res"))
            assertEquals("object.item.audioItem.musicTrack", metadata.value("class"))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun dtdIsRejected() {
        SonosXml.parse("""<!DOCTYPE root [<!ENTITY x SYSTEM "file:///private">]><root>&x;</root>""")
    }
    @Test fun transportDistinguishesStoppedFromPausedPlayback() {
        listOf("STOPPED" to SonosTransportStatus.STOPPED, "PAUSED_PLAYBACK" to SonosTransportStatus.PAUSED).forEach { (wire, status) ->
            val responses = ArrayDeque(listOf("<root><CurrentTransportState>$wire</CurrentTransportState></root>",
                "<root><RelTime>0:00:00</RelTime><TrackDuration>0:00:30</TrackDuration><TrackURI>http://phone/audio</TrackURI></root>",
                "<root><CurrentURI>http://phone/audio</CurrentURI></root>"))
            val client = SonosClient { Response(it, responses.removeFirst()) }
            assertEquals(status, client.state(room).status)
        }
    }
}
