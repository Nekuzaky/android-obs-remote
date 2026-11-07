package dev.nekuz.sceneremote

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Locale

enum class AddressClass { LOOPBACK, PRIVATE, LINK_LOCAL, REFUSED }

object AddressPolicy {
    fun classify(address: ByteArray): AddressClass = when (address.size) {
        4 -> ipv4(address, 0)
        16 -> ipv6(address)
        else -> AddressClass.REFUSED
    }

    fun isAllowed(address: ByteArray) = classify(address) != AddressClass.REFUSED

    private fun ipv4(address: ByteArray, start: Int): AddressClass {
        val first = address[start].toInt() and 0xFF
        val second = address[start + 1].toInt() and 0xFF
        return when {
            first == 127 -> AddressClass.LOOPBACK
            first == 10 -> AddressClass.PRIVATE
            first == 172 && second in 16..31 -> AddressClass.PRIVATE
            first == 192 && second == 168 -> AddressClass.PRIVATE
            first == 169 && second == 254 -> AddressClass.LINK_LOCAL
            else -> AddressClass.REFUSED
        }
    }

    private fun ipv6(address: ByteArray): AddressClass {
        val first = address[0].toInt() and 0xFF
        val second = address[1].toInt() and 0xFF
        val mapped = (0 until 10).all { address[it].toInt() == 0 } &&
            address[10].toInt() and 0xFF == 0xFF && address[11].toInt() and 0xFF == 0xFF
        val loopback = (0 until 15).all { address[it].toInt() == 0 } && address[15].toInt() == 1
        return when {
            mapped -> ipv4(address, 12)
            loopback -> AddressClass.LOOPBACK
            first == 0xFE && second and 0xC0 == 0x80 -> AddressClass.LINK_LOCAL
            first and 0xFE == 0xFC -> AddressClass.PRIVATE
            else -> AddressClass.REFUSED
        }
    }
}

sealed class HostSpec {
    abstract val display: String
    abstract val headerHost: String

    class Literal(
        val bytes: ByteArray,
        val scope: String?,
        override val display: String,
        override val headerHost: String,
    ) : HostSpec()

    class Name(val name: String) : HostSpec() {
        override val display: String get() = name
        override val headerHost: String get() = name
    }
}

enum class HostError { EMPTY, TOO_LONG, INVALID, PUBLIC }

sealed class HostParse {
    class Ok(val spec: HostSpec) : HostParse()
    class Bad(val error: HostError) : HostParse()
}

object HostParser {
    private const val MAX_INPUT = 255
    private const val MAX_NAME = 253
    private const val MAX_LABEL = 63
    private val SCOPE = Regex("[A-Za-z0-9_.-]{1,32}")
    private val OCTET = Regex("0|[1-9][0-9]{0,2}")
    private val HEXTET = Regex("[0-9a-fA-F]{1,4}")
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")
    private val NUMERIC_LABEL = Regex("[0-9]+|0x[0-9a-f]*")
    private val DOTTED = Regex("[0-9.]+")

