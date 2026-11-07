package dev.nekuz.sceneremote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class WsFault { HANDSHAKE, PROTOCOL, TOO_LARGE, BAD_UTF8, TIMEOUT, CLOSED }

class WsException(val fault: WsFault, message: String) : IOException(message)

object WsLimits {
    const val MAX_FRAME = 1024 * 1024
    const val MAX_MESSAGE = 1024 * 1024
    const val MAX_HANDSHAKE = 16 * 1024
    const val MAX_HEADERS = 64
    const val MAX_FRAGMENTS = 4096
    const val MAX_CONTROL = 125
}

object WsOpcode {
    const val CONTINUATION = 0
    const val TEXT = 1
    const val BINARY = 2
    const val CLOSE = 8
    const val PING = 9
    const val PONG = 10

    fun isControl(opcode: Int) = opcode >= CLOSE
    fun isKnown(opcode: Int) = opcode in CONTINUATION..BINARY || opcode in CLOSE..PONG
}

object Utf8 {
    fun isValid(bytes: ByteArray): Boolean {
        var index = 0
        val end = bytes.size
        while (index < end) {
            val first = bytes[index].toInt() and 0xFF
            val extra: Int
            var low = 0x80
            var high = 0xBF
            when (first) {
                in 0x00..0x7F -> extra = 0
                in 0xC2..0xDF -> extra = 1
                0xE0 -> {
                    extra = 2
                    low = 0xA0
                }
                0xED -> {
                    extra = 2
                    high = 0x9F
                }
                in 0xE1..0xEF -> extra = 2
                0xF0 -> {
                    extra = 3
                    low = 0x90
                }
                in 0xF1..0xF3 -> extra = 3
                0xF4 -> {
                    extra = 3
                    high = 0x8F
                }
                else -> return false
            }
            if (end - index - 1 < extra) return false
            for (offset in 1..extra) {
                val next = bytes[index + offset].toInt() and 0xFF
                if (next < low || next > high) return false
                low = 0x80
                high = 0xBF
            }
            index += extra + 1
        }
        return true
    }

    fun decode(bytes: ByteArray): String {
        if (!isValid(bytes)) throw WsException(WsFault.BAD_UTF8, "Invalid UTF-8")
        return String(bytes, Charsets.UTF_8)
    }
}

class WsFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

object WsFrames {
    fun encodeClient(opcode: Int, payload: ByteArray, maskKey: ByteArray, fin: Boolean = true): ByteArray {
        require(maskKey.size == 4)
        val size = payload.size
        val extended = when {
            size < 126 -> 0
            size <= 0xFFFF -> 2
            else -> 8
        }
        val out = ByteArray(2 + extended + 4 + size)
        out[0] = ((if (fin) 0x80 else 0) or opcode).toByte()
        when (extended) {
            0 -> out[1] = (0x80 or size).toByte()
            2 -> {
                out[1] = (0x80 or 126).toByte()
                out[2] = (size ushr 8).toByte()
                out[3] = size.toByte()
            }
            else -> {
                out[1] = (0x80 or 127).toByte()
                for (shift in 0 until 8) {
                    out[9 - shift] = (size.toLong() ushr (8 * shift)).toByte()
                }
            }
        }
        val start = 2 + extended
        maskKey.copyInto(out, start)
        for (index in 0 until size) {
            out[start + 4 + index] = (payload[index].toInt() xor maskKey[index and 3].toInt()).toByte()
        }
        return out
    }
}

