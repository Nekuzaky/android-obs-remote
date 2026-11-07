package dev.nekuz.sceneremote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException

class ObsAuthTest {
    @Test
    fun documentationStyleVector() {
        val password = "supersecretpassword"
        val salt = "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI="
        val challenge = "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="
        assertEquals("H1IfVz1pSREUQzbFTVnX/Tyb+gMhMik5x7yUBCY0PTs=", ObsAuth.secret(password, salt))
        assertEquals("1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4=", ObsAuth.response(password, salt, challenge))
    }

    @Test
    fun simpleVector() {
        assertEquals("+34hqcs5dG+Kl5R+ocZlaJkGOYJ59ibg5DBXmFwfXPk=", ObsAuth.secret("hunter2", "c2FsdA=="))
        assertEquals("HbemcTRAK8GnBZpkRzKZdmk94xa5VYtjm6/uKbA1epI=", ObsAuth.response("hunter2", "c2FsdA==", "Y2hhbGxlbmdl"))
    }

    @Test
    fun unicodeAndWhitespacePasswordIsNotNormalized() {
        assertEquals(
            "UmSVycbrZt5kLBclEoVMV3HDD1NBddrPicAW2AriHZQ=",
            ObsAuth.response("pässwörd🔒 ", "c2FsdA==", "Y2hhbGxlbmdl"),
        )
    }

    @Test
    fun emptyPasswordVector() {
        assertEquals("kmEaZ9AUg7cRF07bRxvHT8JbwCU8B7877A/FP6woVNY=", ObsAuth.response("", "s", "c"))
    }
}

class AddressPolicyTest {
    private fun v4(text: String) = HostParser.parseIpv4(text)!!
    private fun v6(text: String) = HostParser.parseIpv6(text)!!

    @Test
    fun privateV4() {
        listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.255.254").forEach {
            assertEquals(it, AddressClass.PRIVATE, AddressPolicy.classify(v4(it)))
        }
    }

    @Test
    fun loopbackAndLinkLocalV4() {
        assertEquals(AddressClass.LOOPBACK, AddressPolicy.classify(v4("127.0.0.1")))
        assertEquals(AddressClass.LOOPBACK, AddressPolicy.classify(v4("127.255.0.9")))
        assertEquals(AddressClass.LINK_LOCAL, AddressPolicy.classify(v4("169.254.1.1")))
    }

    @Test
    fun refusedV4() {
        listOf(
            "8.8.8.8", "1.1.1.1", "9.255.255.255", "11.0.0.1", "172.15.255.255", "172.32.0.1", "192.167.1.1",
            "192.169.1.1", "169.253.1.1", "169.255.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
            "128.0.0.1", "126.0.0.1",
        ).forEach {
            assertEquals(it, AddressClass.REFUSED, AddressPolicy.classify(v4(it)))
        }
    }

    @Test
    fun allowedV6() {
        assertEquals(AddressClass.LOOPBACK, AddressPolicy.classify(v6("::1")))
        assertEquals(AddressClass.LINK_LOCAL, AddressPolicy.classify(v6("fe80::1")))
        assertEquals(AddressClass.LINK_LOCAL, AddressPolicy.classify(v6("febf::1")))
        assertEquals(AddressClass.PRIVATE, AddressPolicy.classify(v6("fc00::1")))
        assertEquals(AddressClass.PRIVATE, AddressPolicy.classify(v6("fd12:3456::1")))
        assertEquals(AddressClass.PRIVATE, AddressPolicy.classify(v6("::ffff:192.168.1.1")))
        assertEquals(AddressClass.LOOPBACK, AddressPolicy.classify(v6("::ffff:127.0.0.1")))
    }

    @Test
    fun refusedV6() {
        listOf(
            "::", "2001:4860:4860::8888", "fe00::1", "fec0::1", "fbff::1", "ff02::1", "::2", "::ffff:8.8.8.8",
            "::192.168.1.1", "64:ff9b::192.168.1.1", "2002:c0a8:101::1", "::ffff:0:192.168.1.1",
        ).forEach {
            assertEquals(it, AddressClass.REFUSED, AddressPolicy.classify(v6(it)))
        }
    }

    @Test
    fun oddSizesRefused() {
        assertEquals(AddressClass.REFUSED, AddressPolicy.classify(ByteArray(0)))
        assertEquals(AddressClass.REFUSED, AddressPolicy.classify(ByteArray(5)))
        assertEquals(AddressClass.REFUSED, AddressPolicy.classify(ByteArray(15)))
    }
}

class HostParserTest {
    private fun ok(text: String) = (HostParser.parse(text) as HostParse.Ok).spec
    private fun error(text: String?) = (HostParser.parse(text) as HostParse.Bad).error

    @Test
    fun ports() {
        assertEquals(4455, HostParser.parsePort("4455"))
        assertEquals(1, HostParser.parsePort("1"))
        assertEquals(65535, HostParser.parsePort(" 65535 "))
        listOf(null, "", "0", "65536", "99999", "-1", "+1", "1e3", "0x10", "abc", "123456", "٤٤٥٥", "44 55").forEach {
            assertNull(it, HostParser.parsePort(it))
        }
    }

