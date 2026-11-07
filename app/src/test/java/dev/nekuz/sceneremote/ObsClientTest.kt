package dev.nekuz.sceneremote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class FakeObsServer(
    private val password: String? = "secret",
    private val onHandshake: ((Socket, String) -> Boolean)? = null,
) : Closeable {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val server = ServerSocket(0, 50, loopback)
    private val salt = "c2FsdA=="
    private val challenge = "Y2hhbGxlbmdl"

    val port: Int get() = server.localPort
    val connections = AtomicInteger()
    val heads = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<JSONObject>()
    val identifies = CopyOnWriteArrayList<JSONObject>()
    val closes = CopyOnWriteArrayList<Int>()
    val pongs = CopyOnWriteArrayList<String>()
    val unmaskedFrames = AtomicInteger()
    val live = CopyOnWriteArrayList<Connection>()

    @Volatile
    var scenes: List<Pair<String, Int>> = listOf("Intro" to 2, "Game" to 1, "End" to 0)

    @Volatile
    var currentScene = "Game"

    @Volatile
    var audio = linkedMapOf("Mic" to false, "Desktop" to true)

    @Volatile
    var videoInputs = listOf("Webcam")

    @Volatile
    var streaming = false

    @Volatile
    var recording = false

    @Volatile
    var answering = true

    @Volatile
    var failNext: String? = null

    @Volatile
    var afterIdentified: ((Connection) -> Unit)? = null

    @Volatile
    var responseOverride: ((Connection, JSONObject) -> Boolean)? = null

    @Volatile
    var sendHello = true

    @Volatile
    var acceptIdentify = true

    init {
        thread(isDaemon = true) {
            try {
                while (true) {
                    val socket = server.accept()
                    connections.incrementAndGet()
                    thread(isDaemon = true) { Connection(socket).run() }
                }
            } catch (e: IOException) {
                return@thread
            }
        }
    }

    override fun close() {
        server.close()
        dropAll()
    }

    fun dropAll() {
        live.forEach { it.drop() }
    }

    fun broadcast(type: String, data: JSONObject) {
        live.forEach { it.event(type, data) }
    }

    fun rawToAll(bytes: ByteArray) {
        live.forEach { it.raw(bytes) }
    }

    inner class Connection(private val socket: Socket) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream())
        private val lock = Any()

        fun drop() {
            try {
                socket.close()
            } catch (e: IOException) {
                return
            }
        }

        fun raw(bytes: ByteArray) {
            try {
                synchronized(lock) {
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (e: IOException) {
                return
            }
        }

        fun text(value: String) = raw(TestFrames.text(value))

        fun event(type: String, data: JSONObject) {
            text(JSONObject().put("op", 5).put("d", JSONObject().put("eventType", type).put("eventData", data)).toString())
        }

        fun respond(request: JSONObject, ok: Boolean, data: JSONObject?, comment: String? = null, frameText: (String) -> Unit = ::text) {
            val status = JSONObject().put("result", ok).put("code", if (ok) 100 else 600)
            if (comment != null) status.put("comment", comment)
            val body = JSONObject()
                .put("requestType", request.getString("requestType"))
                .put("requestId", request.getString("requestId"))
                .put("requestStatus", status)
            if (data != null) body.put("responseData", data)
            frameText(JSONObject().put("op", 7).put("d", body).toString())
        }

        fun run() {
            try {
                val head = WsHandshake.readHead(input, {})
                heads.add(head)
                val key = Regex("Sec-WebSocket-Key: (\\S+)").find(head)!!.groupValues[1]
                val custom = onHandshake
                if (custom != null && custom(socket, key)) return
                raw(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Protocol: obswebsocket.json\r\n" +
                            "Sec-WebSocket-Accept: ${WsHandshake.acceptFor(key)}\r\n\r\n"
                        ).toByteArray(),
                )
                live.add(this)
                if (sendHello) sendHello()
                while (true) {
                    val first = input.read()
                    if (first < 0) return
                    val second = input.read()
                    if (second and 0x80 == 0) unmaskedFrames.incrementAndGet()
                    var length = (second and 0x7F).toLong()
                    if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
                    if (length == 127L) {
                        length = 0
                        repeat(8) { length = (length shl 8) or input.read().toLong() }
                    }
                    val mask = input.readNBytes(4)
                    val body = input.readNBytes(length.toInt())
                    val payload = ByteArray(body.size) { (body[it].toInt() xor mask[it and 3].toInt()).toByte() }
                    when (first and 0x0F) {
                        WsOpcode.TEXT -> onMessage(JSONObject(String(payload, Charsets.UTF_8)))
                        WsOpcode.PONG -> pongs.add(String(payload))
                        WsOpcode.CLOSE -> {
                            closes.add(((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF))
                            return
                        }
                    }
                }
            } catch (e: IOException) {
                return
            } finally {
                live.remove(this)
                drop()
            }
        }

        private fun sendHello() {
            val data = JSONObject().put("obsWebSocketVersion", "5.5.0").put("rpcVersion", 1)
            if (password != null) data.put("authentication", JSONObject().put("challenge", challenge).put("salt", salt))
            text(JSONObject().put("op", 0).put("d", data).toString())
        }

        private fun onMessage(message: JSONObject) {
            val data = message.getJSONObject("d")
            when (message.getInt("op")) {
                1 -> onIdentify(data)
                6 -> onRequest(data)
            }
        }

        private fun onIdentify(data: JSONObject) {
            identifies.add(data)
            if (!acceptIdentify) return
            if (password != null && data.optString("authentication") != ObsAuth.response(password, salt, challenge)) {
                raw(TestFrames.server(WsOpcode.CLOSE, TestFrames.closePayload(4009, "Authentication failed.")))
                return
            }
            text("""{"op":2,"d":{"negotiatedRpcVersion":1}}""")
            afterIdentified?.invoke(this)
        }

        private fun onRequest(data: JSONObject) {
            requests.add(data)
            if (!answering) return
            val override = responseOverride
            if (override != null && override(this, data)) return
            val failing = failNext
            val type = data.getString("requestType")
            if (failing != null && type in COMMANDS) {
                failNext = null
                respond(data, false, null, failing)
                return
            }
            val parameters = data.optJSONObject("requestData") ?: JSONObject()
            when (type) {
                ObsRequest.GET_SCENE_LIST -> respond(
                    data,
                    true,
                    JSONObject()
                        .put("currentProgramSceneName", currentScene)
                        .put(
                            "scenes",
                            JSONArray(scenes.map { (name, index) -> JSONObject().put("sceneName", name).put("sceneIndex", index) }),
                        ),
                )
                ObsRequest.SET_PROGRAM_SCENE -> {
                    currentScene = parameters.getString("sceneName")
                    respond(data, true, null)
                    event(ObsEventName.PROGRAM_SCENE_CHANGED, JSONObject().put("sceneName", currentScene))
                }
                ObsRequest.GET_INPUT_LIST -> respond(
                    data,
                    true,
                    JSONObject().put(
                        "inputs",
                        JSONArray((audio.keys + videoInputs).map { JSONObject().put("inputName", it) }),
                    ),
                )
                ObsRequest.GET_INPUT_MUTE -> {
                    val muted = audio[parameters.getString("inputName")]
                    if (muted == null) {
                        respond(data, false, null, "no audio")
                    } else {
                        respond(data, true, JSONObject().put("inputMuted", muted))
                    }
                }
                ObsRequest.SET_INPUT_MUTE -> {
                    val name = parameters.getString("inputName")
                    val muted = parameters.getBoolean("inputMuted")
                    audio = LinkedHashMap(audio).apply { put(name, muted) }
                    respond(data, true, null)
                    event(ObsEventName.INPUT_MUTE_CHANGED, JSONObject().put("inputName", name).put("inputMuted", muted))
                }
                ObsRequest.GET_STATS -> respond(
                    data,
                    true,
                    JSONObject()
                        .put("cpuUsage", 12.5)
                        .put("activeFps", 60.0)
                        .put("renderSkippedFrames", 1)
                        .put("renderTotalFrames", 100)
                        .put("outputSkippedFrames", 2)
                        .put("outputTotalFrames", 200),
                )
                ObsRequest.GET_STREAM_STATUS -> respond(
                    data,
                    true,
                    JSONObject()
                        .put("outputActive", streaming)
                        .put("outputReconnecting", false)
                        .put("outputDuration", if (streaming) 65_000 else 0)
                        .put("outputSkippedFrames", 3)
                        .put("outputTotalFrames", 300),
                )
                ObsRequest.GET_RECORD_STATUS -> respond(
                    data,
                    true,
                    JSONObject()
                        .put("outputActive", recording)
                        .put("outputPaused", false)
                        .put("outputDuration", if (recording) 5_000 else 0),
                )
                ObsRequest.START_STREAM -> {
                    streaming = true
                    respond(data, true, null)
                    output(ObsEventName.STREAM_STATE_CHANGED, true, "OBS_WEBSOCKET_OUTPUT_STARTED")
                }
                ObsRequest.STOP_STREAM -> {
                    streaming = false
                    respond(data, true, null)
                    output(ObsEventName.STREAM_STATE_CHANGED, false, "OBS_WEBSOCKET_OUTPUT_STOPPED")
                }
                ObsRequest.START_RECORD -> {
                    recording = true
                    respond(data, true, null)
                    output(ObsEventName.RECORD_STATE_CHANGED, true, "OBS_WEBSOCKET_OUTPUT_STARTED")
                }
                ObsRequest.STOP_RECORD -> {
                    recording = false
                    respond(data, true, null)
                    output(ObsEventName.RECORD_STATE_CHANGED, false, "OBS_WEBSOCKET_OUTPUT_STOPPED")
                }
                else -> respond(data, false, null, "unknown")
            }
        }

        private fun output(type: String, active: Boolean, state: String) {
            event(type, JSONObject().put("outputActive", active).put("outputState", state))
        }
    }

    private companion object {
        val COMMANDS = setOf(
            ObsRequest.SET_PROGRAM_SCENE,
            ObsRequest.SET_INPUT_MUTE,
            ObsRequest.START_STREAM,
            ObsRequest.STOP_STREAM,
            ObsRequest.START_RECORD,
            ObsRequest.STOP_RECORD,
        )
    }
}

