package ai.hans.standard.voice.tts.android

import android.content.Context
import android.os.Build
import android.security.KeyStoreException as AndroidKeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.annotation.RequiresApi
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the Speech API credential with a non-exportable Android Keystore
 * key. Only ciphertext and the random GCM IV are stored in private app data.
 */
class AndroidKeystoreSpeechCredentialStore internal constructor(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : BearerTokenSource {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )
    private val failureRecovery = SpeechCredentialFailureRecovery(
        SpeechCredentialFailureClassifier(::classifyFailure),
    )
    private var lastStatus = SpeechCredentialStatus.MISSING

    @Synchronized
    override fun loadBearerToken(): String? {
        val version = preferences.getInt(KEY_VERSION, 0)
        val encodedIv = preferences.getString(KEY_IV, null)
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
        if (version != ENVELOPE_VERSION || encodedIv == null || encodedCiphertext == null) {
            lastStatus = SpeechCredentialStatus.MISSING
            return null
        }
        return try {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
            require(iv.size == GCM_IV_BYTES)
            require(ciphertext.size in 1..MAX_CIPHERTEXT_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                val token = plaintext.toString(StandardCharsets.UTF_8)
                validateToken(token)
                lastStatus = SpeechCredentialStatus.AVAILABLE
                token
            } finally {
                plaintext.fill(0)
            }
        } catch (failure: Exception) {
            // Locked-device and temporarily unavailable Keystore states fail
            // closed but preserve the envelope. Only a proven invalid pair is
            // cleared; otherwise an ordinary lock could destroy a valid token.
            lastStatus = failureRecovery.recoverReadFailure(failure) {
                clearInternal(deleteKey = true)
            }
            null
        }
    }

    @Synchronized
    fun credentialStatus(): SpeechCredentialStatus {
        loadBearerToken()
        return lastStatus
    }

    @Synchronized
    fun hasCredential(): Boolean = credentialStatus() == SpeechCredentialStatus.AVAILABLE

    @Synchronized
    fun saveBearerToken(token: String) {
        val characters = token.toCharArray()
        try {
            saveBearerToken(characters)
        } finally {
            characters.fill('\u0000')
        }
    }

    /**
     * Preferred UI boundary. API credentials are printable ASCII, so converting directly to a
     * byte buffer avoids creating another immutable plaintext String which could outlive the
     * credential dialog.
     */
    @Synchronized
    fun saveBearerToken(token: CharArray) {
        validateToken(token)
        val plaintext = ByteArray(token.size) { index -> token[index].code.toByte() }
        try {
            val envelope = try {
                encrypt(plaintext)
            } catch (failure: Exception) {
                if (!failureRecovery.shouldClearBeforeReplacing(failure)) {
                    lastStatus = SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE
                    throw IllegalStateException("speech_credential_temporarily_unavailable")
                }
                // Only an invalidated/missing prior key or corrupt envelope is
                // replaced. Transient lock/service failures preserve it.
                clearInternal(deleteKey = true)
                encrypt(plaintext)
            }
            try {
                check(
                    preferences.edit()
                        .clear()
                        .putInt(KEY_VERSION, ENVELOPE_VERSION)
                        .putString(KEY_IV, Base64.encodeToString(envelope.iv, Base64.NO_WRAP))
                        .putString(
                            KEY_CIPHERTEXT,
                            Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                        )
                        .commit(),
                ) { "speech_credential_persistence_failed" }
                lastStatus = SpeechCredentialStatus.AVAILABLE
            } finally {
                envelope.iv.fill(0)
                envelope.ciphertext.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun clear() {
        clearInternal(deleteKey = true)
        lastStatus = SpeechCredentialStatus.MISSING
    }

    private fun getOrCreateKey(): SecretKey {
        existingKeyOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val specification = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply {
                    // Android documents platform bugs for this authorization
                    // on API 31-34 and recommends enabling it only on API 35+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        setUnlockedDeviceRequired(true)
                    }
                }
                .build()
        generator.init(specification)
        return generator.generateKey()
    }

    private fun encrypt(plaintext: ByteArray): EncryptedEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES)
        check(ciphertext.size in 1..MAX_CIPHERTEXT_BYTES)
        return EncryptedEnvelope(iv, ciphertext)
    }

    private fun existingKey(): SecretKey {
        return existingKeyOrNull() ?: throw CredentialMaterialInvalidException()
    }

    private fun existingKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(keyAlias, null) ?: return null
        return key as? SecretKey ?: throw CredentialMaterialInvalidException()
    }

    private fun clearInternal(deleteKey: Boolean) {
        preferences.edit().clear().commit()
        if (deleteKey) {
            runCatching {
                KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
            }
        }
    }

    private fun validateToken(token: String) {
        require(token.length in MIN_TOKEN_CHARACTERS..MAX_TOKEN_CHARACTERS) {
            "speech_credential_length_invalid"
        }
        require(token.all { it.code in 0x21..0x7e }) {
            "speech_credential_characters_invalid"
        }
    }

    private fun validateToken(token: CharArray) {
        require(token.size in MIN_TOKEN_CHARACTERS..MAX_TOKEN_CHARACTERS) {
            "speech_credential_length_invalid"
        }
        require(token.all { it.code in 0x21..0x7e }) {
            "speech_credential_characters_invalid"
        }
    }

    private fun classifyFailure(failure: Throwable): SpeechCredentialFailureDisposition {
        var current: Throwable? = failure
        while (current != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                classifyApi33KeystoreFailure(current)?.let { return it }
            }
            when (current) {
                is UserNotAuthenticatedException ->
                    return SpeechCredentialFailureDisposition.PRESERVE_FOR_RETRY

                is KeyPermanentlyInvalidatedException,
                is AEADBadTagException,
                is UnrecoverableKeyException,
                is CredentialMaterialInvalidException,
                is IllegalArgumentException,
                -> return SpeechCredentialFailureDisposition.CLEAR_INVALID_MATERIAL
            }
            current = current.cause
        }
        // Unknown provider/service failures fail closed without data loss.
        return SpeechCredentialFailureDisposition.PRESERVE_FOR_RETRY
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun classifyApi33KeystoreFailure(
        failure: Throwable,
    ): SpeechCredentialFailureDisposition? {
        if (failure !is AndroidKeyStoreException) return null
        // Both an authenticated-but-locked key and a temporarily unavailable
        // Keystore service are retryable states, never proof of corrupt data.
        return SpeechCredentialFailureDisposition.PRESERVE_FOR_RETRY
    }

    private data class EncryptedEnvelope(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private class CredentialMaterialInvalidException : Exception()

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "hans_speech_credential_v1"
        private const val DEFAULT_KEY_ALIAS = "ai.hans.standard.speech-api.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MIN_TOKEN_CHARACTERS = 16
        private const val MAX_TOKEN_CHARACTERS = 1_024
        private const val MAX_CIPHERTEXT_BYTES = MAX_TOKEN_CHARACTERS + 64
    }
}