    @Test
    fun literalV4() {
        val spec = ok(" 192.168.1.20 ") as HostSpec.Literal
        assertEquals("192.168.1.20", spec.display)
        assertEquals("192.168.1.20", spec.headerHost)
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), spec.bytes)
    }

    @Test
    fun malformedV4() {
        listOf("192.168.1", "192.168.1.1.1", "192.168.01.1", "010.0.0.1", "256.1.1.1", "1.2.3.", ".1.2.3", "10..0.1", "0127.0.0.1", "2130706433").forEach {
            assertEquals(it, HostError.INVALID, error(it))
        }
    }

    @Test
    fun publicLiteralsAreRefusedWithExplanationCode() {
        listOf("8.8.8.8", "2001:db8::1", "[2606:4700::1111]", "::ffff:8.8.8.8", "100.64.0.1").forEach {
            assertEquals(it, HostError.PUBLIC, error(it))
        }
    }

    @Test
    fun emptyAndLong() {
        assertEquals(HostError.EMPTY, error(null))
        assertEquals(HostError.EMPTY, error("   "))
        assertEquals(HostError.TOO_LONG, error("a".repeat(256)))
    }

    @Test
    fun names() {
        assertEquals("obs-pc.local", ok("OBS-PC.local").display)
        assertEquals("obs-pc.local", ok("obs-pc.local.").headerHost)
        assertEquals("localhost", ok("localhost").display)
        assertTrue(ok("my-host") is HostSpec.Name)
    }

    @Test
    fun hostileNames() {
        listOf(
            "evil.com\r\nX: y", "a b", "a\nb", "a/b", "a?b", "a@b", "http://x", "-a.local", "a-.local", "a..b",
            ".a", "é.local", "a_b.local", "a%00.local", "x".repeat(64) + ".local", "1.2.3.4.5", "foo.123", "foo.0x7f",
            "0x7f.1", "a\u0000b", "‮local",
        ).forEach {
            assertEquals(it, HostError.INVALID, error(it))
        }
    }

    @Test
    fun nameLengthLimit() {
        val label = "a".repeat(63)
        assertTrue(HostParser.isValidName(listOf(label, label, label, "a".repeat(61)).joinToString(".")))
        assertFalse(HostParser.isValidName(listOf(label, label, label, "a".repeat(62)).joinToString(".")))
    }

    @Test
    fun literalV6() {
        val plain = ok("[fe80::1]") as HostSpec.Literal
        assertEquals("[fe80::1]", plain.headerHost)
        assertNull(plain.scope)
        val noBrackets = ok("fd00:0:0:0:0:0:0:1") as HostSpec.Literal
        assertEquals("[fd00::1]", noBrackets.headerHost)
        val scoped = ok("fe80::1%wlan0") as HostSpec.Literal
        assertEquals("wlan0", scoped.scope)
        assertEquals("[fe80::1]", scoped.headerHost)
        assertEquals("[fe80::1%wlan0]", scoped.display)
    }

    @Test
    fun hostileScopes() {
        listOf("fe80::1%", "fe80::1%a b", "fe80::1%a\r\nb", "fe80::1%" + "a".repeat(33), "fd00::1%wlan0", "::1%lo", "[fe80::1%a/b]").forEach {
            assertEquals(it, HostError.INVALID, error(it))
        }
    }

    @Test
    fun malformedV6() {
        listOf(
            "[fe80::1", "fe80::1]", "[]", ":", ":::", "1:2:3", "1:2:3:4:5:6:7:8:9", "1::2::3", "12345::1", "g::1",
            "::1.2.3", "1.2.3.4::", "1:2:3:4:5:6:7::8", ":1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7:8:", "::ffff:256.1.1.1",
            "::ffff:01.1.1.1",
        ).forEach {
            assertEquals(it, HostError.INVALID, error(it))
        }
    }

    @Test
    fun parseV6Values() {
        assertArrayEquals(ByteArray(16), HostParser.parseIpv6("::"))
        assertArrayEquals(ByteArray(15) + 1.toByte(), HostParser.parseIpv6("::1"))
        assertArrayEquals(ByteArray(15) + 1.toByte(), HostParser.parseIpv6("0:0:0:0:0:0:0:1"))
        val mapped = HostParser.parseIpv6("::ffff:10.1.2.3")!!
        assertEquals(10, mapped[12].toInt())
        assertEquals(3, mapped[15].toInt())
        assertEquals(0xFF.toByte(), mapped[10])
        val tail = HostParser.parseIpv6("1:2:3:4:5:6:7::")!!
        assertEquals(7, tail[13].toInt())
        assertEquals(0, tail[15].toInt())
    }

    @Test
    fun formatV6() {
        assertEquals("::", HostParser.formatIpv6(ByteArray(16)))
        assertEquals("::1", HostParser.formatIpv6(HostParser.parseIpv6("::1")!!))
        assertEquals("fe80::1", HostParser.formatIpv6(HostParser.parseIpv6("FE80:0:0:0:0:0:0:1")!!))
        assertEquals("1:2:3:4:5:6:7:8", HostParser.formatIpv6(HostParser.parseIpv6("1:2:3:4:5:6:7:8")!!))
        assertEquals("1:0:3:4:5:6:7:8", HostParser.formatIpv6(HostParser.parseIpv6("1:0:3:4:5:6:7:8")!!))
        assertEquals("1::4:0:0:7:8", HostParser.formatIpv6(HostParser.parseIpv6("1:0:0:4:0:0:7:8")!!))
        assertEquals("1:2:3:4:5:6::", HostParser.formatIpv6(HostParser.parseIpv6("1:2:3:4:5:6::")!!))
    }

    @Test
    fun hostileRandomInputNeverCrashes() {
        val random = java.util.Random(99)
        val alphabet = "0123456789abcdefABCDEF:.[]%-_ \r\n\u0000é".toCharArray()
        repeat(5000) {
            val text = String(CharArray(random.nextInt(50)) { alphabet[random.nextInt(alphabet.size)] })
            when (val parsed = HostParser.parse(text)) {
                is HostParse.Ok -> {
                    assertTrue(text, WsHandshake.isSafeHeaderValue(parsed.spec.headerHost))
                    assertFalse(text, parsed.spec.headerHost.contains('\r'))
                }
                is HostParse.Bad -> assertNotNull(parsed.error)
            }
        }
    }
}