class ObsClientTest {
    private var timings = ObsTimings(
        connectDeadlineMs = 1500,
        readTimeoutMs = 1500,
        frameDeadlineMs = 1000,
        requestTimeoutMs = 800,
        writeStallMs = 1000,
        pollMs = 100,
        tickMs = 20,
        refreshDelayMs = 20,
        stableAfterMs = 60_000,
        backoffBaseMs = 40,
        backoffCapMs = 160,
        gracefulCloseMs = 50,
        transitionGraceMs = 2000,
    )
    private val latest = AtomicReference(RemoteState())
    private val phases = CopyOnWriteArrayList<Phase>()
    private val authenticated = CopyOnWriteArrayList<ObsTarget>()
    private val closeables = ArrayList<Closeable>()
    private var clientOrNull: ObsClient? = null

    private fun client(lookup: (String) -> List<InetAddress> = { throw AssertionError("unexpected lookup") }): ObsClient {
        val created = ObsClient(timings, lookup, onAuthenticated = { authenticated.add(it) }) { state ->
            latest.set(state)
            if (phases.lastOrNull() != state.phase) phases.add(state.phase)
        }
        clientOrNull = created
        return created
    }

    private fun server(password: String? = "secret", onHandshake: ((Socket, String) -> Boolean)? = null) =
        FakeObsServer(password, onHandshake).also { closeables.add(it) }

