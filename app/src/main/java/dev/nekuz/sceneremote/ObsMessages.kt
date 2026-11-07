package dev.nekuz.sceneremote

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

object ObsAuth {
    fun secret(password: String, salt: String): String = hash(password + salt)

    fun response(password: String, salt: String, challenge: String): String =
        hash(secret(password, salt) + challenge)

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
}

object JsonGuard {
    const val MAX_DEPTH = 32
    const val MAX_NODES = 64_000

    private const val START = '\u0000'
    private const val OPENERS = "\u0000{[,:"
    private const val AFTER_STRING = ":,}]"
    private const val SCALAR = "0123456789+-.eEtruflasn"
    private const val BLANK = " \t\n\r"

    fun withinLimits(text: String, maxDepth: Int = MAX_DEPTH, maxNodes: Int = MAX_NODES): Boolean {
        var depth = 0
        var nodes = 0
        var inString = false
        var escaped = false
        var previous = START
        for (char in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> {
                        inString = false
                        previous = '"'
                    }
                }
                continue
            }
            if (char in BLANK) continue
            if (previous == '"' && char !in AFTER_STRING) return false
            when (char) {
                '"' -> {
                    if (previous !in OPENERS) return false
                    inString = true
                }
                '{', '[' -> {
                    if (previous !in OPENERS) return false
                    depth++
                    nodes++
                    if (depth > maxDepth || nodes > maxNodes) return false
                }
                '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
                ':' -> Unit
                ',' -> {
                    nodes++
                    if (nodes > maxNodes) return false
                }
                else -> if (char !in SCALAR) return false
            }
            previous = char
        }
        return true
    }
}

object ObsRequest {
    const val GET_SCENE_LIST = "GetSceneList"
    const val SET_PROGRAM_SCENE = "SetCurrentProgramScene"
    const val GET_INPUT_LIST = "GetInputList"
    const val GET_INPUT_MUTE = "GetInputMute"
    const val SET_INPUT_MUTE = "SetInputMute"
    const val GET_STATS = "GetStats"
    const val GET_STREAM_STATUS = "GetStreamStatus"
    const val GET_RECORD_STATUS = "GetRecordStatus"
    const val START_STREAM = "StartStream"
    const val STOP_STREAM = "StopStream"
    const val START_RECORD = "StartRecord"
    const val STOP_RECORD = "StopRecord"
}

object ObsEventName {
    const val PROGRAM_SCENE_CHANGED = "CurrentProgramSceneChanged"
    const val SCENE_LIST_CHANGED = "SceneListChanged"
    const val SCENE_CREATED = "SceneCreated"
    const val SCENE_REMOVED = "SceneRemoved"
    const val SCENE_NAME_CHANGED = "SceneNameChanged"
    const val COLLECTION_CHANGED = "CurrentSceneCollectionChanged"
    const val STREAM_STATE_CHANGED = "StreamStateChanged"
    const val RECORD_STATE_CHANGED = "RecordStateChanged"
    const val INPUT_MUTE_CHANGED = "InputMuteStateChanged"
    const val INPUT_CREATED = "InputCreated"
    const val INPUT_REMOVED = "InputRemoved"
    const val INPUT_NAME_CHANGED = "InputNameChanged"
}

object ObsClose {
    const val AUTHENTICATION_FAILED = 4009
    const val UNSUPPORTED_RPC = 4010
    const val SESSION_INVALIDATED = 4011

    fun isProtocolFault(code: Int) = code in 4002..4008 || code == 4012
}

sealed class ObsInbound {
    class Hello(val rpcVersion: Int, val challenge: String?, val salt: String?) : ObsInbound() {
        val authRequired: Boolean get() = challenge != null
    }

    class Identified(val rpcVersion: Int) : ObsInbound()
    class Event(val type: String, val data: JSONObject) : ObsInbound()
    class Response(
        val type: String,
        val id: String,
        val ok: Boolean,
        val comment: String?,
        val data: JSONObject,
    ) : ObsInbound()

