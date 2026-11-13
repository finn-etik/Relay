package dev.relay

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

class SonosControlTest {

    // ---- Pure helpers ----

    @Test
    fun radioUriReplacesSchemeInsteadOfPrepending() {
        assertEquals(
            "x-rincon-mp3radio://192.168.1.5:8079/stream.aac?t=abc",
            SonosControl.radioUri("http://192.168.1.5:8079/stream.aac?t=abc")
        )
    }

    @Test
    fun unescapeIsSingleLevel() {
        assertEquals("&lt;b&gt;", SonosControl.xmlUnescape("&amp;lt;b&amp;gt;"))
        assertEquals("Tom's \"A&B\" <x>", SonosControl.xmlUnescape("Tom&apos;s &quot;A&amp;B&quot; &lt;x&gt;"))
        assertEquals("'", SonosControl.xmlUnescape("&#39;"))
    }

    @Test
    fun tagHandlesEmptyAndSelfClosing() {
        assertEquals("", SonosControl.tag("<a><CurrentURI></CurrentURI></a>", "CurrentURI"))
        assertEquals("", SonosControl.tag("<a><CurrentURI/></a>", "CurrentURI"))
        assertNull(SonosControl.tag("<a></a>", "CurrentURI"))
    }

    @Test
    fun topologyListsEachGroupOnceAtItsCoordinator() {
        val groups = SonosControl.parseZoneGroups(TOPOLOGY)
        assertEquals(listOf("Kitchen", "Living & Dining", "Office"), groups.map { it.name })

        val living = groups.first { it.name == "Living & Dining" }
        // Stereo-pair partner and surrounds are hidden; satellites too.
        assertEquals("192.168.1.20", living.ip)
        assertEquals("RINCON_LIV1", living.uuid)
        assertEquals("192.168.1.20", living.detail)

        val kitchen = groups.first { it.name == "Kitchen" }
        // Grouped room: addressed to the coordinator, other room listed.
        assertEquals("192.168.1.30", kitchen.ip)
        assertEquals("+ Bath", kitchen.detail)

        // Bridge-only group is skipped; bath (grouped member) is not listed alone.
        assertFalse(groups.any { it.name == "BRIDGE" || it.name == "Bath" })
    }

    // ---- SOAP against a fake speaker ----

    private lateinit var fake: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    @Volatile private var refuseMetadata = false