    private fun target(port: Int, password: String = "secret", host: String = "127.0.0.1") =
        ObsTarget((HostParser.parse(host) as HostParse.Ok).spec, port, password)

    @After
    fun tearDown() {
        clientOrNull?.shutdown()
        closeables.forEach { it.close() }
    }

    private fun await(what: String, timeoutMs: Long = 5000, test: (RemoteState) -> Boolean): RemoteState {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            val state = latest.get()
            if (test(state)) return state
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for $what, state: ${latest.get()}")
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 5000, test: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (test()) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for $what, state: ${latest.get()}")
    }

    private fun ready(state: RemoteState) =
        state.isConnected && state.scenes.isNotEmpty() && state.inputs.isNotEmpty() &&
            state.stats != null && state.stream.phase != OutputPhase.UNKNOWN && state.record.phase != OutputPhase.UNKNOWN

    private fun connected(fake: FakeObsServer, password: String = "secret"): ObsClient {
        val obs = client()
        obs.connect(target(fake.port, password))
        await("ready", test = ::ready)
        return obs
    }

    private fun assertFailed(problem: Problem) {
        val state = await("failure $problem") { it.phase == Phase.FAILED }
        assertEquals(problem, state.problem)
        assertFalse(state.established)
        assertTrue(state.scenes.isEmpty())
    }

    @Test
    fun handshakeHelloIdentifyAuthAndLists() {
        val fake = server()
        connected(fake)
        val identify = fake.identifies.single()
        assertEquals(ObsAuth.response("secret", "c2FsdA==", "Y2hhbGxlbmdl"), identify.getString("authentication"))
        assertEquals(1, identify.getInt("rpcVersion"))
        assertEquals(ObsMessages.EVENT_SUBSCRIPTIONS, identify.getInt("eventSubscriptions"))
        val state = latest.get()
        assertEquals(listOf("Intro", "Game", "End"), state.scenes.map { it.id })
        assertEquals("Game", state.currentScene)
        assertEquals(listOf("Mic", "Desktop"), state.inputs.map { it.id })
        assertEquals(listOf(false, true), state.inputs.map { it.muted })
        assertEquals(OutputPhase.STOPPED, state.stream.phase)
        assertEquals(12.5, state.stats!!.cpuPercent!!, 0.0)
        assertEquals(60.0, state.stats!!.fps!!, 0.0)
        assertEquals(0, fake.unmaskedFrames.get())
        assertTrue(phases.containsAll(listOf(Phase.CONNECTING, Phase.CONNECTED)))
    }

    @Test
    fun noPasswordServerGetsNoAuthField() {
        val fake = server(password = null)
        connected(fake, password = "unused")
        assertFalse(fake.identifies.single().has("authentication"))
    }