class HostResolverTest {
    private fun address(vararg values: Int) = InetAddress.getByAddress(ByteArray(values.size) { values[it].toByte() })
    private val public = address(8, 8, 8, 8)
    private val lan = address(192, 168, 1, 5)
    private val name = HostSpec.Name("obs.local")

    @Test
    fun allPublicIsRefused() {
        assertTrue(HostResolver.resolve(name) { listOf(public) } is Resolution.Refused)
    }

    @Test
    fun onlyAllowedAddressesSurvive() {
        val result = HostResolver.resolve(name) { listOf(public, lan) } as Resolution.Allowed
        assertEquals(1, result.addresses.size)
        assertArrayEquals(lan.address, result.addresses[0].address)
    }

    @Test
    fun ipv4Preferred() {
        val v6 = InetAddress.getByAddress(HostParser.parseIpv6("fd00::1"))
        val result = HostResolver.resolve(name) { listOf(v6, lan) } as Resolution.Allowed
        assertArrayEquals(lan.address, result.addresses[0].address)
        assertArrayEquals(v6.address, result.addresses[1].address)
    }

    @Test
    fun candidatesCapped() {
        val many = List(50) { address(10, 0, 0, it + 1) }
        assertEquals(4, (HostResolver.resolve(name) { many } as Resolution.Allowed).addresses.size)
    }

    @Test
    fun failuresAreUnresolved() {
        assertTrue(HostResolver.resolve(name) { emptyList() } is Resolution.Unresolved)
        assertTrue(HostResolver.resolve(name) { throw UnknownHostException("x") } is Resolution.Unresolved)
        assertTrue(HostResolver.resolve(name) { throw SecurityException("x") } is Resolution.Unresolved)
    }

    @Test
    fun literalDoesNotUseLookup() {
        val spec = (HostParser.parse("192.168.1.9") as HostParse.Ok).spec
        val result = HostResolver.resolve(spec) { throw AssertionError("no lookup") } as Resolution.Allowed
        assertArrayEquals(address(192, 168, 1, 9).address, result.addresses[0].address)
    }

    @Test
    fun publicLiteralSpecIsRefusedAgain() {
        val forged = HostSpec.Literal(public.address, null, "8.8.8.8", "8.8.8.8")
        assertTrue(HostResolver.resolve(forged) { emptyList() } is Resolution.Refused)
    }

    @Test
    fun missingInterfaceIsUnresolved() {
        val spec = (HostParser.parse("fe80::1%nosuchif0") as HostParse.Ok).spec
        assertTrue(HostResolver.resolve(spec) { emptyList() } is Resolution.Unresolved)
    }

    @Test
    fun numericScope() {
        val spec = (HostParser.parse("fe80::1%3") as HostParse.Ok).spec
        val result = HostResolver.resolve(spec) { emptyList() } as Resolution.Allowed
        assertEquals(3, (result.addresses[0] as java.net.Inet6Address).scopeId)
    }
}

class ObsMessagesTest {
    private fun hello(body: String) = """{"op":0,"d":$body}"""

    @Test
    fun parsesHelloWithAuth() {
        val parsed = ObsMessages.parse(hello("""{"rpcVersion":1,"authentication":{"challenge":"c","salt":"s"}}""")) as ObsInbound.Hello
        assertEquals(1, parsed.rpcVersion)
        assertEquals("c", parsed.challenge)
        assertEquals("s", parsed.salt)
        assertTrue(parsed.authRequired)
    }

    @Test
    fun parsesHelloWithoutAuth() {
        val parsed = ObsMessages.parse(hello("""{"rpcVersion":1}""")) as ObsInbound.Hello
        assertFalse(parsed.authRequired)
    }

    @Test
    fun rejectsBadHello() {
        listOf(
            """{}""",
            """{"rpcVersion":0}""",
            """{"rpcVersion":"1"}""",
            """{"rpcVersion":1.5}""",
            """{"rpcVersion":1,"authentication":"x"}""",
            """{"rpcVersion":1,"authentication":{"challenge":"c"}}""",
            """{"rpcVersion":1,"authentication":{"salt":"s"}}""",
            """{"rpcVersion":1,"authentication":{"challenge":"","salt":"s"}}""",
            """{"rpcVersion":1,"authentication":{"challenge":1,"salt":2}}""",
            """{"rpcVersion":1,"authentication":{"challenge":"${"c".repeat(513)}","salt":"s"}}""",
        ).forEach { assertNull(it, ObsMessages.parse(hello(it))) }
    }

    @Test
    fun rejectsMalformedEnvelopes() {
        listOf(
            "", "garbage", "[]", "1", "null", "{", """{"op":0}""", """{"d":{}}""", """{"op":"0","d":{}}""",
            """{"op":0,"d":[]}""", """{"op":1.5,"d":{}}""", """{"op":99999999999,"d":{}}""",
        ).forEach { assertNull(it, ObsMessages.parse(it)) }
    }

    @Test
    fun unknownOpIsIgnored() {
        assertTrue(ObsMessages.parse("""{"op":42,"d":{}}""") is ObsInbound.Ignored)
    }