    @Before
    fun startFake() {
        fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake.createContext("/") { ex ->
            val action = ex.requestHeaders.getFirst("SOAPACTION") ?: ""
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += action to body
            val (code, reply) = when {
                action.endsWith("#SetAVTransportURI\"") && refuseMetadata && body.contains("DIDL-Lite") ->
                    500 to "<s:Fault/>"
                action.endsWith("#SetRelativeVolume\"") -> 200 to "<NewVolume>37</NewVolume>"
                action.endsWith("#GetVolume\"") -> 200 to "<CurrentVolume>21</CurrentVolume>"
                action.endsWith("#GetMediaInfo\"") ->
                    200 to "<CurrentURI>x-rincon-mp3radio://h/stream.aac?t=abc&amp;x=1</CurrentURI>"
                action.endsWith("#GetZoneGroupState\"") ->
                    200 to "<ZoneGroupState>${SonosControl.xmlEscape(TOPOLOGY)}</ZoneGroupState>"
                else -> 200 to "<ok/>"
            }
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        fake.start()
        SonosControl.port = fake.address.port
        SonosControl.lanConnector = null
    }

    @After
    fun stopFake() {
        fake.stop(0)
        SonosControl.port = 1400
    }

    @Test
    fun playSendsRadioUriWithMetadataThenPlay() {
        assertTrue(SonosControl.playStreamBlocking("127.0.0.1", "http://10.0.0.2:8079/stream.aac?t=tok", "Relay"))
        assertEquals(2, requests.size)
        val (setAction, setBody) = requests[0]
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", setAction)
        assertTrue(setBody.contains("<CurrentURI>x-rincon-mp3radio://10.0.0.2:8079/stream.aac?t=tok</CurrentURI>"))
        assertTrue(setBody.contains("&lt;dc:title&gt;Relay&lt;/dc:title&gt;"))
        assertTrue(requests[1].first.endsWith("#Play\""))
    }

    @Test
    fun playFallsBackToEmptyMetadata() {
        refuseMetadata = true
        assertTrue(SonosControl.playStreamBlocking("127.0.0.1", "http://10.0.0.2:8079/s.aac?t=1", "Relay"))
        assertEquals(3, requests.size)
        assertTrue(requests[1].second.contains("<CurrentURIMetaData></CurrentURIMetaData>"))
    }

    @Test
    fun pinnedConnectorFailureFallsBackToDefaultRoute() {
        SonosControl.lanConnector = { throw java.io.IOException("EPERM") }
        assertNotNull(SonosControl.currentUri("127.0.0.1"))
        SonosControl.lanConnector = null
    }

    @Test
    fun relativeVolumeReportsSpeakerLevel() {
        var got = -1
        val latch = java.util.concurrent.CountDownLatch(1)
        SonosControl.adjustVolume("127.0.0.1", 4) { got = it; latch.countDown() }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(37, got)
        assertTrue(requests.single().second.contains("<Adjustment>4</Adjustment>"))
    }

    @Test
    fun mediaInfoAndTopologyAreUnescaped() {
        assertEquals("x-rincon-mp3radio://h/stream.aac?t=abc&x=1", SonosControl.currentUri("127.0.0.1"))
        assertEquals(3, SonosControl.zoneGroups("127.0.0.1")?.size)
    }

    @Test
    fun unreachableSpeakerYieldsNull() {
        SonosControl.port = 1 // nothing listens there
        assertNull(SonosControl.currentUri("127.0.0.1"))
        assertFalse(SonosControl.playStreamBlocking("127.0.0.1", "http://a:1/x", "Relay"))
    }

    companion object {
        // Shape of a real S2 GetZoneGroupState payload (after one unescape).
        val TOPOLOGY = """
            <ZoneGroupState><ZoneGroups>
            <ZoneGroup Coordinator="RINCON_LIV1" ID="RINCON_LIV1:12">
              <ZoneGroupMember UUID="RINCON_LIV1" Location="http://192.168.1.20:1400/xml/device_description.xml" ZoneName="Living &amp; Dining" Invisible="0">
                <Satellite UUID="RINCON_SUB" Location="http://192.168.1.23:1400/xml/device_description.xml" ZoneName="Living &amp; Dining" Invisible="1"/>
              </ZoneGroupMember>
              <ZoneGroupMember UUID="RINCON_LIV2" Location="http://192.168.1.21:1400/xml/device_description.xml" ZoneName="Living &amp; Dining" Invisible="1"/>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_KIT" ID="RINCON_KIT:3">
              <ZoneGroupMember UUID="RINCON_BATH" Location="http://192.168.1.31:1400/xml/device_description.xml" ZoneName="Bath"/>
              <ZoneGroupMember UUID="RINCON_KIT" Location="http://192.168.1.30:1400/xml/device_description.xml" ZoneName="Kitchen"/>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_OFF" ID="RINCON_OFF:9">
              <ZoneGroupMember UUID="RINCON_OFF" Location="http://192.168.1.40:1400/xml/device_description.xml" ZoneName="Office"/>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_BR" ID="RINCON_BR:1">
              <ZoneGroupMember UUID="RINCON_BR" Location="http://192.168.1.50:1400/xml/device_description.xml" ZoneName="BRIDGE" IsZoneBridge="1"/>
            </ZoneGroup>
            </ZoneGroups><VanishedDevices/></ZoneGroupState>
        """.trimIndent()
    }
}