    @Test
    fun requestIdsAreUnique() {
        val fake = server()
        connected(fake)
        awaitCondition("many requests") { fake.requests.size > 30 }
        val ids = fake.requests.map { it.getString("requestId") }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun wrongPasswordCloses4009WithoutRetry() {
        val fake = server()
        val obs = client()
        obs.connect(target(fake.port, password = "wrong"))
        assertFailed(Problem.WRONG_PASSWORD)
        Thread.sleep(300)
        assertEquals(1, fake.connections.get())
        assertEquals(Phase.FAILED, latest.get().phase)
    }

    @Test
    fun passwordRequiredWithoutPasswordSendsNothing() {
        val fake = server()
        val obs = client()
        obs.connect(target(fake.port, password = ""))
        assertFailed(Problem.PASSWORD_REQUIRED)
        assertTrue(fake.identifies.isEmpty())
    }

    @Test
    fun closeCodesMapToProblems() {
        val cases = mapOf(
            4010 to Problem.UNSUPPORTED_VERSION,
            4011 to Problem.SESSION_ENDED,
            4003 to Problem.PROTOCOL,
            4012 to Problem.PROTOCOL,
        )
        cases.forEach { (code, problem) ->
            val fake = server()
            fake.acceptIdentify = false
            val obs = client()
            obs.connect(target(fake.port))
            awaitCondition("identify") { fake.identifies.isNotEmpty() }
            fake.rawToAll(TestFrames.server(WsOpcode.CLOSE, TestFrames.closePayload(code)))
            assertFailed(problem)
            obs.shutdown()
        }
    }

    @Test
    fun switchingSendsRawNameAndFollowsEvent() {
        val hostile = "Se‮cret\r\n\"x\""
        val fake = server()
        fake.scenes = listOf(hostile to 1, "Game" to 0)
        val obs = connected(fake)
        assertEquals("Secret\"x\"", latest.get().scenes[0].label)
        obs.switchScene(hostile)
        await("switched") { it.currentScene == hostile && it.switchingTo == null }
        val sent = fake.requests.first { it.getString("requestType") == ObsRequest.SET_PROGRAM_SCENE }
        assertEquals(hostile, sent.getJSONObject("requestData").getString("sceneName"))
    }

    @Test
    fun switchToUnknownOrCurrentSceneSendsNothing() {
        val fake = server()
        val obs = connected(fake)
        obs.switchScene("Not there")
        obs.switchScene("Game")
        Thread.sleep(150)
        assertTrue(fake.requests.none { it.getString("requestType") == ObsRequest.SET_PROGRAM_SCENE })
    }

    @Test
    fun switchFailureShowsCleanedComment() {
        val fake = server()
        val obs = connected(fake)
        fake.failNext = "bad‮\r\nthing"
        obs.switchScene("End")
        val state = await("notice") { it.notice != null }
        assertEquals(Action.SWITCH_SCENE, state.notice!!.action)
        assertEquals("badthing", state.notice!!.detail)
        assertNull(state.switchingTo)
        assertEquals("Game", state.currentScene)
    }

    @Test
    fun programSceneEventFromOutside() {
        val fake = server()
        connected(fake)
        fake.broadcast(ObsEventName.PROGRAM_SCENE_CHANGED, JSONObject().put("sceneName", "End"))
        await("scene event") { it.currentScene == "End" }
    }

    @Test
    fun unknownSceneInEventTriggersRefresh() {
        val fake = server()
        connected(fake)
        fake.scenes = listOf("New" to 0)
        fake.currentScene = "New"
        fake.broadcast(ObsEventName.PROGRAM_SCENE_CHANGED, JSONObject().put("sceneName", "New"))
        await("refresh") { state -> state.scenes.map { it.id } == listOf("New") }
    }

    @Test
    fun sceneListChangeEventRefreshes() {
        val fake = server()
        connected(fake)
        fake.scenes = listOf("A" to 1, "B" to 0)
        fake.broadcast(ObsEventName.SCENE_LIST_CHANGED, JSONObject())
        await("refresh") { state -> state.scenes.map { it.id } == listOf("A", "B") }
    }

    @Test
    fun eventFloodIsCoalesced() {
        val fake = server()
        connected(fake)
        val before = fake.requests.count { it.getString("requestType") == ObsRequest.GET_SCENE_LIST }
        repeat(200) { fake.broadcast(ObsEventName.SCENE_CREATED, JSONObject()) }
        Thread.sleep(120)
        val after = fake.requests.count { it.getString("requestType") == ObsRequest.GET_SCENE_LIST }
        assertTrue("refreshes: ${after - before}", after - before < 20)
    }

    @Test
    fun streamStartStopWithEvents() {
        val fake = server()
        val obs = connected(fake)
        obs.startStream()
        val live = await("stream on") { it.stream.phase == OutputPhase.ACTIVE && it.stream.durationMs != null }
        assertEquals(65_000L, live.stream.durationMs)
        assertEquals(3L, live.stream.skippedFrames)
        obs.startStream()
        Thread.sleep(50)
        assertEquals(1, fake.requests.count { it.getString("requestType") == ObsRequest.START_STREAM })
        obs.stopStream()
        await("stream off") { it.stream.phase == OutputPhase.STOPPED }
        assertEquals(1, fake.requests.count { it.getString("requestType") == ObsRequest.STOP_STREAM })
    }

    @Test
    fun stopStreamIgnoredWhenNotLive() {
        val fake = server()
        val obs = connected(fake)
        obs.stopStream()
        obs.stopRecord()
        Thread.sleep(100)
        assertTrue(fake.requests.none { it.getString("requestType") == ObsRequest.STOP_STREAM })
        assertTrue(fake.requests.none { it.getString("requestType") == ObsRequest.STOP_RECORD })
    }

    @Test
    fun recordStartStop() {
        val fake = server()
        val obs = connected(fake)
        obs.startRecord()
        await("record on") { it.record.phase == OutputPhase.ACTIVE }
        await("record duration") { it.record.durationMs == 5_000L }
        obs.stopRecord()
        await("record off") { it.record.phase == OutputPhase.STOPPED }
    }

    @Test
    fun streamFailureRestoresStateAndShowsNotice() {
        val fake = server()
        val obs = connected(fake)
        fake.failNext = "no service"
        obs.startStream()
        val state = await("notice") { it.notice?.action == Action.START_STREAM }
        assertEquals("no service", state.notice!!.detail)
        assertEquals(OutputPhase.STOPPED, state.stream.phase)
    }

    @Test
    fun streamStateEventFromOutside() {
        val fake = server()
        connected(fake)
        fake.streaming = true
        fake.broadcast(
            ObsEventName.STREAM_STATE_CHANGED,
            JSONObject().put("outputActive", true).put("outputState", "OBS_WEBSOCKET_OUTPUT_STARTED"),
        )
        await("event") { it.stream.phase == OutputPhase.ACTIVE }
    }

    @Test
    fun muteToggleAndEvents() {
        val fake = server()
        val obs = connected(fake)
        obs.setMuted("Mic", true)
        await("muted") { state -> state.inputs.first { it.id == "Mic" }.muted }
        assertEquals(true, fake.audio["Mic"])
        fake.broadcast(ObsEventName.INPUT_MUTE_CHANGED, JSONObject().put("inputName", "Desktop").put("inputMuted", false))
        await("event") { state -> !state.inputs.first { it.id == "Desktop" }.muted }
    }

    @Test
    fun muteEventForUnknownInputAndBadTypesIgnored() {
        val fake = server()
        connected(fake)
        val before = latest.get().inputs
        fake.broadcast(ObsEventName.INPUT_MUTE_CHANGED, JSONObject().put("inputName", "Ghost").put("inputMuted", true))
        fake.broadcast(ObsEventName.INPUT_MUTE_CHANGED, JSONObject().put("inputName", "Mic").put("inputMuted", "yes"))
        fake.broadcast(ObsEventName.INPUT_MUTE_CHANGED, JSONObject().put("inputName", 5).put("inputMuted", true))
        Thread.sleep(100)
        assertEquals(before, latest.get().inputs)
    }

    @Test
    fun muteUnknownInputSendsNothing() {
        val fake = server()
        val obs = connected(fake)
        obs.setMuted("Ghost", true)
        Thread.sleep(100)
        assertTrue(fake.requests.none { it.getString("requestType") == ObsRequest.SET_INPUT_MUTE })
    }

    @Test
    fun nonAudioInputsAreExcluded() {
        val fake = server()
        connected(fake)
        assertFalse(latest.get().inputs.any { it.id == "Webcam" })
    }

    @Test
    fun hostileNamesAreCappedAndCounted() {
        val fake = server()
        fake.scenes = List(ObsMessages.MAX_SCENES + 40) { ("‮" + "n".repeat(200) + it) to it }
        fake.currentScene = fake.scenes[0].first
        fake.audio = LinkedHashMap((0 until 100).associate { "in\r\n$it" to false })
        val obs = client()
        obs.connect(target(fake.port))
        val state = await("lists", test = ::ready)
        assertEquals(ObsMessages.MAX_SCENES, state.scenes.size)
        assertTrue(state.scenesTruncated)
        state.scenes.forEach {
            assertTrue(it.label.length <= ObsMessages.MAX_LABEL + 1)
            assertFalse(it.label.contains('‮'))
        }
        assertEquals(ObsMessages.MAX_AUDIO_INPUTS, state.inputs.size)
        assertTrue(state.inputsTruncated)
        state.inputs.forEach { assertFalse(it.label.contains('\r')) }
    }

    @Test
    fun fragmentedHelloAndResponses() {
        val fake = server()
        fake.sendHello = false
        fake.responseOverride = { connection, request ->
            if (request.getString("requestType") == ObsRequest.GET_SCENE_LIST) {
                connection.respond(
                    request,
                    true,
                    JSONObject()
                        .put("currentProgramSceneName", "Frag")
                        .put("scenes", JSONArray().put(JSONObject().put("sceneName", "Frag é").put("sceneIndex", 0))),
                    frameText = { text ->
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        val parts = bytes.toList().chunked(7).map { it.toByteArray() }
                        parts.forEachIndexed { index, part ->
                            val opcode = if (index == 0) WsOpcode.TEXT else WsOpcode.CONTINUATION
                            connection.raw(TestFrames.server(opcode, part, fin = index == parts.lastIndex))
                            if (index == 2) connection.raw(TestFrames.server(WsOpcode.PING, "mid".toByteArray()))
                        }
                    },
                )
                true
            } else {
                false
            }
        }
        val obs = client()
        obs.connect(target(fake.port))
        awaitCondition("open") { fake.live.isNotEmpty() }
        val hello = """{"op":0,"d":{"rpcVersion":1,"authentication":{"challenge":"Y2hhbGxlbmdl","salt":"c2FsdA=="}}}"""
        fake.rawToAll(TestFrames.text(hello.substring(0, 10), fin = false))
        fake.rawToAll(TestFrames.server(WsOpcode.CONTINUATION, hello.substring(10).toByteArray()))
        val state = await("fragments") { it.isConnected && it.scenes.isNotEmpty() }
        assertEquals("Frag é", state.scenes[0].id)
        awaitCondition("pong") { fake.pongs.contains("mid") }
    }

    @Test
    fun pingIsAnsweredWithMaskedPong() {
        val fake = server()
        connected(fake)
        fake.rawToAll(TestFrames.server(WsOpcode.PING, "hello".toByteArray()))
        awaitCondition("pong") { fake.pongs.contains("hello") }
        assertEquals(0, fake.unmaskedFrames.get())
    }

    @Test
    fun oversizeFrameBeforeIdentifiedFails() {
        val fake = server()
        fake.sendHello = false
        val obs = client()
        obs.connect(target(fake.port))
        awaitCondition("open") { fake.live.isNotEmpty() }
        fake.rawToAll(byteArrayOf(0x81.toByte(), 127, 0, 0, 0, 0, 0x7F, 0, 0, 0))
        assertFailed(Problem.OVERSIZE)
        awaitCondition("close 1009") { fake.closes.contains(WsClose.TOO_BIG) }
    }

    @Test
    fun oversizeFragmentedMessageFails() {
        val fake = server()
        fake.sendHello = false
        val obs = client()
        obs.connect(target(fake.port))
        awaitCondition("open") { fake.live.isNotEmpty() }
        val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
        fake.rawToAll(TestFrames.server(WsOpcode.TEXT, chunk, fin = false))
        repeat(4) { fake.rawToAll(TestFrames.server(WsOpcode.CONTINUATION, chunk, fin = false)) }
        assertFailed(Problem.OVERSIZE)
    }

    @Test
    fun oversizeAfterConnectedReconnects() {
        val fake = server()
        connected(fake)
        fake.rawToAll(byteArrayOf(0x81.toByte(), 127, 0x7F, -1, -1, -1, -1, -1, -1, -1))
        await("retry") { it.phase == Phase.RECONNECTING }
        await("back", test = ::ready)
        assertEquals(2, fake.connections.get())
    }

    @Test
    fun garbageBytesFail() {
        listOf(
            ByteArray(64) { 0xFF.toByte() },
            byteArrayOf(0xC1.toByte(), 0),
            byteArrayOf(0x83.toByte(), 0),
            byteArrayOf(0x81.toByte(), 0x81.toByte(), 1, 2, 3, 4, 5),
            TestFrames.server(WsOpcode.BINARY, ByteArray(4)),
            TestFrames.server(WsOpcode.CONTINUATION, ByteArray(4)),
            TestFrames.server(WsOpcode.CLOSE, ByteArray(1)),
            TestFrames.server(WsOpcode.TEXT, byteArrayOf(0xC0.toByte(), 0x80.toByte())),
        ).forEach { garbage ->
            val fake = server()
            fake.sendHello = false
            val obs = client()
            latest.set(RemoteState())
            obs.connect(target(fake.port))
            awaitCondition("open") { fake.live.isNotEmpty() }
            fake.rawToAll(garbage)
            assertFailed(Problem.PROTOCOL)
            obs.shutdown()
        }
    }

    @Test
    fun invalidJsonFails() {
        listOf("not json", "[]", """{"op":0}""", "[".repeat(100_000), """{"op":0,"d":{"rpcVersion":"x"}}""").forEach { text ->
            val fake = server()
            fake.sendHello = false
            val obs = client()
            latest.set(RemoteState())
            obs.connect(target(fake.port))
            awaitCondition("open") { fake.live.isNotEmpty() }
            fake.rawToAll(TestFrames.text(text))
            assertFailed(Problem.PROTOCOL)
            obs.shutdown()
        }
    }

    @Test
    fun secondHelloIsAViolation() {
        val fake = server()
        fake.acceptIdentify = false
        val obs = client()
        obs.connect(target(fake.port))
        awaitCondition("identify") { fake.identifies.isNotEmpty() }
        fake.rawToAll(TestFrames.text("""{"op":0,"d":{"rpcVersion":1}}"""))
        assertFailed(Problem.PROTOCOL)
    }

    @Test
    fun identifiedBeforeHelloIsAViolation() {
        val fake = server()
        fake.sendHello = false
        val obs = client()
        obs.connect(target(fake.port))
        awaitCondition("open") { fake.live.isNotEmpty() }
        fake.rawToAll(TestFrames.text("""{"op":2,"d":{"negotiatedRpcVersion":1}}"""))
        assertFailed(Problem.PROTOCOL)
    }

    @Test
    fun wrongNegotiatedVersionIsAViolation() {
        val fake = server()
        fake.acceptIdentify = false
        val obs = client()
        obs.connect(target(fake.port))
        awaitCondition("identify") { fake.identifies.isNotEmpty() }
        fake.rawToAll(TestFrames.text("""{"op":2,"d":{"negotiatedRpcVersion":9}}"""))
        assertFailed(Problem.PROTOCOL)
    }

    @Test
    fun unmatchedDuplicateAndForeignResponsesIgnored() {
        val fake = server()
        var captured: JSONObject? = null
        fake.responseOverride = { connection, request ->
            if (request.getString("requestType") == ObsRequest.GET_SCENE_LIST && captured == null) {
                captured = request
                connection.respond(request, true, JSONObject().put("scenes", JSONArray().put(JSONObject().put("sceneName", "One"))))
                connection.respond(request, true, JSONObject().put("scenes", JSONArray().put(JSONObject().put("sceneName", "Two"))))
                val stranger = JSONObject().put("requestType", ObsRequest.GET_SCENE_LIST).put("requestId", "nobody")
                connection.respond(stranger, true, JSONObject().put("scenes", JSONArray().put(JSONObject().put("sceneName", "Three"))))
                true
            } else {
                false
            }
        }
        val obs = client()
        obs.connect(target(fake.port))
        val state = await("one scene") { it.isConnected && it.scenes.isNotEmpty() }
        assertEquals(listOf("One"), state.scenes.map { it.id })
        Thread.sleep(100)
        assertEquals(listOf("One"), latest.get().scenes.map { it.id })
        assertTrue(latest.get().isConnected)
    }

    @Test
    fun responseWithWrongTypeDoesNotApply() {
        val fake = server()
        fake.responseOverride = { connection, request ->
            if (request.getString("requestType") == ObsRequest.GET_SCENE_LIST) {
                val forged = JSONObject().put("requestType", "Other").put("requestId", request.getString("requestId"))
                connection.respond(forged, true, JSONObject().put("scenes", JSONArray().put(JSONObject().put("sceneName", "Evil"))))
                true
            } else {
                false
            }
        }
        val obs = client()
        obs.connect(target(fake.port))
        await("connected") { it.isConnected && it.inputs.isNotEmpty() }
        assertTrue(latest.get().scenes.isEmpty())
    }

    @Test
    fun abruptDisconnectReconnectsWithBackoffAndRefreshes() {
        val fake = server()
        connected(fake)
        fake.currentScene = "End"
        fake.dropAll()
        val lost = await("reconnecting") { it.phase == Phase.RECONNECTING && it.retryInSeconds != null }
        assertTrue(lost.established)
        assertEquals(1, lost.attempt)
        assertNull(lost.stats)
        assertEquals(OutputPhase.UNKNOWN, lost.stream.phase)
        assertEquals(3, lost.scenes.size)
        val back = await("reconnected") { ready(it) && it.currentScene == "End" }
        assertNull(back.problem)
        assertEquals(0, back.attempt)
        assertEquals(2, fake.connections.get())
        assertEquals(2, fake.identifies.size)
    }

    @Test
    fun repeatedFailureBacksOffWhileServerIsGone() {
        val fake = server()
        connected(fake)
        fake.close()
        await("attempt 3", timeoutMs = 8000) { it.phase == Phase.RECONNECTING && it.attempt >= 3 }
        assertTrue(latest.get().established)
    }

    @Test
    fun serverNormalCloseReconnects() {
        val fake = server()
        connected(fake)
        fake.rawToAll(TestFrames.server(WsOpcode.CLOSE, TestFrames.closePayload(1001, "going away")))
        await("retry") { it.phase == Phase.RECONNECTING && it.problem == Problem.CONNECTION_LOST }
        await("back", test = ::ready)
    }

    @Test
    fun wrongPasswordOnReconnectStops() {
        val fake = server()
        val obs = client()
        obs.connect(target(fake.port))
        await("ready", test = ::ready)
        fake.acceptIdentify = false
        fake.dropAll()
        await("re-identify") { it.phase == Phase.RECONNECTING }
        awaitCondition("identify") { fake.identifies.size >= 2 }
        fake.rawToAll(TestFrames.server(WsOpcode.CLOSE, TestFrames.closePayload(4009)))
        assertFailed(Problem.WRONG_PASSWORD)
    }

    @Test
    fun unresponsiveServerIsDetectedByRequestTimeout() {
        val fake = server()
        connected(fake)
        fake.answering = false
        await("timeout") { it.phase == Phase.RECONNECTING && it.problem == Problem.TIMEOUT }
    }

    @Test
    fun silentServerHitsDeadlineOnFirstConnect() {
        val fake = server()
        fake.sendHello = false
        val obs = client()
        obs.connect(target(fake.port))
        assertFailed(Problem.TIMEOUT)
    }

    @Test
    fun connectionRefusedFails() {
        val free = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val port = free.localPort
        free.close()
        val obs = client()
        obs.connect(target(port))
        assertFailed(Problem.UNREACHABLE)
    }

    @Test
    fun badAcceptFailsAsNotObs() {
        val fake = server { socket, _ ->
            socket.getOutputStream().write(
                "HTTP/1.1 101 x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: nope\r\n\r\n".toByteArray(),
            )
            true
        }
        val obs = client()
        obs.connect(target(fake.port))
        assertFailed(Problem.NOT_OBS)
    }

    @Test
    fun httpServerFailsAsNotObs() {
        val fake = server { socket, _ ->
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
            true
        }
        val obs = client()
        obs.connect(target(fake.port))
        assertFailed(Problem.NOT_OBS)
    }

    @Test
    fun publicResolutionIsRefusedWithoutConnecting() {
        val fake = server()
        val obs = client { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
        obs.connect(target(fake.port, host = "evil.example"))
        assertFailed(Problem.PUBLIC_ADDRESS)
        assertEquals(0, fake.connections.get())
    }

    @Test
    fun mixedResolutionConnectsOnlyToAllowedAddress() {
        val fake = server()
        val obs = client {
            listOf(
                InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
                InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            )
        }
        obs.connect(target(fake.port, host = "obs.local"))
        await("ready", test = ::ready)
        assertEquals(1, fake.connections.get())
    }

    @Test
    fun handshakeHeadersCarryHostAndSubprotocol() {
        val fake = server()
        val obs = client { listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
        obs.connect(target(fake.port, host = "obs.local"))
        await("ready", test = ::ready)
        val head = fake.heads.single()
        assertTrue(head, head.contains("Host: obs.local:${fake.port}\r\n"))
        assertTrue(head, head.contains("Sec-WebSocket-Protocol: obswebsocket.json\r\n"))
        assertFalse(head, head.contains("secret"))
    }

    @Test
    fun passwordNeverCrossesTheWire() {
        val fake = server()
        connected(fake)
        val all = fake.identifies.joinToString() + fake.requests.joinToString() + fake.heads.joinToString()
        assertFalse(all.contains("secret"))
    }

    @Test
    fun unresolvedNameFails() {
        val obs = client { throw java.net.UnknownHostException("x") }
        obs.connect(target(4455, host = "nothing.local"))
        assertFailed(Problem.UNRESOLVED)
    }

    @Test
    fun hangingLookupHitsDeadline() {
        val obs = client {
            Thread.sleep(10_000)
            emptyList()
        }
        obs.connect(target(4455, host = "slow.local"))
        val state = await("failure") { it.phase == Phase.FAILED }
        assertTrue(state.problem == Problem.TIMEOUT || state.problem == Problem.UNRESOLVED)
    }

    @Test
    fun disconnectSendsCloseAndIgnoresLateCallbacks() {
        val fake = server()
        val obs = connected(fake)
        val connection = fake.live.single()
        obs.disconnect()
        await("idle") { it.phase == Phase.IDLE }
        awaitCondition("close frame") { fake.closes.contains(WsClose.NORMAL) }
        connection.event(ObsEventName.PROGRAM_SCENE_CHANGED, JSONObject().put("sceneName", "End"))
        Thread.sleep(200)
        val state = latest.get()
        assertEquals(Phase.IDLE, state.phase)
        assertNull(state.currentScene)
        assertTrue(state.scenes.isEmpty())
        assertEquals(1, fake.connections.get())
    }

    @Test
    fun disconnectDuringConnectStopsIt() {
        val fake = server()
        fake.sendHello = false
        val obs = client()
        obs.connect(target(fake.port))
        await("connecting") { it.phase != Phase.IDLE }
        obs.disconnect()
        await("idle") { it.phase == Phase.IDLE }
        Thread.sleep(timings.connectDeadlineMs + 200)
        assertEquals(Phase.IDLE, latest.get().phase)
    }

    @Test
    fun disconnectDuringBackoffCancelsRetry() {
        val fake = server()
        connected(fake)
        val count = fake.connections.get()
        fake.dropAll()
        clientOrNull!!.disconnect()
        await("idle") { it.phase == Phase.IDLE }
        Thread.sleep(400)
        assertEquals(Phase.IDLE, latest.get().phase)
        assertTrue(fake.connections.get() <= count + 1)
    }

    @Test
    fun pauseClosesAndResumeReconnects() {
        val fake = server()
        val obs = connected(fake)
        obs.pause()
        val paused = await("paused") { it.phase == Phase.PAUSED }
        assertTrue(paused.showsControls)
        awaitCondition("closed") { fake.live.isEmpty() }
        Thread.sleep(150)
        assertEquals(1, fake.connections.get())
        obs.resume()
        await("back", test = ::ready)
        assertEquals(2, fake.connections.get())
    }

    @Test
    fun pauseAndResumeOnIdleDoNothing() {
        val obs = client()
        obs.pause()
        obs.resume()
        Thread.sleep(100)
        assertEquals(Phase.IDLE, latest.get().phase)
    }

    @Test
    fun resumeAfterFailureDoesNotReconnect() {
        val fake = server()
        val obs = client()
        obs.connect(target(fake.port, password = "wrong"))
        assertFailed(Problem.WRONG_PASSWORD)
        obs.resume()
        Thread.sleep(150)
        assertEquals(1, fake.connections.get())
    }

    @Test
    fun commandsWhileDisconnectedAreIgnored() {
        val obs = client()
        obs.startStream()
        obs.stopStream()
        obs.startRecord()
        obs.stopRecord()
        obs.switchScene("x")
        obs.setMuted("x", true)
        Thread.sleep(50)
        assertEquals(RemoteState(), latest.get())
    }

    @Test
    fun secondConnectReplacesFirst() {
        val first = server()
        val second = server()
        second.scenes = listOf("Second" to 0)
        val obs = client()
        obs.connect(target(first.port))
        await("first") { it.isConnected && it.scenes.isNotEmpty() }
        obs.connect(target(second.port))
        await("second") { state -> state.scenes.map { it.id } == listOf("Second") }
        first.broadcast(ObsEventName.PROGRAM_SCENE_CHANGED, JSONObject().put("sceneName", "Ghost"))
        Thread.sleep(150)
        assertFalse(latest.get().currentScene == "Ghost")
    }

    @Test
    fun floodOfLargeMessagesStaysAlive() {
        val fake = server()
        connected(fake)
        val filler = "x".repeat(200_000)
        val text = """{"op":5,"d":{"eventType":"Unknown","eventData":{"a":"$filler"}}}"""
        repeat(50) { fake.rawToAll(TestFrames.text(text)) }
        fake.broadcast(ObsEventName.PROGRAM_SCENE_CHANGED, JSONObject().put("sceneName", "End"))
        await("after flood") { it.currentScene == "End" }
        assertTrue(latest.get().isConnected)
    }

    @Test
    fun authenticatedFiresOnlyAfterIdentified() {
        val fake = server()
        fake.acceptIdentify = false
        val obs = client()
        val goal = target(fake.port)
        obs.connect(goal)
        awaitCondition("identify") { fake.identifies.isNotEmpty() }
        Thread.sleep(100)
        assertTrue(authenticated.isEmpty())
        fake.rawToAll(TestFrames.text("""{"op":2,"d":{"negotiatedRpcVersion":1}}"""))
        awaitCondition("authenticated") { authenticated.isNotEmpty() }
        assertTrue(authenticated.single() === goal)
    }

    @Test
    fun wrongPasswordNeverReportsAuthenticated() {
        val fake = server()
        val obs = client()
        obs.connect(target(fake.port, password = "wrong"))
        assertFailed(Problem.WRONG_PASSWORD)
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun screenOnEndsAfterLongReconnectAndReturnsWhenBack() {
        timings = ObsTimings(
            connectDeadlineMs = 500,
            readTimeoutMs = 1500,
            requestTimeoutMs = 800,
            pollMs = 100,
            tickMs = 20,
            refreshDelayMs = 20,
            backoffBaseMs = 40,
            backoffCapMs = 80,
            gracefulCloseMs = 50,
            reconnectScreenOnMs = 300,
        )
        val fake = server()
        connected(fake)
        assertTrue(latest.get().keepsScreenOn)
        fake.acceptIdentify = false
        fake.dropAll()
        val stale = await("stale") { it.phase == Phase.RECONNECTING && it.reconnectStale }
        assertFalse(stale.keepsScreenOn)
        fake.acceptIdentify = true
        await("back") { it.isConnected && it.keepsScreenOn }
    }

    @Test
    fun maskingUsedOnAllClientFrames() {
        val fake = server()
        val obs = connected(fake)
        obs.switchScene("End")
        await("switched") { it.currentScene == "End" }
        assertEquals(0, fake.unmaskedFrames.get())
    }
}
