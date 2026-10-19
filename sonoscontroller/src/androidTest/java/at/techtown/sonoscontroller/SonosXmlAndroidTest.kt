package at.techtown.sonoscontroller

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SonosXmlAndroidTest {
    @Test fun deviceDescriptionParsesOnAndroid() {
        val doc = SonosXml.parse("""<root xmlns="urn:schemas-upnp-org:device-1-0"><device><manufacturer>Sonos, Inc.</manufacturer><roomName>Wohnzimmer</roomName><UDN>uuid:RINCON_TEST</UDN></device></root>""")
        with(SonosXml) {
            assertEquals("Sonos, Inc.", doc.value("manufacturer"))
            assertEquals("Wohnzimmer", doc.value("roomName"))
        }
    }

    @Test fun soapAndEscapedTopologyParseOnAndroid() {
        val topology = """<ZoneGroups><ZoneGroup Coordinator="RINCON_TEST"><ZoneGroupMember UUID="RINCON_TEST" ZoneName="Wohnzimmer" Location="http://192.168.0.20:1400/xml/device_description.xml"/></ZoneGroup></ZoneGroups>"""
        val doc = SonosXml.parse("<root><ZoneGroupState>" + SonosXml.escape(topology) + "</ZoneGroupState></root>")
        with(SonosXml) {
            assertEquals("192.168.0.20", coordinator(doc.value("ZoneGroupState"), SonosDevice("RINCON_TEST", "Wohnzimmer", "192.168.0.20")).host)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun externalEntityDocumentIsRejected() {
        SonosXml.parse("""<!DOCTYPE root [<!ENTITY x SYSTEM "file:///private">]><root>&x;</root>""")
    }
}