    object Ignored : ObsInbound()
}

class SceneList(val current: String?, val scenes: List<Scene>, val truncated: Boolean)

class NameList(val names: List<String>, val truncated: Boolean)

object ObsMessages {
    const val RPC_VERSION = 1
    const val SUBPROTOCOL = "obswebsocket.json"
    const val MAX_SCENES = 128
    const val MAX_INPUT_PROBES = 128
    const val MAX_AUDIO_INPUTS = 48
    const val MAX_RAW_NAME = 512
    const val MAX_LABEL = 64
    const val MAX_COMMENT = 160

    const val SUB_CONFIG = 1 shl 1
    const val SUB_SCENES = 1 shl 2
    const val SUB_INPUTS = 1 shl 3
    const val SUB_OUTPUTS = 1 shl 6
    const val EVENT_SUBSCRIPTIONS = SUB_CONFIG or SUB_SCENES or SUB_INPUTS or SUB_OUTPUTS

    private const val OP_HELLO = 0
    private const val OP_IDENTIFY = 1
    private const val OP_IDENTIFIED = 2
    private const val OP_EVENT = 5
    private const val OP_REQUEST = 6
    private const val OP_RESPONSE = 7
    private const val MAX_AUTH_FIELD = 512
    private const val MAX_TYPE = 128
    private const val MAX_ID = 128
    private const val MAX_SCAN = 4096
    private const val FALLBACK_LABEL = "(unnamed)"
    private const val MAX_FRAMES = 1L shl 48
    private const val MAX_DURATION_MS = 100L * 365 * 24 * 3600 * 1000

    fun parse(text: String): ObsInbound? {
        if (!JsonGuard.withinLimits(text)) return null
        return try {
            val root = JSONObject(text)
            val op = integer(root, "op") ?: return null
            val data = root.opt("d") as? JSONObject ?: return null
            when (op) {
                OP_HELLO -> hello(data)
                OP_IDENTIFIED -> integer(data, "negotiatedRpcVersion")?.let { ObsInbound.Identified(it) }
                OP_EVENT -> event(data)
                OP_RESPONSE -> response(data)
                else -> ObsInbound.Ignored
            }
        } catch (e: JSONException) {
            null
        } catch (e: RuntimeException) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    fun identify(authentication: String?): String {
        val data = JSONObject()
            .put("rpcVersion", RPC_VERSION)
            .put("eventSubscriptions", EVENT_SUBSCRIPTIONS)
        if (authentication != null) data.put("authentication", authentication)
        return JSONObject().put("op", OP_IDENTIFY).put("d", data).toString()
    }

    fun request(type: String, id: String, parameters: JSONObject? = null): String {
        val data = JSONObject().put("requestType", type).put("requestId", id)
        if (parameters != null) data.put("requestData", parameters)
        return JSONObject().put("op", OP_REQUEST).put("d", data).toString()
    }

    fun sceneParameters(name: String): JSONObject = JSONObject().put("sceneName", name)

    fun inputParameters(name: String): JSONObject = JSONObject().put("inputName", name)

    fun muteParameters(name: String, muted: Boolean): JSONObject =
        JSONObject().put("inputName", name).put("inputMuted", muted)

    fun label(raw: String): String = ForeignText.clean(raw, MAX_LABEL).ifEmpty { FALLBACK_LABEL }

    fun comment(raw: String?): String? = raw?.let { ForeignText.clean(it, MAX_COMMENT).ifEmpty { null } }

    fun rawName(value: Any?): String? {
        val text = value as? String ?: return null
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_RAW_NAME }
    }

