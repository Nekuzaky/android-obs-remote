package dev.nekuz.sceneremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

object TestFrames {
    fun server(opcode: Int, payload: ByteArray, fin: Boolean = true, firstExtra: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode or firstExtra)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xFFFF -> {
                out.write(126)
                out.write(payload.size ushr 8)
                out.write(payload.size)
            }
            else -> {
                out.write(127)
                for (shift in 7 downTo 0) out.write((payload.size.toLong() ushr (8 * shift)).toInt())
            }
        }
        out.write(payload)
        return out.toByteArray()
    }

    fun text(value: String, fin: Boolean = true) = server(WsOpcode.TEXT, value.toByteArray(Charsets.UTF_8), fin)

    fun closePayload(code: Int, reason: String = "") =
        byteArrayOf((code ushr 8).toByte(), code.toByte()) + reason.toByteArray(Charsets.UTF_8)

    fun unmask(frame: ByteArray): ByteArray {
        val short = frame[1].toInt() and 0x7F
        val start = when (short) {
            126 -> 4
            127 -> 10
            else -> 2
        }
        val key = frame.copyOfRange(start, start + 4)
        val body = frame.copyOfRange(start + 4, frame.size)
        return ByteArray(body.size) { (body[it].toInt() xor key[it and 3].toInt()).toByte() }
    }
}

class Utf8Test {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun acceptsValid() {
        listOf("", "plain", "é", "€", "😀", "mixed é€😀 text").forEach {
            assertTrue(it, Utf8.isValid(it.toByteArray(Charsets.UTF_8)))
        }
        assertTrue(Utf8.isValid(bytes(0xF4, 0x8F, 0xBF, 0xBF)))
        assertTrue(Utf8.isValid(bytes(0xED, 0x9F, 0xBF)))
    }

    @Test
    fun rejectsOverlong() {
        assertFalse(Utf8.isValid(bytes(0xC0, 0x80)))
        assertFalse(Utf8.isValid(bytes(0xC1, 0xBF)))
        assertFalse(Utf8.isValid(bytes(0xE0, 0x80, 0x80)))
        assertFalse(Utf8.isValid(bytes(0xE0, 0x9F, 0xBF)))
        assertFalse(Utf8.isValid(bytes(0xF0, 0x80, 0x80, 0x80)))
        assertFalse(Utf8.isValid(bytes(0xF0, 0x8F, 0xBF, 0xBF)))
    }

    @Test
    fun rejectsSurrogatesAndOutOfRange() {
        assertFalse(Utf8.isValid(bytes(0xED, 0xA0, 0x80)))
        assertFalse(Utf8.isValid(bytes(0xED, 0xBF, 0xBF)))
        assertFalse(Utf8.isValid(bytes(0xF4, 0x90, 0x80, 0x80)))
        assertFalse(Utf8.isValid(bytes(0xF5, 0x80, 0x80, 0x80)))
        assertFalse(Utf8.isValid(bytes(0xFF)))
        assertFalse(Utf8.isValid(bytes(0xFE)))
    }

    @Test
    fun rejectsTruncationAndStrayContinuation() {
        assertFalse(Utf8.isValid(bytes(0xC3)))
        assertFalse(Utf8.isValid(bytes(0xE2, 0x82)))
        assertFalse(Utf8.isValid(bytes(0xF0, 0x9F, 0x98)))
        assertFalse(Utf8.isValid(bytes(0x80)))
        assertFalse(Utf8.isValid(bytes(0x41, 0xBF)))
        assertFalse(Utf8.isValid(bytes(0xC3, 0x41)))
    }

    @Test
    fun decodeThrowsTypedFault() {
        try {
            Utf8.decode(bytes(0xC0, 0x80))
            fail()
        } catch (e: WsException) {
            assertEquals(WsFault.BAD_UTF8, e.fault)
        }
    }
}

class WsFramesEncodeTest {
    private val mask = byteArrayOf(1, 2, 3, 4)

