package com.romirmile.hermes.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the credentials this app stores on the device — the gateway API key and any speech
 * provider key the user enters — with an AES-256-GCM key held in the Android keystore.
 *
 * SharedPreferences is plain text, so a key sitting there is readable by anything that can read the
 * app's data (a backup, a rooted device, a debug dump). Encrypting with a keystore key that never
 * leaves the device closes that hole; the stored form is `enc:v1:` + base64(iv ‖ ciphertext).
 *
 * Failure is never fatal: decrypting a value this device can no longer read (key rotated, or app data
 * restored onto a different device where the keystore key does not exist) yields an empty string, so
 * the UI simply asks for the key again instead of crashing or sending a broken credential.
 */
object SecretStore {

    private const val PREFIX = "enc:v1:"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "hermes_settings_secret"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** True when [value] is already an encrypted blob produced by [encrypt]. */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /**
     * Returns the stored form of [plain]: encrypted when the keystore is available (the normal case),
     * otherwise the value unchanged so the app keeps working on an unusual device.
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /** Returns the plaintext behind [stored]; "" when unreadable. Plain, legacy values pass through. */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!isEncrypted(stored)) return stored
        return runCatching {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val body = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** The device's secret key, generated on first use and kept in the keystore from then on. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
