package dev.nekuz.sceneremote

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

object AtomicText {
    const val MAX_BYTES = 64 * 1024

    fun read(file: File, maxBytes: Int = MAX_BYTES): String? {
        return try {
            if (!file.isFile || file.length() > maxBytes) return null
            val bytes = file.inputStream().use { stream ->
                val buffer = ByteArray(maxBytes + 1)
                var total = 0
                while (total < buffer.size) {
                    val count = stream.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    total += count
                }
                if (total > maxBytes) return null
                buffer.copyOf(total)
            }
            if (Utf8.isValid(bytes)) String(bytes, Charsets.UTF_8) else null
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    fun write(file: File, text: String): Boolean {
        val temp = File(file.parentFile, "${file.name}.tmp")
        return try {
            FileOutputStream(temp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            temp.delete()
            false
        } catch (e: SecurityException) {
            temp.delete()
            false
        }
    }
}

data class Settings(
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val savePassword: Boolean = false,
    val keepScreenOn: Boolean = true,
) {
    companion object {
        const val DEFAULT_PORT = 4455
    }
}

object SettingsCodec {
    private const val MAX_HOST = 255

    fun encode(settings: Settings): String = JSONObject()
        .put("host", settings.host.take(MAX_HOST))
        .put("port", settings.port)
        .put("save", settings.savePassword)
        .put("keep", settings.keepScreenOn)
        .toString()

    fun decode(text: String?): Settings {
        val defaults = Settings()
        if (text == null || !JsonGuard.withinLimits(text, 4)) return defaults
        return try {
            val root = JSONObject(text)
            Settings(
                host = (root.opt("host") as? String)?.takeIf { it.length <= MAX_HOST } ?: defaults.host,
                port = (root.opt("port") as? Number)?.toInt()?.takeIf { it in 1..65535 } ?: defaults.port,
                savePassword = root.opt("save") as? Boolean ?: defaults.savePassword,
                keepScreenOn = root.opt("keep") as? Boolean ?: defaults.keepScreenOn,
            )
        } catch (e: JSONException) {
            defaults
        } catch (e: RuntimeException) {
            defaults
        }
    }
}

class SavedPassword(val endpoint: String, val password: String) {
    override fun toString() = "SavedPassword($endpoint)"
}

object SavedPasswordCodec {
    private const val MAX_ENDPOINT = 300
    private const val MAX_PASSWORD = 1024

    fun encode(saved: SavedPassword): String = JSONObject()
        .put("e", saved.endpoint)
        .put("p", saved.password)
        .toString()

    fun decode(text: String?): SavedPassword? {
        if (text == null || !JsonGuard.withinLimits(text, 4)) return null
        return try {
            val root = JSONObject(text)
            val endpoint = root.opt("e") as? String ?: return null
            val password = root.opt("p") as? String ?: return null
            if (endpoint.isEmpty() || endpoint.length > MAX_ENDPOINT || password.length > MAX_PASSWORD) return null
            SavedPassword(endpoint, password)
        } catch (e: JSONException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}

sealed class PasswordChoice {
    class Use(val password: String) : PasswordChoice()
    object Unreadable : PasswordChoice()
    object OtherEndpoint : PasswordChoice()
}

object PasswordPolicy {
    fun choose(typed: String, save: Boolean, hasSaved: Boolean, stored: SavedPassword?, endpoint: String): PasswordChoice = when {
        typed.isNotEmpty() -> PasswordChoice.Use(typed)
        !save || !hasSaved -> PasswordChoice.Use("")
        stored == null -> PasswordChoice.Unreadable
        stored.endpoint != endpoint -> PasswordChoice.OtherEndpoint
        else -> PasswordChoice.Use(stored.password)
    }
}

class SecretBlob(val iv: ByteArray, val cipherText: ByteArray)

object SecretBlobCodec {
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val MAX_CIPHER = 4096

    fun encode(blob: SecretBlob): String = JSONObject()
        .put("v", 1)
        .put("iv", Base64.getEncoder().encodeToString(blob.iv))
        .put("ct", Base64.getEncoder().encodeToString(blob.cipherText))
        .toString()

    fun decode(text: String?): SecretBlob? {
        if (text == null || !JsonGuard.withinLimits(text, 4)) return null
        return try {
            val root = JSONObject(text)
            if (root.opt("v") != 1) return null
            val iv = Base64.getDecoder().decode(root.opt("iv") as? String ?: return null)
            val cipherText = Base64.getDecoder().decode(root.opt("ct") as? String ?: return null)
            if (iv.size != IV_BYTES || cipherText.size < TAG_BYTES || cipherText.size > MAX_CIPHER) return null
            SecretBlob(iv, cipherText)
        } catch (e: JSONException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }
}