    @Test
    fun deepNestingIsRefusedWithoutOverflow() {
        val deep = "[".repeat(2_000_000)
        assertNull(ObsMessages.parse(deep))
        val deepObject = """{"op":7,"d":""" + "{\"a\":".repeat(500_000)
        assertNull(ObsMessages.parse(deepObject))
    }

    @Test
    fun depthGuardIgnoresBracketsInStrings() {
        assertTrue(JsonGuard.withinLimits("""{"a":"[[[[[[[[[[[[\"[["}""", 2))
        assertFalse(JsonGuard.withinLimits("[[[", 2))
        assertTrue(JsonGuard.withinLimits("[[]]", 2))
        assertTrue(JsonGuard.withinLimits("]]]]", 2))
    }

    @Test
    fun depthGuardCannotBeFooledByLenientParserSyntax() {
        val deep = "[".repeat(100)
        listOf(
            "{'\"':$deep",
            "{a\":$deep",
            "{f\":$deep",
            "/*\"*/$deep",
            "#\"\n$deep",
            "{\"a\"b\"$deep",
            "{\"a\"\"$deep",
            "{a=$deep",
            "{a;$deep",
            "tr$deep",
        ).forEach {
            assertFalse(it, JsonGuard.withinLimits(it))
            assertNull(it, ObsMessages.parse(it))
        }
    }

    @Test
    fun manyEmptyContainersAreRefusedBeforeParsing() {
        val flood = "{\"op\":7,\"d\":{\"x\":[" + "{},".repeat(100_000) + "{}]}}"
        assertTrue(flood.length < WsLimits.MAX_MESSAGE)
        assertFalse(JsonGuard.withinLimits(flood))
        assertNull(ObsMessages.parse(flood))
        val numbers = "[" + "1,".repeat(100_000) + "1]"
        assertFalse(JsonGuard.withinLimits(numbers))
    }

    @Test
    fun largeRealSceneListStillFitsTheLimits() {
        val scenes = JSONArray()
        repeat(3000) {
            scenes.put(
                JSONObject()
                    .put("sceneIndex", it)
                    .put("sceneName", "A rather long scene name number $it")
                    .put("sceneUuid", "01234567-89ab-cdef-0123-456789abcdef"),
            )
        }
        val body = JSONObject().put("requestType", "GetSceneList").put("requestId", "a")
            .put("requestStatus", JSONObject().put("result", true))
            .put("responseData", JSONObject().put("scenes", scenes))
        val text = JSONObject().put("op", 7).put("d", body).toString()
        assertTrue(text.length < WsLimits.MAX_MESSAGE)
        val parsed = ObsMessages.parse(text) as ObsInbound.Response
        assertEquals(ObsMessages.MAX_SCENES, ObsMessages.sceneList(parsed.data)!!.scenes.size)
    }

    @Test
    fun frameAndMessageLimitsAreAboutOneMebibyte() {
        assertEquals(1024 * 1024, WsLimits.MAX_FRAME)
        assertEquals(1024 * 1024, WsLimits.MAX_MESSAGE)
    }

    @Test
    fun depthGuardAcceptsNormalJson() {
        assertTrue(JsonGuard.withinLimits("{ \"a\" : [ 1, -2.5e3, true, false, null, \"x\" ], \"b\" : { \"c\" : \"\\\"\" } }"))
        assertFalse(JsonGuard.withinLimits("[".repeat(JsonGuard.MAX_DEPTH + 1)))
        assertTrue(JsonGuard.withinLimits("[".repeat(JsonGuard.MAX_DEPTH)))
    }

    @Test
    fun parsesResponse() {
        val parsed = ObsMessages.parse(
            """{"op":7,"d":{"requestType":"GetStats","requestId":"a","requestStatus":{"result":true,"code":100},"responseData":{"cpuUsage":1.5}}}""",
        ) as ObsInbound.Response
        assertEquals("a", parsed.id)
        assertTrue(parsed.ok)
        assertEquals(1.5, ObsMessages.stats(parsed.data).cpuPercent!!, 0.001)
    }

    @Test
    fun rejectsBadResponses() {
        listOf(
            """{"op":7,"d":{"requestId":"a","requestStatus":{"result":true}}}""",
            """{"op":7,"d":{"requestType":"X","requestStatus":{"result":true}}}""",
            """{"op":7,"d":{"requestType":"X","requestId":"a"}}""",
            """{"op":7,"d":{"requestType":"X","requestId":"a","requestStatus":{}}}""",
            """{"op":7,"d":{"requestType":"X","requestId":"a","requestStatus":{"result":"yes"}}}""",
            """{"op":7,"d":{"requestType":"X","requestId":7,"requestStatus":{"result":true}}}""",
            """{"op":7,"d":{"requestType":"X","requestId":"${"i".repeat(129)}","requestStatus":{"result":true}}}""",
        ).forEach { assertNull(it, ObsMessages.parse(it)) }
    }

    @Test
    fun responseDataOfWrongTypeBecomesEmpty() {
        val parsed = ObsMessages.parse(
            """{"op":7,"d":{"requestType":"X","requestId":"a","requestStatus":{"result":false,"comment":5},"responseData":"nope"}}""",
        ) as ObsInbound.Response
        assertEquals(0, parsed.data.length())
        assertNull(parsed.comment)
    }

    @Test
    fun parsesEvent() {
        val parsed = ObsMessages.parse("""{"op":5,"d":{"eventType":"X","eventIntent":1,"eventData":{"a":1}}}""") as ObsInbound.Event
        assertEquals("X", parsed.type)
        assertEquals(1, parsed.data.opt("a"))
        val empty = ObsMessages.parse("""{"op":5,"d":{"eventType":"X"}}""") as ObsInbound.Event
        assertEquals(0, empty.data.length())
        assertNull(ObsMessages.parse("""{"op":5,"d":{"eventData":{}}}"""))
    }