    fun sceneList(data: JSONObject): SceneList? {
        val array = data.opt("scenes") as? JSONArray ?: return null
        val seen = HashSet<String>()
        val found = ArrayList<Pair<Double, Scene>>()
        var truncated = array.length() > MAX_SCAN
        for (index in 0 until minOf(array.length(), MAX_SCAN)) {
            val item = array.opt(index) as? JSONObject ?: continue
            val name = rawName(item.opt("sceneName")) ?: continue
            if (!seen.add(name)) continue
            if (found.size >= MAX_SCENES) {
                truncated = true
                break
            }
            found.add(Pair(number(item, "sceneIndex") ?: 0.0, Scene(name, label(name))))
        }
        val ordered = found.sortedByDescending { it.first }.map { it.second }
        return SceneList(rawName(data.opt("currentProgramSceneName")), ordered, truncated)
    }

    fun inputNames(data: JSONObject): NameList? {
        val array = data.opt("inputs") as? JSONArray ?: return null
        val seen = HashSet<String>()
        val names = ArrayList<String>()
        var truncated = array.length() > MAX_SCAN
        for (index in 0 until minOf(array.length(), MAX_SCAN)) {
            val item = array.opt(index) as? JSONObject ?: continue
            val name = rawName(item.opt("inputName")) ?: continue
            if (!seen.add(name)) continue
            if (names.size >= MAX_INPUT_PROBES) {
                truncated = true
                break
            }
            names.add(name)
        }
        return NameList(names, truncated)
    }

    fun stats(data: JSONObject) = Stats(
        cpuPercent = number(data, "cpuUsage")?.coerceIn(0.0, 100_000.0),
        fps = number(data, "activeFps")?.coerceIn(0.0, 100_000.0),
        renderSkipped = count(data, "renderSkippedFrames"),
        renderTotal = count(data, "renderTotalFrames"),
        outputSkipped = count(data, "outputSkippedFrames"),
        outputTotal = count(data, "outputTotalFrames"),
    )

    fun duration(data: JSONObject): Long? =
        number(data, "outputDuration")?.coerceIn(0.0, MAX_DURATION_MS.toDouble())?.toLong()

    fun count(data: JSONObject, key: String): Long? =
        number(data, key)?.coerceIn(0.0, MAX_FRAMES.toDouble())?.toLong()

    fun bool(data: JSONObject, key: String): Boolean? = data.opt(key) as? Boolean

    fun text(data: JSONObject, key: String, maxLength: Int = MAX_TYPE): String? =
        (data.opt(key) as? String)?.takeIf { it.length <= maxLength }

    fun number(data: JSONObject, key: String): Double? {
        val value = (data.opt(key) as? Number)?.toDouble() ?: return null
        return value.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    private fun integer(data: JSONObject, key: String): Int? {
        val value = number(data, key) ?: return null
        if (value != Math.rint(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
        return value.toInt()
    }

    private fun hello(data: JSONObject): ObsInbound? {
        val rpc = integer(data, "rpcVersion") ?: return null
        if (rpc < 1) return null
        val raw = data.opt("authentication") ?: return ObsInbound.Hello(rpc, null, null)
        val auth = raw as? JSONObject ?: return null
        val challenge = text(auth, "challenge", MAX_AUTH_FIELD)?.takeIf { it.isNotEmpty() } ?: return null
        val salt = text(auth, "salt", MAX_AUTH_FIELD)?.takeIf { it.isNotEmpty() } ?: return null
        return ObsInbound.Hello(rpc, challenge, salt)
    }

    private fun event(data: JSONObject): ObsInbound? {
        val type = text(data, "eventType") ?: return null
        return ObsInbound.Event(type, data.opt("eventData") as? JSONObject ?: JSONObject())
    }

    private fun response(data: JSONObject): ObsInbound? {
        val type = text(data, "requestType") ?: return null
        val id = text(data, "requestId", MAX_ID) ?: return null
        val status = data.opt("requestStatus") as? JSONObject ?: return null
        val ok = bool(status, "result") ?: return null
        val comment = status.opt("comment") as? String
        return ObsInbound.Response(type, id, ok, comment, data.opt("responseData") as? JSONObject ?: JSONObject())
    }
}