class WsFrameReader(
    private val input: InputStream,
    private val maxFrame: Int = WsLimits.MAX_FRAME,
    private val frameDeadlineMs: Long = 10_000,
) {
    fun read(): WsFrame {
        val first = input.read()
        if (first < 0) throw WsException(WsFault.CLOSED, "Connection closed")
        val deadline = System.nanoTime() + frameDeadlineMs * NANOS_PER_MS
        val second = readByte(deadline)
        if (first and 0x70 != 0) throw WsException(WsFault.PROTOCOL, "Reserved bits set")
        val opcode = first and 0x0F
        if (!WsOpcode.isKnown(opcode)) throw WsException(WsFault.PROTOCOL, "Unknown opcode")
        if (second and 0x80 != 0) throw WsException(WsFault.PROTOCOL, "Masked server frame")
        val fin = first and 0x80 != 0
        val short = second and 0x7F
        if (WsOpcode.isControl(opcode)) {
            if (!fin) throw WsException(WsFault.PROTOCOL, "Fragmented control frame")
            if (short > WsLimits.MAX_CONTROL) throw WsException(WsFault.PROTOCOL, "Control frame too long")
        }
        val length = when (short) {
            126 -> {
                val value = readNumber(2, deadline)
                if (value < 126) throw WsException(WsFault.PROTOCOL, "Length not minimal")
                value
            }
            127 -> {
                val value = readNumber(8, deadline)
                if (value < 0) throw WsException(WsFault.PROTOCOL, "Invalid length")
                if (value <= 0xFFFF) throw WsException(WsFault.PROTOCOL, "Length not minimal")
                value
            }
            else -> short.toLong()
        }
        if (length > maxFrame) throw WsException(WsFault.TOO_LARGE, "Frame too large")
        val payload = ByteArray(length.toInt())
        readFully(payload, deadline)
        return WsFrame(fin, opcode, payload)
    }

    private fun readByte(deadline: Long): Int {
        checkDeadline(deadline)
        val value = input.read()
        if (value < 0) throw EOFException("Truncated frame")
        return value
    }

    private fun readNumber(count: Int, deadline: Long): Long {
        var value = 0L
        repeat(count) { value = (value shl 8) or readByte(deadline).toLong() }
        return value
    }

    private fun readFully(target: ByteArray, deadline: Long) {
        var offset = 0
        while (offset < target.size) {
            checkDeadline(deadline)
            val count = input.read(target, offset, minOf(target.size - offset, READ_CHUNK))
            if (count < 0) throw EOFException("Truncated frame")
            offset += count
        }
    }

    private fun checkDeadline(deadline: Long) {
        if (System.nanoTime() - deadline > 0) throw WsException(WsFault.TIMEOUT, "Frame too slow")
    }

    private companion object {
        const val READ_CHUNK = 64 * 1024
        const val NANOS_PER_MS = 1_000_000L
    }
}

class WsAssembler(
    private val maxMessage: Int = WsLimits.MAX_MESSAGE,
    private val maxFragments: Int = WsLimits.MAX_FRAGMENTS,
) {
    private var buffer: ByteArrayOutputStream? = null
    private var fragments = 0

    fun accept(frame: WsFrame): String? {
        when (frame.opcode) {
            WsOpcode.TEXT -> {
                if (buffer != null) throw WsException(WsFault.PROTOCOL, "New message inside a fragmented one")
                if (frame.payload.size > maxMessage) throw WsException(WsFault.TOO_LARGE, "Message too large")
                if (frame.fin) return Utf8.decode(frame.payload)
                buffer = ByteArrayOutputStream(minOf(frame.payload.size * 2, maxMessage)).also {
                    it.write(frame.payload, 0, frame.payload.size)
                }
                fragments = 1
                return null
            }
            WsOpcode.CONTINUATION -> {
                val current = buffer ?: throw WsException(WsFault.PROTOCOL, "Continuation with nothing to continue")
                if (current.size().toLong() + frame.payload.size > maxMessage) {
                    throw WsException(WsFault.TOO_LARGE, "Message too large")
                }
                fragments++
                if (fragments > maxFragments) throw WsException(WsFault.TOO_LARGE, "Too many fragments")
                current.write(frame.payload, 0, frame.payload.size)
                if (!frame.fin) return null
                buffer = null
                fragments = 0
                return Utf8.decode(current.toByteArray())
            }
            WsOpcode.BINARY -> throw WsException(WsFault.PROTOCOL, "Binary message not expected")
            else -> throw WsException(WsFault.PROTOCOL, "Not a data frame")
        }
    }
}

sealed class WsInbound {
    class Text(val text: String) : WsInbound()
    class Closed(val code: Int?, val reason: String) : WsInbound()
}

