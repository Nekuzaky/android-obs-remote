package dev.nekuz.sceneremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ConnectionStore {
    private const val SETTINGS_FILE = "settings.json"

    private val main = Handler(Looper.getMainLooper())
    private val disk = Executors.newSingleThreadExecutor { task ->
        Thread(task, "disk").apply { isDaemon = true }
    }
    private val listeners = ArrayList<() -> Unit>()
    private val notifyQueued = AtomicBoolean(false)
    private val pendingSave = AtomicReference<ObsTarget?>(null)
    private val activities = VisibleActivities()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var latest = RemoteState()

    private val client: ObsClient by lazy {
        ObsClient(onAuthenticated = ::saveAfterLogin) { next ->
            latest = next
            queueNotify()
        }
    }

    private var prepareToken = 0L
    private var loadStarted = false

    val state: RemoteState get() = latest

    var settings: Settings? = null
        private set
    var hasSavedPassword = false
        private set
    var preparing = false
        private set
    var savedPasswordUnreadable = false
        private set
    var savedPasswordOtherHost = false
        private set
    var saveFailed = false
        private set
    var passwordDraft: String? = null

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun load(context: Context) {
        if (loadStarted) return
        loadStarted = true
        val app = context.applicationContext
        disk.execute {
            val loaded = SettingsCodec.decode(AtomicText.read(settingsFile(app)))
            val saved = SecretStore.exists(app)
            main.post {
                if (settings == null) settings = loaded
                hasSavedPassword = saved
                notifyListeners()
            }
        }
    }

    fun enter() {
        activities.onStart()
        client.resume()
    }

    fun leave(changingConfigurations: Boolean) {
        if (activities.onStop(changingConfigurations)) {
            prepareToken++
            preparing = false
            client.pause()
        }
    }

    fun connect(context: Context, host: HostSpec, hostText: String, port: Int, typed: String, save: Boolean) {
        val app = context.applicationContext
        appContext = app
        val token = ++prepareToken
        val target = ObsTarget(host, port, typed)
        pendingSave.set(null)
        val keepScreenOn = settings?.keepScreenOn ?: Settings().keepScreenOn
        val next = Settings(hostText, port, save, keepScreenOn)
        settings = next
        preparing = true
        savedPasswordUnreadable = false
        savedPasswordOtherHost = false
        saveFailed = false
        notifyListeners()
        disk.execute {
            AtomicText.write(settingsFile(app), SettingsCodec.encode(next))
            val exists = SecretStore.exists(app)
            val stored = if (typed.isEmpty() && save && exists) SecretStore.load(app) else null
            val choice = PasswordPolicy.choose(typed, save, exists, stored, target.endpoint)
            if (!save) SecretStore.clear(app)
            val saved = SecretStore.exists(app)
            main.post {
                hasSavedPassword = saved
                if (token == prepareToken) {
                    preparing = false
                    savedPasswordUnreadable = choice is PasswordChoice.Unreadable
                    savedPasswordOtherHost = choice is PasswordChoice.OtherEndpoint
                    if (choice is PasswordChoice.Use && activities.visible) {
                        val ready = ObsTarget(host, port, choice.password)
                        if (save && typed.isNotEmpty()) pendingSave.set(ready)
                        client.connect(ready)
                    }
                }
                notifyListeners()
            }
        }
    }

    private fun saveAfterLogin(target: ObsTarget) {
        if (!pendingSave.compareAndSet(target, null)) return
        val app = appContext ?: return
        disk.execute {
            val done = SecretStore.save(app, SavedPassword(target.endpoint, target.password))
            val saved = SecretStore.exists(app)
            main.post {
                saveFailed = !done
                hasSavedPassword = saved
                notifyListeners()
            }
        }
    }

    fun disconnect() {
        prepareToken++
        preparing = false
        pendingSave.set(null)
        client.disconnect()
        notifyListeners()
    }

    fun forgetPassword(context: Context) {
        val app = context.applicationContext
        hasSavedPassword = false
        savedPasswordUnreadable = false
        savedPasswordOtherHost = false
        pendingSave.set(null)
        disk.execute { SecretStore.clear(app) }
        settings = settings?.copy(savePassword = false)
        persistSettings(app)
        notifyListeners()
    }

    fun setKeepScreenOn(context: Context, keep: Boolean) {
        val current = settings ?: return
        if (current.keepScreenOn == keep) return
        settings = current.copy(keepScreenOn = keep)
        persistSettings(context.applicationContext)
        notifyListeners()
    }

    fun switchScene(id: String) = client.switchScene(id)

    fun setMuted(id: String, muted: Boolean) = client.setMuted(id, muted)

    fun startStream() = client.startStream()

    fun stopStream() = client.stopStream()

    fun startRecord() = client.startRecord()

    fun stopRecord() = client.stopRecord()

    private fun persistSettings(app: Context) {
        val snapshot = settings ?: return
        disk.execute { AtomicText.write(settingsFile(app), SettingsCodec.encode(snapshot)) }
    }

    private fun settingsFile(context: Context) = File(context.filesDir, SETTINGS_FILE)

    private fun queueNotify() {
        if (notifyQueued.compareAndSet(false, true)) {
            main.post {
                notifyQueued.set(false)
                notifyListeners()
            }
        }
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