    fun parsePort(raw: String?): Int? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty() || text.length > 5 || text.any { it !in '0'..'9' }) return null
        return text.toInt().takeIf { it in 1..65535 }
    }

    fun parse(raw: String?): HostParse {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return HostParse.Bad(HostError.EMPTY)
        if (text.length > MAX_INPUT) return HostParse.Bad(HostError.TOO_LONG)
        if (text.startsWith("[") || ':' in text) return parseV6Literal(text)
        if (DOTTED.matches(text)) {
            val bytes = parseIpv4(text.removeSuffix(".").takeIf { it == text } ?: "")
                ?: return HostParse.Bad(HostError.INVALID)
            if (!AddressPolicy.isAllowed(bytes)) return HostParse.Bad(HostError.PUBLIC)
            return HostParse.Ok(HostSpec.Literal(bytes, null, text, text))
        }
        val name = text.removeSuffix(".").lowercase(Locale.ROOT)
        return if (isValidName(name)) HostParse.Ok(HostSpec.Name(name)) else HostParse.Bad(HostError.INVALID)
    }

    fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME) return false
        val labels = name.split('.')
        if (labels.any { it.length > MAX_LABEL || !LABEL.matches(it) }) return false
        return !NUMERIC_LABEL.matches(labels.last())
    }

    fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        parts.forEachIndexed { index, part ->
            if (!OCTET.matches(part)) return null
            val value = part.toInt()
            if (value > 255) return null
            out[index] = value.toByte()
        }
        return out
    }

    fun parseIpv6(text: String): ByteArray? {
        if (text.isEmpty() || text.length > 45) return null
        val gap = text.indexOf("::")
        if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null
        val head = groups(if (gap >= 0) text.substring(0, gap) else text, gap < 0) ?: return null
        val tail = (if (gap >= 0) groups(text.substring(gap + 2), true) else emptyList()) ?: return null
        val count = head.size + tail.size
        if (gap >= 0 && count > 7) return null
        if (gap < 0 && count != 8) return null
        val out = ByteArray(16)
        head.forEachIndexed { index, value -> putGroup(out, index, value) }
        tail.forEachIndexed { index, value -> putGroup(out, 8 - tail.size + index, value) }
        return out
    }

    fun formatIpv6(bytes: ByteArray): String {
        require(bytes.size == 16)
        val groups = IntArray(8) { ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF) }
        var bestStart = -1
        var bestLength = 0
        var index = 0
        while (index < 8) {
            if (groups[index] != 0) {
                index++
                continue
            }
            val start = index
            while (index < 8 && groups[index] == 0) index++
            if (index - start > bestLength) {
                bestStart = start
                bestLength = index - start
            }
        }
        if (bestLength < 2) return groups.joinToString(":") { it.toString(16) }
        val before = groups.take(bestStart).joinToString(":") { it.toString(16) }
        val after = groups.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
        return "$before::$after"
    }

    private fun parseV6Literal(text: String): HostParse {
        val inner = when {
            text.startsWith("[") && text.endsWith("]") -> text.substring(1, text.length - 1)
            text.startsWith("[") || text.endsWith("]") -> return HostParse.Bad(HostError.INVALID)
            else -> text
        }
        val percent = inner.indexOf('%')
        val address = if (percent >= 0) inner.substring(0, percent) else inner
        val scope = if (percent >= 0) inner.substring(percent + 1) else null
        val bytes = parseIpv6(address) ?: return HostParse.Bad(HostError.INVALID)
        val type = AddressPolicy.classify(bytes)
        if (type == AddressClass.REFUSED) return HostParse.Bad(HostError.PUBLIC)
        if (scope != null && (type != AddressClass.LINK_LOCAL || !SCOPE.matches(scope))) {
            return HostParse.Bad(HostError.INVALID)
        }
        val canonical = formatIpv6(bytes)
        val shown = if (scope == null) canonical else "$canonical%$scope"
        return HostParse.Ok(HostSpec.Literal(bytes, scope, "[$shown]", "[$canonical]"))
    }

    private fun groups(text: String, allowIpv4: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        val out = ArrayList<Int>(8)
        parts.forEachIndexed { index, part ->
            if (allowIpv4 && index == parts.size - 1 && '.' in part) {
                val v4 = parseIpv4(part) ?: return null
                out.add(((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF))
                out.add(((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF))
            } else {
                if (!HEXTET.matches(part)) return null
                out.add(part.toInt(16))
            }
        }
        return out
    }

    private fun putGroup(out: ByteArray, index: Int, value: Int) {
        out[index * 2] = (value ushr 8).toByte()
        out[index * 2 + 1] = value.toByte()
    }
}

sealed class Resolution {
    class Allowed(val addresses: List<InetAddress>) : Resolution()
    object Refused : Resolution()
    object Unresolved : Resolution()
}

object HostResolver {
    private const val MAX_CANDIDATES = 4

    fun resolve(spec: HostSpec, lookup: (String) -> List<InetAddress>): Resolution = when (spec) {
        is HostSpec.Literal -> literal(spec)
        is HostSpec.Name -> named(spec.name, lookup)
    }

    fun filter(found: List<InetAddress>): Resolution {
        if (found.isEmpty()) return Resolution.Unresolved
        val allowed = found.filter { AddressPolicy.isAllowed(it.address) }
        if (allowed.isEmpty()) return Resolution.Refused
        val ordered = allowed.filterIsInstance<Inet4Address>() + allowed.filterNot { it is Inet4Address }
        return Resolution.Allowed(ordered.take(MAX_CANDIDATES).map(::pinned))
    }

    private fun literal(spec: HostSpec.Literal): Resolution {
        if (!AddressPolicy.isAllowed(spec.bytes)) return Resolution.Refused
        return try {
            val address = spec.scope?.let { scoped(spec.bytes, it) } ?: InetAddress.getByAddress(spec.bytes)
            Resolution.Allowed(listOf(address))
        } catch (e: UnknownHostException) {
            Resolution.Unresolved
        } catch (e: SocketException) {
            Resolution.Unresolved
        }
    }

    private fun scoped(bytes: ByteArray, scope: String): InetAddress {
        val numeric = scope.toIntOrNull()
        if (numeric != null) return Inet6Address.getByAddress(null, bytes, numeric)
        val network = NetworkInterface.getByName(scope) ?: throw UnknownHostException("No such interface")
        return Inet6Address.getByAddress(null, bytes, network)
    }

    private fun named(name: String, lookup: (String) -> List<InetAddress>): Resolution = try {
        filter(lookup(name))
    } catch (e: UnknownHostException) {
        Resolution.Unresolved
    } catch (e: SecurityException) {
        Resolution.Unresolved
    }

    private fun pinned(address: InetAddress): InetAddress {
        val scope = (address as? Inet6Address)?.scopeId ?: 0
        return if (scope > 0 && address.address.size == 16) {
            Inet6Address.getByAddress(null, address.address, scope)
        } else {
            InetAddress.getByAddress(address.address)
        }
    }
}