    @Test
    fun shortFrameIsMasked() {
        val frame = WsFrames.encodeClient(WsOpcode.TEXT, "Hi".toByteArray(), mask)
        assertEquals(0x81.toByte(), frame[0])
        assertEquals((0x80 or 2).toByte(), frame[1])
        assertArrayEquals(mask, frame.copyOfRange(2, 6))
        assertArrayEquals("Hi".toByteArray(), TestFrames.unmask(frame))
    }

    @Test
    fun boundaryLengths() {
        val at125 = WsFrames.encodeClient(WsOpcode.TEXT, ByteArray(125), mask)
        assertEquals((0x80 or 125).toByte(), at125[1])
        val at126 = WsFrames.encodeClient(WsOpcode.TEXT, ByteArray(126), mask)
        assertEquals((0x80 or 126).toByte(), at126[1])
        assertEquals(0.toByte(), at126[2])
        assertEquals(126.toByte(), at126[3])
        val at65535 = WsFrames.encodeClient(WsOpcode.TEXT, ByteArray(65535), mask)
        assertEquals((0x80 or 126).toByte(), at65535[1])
        val at65536 = WsFrames.encodeClient(WsOpcode.TEXT, ByteArray(65536), mask)
        assertEquals((0x80 or 127).toByte(), at65536[1])
        assertEquals(1.toByte(), at65536[7])
        assertEquals(0.toByte(), at65536[8])
        assertEquals(65536, TestFrames.unmask(at65536).size)
    }

    @Test
    fun finFlagAndMaskRoundTrip() {
        val data = ByteArray(1000) { it.toByte() }
        val frame = WsFrames.encodeClient(WsOpcode.TEXT, data, byteArrayOf(-1, 0x55, 0, 127), fin = false)
        assertEquals(0x01.toByte(), frame[0])
        assertArrayEquals(data, TestFrames.unmask(frame))
    }
}

class WsFrameReaderTest {
    private fun reader(bytes: ByteArray, max: Int = WsLimits.MAX_FRAME) =
        WsFrameReader(ByteArrayInputStream(bytes), max)