object WsClose {
    const val NORMAL = 1000
    const val PROTOCOL_ERROR = 1002
    const val INVALID_DATA = 1007
    const val TOO_BIG = 1009

    fun parse(payload: ByteArray): WsInbound.Closed {
        if (payload.isEmpty()) return WsInbound.Closed(null, "")
        if (payload.size == 1) throw WsException(WsFault.PROTOCOL, "Invalid close frame")
        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (!isValidCode(code)) throw WsException(WsFault.PROTOCOL, "Invalid close code")
        return WsInbound.Closed(code, Utf8.decode(payload.copyOfRange(2, payload.size)))
    }

    fun isValidCode(code: Int) = when (code) {
        1004, 1005, 1006 -> false
        in 1000..1014, in 3000..4999 -> true
        else -> false
    }

    fun forFault(fault: WsFault) = when (fault) {
        WsFault.TOO_LARGE -> TOO_BIG
        WsFault.BAD_UTF8 -> INVALID_DATA
        else -> PROTOCOL_ERROR
    }
}

object WsHandshake {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val STATUS_LINE = Regex("HTTP/1\\.1 101( .*)?")

    fun newKey(random: SecureRandom): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun acceptFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun isSafeHeaderValue(value: String): Boolean =
        value.isNotEmpty() && value.length <= 1024 && value.all { it in ' '..'~' }

    fun request(hostHeader: String, path: String, key: String, protocol: String): ByteArray {
        listOf(hostHeader, path, key, protocol).forEach {
            if (!isSafeHeaderValue(it)) throw WsException(WsFault.HANDSHAKE, "Unsafe header value")
        }
        if (!path.startsWith("/") || ' ' in path) throw WsException(WsFault.HANDSHAKE, "Invalid path")
        val text = "GET $path HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Protocol: $protocol\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"
        return text.toByteArray(Charsets.US_ASCII)
    }

    fun readHead(input: InputStream, checkDeadline: () -> Unit, maxBytes: Int = WsLimits.MAX_HANDSHAKE): String {
        val out = StringBuilder()
        while (true) {
            checkDeadline()
            val value = input.read()
            if (value < 0 && out.isEmpty()) throw WsException(WsFault.CLOSED, "Closed before any answer")
            if (value < 0) throw WsException(WsFault.HANDSHAKE, "Closed during the handshake")
            if (out.length >= maxBytes) throw WsException(WsFault.HANDSHAKE, "Handshake too long")
            out.append(value.toChar())
            if (value == '\n'.code && out.endsWith("\r\n\r\n")) return out.toString()
        }
    }

    fun validate(head: String, key: String, protocol: String) {
        val lines = head.removeSuffix("\r\n\r\n").split("\r\n")
        if (lines.size - 1 > WsLimits.MAX_HEADERS) fail("Too many headers")
        lines.forEach { line ->
            if (line.any { it.code < 0x20 && it != '\t' || it.code >= 0x7F }) fail("Invalid characters")
        }
        if (!STATUS_LINE.matches(lines[0])) fail("Server did not switch protocols")
        val headers = HashMap<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) fail("Malformed header")
            val name = line.substring(0, colon)
            if (!HEADER_NAME.matches(name)) fail("Malformed header name")
            val value = line.substring(colon + 1).trim(' ', '\t')
            headers.getOrPut(name.lowercase(java.util.Locale.ROOT)) { ArrayList() }.add(value)
        }
        val upgrade = headers["upgrade"]
        if (upgrade?.size != 1 || !upgrade[0].equals("websocket", ignoreCase = true)) fail("Missing Upgrade")
        val connection = headers["connection"].orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim(' ', '\t') }
        if (connection.none { it.equals("upgrade", ignoreCase = true) }) fail("Missing Connection")
        val accept = headers["sec-websocket-accept"]
        if (accept?.size != 1 || accept[0] != acceptFor(key)) fail("Sec-WebSocket-Accept mismatch")
        if (headers.containsKey("sec-websocket-extensions")) fail("Unrequested extension")
        val chosen = headers["sec-websocket-protocol"]
        if (chosen != null && (chosen.size != 1 || chosen[0] != protocol)) fail("Unrequested subprotocol")
    }

    private fun fail(message: String): Nothing = throw WsException(WsFault.HANDSHAKE, message)
}