    @Test
    fun identifyShape() {
        val root = JSONObject(ObsMessages.identify("abc"))
        assertEquals(1, root.getInt("op"))
        val data = root.getJSONObject("d")
        assertEquals(1, data.getInt("rpcVersion"))
        assertEquals("abc", data.getString("authentication"))
        assertEquals(2 or 4 or 8 or 64, data.getInt("eventSubscriptions"))
        assertFalse(JSONObject(ObsMessages.identify(null)).getJSONObject("d").has("authentication"))
    }

    @Test
    fun requestEscapesHostileNames() {
        val hostile = "a\"},{\"op\":6\r\n\u0000\\"
        val text = ObsMessages.request(ObsRequest.SET_PROGRAM_SCENE, "id", ObsMessages.sceneParameters(hostile))
        val root = JSONObject(text)
        assertEquals(6, root.getInt("op"))
        assertEquals(hostile, root.getJSONObject("d").getJSONObject("requestData").getString("sceneName"))
        assertFalse(text.contains('\r'))
    }

    @Test
    fun sceneListOrderedTopFirstAndCleaned() {
        val data = JSONObject(
            """{"currentProgramSceneName":"B","scenes":[{"sceneIndex":0,"sceneName":"A"},{"sceneIndex":2,"sceneName":"‮evil\r\n"},{"sceneIndex":1,"sceneName":"B"}]}""",
        )
        val list = ObsMessages.sceneList(data)!!
        assertEquals(listOf("‮evil\r\n", "B", "A"), list.scenes.map { it.id })
        assertEquals("evil", list.scenes[0].label)
        assertEquals("B", list.current)
        assertFalse(list.truncated)
    }

    @Test
    fun sceneListHostileEntries() {
        val array = JSONArray()
            .put(5)
            .put("str")
            .put(JSONObject.NULL)
            .put(JSONObject().put("sceneName", 5))
            .put(JSONObject().put("sceneName", ""))
            .put(JSONObject().put("sceneName", "x".repeat(ObsMessages.MAX_RAW_NAME + 1)))
            .put(JSONObject().put("sceneName", "ok"))
            .put(JSONObject().put("sceneName", "ok"))
            .put(JSONObject().put("sceneName", "​‍"))
        val list = ObsMessages.sceneList(JSONObject().put("scenes", array))!!
        assertEquals(2, list.scenes.size)
        assertEquals("(unnamed)", list.scenes[1].label)
        assertNull(list.current)
    }

    @Test
    fun sceneCountCapped() {
        val array = JSONArray()
        repeat(ObsMessages.MAX_SCENES + 50) { array.put(JSONObject().put("sceneName", "s$it").put("sceneIndex", it)) }
        val list = ObsMessages.sceneList(JSONObject().put("scenes", array))!!
        assertEquals(ObsMessages.MAX_SCENES, list.scenes.size)
        assertTrue(list.truncated)
    }

    @Test
    fun sceneListWrongShape() {
        assertNull(ObsMessages.sceneList(JSONObject()))
        assertNull(ObsMessages.sceneList(JSONObject().put("scenes", "x")))
    }

    @Test
    fun labelLengthIsCapped() {
        val label = ObsMessages.label("y".repeat(5000))
        assertTrue(label.length <= ObsMessages.MAX_LABEL + 1)
    }

    @Test
    fun inputListCapAndDedupe() {
        val array = JSONArray()
        repeat(ObsMessages.MAX_INPUT_PROBES + 5) { array.put(JSONObject().put("inputName", "i$it")) }
        array.put(JSONObject().put("inputName", "i0"))
        val list = ObsMessages.inputNames(JSONObject().put("inputs", array))!!
        assertEquals(ObsMessages.MAX_INPUT_PROBES, list.names.size)
        assertTrue(list.truncated)
        assertNull(ObsMessages.inputNames(JSONObject().put("inputs", 5)))
    }

    @Test
    fun statsHostileNumbers() {
        val stats = ObsMessages.stats(
            JSONObject("""{"cpuUsage":-5,"activeFps":1e300,"renderSkippedFrames":-1,"renderTotalFrames":"x","outputSkippedFrames":1e30,"outputTotalFrames":null}"""),
        )
        assertEquals(0.0, stats.cpuPercent!!, 0.0)
        assertEquals(100_000.0, stats.fps!!, 0.0)
        assertEquals(0L, stats.renderSkipped)
        assertNull(stats.renderTotal)
        assertEquals(1L shl 48, stats.outputSkipped)
        assertNull(stats.outputTotal)
    }

    @Test
    fun durationClamped() {
        assertEquals(0L, ObsMessages.duration(JSONObject("""{"outputDuration":-5}""")))
        assertNull(ObsMessages.duration(JSONObject("""{"outputDuration":"5"}""")))
        assertTrue(ObsMessages.duration(JSONObject("""{"outputDuration":1e300}"""))!! < Long.MAX_VALUE)
    }

    @Test
    fun commentIsCleanedAndCapped() {
        assertNull(ObsMessages.comment(null))
        assertNull(ObsMessages.comment("‮"))
        assertEquals("bad", ObsMessages.comment("‮b\r\na\u0000d"))
        assertTrue(ObsMessages.comment("z".repeat(9000))!!.length <= ObsMessages.MAX_COMMENT + 1)
    }