    private fun faultOf(bytes: ByteArray, max: Int = WsLimits.MAX_FRAME): WsFault? = try {
        reader(bytes, max).read()
        null
    } catch (e: WsException) {
        e.fault
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun readsAllLengthForms() {
        listOf(0, 1, 125, 126, 127, 65535, 65536, 100_000).forEach { size ->
            val frame = reader(TestFrames.server(WsOpcode.TEXT, ByteArray(size))).read()
            assertEquals(size, frame.payload.size)
            assertTrue(frame.fin)
            assertEquals(WsOpcode.TEXT, frame.opcode)
        }
    }

    @Test
    fun rejectsReservedBits() {
        listOf(0x40, 0x20, 0x10, 0x70).forEach {
            assertEquals(WsFault.PROTOCOL, faultOf(TestFrames.server(WsOpcode.TEXT, ByteArray(1), firstExtra = it)))
        }
    }

    @Test
    fun rejectsUnknownOpcodes() {
        listOf(3, 4, 5, 6, 7, 0xB, 0xC, 0xD, 0xE, 0xF).forEach {
            assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x80 or it, 0)))
        }
    }

    @Test
    fun rejectsMaskedServerFrame() {
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x81, 0x81, 1, 2, 3, 4, 5)))
    }

    @Test
    fun rejectsFragmentedOrLongControlFrames() {
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x09, 0)))
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x08, 0)))
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x89, 126, 0, 126) + ByteArray(126)))
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x88, 127, 0, 0, 0, 0, 0, 1, 0, 0)))
    }

    @Test
    fun rejectsNonMinimalLengths() {
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x81, 126, 0, 125) + ByteArray(125)))
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x81, 127, 0, 0, 0, 0, 0, 0, 0, 5) + ByteArray(5)))
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x81, 127, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF)))
    }

    @Test
    fun rejectsNegativeLength() {
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x81, 127, 0x80, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(WsFault.PROTOCOL, faultOf(bytes(0x81, 127, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
    }

    @Test
    fun rejectsOversizeBeforeAllocating() {
        assertEquals(WsFault.TOO_LARGE, faultOf(bytes(0x81, 127, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertEquals(WsFault.TOO_LARGE, faultOf(bytes(0x81, 127, 0, 0, 0, 0, 0, 0x40, 0, 1)))
        assertEquals(WsFault.TOO_LARGE, faultOf(TestFrames.server(WsOpcode.TEXT, ByteArray(11)), 10))
    }

    @Test
    fun acceptsExactLimit() {
        assertEquals(10, reader(TestFrames.server(WsOpcode.TEXT, ByteArray(10)), 10).read().payload.size)
    }

    @Test
    fun cleanEndOfStreamIsClosedFault() {
        assertEquals(WsFault.CLOSED, faultOf(ByteArray(0)))
    }

    @Test(expected = EOFException::class)
    fun truncatedHeaderIsEof() {
        reader(bytes(0x81)).read()
    }

    @Test(expected = EOFException::class)
    fun truncatedExtendedLengthIsEof() {
        reader(bytes(0x81, 126, 1)).read()
    }

    @Test(expected = EOFException::class)
    fun truncatedPayloadIsEof() {
        reader(bytes(0x81, 5, 1, 2)).read()
    }

    @Test
    fun slowFrameHitsItsDeadline() {
        val slow = object : InputStream() {
            private var sent = 0
            override fun read(): Int {
                sent++
                Thread.sleep(20)
                return if (sent == 1) 0x81 else 100
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(20)
                b[off] = 0x41
                return 1
            }
        }
        try {
            WsFrameReader(slow, WsLimits.MAX_FRAME, frameDeadlineMs = 60).read()
            fail()
        } catch (e: WsException) {
            assertEquals(WsFault.TIMEOUT, e.fault)
        }
    }

    @Test
    fun randomGarbageNeverThrowsAnythingUnexpected() {
        val random = java.util.Random(1234)
        repeat(3000) {
            val data = ByteArray(random.nextInt(64)).also(random::nextBytes)
            try {
                reader(data, 1024).read()
            } catch (e: java.io.IOException) {
                assertTrue(true)
            }
        }
    }
}

class WsAssemblerTest {
    private fun frame(op: Int, text: String, fin: Boolean) = WsFrame(fin, op, text.toByteArray(Charsets.UTF_8))

    private fun fault(block: () -> Unit): WsFault? = try {
        block()
        null
    } catch (e: WsException) {
        e.fault
    }

    @Test
    fun singleTextFrame() {
        assertEquals("hello", WsAssembler().accept(frame(WsOpcode.TEXT, "hello", true)))
    }

    @Test
    fun fragmentsJoin() {
        val assembler = WsAssembler()
        assertNull(assembler.accept(frame(WsOpcode.TEXT, "he", false)))
        assertNull(assembler.accept(frame(WsOpcode.CONTINUATION, "", false)))
        assertNull(assembler.accept(frame(WsOpcode.CONTINUATION, "ll", false)))
        assertEquals("hello", assembler.accept(frame(WsOpcode.CONTINUATION, "o", true)))
        assertEquals("next", assembler.accept(frame(WsOpcode.TEXT, "next", true)))
    }

    @Test
    fun multibyteCharacterSplitAcrossFragments() {
        val bytes = "é".toByteArray(Charsets.UTF_8)
        val assembler = WsAssembler()
        assertNull(assembler.accept(WsFrame(false, WsOpcode.TEXT, bytes.copyOfRange(0, 1))))
        assertEquals("é", assembler.accept(WsFrame(true, WsOpcode.CONTINUATION, bytes.copyOfRange(1, 2))))
    }

    @Test
    fun continuationWithoutStart() {
        assertEquals(WsFault.PROTOCOL, fault { WsAssembler().accept(frame(WsOpcode.CONTINUATION, "x", true)) })
    }

    @Test
    fun newTextInsideFragmented() {
        val assembler = WsAssembler()
        assembler.accept(frame(WsOpcode.TEXT, "a", false))
        assertEquals(WsFault.PROTOCOL, fault { assembler.accept(frame(WsOpcode.TEXT, "b", true)) })
    }

    @Test
    fun binaryIsRefused() {
        assertEquals(WsFault.PROTOCOL, fault { WsAssembler().accept(WsFrame(true, WsOpcode.BINARY, ByteArray(1))) })
    }

    @Test
    fun invalidUtf8Refused() {
        assertEquals(
            WsFault.BAD_UTF8,
            fault { WsAssembler().accept(WsFrame(true, WsOpcode.TEXT, byteArrayOf(0xC0.toByte(), 0x80.toByte()))) },
        )
        val assembler = WsAssembler()
        assembler.accept(WsFrame(false, WsOpcode.TEXT, byteArrayOf(0xC3.toByte())))
        assertEquals(WsFault.BAD_UTF8, fault { assembler.accept(frame(WsOpcode.CONTINUATION, "A", true)) })
    }

    @Test
    fun messageLimitAcrossFragments() {
        val assembler = WsAssembler(maxMessage = 5)
        assembler.accept(frame(WsOpcode.TEXT, "abc", false))
        assertEquals(WsFault.TOO_LARGE, fault { assembler.accept(frame(WsOpcode.CONTINUATION, "def", true)) })
        assertEquals(WsFault.TOO_LARGE, fault { WsAssembler(maxMessage = 5).accept(frame(WsOpcode.TEXT, "123456", true)) })
        assertEquals("12345", WsAssembler(maxMessage = 5).accept(frame(WsOpcode.TEXT, "12345", true)))
    }

    @Test
    fun fragmentCountLimit() {
        val assembler = WsAssembler(maxFragments = 3)
        assembler.accept(frame(WsOpcode.TEXT, "", false))
        assembler.accept(frame(WsOpcode.CONTINUATION, "", false))
        assembler.accept(frame(WsOpcode.CONTINUATION, "", false))
        assertEquals(WsFault.TOO_LARGE, fault { assembler.accept(frame(WsOpcode.CONTINUATION, "", false)) })
    }
}

class WsCloseTest {
    @Test
    fun parsesCodeAndReason() {
        val closed = WsClose.parse(TestFrames.closePayload(4009, "Authentication failed."))
        assertEquals(4009, closed.code)
        assertEquals("Authentication failed.", closed.reason)
    }

    @Test
    fun emptyHasNoCode() {
        assertNull(WsClose.parse(ByteArray(0)).code)
    }

    @Test(expected = WsException::class)
    fun oneByteIsInvalid() {
        WsClose.parse(ByteArray(1))
    }

    @Test
    fun invalidCodes() {
        listOf(0, 999, 1004, 1005, 1006, 1015, 2999, 5000, 65535).forEach {
            try {
                WsClose.parse(TestFrames.closePayload(it))
                fail("code $it")
            } catch (e: WsException) {
                assertEquals(WsFault.PROTOCOL, e.fault)
            }
        }
    }

    @Test
    fun invalidReasonUtf8() {
        try {
            WsClose.parse(byteArrayOf(0x03, 0xE8.toByte(), 0xC0.toByte(), 0x80.toByte()))
            fail()
        } catch (e: WsException) {
            assertEquals(WsFault.BAD_UTF8, e.fault)
        }
    }
}

class WsHandshakeTest {
    private val key = "dGhlIHNhbXBsZSBub25jZQ=="
    private val accept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
    private val protocol = "obswebsocket.json"

    private fun response(vararg lines: String) = (lines.toList() + "" + "").joinToString("\r\n")

    private fun good(vararg extra: String) = response(
        "HTTP/1.1 101 Switching Protocols",
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Accept: $accept",
        *extra,
    )

    private fun assertRejected(head: String) {
        try {
            WsHandshake.validate(head, key, protocol)
            fail(head)
        } catch (e: WsException) {
            assertEquals(WsFault.HANDSHAKE, e.fault)
        }
    }

    @Test
    fun rfcAcceptVector() {
        assertEquals(accept, WsHandshake.acceptFor(key))
    }

    @Test
    fun keyIsRandom16BytesBase64() {
        val random = SecureRandom()
        val first = WsHandshake.newKey(random)
        assertEquals(16, java.util.Base64.getDecoder().decode(first).size)
        assertFalse(first == WsHandshake.newKey(random))
    }

    @Test
    fun requestShape() {
        val text = String(WsHandshake.request("192.168.1.2:4455", "/", key, protocol), Charsets.US_ASCII)
        assertTrue(text.startsWith("GET / HTTP/1.1\r\nHost: 192.168.1.2:4455\r\n"))
        assertTrue(text.contains("Sec-WebSocket-Key: $key\r\n"))
        assertTrue(text.contains("Sec-WebSocket-Version: 13\r\n"))
        assertTrue(text.contains("Sec-WebSocket-Protocol: $protocol\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun requestRefusesHeaderInjection() {
        listOf("a\r\nEvil: 1", "a\nb", "a\rb", "a\u0000b", "é", "", "a\tb", "a\u007Fb").forEach { bad ->
            try {
                WsHandshake.request(bad, "/", key, protocol)
                fail(bad)
            } catch (e: WsException) {
                assertEquals(WsFault.HANDSHAKE, e.fault)
            }
            try {
                WsHandshake.request("host", "/", key, bad)
                fail(bad)
            } catch (e: WsException) {
                assertEquals(WsFault.HANDSHAKE, e.fault)
            }
        }
    }

    @Test
    fun requestRefusesBadPath() {
        listOf("x", "/a b", "/a\r\nb").forEach {
            try {
                WsHandshake.request("host", it, key, protocol)
                fail(it)
            } catch (e: WsException) {
                assertEquals(WsFault.HANDSHAKE, e.fault)
            }
        }
    }

    @Test
    fun acceptsValidResponse() {
        WsHandshake.validate(good(), key, protocol)
        WsHandshake.validate(good("Sec-WebSocket-Protocol: $protocol"), key, protocol)
        WsHandshake.validate(
            response(
                "HTTP/1.1 101",
                "UPGRADE: WebSocket",
                "connection: keep-alive, upgrade",
                "SEC-WEBSOCKET-ACCEPT:$accept",
                "Server: x",
            ),
            key,
            protocol,
        )
    }

    @Test
    fun rejectsOtherStatus() {
        assertRejected(response("HTTP/1.1 200 OK", "Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Accept: $accept"))
        assertRejected(response("HTTP/1.0 101 x", "Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Accept: $accept"))
        assertRejected(response("HTTP/1.1 1010", "Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Accept: $accept"))
        assertRejected(response("garbage"))
        assertRejected("\r\n\r\n")
    }

    @Test
    fun rejectsWrongOrDuplicateAccept() {
        assertRejected(response("HTTP/1.1 101 x", "Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Accept: AAAA"))
        assertRejected(response("HTTP/1.1 101 x", "Upgrade: websocket", "Connection: Upgrade"))
        assertRejected(good("Sec-WebSocket-Accept: $accept"))
    }

    @Test
    fun rejectsMissingUpgradeOrConnection() {
        assertRejected(response("HTTP/1.1 101 x", "Connection: Upgrade", "Sec-WebSocket-Accept: $accept"))
        assertRejected(response("HTTP/1.1 101 x", "Upgrade: h2c", "Connection: Upgrade", "Sec-WebSocket-Accept: $accept"))
        assertRejected(response("HTTP/1.1 101 x", "Upgrade: websocket", "Connection: close", "Sec-WebSocket-Accept: $accept"))
    }

    @Test
    fun rejectsUnrequestedExtensionsAndProtocols() {
        assertRejected(good("Sec-WebSocket-Extensions: permessage-deflate"))
        assertRejected(good("Sec-WebSocket-Protocol: other"))
    }

    @Test
    fun rejectsMalformedHeaders() {
        assertRejected(good("no colon here"))
        assertRejected(good(": empty name"))
        assertRejected(good(" folded: value"))
        assertRejected(good("bad name: value"))
        assertRejected(good("X: bare\nLF"))
        assertRejected(good("X: bare\rCR"))
        assertRejected(good("X: nul\u0000"))
        assertRejected(good("X: highé"))
    }

    @Test
    fun rejectsTooManyHeaders() {
        val many = Array(WsLimits.MAX_HEADERS + 1) { "X-$it: y" }
        assertRejected(good(*many))
    }

    @Test
    fun readHeadStopsAtTerminatorWithoutOverread() {
        val stream = ByteArrayInputStream("HTTP/1.1 101\r\n\r\nNEXT".toByteArray())
        assertEquals("HTTP/1.1 101\r\n\r\n", WsHandshake.readHead(stream, {}))
        assertEquals('N'.code, stream.read())
    }

    @Test
    fun closedBeforeAnyByteIsClosedNotHandshake() {
        try {
            WsHandshake.readHead(ByteArrayInputStream(ByteArray(0)), {})
            fail()
        } catch (e: WsException) {
            assertEquals(WsFault.CLOSED, e.fault)
        }
    }

    @Test
    fun readHeadLimits() {
        try {
            WsHandshake.readHead(ByteArrayInputStream(ByteArray(100) { 'a'.code.toByte() }), {}, 50)
            fail()
        } catch (e: WsException) {
            assertEquals(WsFault.HANDSHAKE, e.fault)
        }
        try {
            WsHandshake.readHead(ByteArrayInputStream("short".toByteArray()), {})
            fail()
        } catch (e: WsException) {
            assertEquals(WsFault.HANDSHAKE, e.fault)
        }
    }
}

class WsClientSocketTest {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun withServer(script: (Socket) -> Unit, test: (Int) -> Unit) {
        ServerSocket(0, 5, loopback).use { server ->
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use(script)
                } catch (e: java.io.IOException) {
                    return@thread
                }
            }
            test(server.localPort)
            worker.join(2000)
        }
    }

    private fun readRequestKey(socket: Socket): String {
        val head = WsHandshake.readHead(socket.getInputStream(), {})
        return Regex("Sec-WebSocket-Key: (\\S+)").find(head)!!.groupValues[1]
    }

    private fun acceptFor(socket: Socket, key: String) {
        val text = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${WsHandshake.acceptFor(key)}\r\n\r\n"
        socket.getOutputStream().write(text.toByteArray())
    }

    private fun client(readTimeout: Int = 2000) = WsClient(WsConfig(readTimeoutMs = readTimeout, frameDeadlineMs = 1000))

    private fun connect(client: WsClient, port: Int, timeout: Long = 2000) =
        client.connect(loopback, port, "127.0.0.1:$port", "/", "obswebsocket.json", timeout)

    @Test
    fun exchangesTextPingPongAndClose() {
        val received = ArrayList<ByteArray>()
        withServer({ socket ->
            acceptFor(socket, readRequestKey(socket))
            val out = socket.getOutputStream()
            out.write(TestFrames.text("first"))
            out.write(TestFrames.server(WsOpcode.PING, "ab".toByteArray()))
            out.write(TestFrames.server(WsOpcode.PONG, "zz".toByteArray()))
            out.write(TestFrames.text("frag", fin = false))
            out.write(TestFrames.server(WsOpcode.PING, "cd".toByteArray()))
            out.write(TestFrames.server(WsOpcode.CONTINUATION, "ment".toByteArray()))
            out.write(TestFrames.server(WsOpcode.CLOSE, TestFrames.closePayload(1000, "bye")))
            out.flush()
            val input = socket.getInputStream()
            repeat(2) {
                val first = input.read()
                assertEquals(0x80 or WsOpcode.PONG, first)
                val second = input.read()
                assertTrue("client frames must be masked", second and 0x80 != 0)
                val mask = ByteArray(4).also { input.readNBytes(it, 0, 4) }
                val body = ByteArray(second and 0x7F).also { input.readNBytes(it, 0, it.size) }
                received.add(ByteArray(body.size) { i -> (body[i].toInt() xor mask[i and 3].toInt()).toByte() })
            }
        }) { port ->
            val socket = client()
            connect(socket, port)
            assertEquals("first", (socket.receive() as WsInbound.Text).text)
            assertEquals("fragment", (socket.receive() as WsInbound.Text).text)
            val closed = socket.receive() as WsInbound.Closed
            assertEquals(1000, closed.code)
            assertEquals("bye", closed.reason)
            socket.abort()
        }
        assertEquals(listOf("ab", "cd"), received.map { String(it) })
    }

    @Test
    fun badAcceptIsHandshakeFault() {
        withServer({ socket ->
            readRequestKey(socket)
            socket.getOutputStream().write(
                "HTTP/1.1 101 x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: bad\r\n\r\n".toByteArray(),
            )
        }) { port ->
            try {
                connect(client(), port)
                fail()
            } catch (e: WsException) {
                assertEquals(WsFault.HANDSHAKE, e.fault)
            }
        }
    }

    @Test
    fun silentServerHitsConnectionDeadline() {
        withServer({ socket ->
            readRequestKey(socket)
            Thread.sleep(600)
        }) { port ->
            val started = System.nanoTime()
            try {
                connect(client(), port, timeout = 200)
                fail()
            } catch (e: WsException) {
                assertEquals(WsFault.TIMEOUT, e.fault)
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 550)
        }
    }

    @Test
    fun drippingHandshakeHitsConnectionDeadline() {
        withServer({ socket ->
            readRequestKey(socket)
            val out = socket.getOutputStream()
            try {
                repeat(40) {
                    out.write('H'.code)
                    out.flush()
                    Thread.sleep(25)
                }
            } catch (e: java.io.IOException) {
                return@withServer
            }
        }) { port ->
            try {
                connect(client(), port, timeout = 200)
                fail()
            } catch (e: WsException) {
                assertEquals(WsFault.TIMEOUT, e.fault)
            }
        }
    }

    @Test
    fun readTimeoutSurfaces() {
        withServer({ socket ->
            acceptFor(socket, readRequestKey(socket))
            Thread.sleep(400)
        }) { port ->
            val socket = client(readTimeout = 100)
            connect(socket, port)
            try {
                socket.receive()
                fail()
            } catch (e: WsException) {
                assertEquals(WsFault.TIMEOUT, e.fault)
            }
            socket.abort()
        }
    }

    @Test
    fun abortUnblocksRead() {
        withServer({ socket ->
            acceptFor(socket, readRequestKey(socket))
            Thread.sleep(500)
        }) { port ->
            val socket = client()
            connect(socket, port)
            thread(isDaemon = true) {
                Thread.sleep(50)
                socket.abort()
            }
            val started = System.nanoTime()
            try {
                socket.receive()
                fail()
            } catch (e: java.io.IOException) {
                assertTrue((System.nanoTime() - started) / 1_000_000 < 450)
            }
        }
    }

    @Test
    fun abortBeforeConnectRefuses() {
        val socket = client()
        socket.abort()
        try {
            connect(socket, 1)
            fail()
        } catch (e: WsException) {
            assertEquals(WsFault.CLOSED, e.fault)
        }
    }

    @Test
    fun sendingBeforeConnectFails() {
        try {
            client().sendText("x")
            fail()
        } catch (e: WsException) {
            assertEquals(WsFault.CLOSED, e.fault)
        }
    }

    @Test
    fun outgoingLimits() {
        withServer({ socket ->
            acceptFor(socket, readRequestKey(socket))
            Thread.sleep(100)
        }) { port ->
            val socket = WsClient(WsConfig(maxMessage = 4))
            connect(socket, port)
            try {
                socket.sendText("12345")
                fail()
            } catch (e: WsException) {
                assertEquals(WsFault.TOO_LARGE, e.fault)
            }
            try {
                socket.sendPong(ByteArray(126))
                fail()
            } catch (e: WsException) {
                assertEquals(WsFault.PROTOCOL, e.fault)
            }
            try {
                socket.sendClose(1005)
                fail()
            } catch (e: WsException) {
                assertEquals(WsFault.PROTOCOL, e.fault)
            }
            socket.abort()
        }
    }
}