class WsConfig(
    val readTimeoutMs: Int = 15_000,
    val frameDeadlineMs: Long = 10_000,
    val maxFrame: Int = WsLimits.MAX_FRAME,
    val maxMessage: Int = WsLimits.MAX_MESSAGE,
)

class WsClient(
    private val config: WsConfig = WsConfig(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val writeLock = Any()
    private val assembler = WsAssembler(config.maxMessage)

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var aborted = false

    @Volatile
    private var output: OutputStream? = null

    private var frames: WsFrameReader? = null

    fun connect(
        address: InetAddress,
        port: Int,
        hostHeader: String,
        path: String,
        protocol: String,
        timeoutMs: Long,
    ) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
        val key = WsHandshake.newKey(random)
        val request = WsHandshake.request(hostHeader, path, key, protocol)
        val opened = Socket()
        socket = opened
        if (aborted) {
            abort()
            throw WsException(WsFault.CLOSED, "Aborted")
        }
        try {
            opened.tcpNoDelay = true
            opened.connect(InetSocketAddress(address, port), remainingMs(deadline))
            opened.soTimeout = remainingMs(deadline)
            val stream = BufferedInputStream(opened.getInputStream(), BUFFER)
            val sink = opened.getOutputStream()
            sink.write(request)
            sink.flush()
            val head = WsHandshake.readHead(stream, { remainingMs(deadline) })
            WsHandshake.validate(head, key, protocol)
            opened.soTimeout = config.readTimeoutMs
            output = sink
            frames = WsFrameReader(stream, config.maxFrame, config.frameDeadlineMs)
        } catch (e: SocketTimeoutException) {
            abort()
            throw WsException(WsFault.TIMEOUT, "Timed out while connecting")
        } catch (e: IOException) {
            abort()
            throw e
        }
    }

    fun receive(onPing: ((ByteArray) -> Unit)? = null): WsInbound {
        val reader = frames ?: throw WsException(WsFault.CLOSED, "Not connected")
        try {
            while (true) {
                val frame = reader.read()
                when (frame.opcode) {
                    WsOpcode.PING -> if (onPing != null) onPing(frame.payload) else sendPong(frame.payload)
                    WsOpcode.PONG -> Unit
                    WsOpcode.CLOSE -> return WsClose.parse(frame.payload)
                    else -> assembler.accept(frame)?.let { return WsInbound.Text(it) }
                }
            }
        } catch (e: SocketTimeoutException) {
            throw WsException(WsFault.TIMEOUT, "Read timed out")
        }
    }

    fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > config.maxMessage) throw WsException(WsFault.TOO_LARGE, "Message too large")
        send(WsOpcode.TEXT, bytes)
    }

    fun sendPong(payload: ByteArray) {
        if (payload.size > WsLimits.MAX_CONTROL) throw WsException(WsFault.PROTOCOL, "Control frame too long")
        send(WsOpcode.PONG, payload)
    }

    fun sendClose(code: Int) {
        if (!WsClose.isValidCode(code)) throw WsException(WsFault.PROTOCOL, "Invalid close code")
        send(WsOpcode.CLOSE, byteArrayOf((code ushr 8).toByte(), code.toByte()))
    }

    fun abort() {
        aborted = true
        try {
            socket?.close()
        } catch (e: IOException) {
            return
        }
    }

    private fun send(opcode: Int, payload: ByteArray) {
        val sink = output ?: throw WsException(WsFault.CLOSED, "Not connected")
        val mask = ByteArray(4)
        random.nextBytes(mask)
        val frame = WsFrames.encodeClient(opcode, payload, mask)
        synchronized(writeLock) {
            sink.write(frame)
            sink.flush()
        }
    }

    private fun remainingMs(deadline: Long): Int {
        val left = (deadline - System.nanoTime()) / NANOS_PER_MS
        if (left <= 0) throw WsException(WsFault.TIMEOUT, "Connection deadline passed")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val BUFFER = 16 * 1024
        const val NANOS_PER_MS = 1_000_000L
    }
}
