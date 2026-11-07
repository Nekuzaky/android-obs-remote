package dev.nekuz.sceneremote

import java.util.Locale

enum class Phase { IDLE, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, PAUSED, FAILED }

enum class Problem {
    PUBLIC_ADDRESS,
    UNRESOLVED,
    UNREACHABLE,
    TIMEOUT,
    NOT_OBS,
    PROTOCOL,
    OVERSIZE,
    WRONG_PASSWORD,
    PASSWORD_REQUIRED,
    UNSUPPORTED_VERSION,
    SESSION_ENDED,
    CONNECTION_LOST,
}

enum class OutputPhase { UNKNOWN, STOPPED, STARTING, ACTIVE, RECONNECTING, PAUSED, STOPPING }

enum class Action { SWITCH_SCENE, START_STREAM, STOP_STREAM, START_RECORD, STOP_RECORD, MUTE, BUSY }

data class Scene(val id: String, val label: String)

data class AudioInput(val id: String, val label: String, val muted: Boolean)

data class OutputView(
    val phase: OutputPhase = OutputPhase.UNKNOWN,
    val durationMs: Long? = null,
    val sinceMs: Long = 0,
    val skippedFrames: Long? = null,
    val totalFrames: Long? = null,
) {
    val canStart: Boolean get() = phase == OutputPhase.STOPPED
    val canStop: Boolean
        get() = phase == OutputPhase.ACTIVE || phase == OutputPhase.RECONNECTING || phase == OutputPhase.PAUSED
    val isLive: Boolean get() = canStop || phase == OutputPhase.STOPPING
}

data class Stats(
    val cpuPercent: Double? = null,
    val fps: Double? = null,
    val renderSkipped: Long? = null,
    val renderTotal: Long? = null,
    val outputSkipped: Long? = null,
    val outputTotal: Long? = null,
)

data class Notice(val action: Action, val detail: String?)

data class RemoteState(
    val phase: Phase = Phase.IDLE,
    val problem: Problem? = null,
    val hostLabel: String = "",
    val established: Boolean = false,
    val attempt: Int = 0,
    val retryInSeconds: Int? = null,
    val scenes: List<Scene> = emptyList(),
    val scenesTruncated: Boolean = false,
    val currentScene: String? = null,
    val switchingTo: String? = null,
    val inputs: List<AudioInput> = emptyList(),
    val inputsTruncated: Boolean = false,
    val stream: OutputView = OutputView(),
    val record: OutputView = OutputView(),
    val stats: Stats? = null,
    val notice: Notice? = null,
    val reconnectStale: Boolean = false,
) {
    val isConnected: Boolean get() = phase == Phase.CONNECTED
    val isBusy: Boolean
        get() = phase == Phase.CONNECTING || phase == Phase.AUTHENTICATING
    val showsControls: Boolean
        get() = phase == Phase.CONNECTED ||
            (established && (phase == Phase.RECONNECTING || phase == Phase.PAUSED))
    val keepsScreenOn: Boolean
        get() = phase == Phase.CONNECTED || (phase == Phase.RECONNECTING && !reconnectStale) || isBusy
}

class VisibleActivities {
    private var started = 0
    private var rotating = false

    val visible: Boolean get() = started > 0 || rotating

    fun onStart() {
        started++
        rotating = false
    }

    fun onStop(changingConfigurations: Boolean): Boolean {
        started = (started - 1).coerceAtLeast(0)
        if (changingConfigurations) rotating = true
        return started == 0 && !rotating
    }
}

object OutputReducer {
    fun onEvent(previous: OutputView, active: Boolean?, stateName: String?, nowMs: Long): OutputView {
        val phase = when (stateName) {
            "OBS_WEBSOCKET_OUTPUT_STARTING" -> OutputPhase.STARTING
            "OBS_WEBSOCKET_OUTPUT_STARTED",
            "OBS_WEBSOCKET_OUTPUT_RECONNECTED",
            "OBS_WEBSOCKET_OUTPUT_RESUMED" -> OutputPhase.ACTIVE
            "OBS_WEBSOCKET_OUTPUT_STOPPING" -> OutputPhase.STOPPING
            "OBS_WEBSOCKET_OUTPUT_STOPPED" -> OutputPhase.STOPPED
            "OBS_WEBSOCKET_OUTPUT_RECONNECTING" -> OutputPhase.RECONNECTING
            "OBS_WEBSOCKET_OUTPUT_PAUSED" -> OutputPhase.PAUSED
            else -> when (active) {
                true -> OutputPhase.ACTIVE
                false -> OutputPhase.STOPPED
                null -> previous.phase
            }
        }
        return moved(previous, phase, nowMs)
    }

    fun onCommand(previous: OutputView, phase: OutputPhase, nowMs: Long): OutputView = moved(previous, phase, nowMs)

    fun onPoll(
        previous: OutputView,
        active: Boolean,
        degraded: OutputPhase?,
        durationMs: Long?,
        skipped: Long?,
        total: Long?,
        nowMs: Long,
        graceMs: Long,
    ): OutputView {
        val settling = nowMs - previous.sinceMs < graceMs
        val phase = when {
            previous.phase == OutputPhase.STARTING && !active && settling -> OutputPhase.STARTING
            previous.phase == OutputPhase.STOPPING && active && settling -> OutputPhase.STOPPING
            !active -> OutputPhase.STOPPED
            degraded != null -> degraded
            else -> OutputPhase.ACTIVE
        }
        val moved = moved(previous, phase, nowMs)
        return moved.copy(
            durationMs = if (active) durationMs else null,
            skippedFrames = if (active) skipped else null,
            totalFrames = if (active) total else null,
        )
    }

    private fun moved(previous: OutputView, phase: OutputPhase, nowMs: Long): OutputView {
        if (phase == previous.phase) return previous
        val base = previous.copy(phase = phase, sinceMs = nowMs)
        return if (phase == OutputPhase.STOPPED) base.copy(durationMs = null, skippedFrames = null, totalFrames = null) else base
    }
}

object Backoff {
    const val MAX_SHIFT = 16

    fun delayMs(attempt: Int, jitter: Double, baseMs: Long, capMs: Long): Long {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        val plain = (baseMs shl shift).coerceAtMost(capMs)
        val factor = 0.8 + 0.4 * (if (jitter.isNaN()) 0.5 else jitter.coerceIn(0.0, 1.0))
        return (plain * factor).toLong().coerceAtLeast(1)
    }
}

object Format {
    const val NO_VALUE = "--"
    private const val MS_PER_SECOND = 1000L

    fun duration(ms: Long?): String {
        if (ms == null) return "--:--:--"
        val seconds = ms.coerceAtLeast(0) / MS_PER_SECOND
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    fun percent(value: Double?): String =
        if (value == null || value.isNaN()) NO_VALUE else String.format(Locale.ROOT, "%.1f%%", value)

    fun fps(value: Double?): String =
        if (value == null || value.isNaN()) NO_VALUE else String.format(Locale.ROOT, "%.1f", value)

    fun dropped(skipped: Long?, total: Long?): String {
        if (skipped == null) return NO_VALUE
        if (total == null || total <= 0) return skipped.toString()
        return String.format(Locale.ROOT, "%d (%.1f%%)", skipped, skipped.toDouble() / total * 100)
    }

    fun droppedFrames(state: RemoteState): String {
        val stream = state.stream
        return if (stream.isLive && stream.skippedFrames != null) {
            dropped(stream.skippedFrames, stream.totalFrames)
        } else {
            dropped(state.stats?.outputSkipped, state.stats?.outputTotal)
        }
    }
}