    @Test
    fun randomJsonLikeGarbageNeverThrows() {
        val random = java.util.Random(7)
        val pieces = listOf("{", "}", "[", "]", ":", ",", "\"", "op", "d", "0", "5", "7", "null", "true", "\\", "\\u12", "1e999", "-", " ")
        repeat(5000) {
            val text = (0 until random.nextInt(30)).joinToString("") { pieces[random.nextInt(pieces.size)] }
            ObsMessages.parse(text)
        }
    }
}

class OutputReducerTest {
    private val grace = 1000L

    @Test
    fun eventsMapToPhases() {
        val start = OutputView(OutputPhase.STOPPED)
        fun phase(name: String) = OutputReducer.onEvent(start, null, name, 5).phase
        assertEquals(OutputPhase.STARTING, phase("OBS_WEBSOCKET_OUTPUT_STARTING"))
        assertEquals(OutputPhase.ACTIVE, phase("OBS_WEBSOCKET_OUTPUT_STARTED"))
        assertEquals(OutputPhase.STOPPING, phase("OBS_WEBSOCKET_OUTPUT_STOPPING"))
        assertEquals(OutputPhase.RECONNECTING, phase("OBS_WEBSOCKET_OUTPUT_RECONNECTING"))
        assertEquals(OutputPhase.ACTIVE, phase("OBS_WEBSOCKET_OUTPUT_RECONNECTED"))
        assertEquals(OutputPhase.PAUSED, phase("OBS_WEBSOCKET_OUTPUT_PAUSED"))
        assertEquals(OutputPhase.ACTIVE, phase("OBS_WEBSOCKET_OUTPUT_RESUMED"))
    }

    @Test
    fun unknownStateFallsBackToActiveFlag() {
        val start = OutputView(OutputPhase.ACTIVE)
        assertEquals(OutputPhase.STOPPED, OutputReducer.onEvent(start, false, "EVIL", 1).phase)
        assertEquals(OutputPhase.ACTIVE, OutputReducer.onEvent(start, null, "EVIL", 1).phase)
        assertEquals(OutputPhase.ACTIVE, OutputReducer.onEvent(start, null, null, 1).phase)
    }

    @Test
    fun stoppedClearsDuration() {
        val live = OutputView(OutputPhase.ACTIVE, 500, 0, 3, 9)
        val stopped = OutputReducer.onEvent(live, false, "OBS_WEBSOCKET_OUTPUT_STOPPED", 10)
        assertNull(stopped.durationMs)
        assertNull(stopped.skippedFrames)
    }

    @Test
    fun pollHoldsTransitionsDuringGrace() {
        val starting = OutputView(OutputPhase.STARTING, sinceMs = 100)
        assertEquals(OutputPhase.STARTING, OutputReducer.onPoll(starting, false, null, 0, null, null, 200, grace).phase)
        assertEquals(OutputPhase.STOPPED, OutputReducer.onPoll(starting, false, null, 0, null, null, 2000, grace).phase)
        assertEquals(OutputPhase.ACTIVE, OutputReducer.onPoll(starting, true, null, 0, null, null, 200, grace).phase)
        val stopping = OutputView(OutputPhase.STOPPING, sinceMs = 100)
        assertEquals(OutputPhase.STOPPING, OutputReducer.onPoll(stopping, true, null, 0, null, null, 200, grace).phase)
        assertEquals(OutputPhase.ACTIVE, OutputReducer.onPoll(stopping, true, null, 0, null, null, 2000, grace).phase)
        assertEquals(OutputPhase.STOPPED, OutputReducer.onPoll(stopping, false, null, 0, null, null, 200, grace).phase)
    }

    @Test
    fun pollDegradedAndMetrics() {
        val live = OutputView(OutputPhase.ACTIVE)
        val view = OutputReducer.onPoll(live, true, OutputPhase.RECONNECTING, 9000, 2, 100, 5, grace)
        assertEquals(OutputPhase.RECONNECTING, view.phase)
        assertEquals(9000L, view.durationMs)
        assertEquals(2L, view.skippedFrames)
        assertEquals(100L, view.totalFrames)
        assertNull(OutputReducer.onPoll(view, false, null, 9000, 2, 100, 6, grace).durationMs)
    }

    @Test
    fun unchangedPhaseKeepsSince() {
        val live = OutputView(OutputPhase.ACTIVE, sinceMs = 77)
        assertEquals(77L, OutputReducer.onPoll(live, true, null, 1, null, null, 999, grace).sinceMs)
    }

    @Test
    fun capabilities() {
        assertTrue(OutputView(OutputPhase.STOPPED).canStart)
        assertFalse(OutputView(OutputPhase.UNKNOWN).canStart)
        assertFalse(OutputView(OutputPhase.UNKNOWN).canStop)
        assertTrue(OutputView(OutputPhase.ACTIVE).canStop)
        assertTrue(OutputView(OutputPhase.RECONNECTING).canStop)
        assertTrue(OutputView(OutputPhase.PAUSED).canStop)
        assertFalse(OutputView(OutputPhase.STARTING).canStop)
        assertFalse(OutputView(OutputPhase.STOPPING).canStop)
        assertTrue(OutputView(OutputPhase.STOPPING).isLive)
    }
}

