package com.ahmedgamil.wakili.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM wrap for the gateway token using an Android Keystore key — the token
 * is a credential and must not sit in plaintext preferences. The key never
 * leaves secure hardware; we store "enc:<iv>:<ciphertext>" (both base64).
 * Anything that fails to decrypt is treated as absent (the user reconnects).
 */
internal object TokenCipher {

    private const val ALIAS = "wakili_token_key"
    private const val PREFIX = "enc:"
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrDefault(plain) // keystore unavailable → store as-is rather than lose access

    fun decrypt(stored: String): String? {
        // Legacy plaintext (pre-encryption installs) passes through; save()
        // re-encrypts it on the next connect.
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val (ivPart, dataPart) = stored.removePrefix(PREFIX).split(':', limit = 2)
                .let { it[0] to it[1] }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, Base64.decode(ivPart, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(dataPart, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
