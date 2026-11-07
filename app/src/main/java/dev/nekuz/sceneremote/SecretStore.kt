package dev.nekuz.sceneremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretStore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "sceneremote_password"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FILE_NAME = "password.json"
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private val AAD = "sceneremote/password/v1".toByteArray(Charsets.US_ASCII)

    fun exists(context: Context): Boolean = file(context).isFile

    fun save(context: Context, saved: SavedPassword): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val cipherText = cipher.doFinal(SavedPasswordCodec.encode(saved).toByteArray(Charsets.UTF_8))
        AtomicText.write(file(context), SecretBlobCodec.encode(SecretBlob(cipher.iv, cipherText)))
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: ProviderException) {
        false
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    fun load(context: Context): SavedPassword? {
        val blob = SecretBlobCodec.decode(AtomicText.read(file(context))) ?: return null
        return try {
            val stored = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, stored, GCMParameterSpec(TAG_BITS, blob.iv))
            cipher.updateAAD(AAD)
            val plain = cipher.doFinal(blob.cipherText)
            if (Utf8.isValid(plain)) SavedPasswordCodec.decode(String(plain, Charsets.UTF_8)) else null
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: ProviderException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
            val store = KeyStore.getInstance(PROVIDER)
            store.load(null)
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        } catch (e: GeneralSecurityException) {
            return
        } catch (e: IOException) {
            return
        } catch (e: RuntimeException) {
            return
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER)
        store.load(null)
        return store.getKey(ALIAS, null) as? SecretKey
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }
}
