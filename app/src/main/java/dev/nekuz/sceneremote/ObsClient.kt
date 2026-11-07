package dev.nekuz.sceneremote

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ObsTimings(
    val connectDeadlineMs: Long = 12_000,
    val readTimeoutMs: Int = 15_000,
    val frameDeadlineMs: Long = 10_000,
    val requestTimeoutMs: Long = 10_000,
    val writeStallMs: Long = 10_000,
    val pollMs: Long = 2_000,
    val tickMs: Long = 500,
    val refreshDelayMs: Long = 300,
    val stableAfterMs: Long = 10_000,
    val backoffBaseMs: Long = 1_000,
    val backoffCapMs: Long = 30_000,
    val gracefulCloseMs: Long = 300,
    val transitionGraceMs: Long = 20_000,
    val reconnectScreenOnMs: Long = 300_000,
)

class ObsTarget(val host: HostSpec, val port: Int, val password: String) {
    val endpoint: String get() = "${host.headerHost}:$port"

    override fun toString() = "ObsTarget(${host.display}:$port)"
}

class ObsClient(
    private val timings: ObsTimings = ObsTimings(),
    private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val random: SecureRandom = SecureRandom(),
    private val onAuthenticated: (ObsTarget) -> Unit = {},
    private val sink: (RemoteState) -> Unit,
) {
    private class Pending(val type: String, val sentMs: Long, val onResult: (ObsInbound.Response?) -> Unit)

    private sealed class Outbound {
        class Text(val text: String) : Outbound()
        class Pong(val payload: ByteArray) : Outbound()
        class Close(val code: Int) : Outbound()
    }

    private class Link(val generation: Long) {
        val queue = LinkedBlockingQueue<Outbound>(MAX_OUTBOUND)
        val inflight = Semaphore(MAX_INFLIGHT)

        @Volatile
        var writeStartedMs = 0L

        @Volatile
        var writer: Thread? = null
            private set

        @Volatile
        var reader: Thread? = null

        @Volatile
        private var client: WsClient? = null

        @Volatile
        private var aborted = false

        fun attach(next: WsClient): Boolean {
            client = next
            if (aborted) {
                next.abort()
                return false
            }
            return true
        }

        fun adopt(thread: Thread) {
            writer = thread
            thread.start()
            if (aborted) thread.interrupt()
        }

        fun abort() {
            aborted = true
            client?.abort()
            writer?.interrupt()
            reader?.interrupt()
        }
    }

    private val owner = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "obs-owner").apply { isDaemon = true }
    }

    private var generation = 0L
    private var link: Link? = null
    private var target: ObsTarget? = null
    private var state = RemoteState()
    private var published: RemoteState? = null
    private var attempt = 0
    private var requestSeq = 0L
    private val pending = LinkedHashMap<String, Pending>()
    private var helloSeen = false
    private var identifySent = false
    private var connectedAtMs = 0L
    private var lastPollMs = 0L
    private var pollInFlight = 0
    private var retryAtMs = 0L
    private var lostAtMs = 0L
    private var scenesRefreshQueued = false
    private var inputsRefreshQueued = false
    private var inputsToken = 0L
    private var latestSwitch = 0L
    private var tick: ScheduledFuture<*>? = null
    private var deadline: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null

    fun connect(next: ObsTarget) = post { begin(next) }

    fun disconnect() = post {
        target = null
        endSession(graceful = true)
        state = RemoteState()
        publish()
    }

    fun pause() = post {
        val live = target != null && state.phase != Phase.IDLE && state.phase != Phase.FAILED && state.phase != Phase.PAUSED
        if (live) {
            endSession(graceful = true)
            state = staleState().copy(phase = Phase.PAUSED, retryInSeconds = null)
            publish()
        }
    }

    fun resume() = post {
        if (state.phase == Phase.PAUSED && target != null) {
            attempt = 0
            lostAtMs = 0
            state = state.copy(reconnectStale = false)
            startAttempt()
        }
    }

    fun switchScene(id: String) = post {
        if (state.isConnected && id != state.currentScene && state.scenes.any { it.id == id }) {
            val token = ++latestSwitch
            state = state.copy(switchingTo = id, notice = null)
            val sent = request(ObsRequest.SET_PROGRAM_SCENE, ObsMessages.sceneParameters(id)) { response ->
                if (token == latestSwitch) state = state.copy(switchingTo = null)
                if (response?.ok == true) {
                    queueSceneRefresh()
                } else {
                    noticeFor(Action.SWITCH_SCENE, response)
                }
            }
            if (!sent) state = state.copy(switchingTo = null)
            publish()
        }
    }

    fun setMuted(id: String, muted: Boolean) = post {
        if (state.isConnected && state.inputs.any { it.id == id }) {
            state = state.copy(notice = null)
            request(ObsRequest.SET_INPUT_MUTE, ObsMessages.muteParameters(id, muted)) { response ->
                if (response?.ok == true) applyMute(id, muted) else noticeFor(Action.MUTE, response)
            }
            publish()
        }
    }

    fun startStream() = post { streamCommand(start = true) }

    fun stopStream() = post { streamCommand(start = false) }

    fun startRecord() = post { recordCommand(start = true) }

    fun stopRecord() = post { recordCommand(start = false) }

    fun shutdown() {
        post {
            target = null
            endSession(graceful = false)
        }
        owner.shutdown()
    }

    private fun post(task: () -> Unit) {
        try {
            owner.execute { guarded(task) }
        } catch (e: RejectedExecutionException) {
            return
        }
    }

    private fun postFrom(from: Link, task: () -> Unit) = post {
        if (from.generation == generation) task()
    }

    private fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (e: RuntimeException) {
            fail(Problem.PROTOCOL, retryable = true)
        } catch (e: StackOverflowError) {
            fail(Problem.PROTOCOL, retryable = true)
        } catch (e: OutOfMemoryError) {
            fail(Problem.OVERSIZE, retryable = true)
        }
    }

    private fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*>? = try {
        owner.schedule({ guarded(task) }, delayMs, TimeUnit.MILLISECONDS)
    } catch (e: RejectedExecutionException) {
        null
    }

    private fun publish() {
        if (state == published) return
        published = state
        sink(state)
    }

    private fun nowMs() = System.nanoTime() / NANOS_PER_MS

    private fun begin(next: ObsTarget) {
        endSession(graceful = true)
        target = next
        attempt = 0
        lostAtMs = 0
        state = RemoteState(hostLabel = next.host.display)
        startAttempt()
    }

    private fun startAttempt() {
        val current = target ?: return
        endSession(graceful = false)
        generation++
        val opened = Link(generation)
        link = opened
        state = state.copy(
            phase = if (state.established) Phase.RECONNECTING else Phase.CONNECTING,
            problem = null,
            attempt = attempt,
            retryInSeconds = null,
            hostLabel = current.host.display,
        )
        publish()
        startTick()
        deadline = schedule(timings.connectDeadlineMs) {
            if (opened.generation == generation && !state.isConnected) fail(Problem.TIMEOUT, retryable = true)
        }
        val thread = Thread({ runLink(opened, current) }, "obs-link")
        thread.isDaemon = true
        opened.reader = thread
        thread.start()
    }

    private fun endSession(graceful: Boolean) {
        generation++
        tick?.cancel(false)
        deadline?.cancel(false)
        retry?.cancel(false)
        tick = null
        deadline = null
        retry = null
        pending.clear()
        pollInFlight = 0
        helloSeen = false
        identifySent = false
        scenesRefreshQueued = false
        inputsRefreshQueued = false
        inputsToken++
        retryAtMs = 0
        val closing = link ?: return
        link = null
        if (graceful && closing.writer != null && closing.queue.offer(Outbound.Close(WsClose.NORMAL))) {
            schedule(timings.gracefulCloseMs) { closing.abort() }
        } else {
            closing.abort()
        }
    }

    private fun startTick() {
        if (tick != null) return
        tick = try {
            owner.scheduleWithFixedDelay({ guarded(::onTick) }, timings.tickMs, timings.tickMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            null
        }
    }

    private fun onTick() {
        val now = nowMs()
        val current = link
        if (lostAtMs > 0 && !state.reconnectStale && now - lostAtMs >= timings.reconnectScreenOnMs) {
            state = state.copy(reconnectStale = true)
            publish()
        }
        if (retryAtMs > 0) {
            val left = ((retryAtMs - now + 999) / 1000).toInt().coerceAtLeast(0)
            state = state.copy(retryInSeconds = left)
            publish()
        }
        if (current == null || !state.isConnected) return
        val writing = current.writeStartedMs
        if (writing != 0L && now - writing > timings.writeStallMs) {
            fail(Problem.TIMEOUT, retryable = true)
            return
        }
        if (pending.values.any { now - it.sentMs > timings.requestTimeoutMs }) {
            fail(Problem.TIMEOUT, retryable = true)
            return
        }
        if (now - lastPollMs >= timings.pollMs) poll()
    }

    private fun runLink(opened: Link, current: ObsTarget) {
        try {
            val started = System.nanoTime()
            val resolution = boundedResolve(current.host)
            val addresses = when (resolution) {
                is Resolution.Allowed -> resolution.addresses
                Resolution.Refused -> {
                    postFrom(opened) { fail(Problem.PUBLIC_ADDRESS, retryable = false) }
                    return
                }
                Resolution.Unresolved -> {
                    postFrom(opened) { fail(Problem.UNRESOLVED, retryable = true) }
                    return
                }
            }
            val socket = dial(opened, current, addresses, started) ?: return
            startWriter(opened, socket)
            postFrom(opened) { onOpen() }
            readLoop(opened, socket)
        } catch (e: InterruptedException) {
            return
        } catch (e: IOException) {
            postFrom(opened) { onIoFailure(opened, e) }
        } catch (e: Throwable) {
            postFrom(opened) { fail(Problem.PROTOCOL, retryable = true) }
        }
    }

    private fun boundedResolve(host: HostSpec): Resolution {
        val task = FutureTask { HostResolver.resolve(host, lookup) }
        val thread = Thread(task, "obs-resolve")
        thread.isDaemon = true
        thread.start()
        return try {
            task.get(timings.connectDeadlineMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            Resolution.Unresolved
        } catch (e: ExecutionException) {
            Resolution.Unresolved
        }
    }

    private fun dial(opened: Link, current: ObsTarget, addresses: List<InetAddress>, started: Long): WsClient? {
        var last: IOException? = null
        val config = WsConfig(timings.readTimeoutMs, timings.frameDeadlineMs)
        val header = "${current.host.headerHost}:${current.port}"
        for (address in addresses) {
            val socket = WsClient(config, random)
            if (!opened.attach(socket)) return null
            val spent = (System.nanoTime() - started) / NANOS_PER_MS
            val left = timings.connectDeadlineMs - spent
            if (left <= 0) throw WsException(WsFault.TIMEOUT, "Connection deadline passed")
            try {
                socket.connect(address, current.port, header, "/", ObsMessages.SUBPROTOCOL, left)
                return socket
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: UnknownHostException("No address")
    }

    private fun startWriter(opened: Link, socket: WsClient) {
        val thread = Thread({
            try {
                while (true) {
                    val item = opened.queue.take()
                    opened.writeStartedMs = nowMs()
                    when (item) {
                        is Outbound.Text -> socket.sendText(item.text)
                        is Outbound.Pong -> socket.sendPong(item.payload)
                        is Outbound.Close -> {
                            socket.sendClose(item.code)
                            socket.abort()
                            return@Thread
                        }
                    }
                    opened.writeStartedMs = 0
                }
            } catch (e: InterruptedException) {
                return@Thread
            } catch (e: IOException) {
                postFrom(opened) { onIoFailure(opened, e) }
            } catch (e: Throwable) {
                postFrom(opened) { fail(Problem.PROTOCOL, retryable = true) }
            }
        }, "obs-writer")
        thread.isDaemon = true
        opened.adopt(thread)
    }

    private fun readLoop(opened: Link, socket: WsClient) {
        try {
            while (true) {
                val message = socket.receive { payload ->
                    if (!opened.queue.offer(Outbound.Pong(payload))) {
                        throw WsException(WsFault.PROTOCOL, "Too many pings")
                    }
                }
                when (message) {
                    is WsInbound.Text -> {
                        opened.inflight.acquire()
                        post {
                            try {
                                if (opened.generation == generation) onText(message.text)
                            } finally {
                                opened.inflight.release()
                            }
                        }
                    }
                    is WsInbound.Closed -> {
                        opened.queue.offer(Outbound.Close(message.code?.takeIf { it < 4000 } ?: WsClose.NORMAL))
                        postFrom(opened) { onServerClose(message.code) }
                        return
                    }
                }
            }
        } catch (e: WsException) {
            if (e.fault != WsFault.TIMEOUT && e.fault != WsFault.CLOSED) {
                opened.queue.offer(Outbound.Close(WsClose.forFault(e.fault)))
            }
            throw e
        }
    }

    private fun onOpen() {
        state = state.copy(phase = if (state.established) Phase.RECONNECTING else Phase.AUTHENTICATING)
        publish()
    }

    private fun onIoFailure(from: Link, error: IOException) {
        if (from.generation != generation) return
        val wasConnected = state.isConnected || state.established
        val problem = when (error) {
            is WsException -> when (error.fault) {
                WsFault.HANDSHAKE -> Problem.NOT_OBS
                WsFault.TOO_LARGE -> Problem.OVERSIZE
                WsFault.PROTOCOL, WsFault.BAD_UTF8 -> Problem.PROTOCOL
                WsFault.TIMEOUT -> Problem.TIMEOUT
                WsFault.CLOSED -> if (wasConnected) Problem.CONNECTION_LOST else Problem.UNREACHABLE
            }
            is ConnectException, is NoRouteToHostException, is UnknownHostException -> Problem.UNREACHABLE
            else -> if (wasConnected) Problem.CONNECTION_LOST else Problem.UNREACHABLE
        }
        fail(problem, retryable = true)
    }

    private fun onServerClose(code: Int?) {
        when {
            code == ObsClose.AUTHENTICATION_FAILED -> fail(Problem.WRONG_PASSWORD, retryable = false)
            code == ObsClose.UNSUPPORTED_RPC -> fail(Problem.UNSUPPORTED_VERSION, retryable = false)
            code == ObsClose.SESSION_INVALIDATED -> fail(Problem.SESSION_ENDED, retryable = false)
            code != null && ObsClose.isProtocolFault(code) -> fail(Problem.PROTOCOL, retryable = false)
            else -> fail(Problem.CONNECTION_LOST, retryable = true, graceful = true)
        }
    }

    private fun fail(problem: Problem, retryable: Boolean, graceful: Boolean = false) {
        if (target == null) {
            endSession(graceful = false)
            return
        }
        val now = nowMs()
        val wasStable = state.isConnected && now - connectedAtMs >= timings.stableAfterMs
        val finalAttempt = !retryable || !state.established
        endSession(graceful = graceful || problem == Problem.WRONG_PASSWORD)
        if (finalAttempt) {
            target = null
            state = staleState().copy(
                phase = Phase.FAILED,
                problem = problem,
                established = false,
                retryInSeconds = null,
                attempt = 0,
                scenes = emptyList(),
                inputs = emptyList(),
                currentScene = null,
                scenesTruncated = false,
                inputsTruncated = false,
            )
            publish()
            return
        }
        if (wasStable) {
            attempt = 0
            lostAtMs = 0
        }
        attempt++
        if (lostAtMs == 0L) lostAtMs = now
        val delay = Backoff.delayMs(attempt, random.nextDouble(), timings.backoffBaseMs, timings.backoffCapMs)
        retryAtMs = now + delay
        state = staleState().copy(
            phase = Phase.RECONNECTING,
            problem = problem,
            attempt = attempt,
            retryInSeconds = ((delay + 999) / 1000).toInt(),
            reconnectStale = state.reconnectStale && !wasStable,
        )
        publish()
        startTick()
        val waiting = generation
        retry = schedule(delay) {
            if (waiting == generation) startAttempt()
        }
    }

    private fun staleState() = state.copy(
        stream = OutputView(),
        record = OutputView(),
        stats = null,
        switchingTo = null,
        notice = null,
    )

    private fun onText(text: String) {
        when (val message = ObsMessages.parse(text)) {
            null -> fail(Problem.PROTOCOL, retryable = true)
            is ObsInbound.Hello -> onHello(message)
            is ObsInbound.Identified -> onIdentified(message)
            is ObsInbound.Response -> onResponse(message)
            is ObsInbound.Event -> if (state.isConnected) onEvent(message)
            ObsInbound.Ignored -> Unit
        }
    }

    private fun onHello(hello: ObsInbound.Hello) {
        val current = target
        if (helloSeen || current == null) {
            fail(Problem.PROTOCOL, retryable = true)
            return
        }
        helloSeen = true
        val challenge = hello.challenge
        val salt = hello.salt
        val authentication = if (challenge != null && salt != null) {
            if (current.password.isEmpty()) {
                fail(Problem.PASSWORD_REQUIRED, retryable = false)
                return
            }
            ObsAuth.response(current.password, salt, challenge)
        } else {
            null
        }
        identifySent = true
        enqueue(ObsMessages.identify(authentication))
    }

    private fun onIdentified(identified: ObsInbound.Identified) {
        if (!identifySent || state.isConnected || identified.rpcVersion != ObsMessages.RPC_VERSION) {
            fail(Problem.PROTOCOL, retryable = true)
            return
        }
        deadline?.cancel(false)
        deadline = null
        connectedAtMs = nowMs()
        state = state.copy(
            phase = Phase.CONNECTED,
            problem = null,
            established = true,
            retryInSeconds = null,
            attempt = 0,
        )
        publish()
        target?.let(onAuthenticated)
        refreshScenes()
        refreshInputs()
        poll()
    }

    private fun enqueue(text: String): Boolean {
        val current = link ?: return false
        if (current.queue.offer(Outbound.Text(text))) return true
        fail(Problem.TIMEOUT, retryable = true)
        return false
    }

    private fun request(type: String, parameters: JSONObject?, onResult: (ObsInbound.Response?) -> Unit): Boolean {
        if (pending.size >= MAX_PENDING) {
            state = state.copy(notice = Notice(Action.BUSY, null))
            return false
        }
        val id = "sr-$generation-${++requestSeq}"
        pending[id] = Pending(type, nowMs(), onResult)
        if (!enqueue(ObsMessages.request(type, id, parameters))) {
            pending.remove(id)
            return false
        }
        return true
    }

    private fun onResponse(response: ObsInbound.Response) {
        val waiting = pending.remove(response.id) ?: return
        waiting.onResult(response.takeIf { it.type == waiting.type })
        publish()
    }

    private fun noticeFor(action: Action, response: ObsInbound.Response?) {
        state = state.copy(notice = Notice(action, ObsMessages.comment(response?.comment)))
    }

    private fun streamCommand(start: Boolean) {
        if (!state.isConnected) return
        val before = state.stream
        if (if (start) !before.canStart else !before.canStop) return
        val type = if (start) ObsRequest.START_STREAM else ObsRequest.STOP_STREAM
        val action = if (start) Action.START_STREAM else Action.STOP_STREAM
        val moving = if (start) OutputPhase.STARTING else OutputPhase.STOPPING
        state = state.copy(stream = OutputReducer.onCommand(before, moving, nowMs()), notice = null)
        val sent = request(type, null) { response ->
            if (response?.ok != true) {
                state = state.copy(stream = before)
                noticeFor(action, response)
            }
        }
        if (!sent) state = state.copy(stream = before)
        publish()
    }

    private fun recordCommand(start: Boolean) {
        if (!state.isConnected) return
        val before = state.record
        if (if (start) !before.canStart else !before.canStop) return
        val type = if (start) ObsRequest.START_RECORD else ObsRequest.STOP_RECORD
        val action = if (start) Action.START_RECORD else Action.STOP_RECORD
        val moving = if (start) OutputPhase.STARTING else OutputPhase.STOPPING
        state = state.copy(record = OutputReducer.onCommand(before, moving, nowMs()), notice = null)
        val sent = request(type, null) { response ->
            if (response?.ok != true) {
                state = state.copy(record = before)
                noticeFor(action, response)
            }
        }
        if (!sent) state = state.copy(record = before)
        publish()
    }

    private fun poll() {
        if (pollInFlight > 0 || pending.size + POLL_REQUESTS > MAX_PENDING) return
        lastPollMs = nowMs()
        pollRequest(ObsRequest.GET_STATS) { data -> state = state.copy(stats = ObsMessages.stats(data)) }
        pollRequest(ObsRequest.GET_STREAM_STATUS) { data ->
            val active = ObsMessages.bool(data, "outputActive")
            if (active != null) {
                state = state.copy(
                    stream = OutputReducer.onPoll(
                        state.stream,
                        active,
                        if (ObsMessages.bool(data, "outputReconnecting") == true) OutputPhase.RECONNECTING else null,
                        ObsMessages.duration(data),
                        ObsMessages.count(data, "outputSkippedFrames"),
                        ObsMessages.count(data, "outputTotalFrames"),
                        nowMs(),
                        timings.transitionGraceMs,
                    ),
                )
            }
        }
        pollRequest(ObsRequest.GET_RECORD_STATUS) { data ->
            val active = ObsMessages.bool(data, "outputActive")
            if (active != null) {
                state = state.copy(
                    record = OutputReducer.onPoll(
                        state.record,
                        active,
                        if (ObsMessages.bool(data, "outputPaused") == true) OutputPhase.PAUSED else null,
                        ObsMessages.duration(data),
                        null,
                        null,
                        nowMs(),
                        timings.transitionGraceMs,
                    ),
                )
            }
        }
    }

    private fun pollRequest(type: String, onData: (JSONObject) -> Unit) {
        pollInFlight++
        val sent = request(type, null) { response ->
            pollInFlight = (pollInFlight - 1).coerceAtLeast(0)
            if (response?.ok == true) onData(response.data)
        }
        if (!sent && link != null) pollInFlight = (pollInFlight - 1).coerceAtLeast(0)
    }

    private fun refreshScenes() {
        request(ObsRequest.GET_SCENE_LIST, null) { response ->
            val list = response?.takeIf { it.ok }?.let { ObsMessages.sceneList(it.data) }
            if (list != null) {
                state = state.copy(
                    scenes = list.scenes,
                    scenesTruncated = list.truncated,
                    currentScene = list.current ?: state.currentScene,
                )
            }
        }
    }

    private fun refreshInputs() {
        val token = ++inputsToken
        request(ObsRequest.GET_INPUT_LIST, null) { response ->
            val list = response?.takeIf { it.ok }?.let { ObsMessages.inputNames(it.data) }
            if (list != null && token == inputsToken) probeInputs(list, token)
        }
    }

    private fun probeInputs(list: NameList, token: Long) {
        val found = arrayOfNulls<AudioInput>(list.names.size)
        var left = list.names.size
        val finish = {
            val audio = found.filterNotNull()
            state = state.copy(
                inputs = audio.take(ObsMessages.MAX_AUDIO_INPUTS),
                inputsTruncated = list.truncated || audio.size > ObsMessages.MAX_AUDIO_INPUTS,
            )
        }
        if (left == 0) {
            finish()
            return
        }
        list.names.forEachIndexed { index, name ->
            val sent = request(ObsRequest.GET_INPUT_MUTE, ObsMessages.inputParameters(name)) { response ->
                if (token == inputsToken) {
                    val muted = response?.takeIf { it.ok }?.let { ObsMessages.bool(it.data, "inputMuted") }
                    if (muted != null) found[index] = AudioInput(name, ObsMessages.label(name), muted)
                    left--
                    if (left == 0) finish()
                }
            }
            if (!sent && token == inputsToken) {
                left--
                if (left == 0) finish()
            }
        }
    }

    private fun queueSceneRefresh() {
        if (scenesRefreshQueued) return
        scenesRefreshQueued = true
        val current = generation
        schedule(timings.refreshDelayMs) {
            if (current == generation) {
                scenesRefreshQueued = false
                if (state.isConnected) refreshScenes()
                publish()
            }
        }
    }

    private fun queueInputRefresh() {
        if (inputsRefreshQueued) return
        inputsRefreshQueued = true
        val current = generation
        schedule(timings.refreshDelayMs) {
            if (current == generation) {
                inputsRefreshQueued = false
                if (state.isConnected) refreshInputs()
                publish()
            }
        }
    }

    private fun applyMute(id: String, muted: Boolean) {
        if (state.inputs.none { it.id == id && it.muted != muted }) return
        state = state.copy(inputs = state.inputs.map { if (it.id == id) it.copy(muted = muted) else it })
    }

    private fun onEvent(event: ObsInbound.Event) {
        val data = event.data
        when (event.type) {
            ObsEventName.PROGRAM_SCENE_CHANGED -> {
                val name = ObsMessages.rawName(data.opt("sceneName"))
                if (name != null) {
                    state = state.copy(currentScene = name)
                    if (state.scenes.none { it.id == name }) queueSceneRefresh()
                }
            }
            ObsEventName.SCENE_LIST_CHANGED,
            ObsEventName.SCENE_CREATED,
            ObsEventName.SCENE_REMOVED,
            ObsEventName.SCENE_NAME_CHANGED -> queueSceneRefresh()
            ObsEventName.COLLECTION_CHANGED -> {
                queueSceneRefresh()
                queueInputRefresh()
            }
            ObsEventName.STREAM_STATE_CHANGED -> state = state.copy(
                stream = OutputReducer.onEvent(
                    state.stream,
                    ObsMessages.bool(data, "outputActive"),
                    ObsMessages.text(data, "outputState"),
                    nowMs(),
                ),
            )
            ObsEventName.RECORD_STATE_CHANGED -> state = state.copy(
                record = OutputReducer.onEvent(
                    state.record,
                    ObsMessages.bool(data, "outputActive"),
                    ObsMessages.text(data, "outputState"),
                    nowMs(),
                ),
            )
            ObsEventName.INPUT_MUTE_CHANGED -> {
                val name = ObsMessages.rawName(data.opt("inputName"))
                val muted = ObsMessages.bool(data, "inputMuted")
                if (name != null && muted != null) applyMute(name, muted)
            }
            ObsEventName.INPUT_CREATED,
            ObsEventName.INPUT_REMOVED,
            ObsEventName.INPUT_NAME_CHANGED -> queueInputRefresh()
        }
        publish()
    }

    private companion object {
        const val MAX_OUTBOUND = 512
        const val MAX_INFLIGHT = 2
        const val MAX_PENDING = 256
        const val POLL_REQUESTS = 3
        const val NANOS_PER_MS = 1_000_000L
    }
}
