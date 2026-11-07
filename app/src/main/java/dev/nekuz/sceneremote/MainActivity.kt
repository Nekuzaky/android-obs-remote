package dev.nekuz.sceneremote

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var connectPanel: View
    private lateinit var controlPanel: View
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var saveCheck: CheckBox
    private lateinit var connectButton: Button
    private lateinit var forgetButton: Button
    private lateinit var problemText: TextView
    private lateinit var streamButton: Button
    private lateinit var recordButton: Button
    private lateinit var noticeText: TextView
    private lateinit var streamTime: TextView
    private lateinit var recordTime: TextView
    private lateinit var droppedValue: TextView
    private lateinit var cpuValue: TextView
    private lateinit var fpsValue: TextView
    private lateinit var keepSwitch: Switch
    private lateinit var adapter: RowAdapter

    private var stopDialog: AlertDialog? = null
    private var infoDialog: AlertDialog? = null
    private var formError: String? = null
    private var formFilled = false
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        connectPanel = findViewById(R.id.connectPanel)
        controlPanel = findViewById(R.id.controlPanel)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        passwordInput = findViewById(R.id.passwordInput)
        saveCheck = findViewById(R.id.saveCheck)
        connectButton = findViewById(R.id.connectButton)
        forgetButton = findViewById(R.id.forgetButton)
        problemText = findViewById(R.id.problemText)
        streamButton = findViewById(R.id.streamButton)
        recordButton = findViewById(R.id.recordButton)
        noticeText = findViewById(R.id.noticeText)
        streamTime = findViewById(R.id.streamTime)
        recordTime = findViewById(R.id.recordTime)
        droppedValue = findViewById(R.id.droppedValue)
        cpuValue = findViewById(R.id.cpuValue)
        fpsValue = findViewById(R.id.fpsValue)
        keepSwitch = findViewById(R.id.keepSwitch)

        adapter = RowAdapter(
            this,
            onScene = { ConnectionStore.switchScene(it) },
            onMute = { id, muted -> ConnectionStore.setMuted(id, muted) },
        )
        findViewById<ListView>(R.id.list).adapter = adapter

        formFilled = savedInstanceState != null
        ConnectionStore.passwordDraft?.let { passwordInput.setText(it) }
        ConnectionStore.passwordDraft = null

        connectButton.setOnClickListener { onConnectClick() }
        forgetButton.setOnClickListener {
            saveCheck.isChecked = false
            ConnectionStore.forgetPassword(this)
        }
        saveCheck.setOnCheckedChangeListener { _, _ -> render() }
        streamButton.setOnClickListener { onStreamClick() }
        recordButton.setOnClickListener { onRecordClick() }
        findViewById<View>(R.id.disconnectButton).setOnClickListener { ConnectionStore.disconnect() }
        findViewById<View>(R.id.infoButton).setOnClickListener { showInfo() }
        keepSwitch.setOnCheckedChangeListener { _, checked ->
            ConnectionStore.setKeepScreenOn(this, checked)
        }

        ConnectionStore.load(this)
        val state = ConnectionStore.state
        if (savedInstanceState?.getBoolean(STATE_STOP_DIALOG) == true && state.isConnected && state.stream.canStop) {
            showStopDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_STOP_DIALOG, stopDialog?.isShowing == true)
    }

    override fun onStart() {
        super.onStart()
        ConnectionStore.addListener(listener)
        ConnectionStore.enter()
        render()
    }

    override fun onStop() {
        super.onStop()
        ConnectionStore.removeListener(listener)
        if (isChangingConfigurations) {
            ConnectionStore.passwordDraft = passwordInput.text.toString().ifEmpty { null }
        }
        ConnectionStore.leave(isChangingConfigurations)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDialog?.setOnDismissListener(null)
        stopDialog?.dismiss()
        stopDialog = null
        infoDialog?.dismiss()
        infoDialog = null
    }

    private fun onConnectClick() {
        val state = ConnectionStore.state
        if (state.isBusy || ConnectionStore.preparing) {
            ConnectionStore.disconnect()
            return
        }
        formError = null
        hostInput.error = null
        portInput.error = null
        val port = HostParser.parsePort(portInput.text.toString())
        if (port == null) portInput.error = getString(R.string.error_port)
        val hostText = hostInput.text.toString().trim()
        when (val parsed = HostParser.parse(hostText)) {
            is HostParse.Bad -> when (parsed.error) {
                HostError.EMPTY -> hostInput.error = getString(R.string.error_host_empty)
                HostError.TOO_LONG, HostError.INVALID -> hostInput.error = getString(R.string.error_host_invalid)
                HostError.PUBLIC -> formError = getString(R.string.problem_public)
            }
            is HostParse.Ok -> if (port != null) {
                hideKeyboard()
                ConnectionStore.connect(
                    this,
                    parsed.spec,
                    hostText,
                    port,
                    passwordInput.text.toString(),
                    saveCheck.isChecked,
                )
            }
        }
        render()
    }

    private fun onStreamClick() {
        val stream = ConnectionStore.state.stream
        when {
            stream.canStop -> showStopDialog()
            stream.canStart -> ConnectionStore.startStream()
        }
    }

    private fun onRecordClick() {
        val record = ConnectionStore.state.record
        when {
            record.canStop -> ConnectionStore.stopRecord()
            record.canStart -> ConnectionStore.startRecord()
        }
    }

    private fun showStopDialog() {
        if (stopDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.stop_stream_title)
            .setMessage(R.string.stop_stream_message)
            .setPositiveButton(R.string.stop_stream_yes) { _, _ -> ConnectionStore.stopStream() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { stopDialog = null }
        stopDialog = dialog
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
    }

    private fun showInfo() {
        infoDialog?.dismiss()
        infoDialog = AlertDialog.Builder(this)
            .setTitle(R.string.info)
            .setMessage(R.string.info_message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun render() {
        val state = ConnectionStore.state
        fillForm()
        val settings = ConnectionStore.settings
        val keep = settings?.keepScreenOn ?: Settings().keepScreenOn
        if (keepSwitch.isChecked != keep) keepSwitch.isChecked = keep
        if (keep && state.keepsScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (state.isConnected && passwordInput.text.isNotEmpty()) passwordInput.text.clear()
        val controls = state.showsControls
        connectPanel.visibility = if (controls) View.GONE else View.VISIBLE
        controlPanel.visibility = if (controls) View.VISIBLE else View.GONE
        status.text = statusText(state)
        if (controls) renderControls(state) else renderForm(state)
        if (stopDialog?.isShowing == true && !(state.isConnected && state.stream.canStop)) stopDialog?.dismiss()
    }

    private fun fillForm() {
        val settings = ConnectionStore.settings ?: return
        if (formFilled) return
        formFilled = true
        if (hostInput.text.isEmpty()) hostInput.setText(settings.host)
        if (portInput.text.isEmpty()) portInput.setText(settings.port.toString())
        saveCheck.isChecked = settings.savePassword
    }

    private fun renderForm(state: RemoteState) {
        val busy = state.isBusy || ConnectionStore.preparing
        connectButton.setText(if (busy) R.string.cancel else R.string.connect)
        hostInput.isEnabled = !busy
        portInput.isEnabled = !busy
        passwordInput.isEnabled = !busy
        saveCheck.isEnabled = !busy
        val saved = ConnectionStore.hasSavedPassword
        forgetButton.visibility = if (saved) View.VISIBLE else View.GONE
        passwordInput.setHint(if (saved && saveCheck.isChecked) R.string.password_saved_hint else R.string.password_hint)
        val message = when {
            formError != null -> formError
            ConnectionStore.savedPasswordUnreadable -> getString(R.string.problem_saved_unreadable)
            ConnectionStore.savedPasswordOtherHost -> getString(R.string.problem_saved_other_host)
            state.phase == Phase.FAILED -> problemMessage(state.problem)
            else -> null
        }
        problemText.visibility = if (message == null) View.GONE else View.VISIBLE
        problemText.text = message
    }

    private fun renderControls(state: RemoteState) {
        val connected = state.isConnected
        bindOutput(streamButton, state.stream, connected, R.string.stream_start, R.string.stream_stop, R.string.stream_starting, R.string.stream_stopping)
        bindOutput(recordButton, state.record, connected, R.string.record_start, R.string.record_stop, R.string.record_starting, R.string.record_stopping)
        streamTime.text = Format.duration(state.stream.durationMs.takeIf { state.stream.isLive })
        recordTime.text = Format.duration(state.record.durationMs.takeIf { state.record.isLive })
        droppedValue.text = Format.droppedFrames(state)
        cpuValue.text = Format.percent(state.stats?.cpuPercent)
        fpsValue.text = Format.fps(state.stats?.fps)
        val notice = state.notice?.let { noticeText(it) }
            ?: if (ConnectionStore.saveFailed) getString(R.string.notice_save_failed) else null
        noticeText.visibility = if (notice == null) View.GONE else View.VISIBLE
        noticeText.text = notice
        adapter.rows = rows(state)
    }

    private fun bindOutput(button: Button, output: OutputView, connected: Boolean, start: Int, stop: Int, starting: Int, stopping: Int) {
        button.setText(
            when (output.phase) {
                OutputPhase.STARTING -> starting
                OutputPhase.STOPPING -> stopping
                OutputPhase.ACTIVE, OutputPhase.RECONNECTING, OutputPhase.PAUSED -> stop
                OutputPhase.STOPPED, OutputPhase.UNKNOWN -> start
            },
        )
        val enabled = connected && (output.canStart || output.canStop)
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
        button.setBackgroundResource(if (output.isLive) R.drawable.bg_button_live else R.drawable.bg_button_primary)
    }

    private fun rows(state: RemoteState): List<Row> {
        val rows = ArrayList<Row>()
        val connected = state.isConnected
        rows.add(Row.Header(getString(R.string.section_scenes)))
        if (state.scenes.isEmpty()) rows.add(Row.Note(getString(R.string.scenes_empty)))
        state.scenes
            .map { scene ->
                SceneCell(
                    id = scene.id,
                    label = scene.label,
                    current = scene.id == state.currentScene,
                    pending = scene.id == state.switchingTo,
                    enabled = connected,
                )
            }
            .chunked(sceneColumns())
            .forEach { rows.add(Row.Scenes(it, sceneColumns())) }
        if (state.scenesTruncated) rows.add(Row.Note(getString(R.string.scenes_truncated)))
        rows.add(Row.Header(getString(R.string.section_audio)))
        if (state.inputs.isEmpty()) rows.add(Row.Note(getString(R.string.audio_empty)))
        state.inputs.forEach { rows.add(Row.Audio(it.id, it.label, it.muted, connected)) }
        if (state.inputsTruncated) rows.add(Row.Note(getString(R.string.audio_truncated)))
        return rows
    }

    private fun sceneColumns(): Int {
        val configuration = resources.configuration
        val width = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            configuration.screenWidthDp / 2
        } else {
            configuration.screenWidthDp
        }
        return if (width >= WIDE_LIST_DP) 3 else 2
    }

    private fun statusText(state: RemoteState): String = when (state.phase) {
        Phase.IDLE -> getString(if (ConnectionStore.preparing) R.string.status_preparing else R.string.status_idle)
        Phase.CONNECTING -> getString(R.string.status_connecting, state.hostLabel)
        Phase.AUTHENTICATING -> getString(R.string.status_authenticating)
        Phase.CONNECTED -> getString(R.string.status_connected, state.hostLabel)
        Phase.RECONNECTING -> {
            val wait = state.retryInSeconds
            if (wait != null) {
                getString(R.string.status_retry_wait, wait, state.attempt)
            } else {
                getString(R.string.status_retrying, state.hostLabel)
            }
        }
        Phase.PAUSED -> getString(R.string.status_paused)
        Phase.FAILED -> getString(R.string.status_failed)
    }

    private fun problemMessage(problem: Problem?): String? = when (problem) {
        Problem.PUBLIC_ADDRESS -> getString(R.string.problem_public)
        Problem.UNRESOLVED -> getString(R.string.problem_unresolved)
        Problem.UNREACHABLE -> getString(R.string.problem_unreachable)
        Problem.TIMEOUT -> getString(R.string.problem_timeout)
        Problem.NOT_OBS -> getString(R.string.problem_not_obs)
        Problem.PROTOCOL -> getString(R.string.problem_protocol)
        Problem.OVERSIZE -> getString(R.string.problem_oversize)
        Problem.WRONG_PASSWORD -> getString(R.string.problem_wrong_password)
        Problem.PASSWORD_REQUIRED -> getString(R.string.problem_password_required)
        Problem.UNSUPPORTED_VERSION -> getString(R.string.problem_unsupported)
        Problem.SESSION_ENDED -> getString(R.string.problem_session_ended)
        Problem.CONNECTION_LOST -> getString(R.string.problem_lost)
        null -> null
    }

    private fun noticeText(notice: Notice): String {
        val what = getString(
            when (notice.action) {
                Action.SWITCH_SCENE -> R.string.notice_switch
                Action.START_STREAM -> R.string.notice_start_stream
                Action.STOP_STREAM -> R.string.notice_stop_stream
                Action.START_RECORD -> R.string.notice_start_record
                Action.STOP_RECORD -> R.string.notice_stop_record
                Action.MUTE -> R.string.notice_mute
                Action.BUSY -> R.string.notice_busy
            },
        )
        return if (notice.detail == null) what else getString(R.string.notice_detail, what, notice.detail)
    }

    private companion object {
        const val STATE_STOP_DIALOG = "stopDialog"
        const val DISABLED_ALPHA = 0.45f
        const val WIDE_LIST_DP = 480
    }
}