class BackoffAndFormatTest {
    @Test
    fun backoffGrowsAndCaps() {
        assertEquals(1000L, Backoff.delayMs(1, 0.5, 1000, 30000))
        assertEquals(2000L, Backoff.delayMs(2, 0.5, 1000, 30000))
        assertEquals(16000L, Backoff.delayMs(5, 0.5, 1000, 30000))
        assertEquals(30000L, Backoff.delayMs(6, 0.5, 1000, 30000))
        assertEquals(30000L, Backoff.delayMs(Int.MAX_VALUE, 0.5, 1000, 30000))
        assertEquals(1000L, Backoff.delayMs(0, 0.5, 1000, 30000))
        assertEquals(1000L, Backoff.delayMs(-5, 0.5, 1000, 30000))
    }

    @Test
    fun jitterIsBounded() {
        assertEquals(800L, Backoff.delayMs(1, 0.0, 1000, 30000))
        assertEquals(1200L, Backoff.delayMs(1, 1.0, 1000, 30000))
        assertEquals(1200L, Backoff.delayMs(1, 99.0, 1000, 30000))
        assertEquals(800L, Backoff.delayMs(1, -9.0, 1000, 30000))
        assertEquals(1000L, Backoff.delayMs(1, Double.NaN, 1000, 30000))
    }

    @Test
    fun durationFormat() {
        assertEquals("--:--:--", Format.duration(null))
        assertEquals("00:00:00", Format.duration(0))
        assertEquals("00:00:00", Format.duration(-100))
        assertEquals("00:01:01", Format.duration(61_999))
        assertEquals("101:00:00", Format.duration(101L * 3600 * 1000))
    }

    @Test
    fun numberFormats() {
        assertEquals("12.3%", Format.percent(12.34))
        assertEquals("--", Format.percent(null))
        assertEquals("--", Format.percent(Double.NaN))
        assertEquals("60.0", Format.fps(60.0))
        assertEquals("--", Format.fps(null))
    }

    @Test
    fun droppedFormats() {
        assertEquals("--", Format.dropped(null, 5))
        assertEquals("5", Format.dropped(5, null))
        assertEquals("5", Format.dropped(5, 0))
        assertEquals("1 (10.0%)", Format.dropped(1, 10))
    }

    @Test
    fun droppedPrefersLiveStream() {
        val stats = Stats(outputSkipped = 9, outputTotal = 90)
        val live = RemoteState(stream = OutputView(OutputPhase.ACTIVE, skippedFrames = 1, totalFrames = 10), stats = stats)
        assertEquals("1 (10.0%)", Format.droppedFrames(live))
        assertEquals("9 (10.0%)", Format.droppedFrames(RemoteState(stats = stats)))
        assertEquals("--", Format.droppedFrames(RemoteState()))
    }

    @Test
    fun stateFlags() {
        assertTrue(RemoteState(phase = Phase.CONNECTED).showsControls)
        assertTrue(RemoteState(phase = Phase.RECONNECTING, established = true).showsControls)
        assertTrue(RemoteState(phase = Phase.PAUSED, established = true).showsControls)
        assertFalse(RemoteState(phase = Phase.PAUSED).showsControls)
        assertFalse(RemoteState(phase = Phase.FAILED).showsControls)
        assertFalse(RemoteState(phase = Phase.IDLE).keepsScreenOn)
        assertFalse(RemoteState(phase = Phase.PAUSED).keepsScreenOn)
        assertTrue(RemoteState(phase = Phase.RECONNECTING).keepsScreenOn)
        assertFalse(RemoteState(phase = Phase.RECONNECTING, reconnectStale = true).keepsScreenOn)
    }

    @Test
    fun secondActivityStoppingFirstKeepsConnection() {
        val activities = VisibleActivities()
        activities.onStart()
        activities.onStart()
        assertFalse(activities.onStop(false))
        assertTrue(activities.visible)
        assertTrue(activities.onStop(false))
        assertFalse(activities.visible)
    }

    @Test
    fun rotationDoesNotCountAsLeaving() {
        val activities = VisibleActivities()
        activities.onStart()
        assertFalse(activities.onStop(true))
        assertTrue(activities.visible)
        activities.onStart()
        assertTrue(activities.visible)
        assertTrue(activities.onStop(false))
        assertFalse(activities.visible)
    }

    @Test
    fun strayStopNeverGoesNegative() {
        val activities = VisibleActivities()
        assertTrue(activities.onStop(false))
        activities.onStart()
        assertTrue(activities.visible)
    }
}

class StorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "data.json")

    @Test
    fun writeThenRead() {
        assertTrue(AtomicText.write(file(), "héllo"))
        assertEquals("héllo", AtomicText.read(file()))
        assertFalse(File(folder.root, "data.json.tmp").exists())
    }

    @Test
    fun overwriteIsAtomicAndNoTempLeft() {
        AtomicText.write(file(), "one")
        AtomicText.write(file(), "two")
        assertEquals("two", AtomicText.read(file()))
        assertEquals(1, folder.root.listFiles()!!.size)
    }

    @Test
    fun missingOrDirectoryOrOversizeOrBinaryIsNull() {
        assertNull(AtomicText.read(file()))
        assertNull(AtomicText.read(folder.root))
        file().writeBytes(ByteArray(100))
        assertNull(AtomicText.read(file(), 50))
        file().writeBytes(byteArrayOf(0xC0.toByte(), 0x80.toByte()))
        assertNull(AtomicText.read(file()))
    }

    @Test
    fun writeIntoMissingFolderFailsQuietly() {
        assertFalse(AtomicText.write(File(File(folder.root, "none"), "x.json"), "a"))
    }

    @Test
    fun settingsRoundTrip() {
        val settings = Settings("192.168.1.2", 4456, savePassword = true, keepScreenOn = false)
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
    }

    @Test
    fun settingsNeverContainPassword() {
        assertFalse(SettingsCodec.encode(Settings()).contains("password", ignoreCase = true))
    }

    @Test
    fun corruptSettingsFallBack() {
        val defaults = Settings()
        listOf(null, "", "garbage", "[]", "{", "[[[[[[[[[[", """{"port":"x"}""", """{"port":99999}""", """{"port":-1}""", """{"host":5}""", """{"save":"yes"}""").forEach {
            val decoded = SettingsCodec.decode(it)
            assertEquals(it, defaults.port, decoded.port)
            assertEquals(it, defaults.host, decoded.host)
            assertEquals(it, defaults.savePassword, decoded.savePassword)
        }
        assertEquals("", SettingsCodec.decode("""{"host":"${"a".repeat(256)}"}""").host)
    }

    @Test
    fun blobRoundTrip() {
        val blob = SecretBlob(ByteArray(12) { 1 }, ByteArray(32) { 2 })
        val decoded = SecretBlobCodec.decode(SecretBlobCodec.encode(blob))!!
        assertArrayEquals(blob.iv, decoded.iv)
        assertArrayEquals(blob.cipherText, decoded.cipherText)
    }

    @Test
    fun corruptBlobsRefused() {
        val goodIv = java.util.Base64.getEncoder().encodeToString(ByteArray(12))
        val goodCt = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        val shortCt = java.util.Base64.getEncoder().encodeToString(ByteArray(15))
        val longCt = java.util.Base64.getEncoder().encodeToString(ByteArray(4097))
        listOf(
            null, "", "x", "[]", "{}",
            """{"v":2,"iv":"$goodIv","ct":"$goodCt"}""",
            """{"v":1,"iv":"!!","ct":"$goodCt"}""",
            """{"v":1,"iv":"$goodIv"}""",
            """{"v":1,"iv":"$goodCt","ct":"$goodCt"}""",
            """{"v":1,"iv":"$goodIv","ct":"$shortCt"}""",
            """{"v":1,"iv":"$goodIv","ct":"$longCt"}""",
            """{"v":1,"iv":5,"ct":6}""",
        ).forEach { assertNull(it, SecretBlobCodec.decode(it)) }
        assertNotNull(SecretBlobCodec.decode("""{"v":1,"iv":"$goodIv","ct":"$goodCt"}"""))
    }

    @Test
    fun savedPasswordRoundTrip() {
        val saved = SavedPassword("192.168.1.2:4455", "p\"\u00E4\r\n\\")
        val decoded = SavedPasswordCodec.decode(SavedPasswordCodec.encode(saved))!!
        assertEquals(saved.endpoint, decoded.endpoint)
        assertEquals(saved.password, decoded.password)
        assertFalse(saved.toString().contains("\u00E4"))
    }

    @Test
    fun corruptSavedPasswordRefused() {
        listOf(
            null, "", "x", "[]", "{}", "hunter2",
            """{"e":"a:1"}""",
            """{"p":"x"}""",
            """{"e":"","p":"x"}""",
            """{"e":5,"p":"x"}""",
            """{"e":"a:1","p":5}""",
            """{"e":"${"a".repeat(301)}","p":"x"}""",
            """{"e":"a:1","p":"${"x".repeat(1025)}"}""",
        ).forEach { assertNull(it, SavedPasswordCodec.decode(it)) }
    }

    @Test
    fun savedPasswordOnlyGoesToItsOwnEndpoint() {
        val stored = SavedPassword("192.168.1.2:4455", "good")
        val use = PasswordPolicy.choose("", true, true, stored, "192.168.1.2:4455") as PasswordChoice.Use
        assertEquals("good", use.password)
        assertTrue(PasswordPolicy.choose("", true, true, stored, "192.168.1.66:4455") is PasswordChoice.OtherEndpoint)
        assertTrue(PasswordPolicy.choose("", true, true, stored, "192.168.1.2:4456") is PasswordChoice.OtherEndpoint)
    }

    @Test
    fun typedPasswordAlwaysWinsAndIsNotReplacedByStored() {
        val stored = SavedPassword("a:1", "good")
        assertEquals("typed", (PasswordPolicy.choose("typed", true, true, stored, "b:2") as PasswordChoice.Use).password)
        assertEquals("typed", (PasswordPolicy.choose("typed", false, false, null, "b:2") as PasswordChoice.Use).password)
    }

    @Test
    fun emptyPasswordCases() {
        assertEquals("", (PasswordPolicy.choose("", false, true, null, "a:1") as PasswordChoice.Use).password)
        assertEquals("", (PasswordPolicy.choose("", true, false, null, "a:1") as PasswordChoice.Use).password)
        assertTrue(PasswordPolicy.choose("", true, true, null, "a:1") is PasswordChoice.Unreadable)
    }

    @Test
    fun endpointIsCanonical() {
        val upper = ObsTarget((HostParser.parse("OBS.local") as HostParse.Ok).spec, 4455, "")
        val lower = ObsTarget((HostParser.parse("obs.local.") as HostParse.Ok).spec, 4455, "")
        assertEquals(upper.endpoint, lower.endpoint)
        val v6 = ObsTarget((HostParser.parse("FD00:0:0:0:0:0:0:1") as HostParse.Ok).spec, 1, "")
        assertEquals("[fd00::1]:1", v6.endpoint)
    }

    @Test
    fun targetToStringHidesPassword() {
        val spec = (HostParser.parse("127.0.0.1") as HostParse.Ok).spec
        assertFalse(ObsTarget(spec, 4455, "hunter2").toString().contains("hunter2"))
    }
}
