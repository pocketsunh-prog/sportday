package com.sportday.mobile.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The fingerprint/face half of signing in.
 *
 * A fingerprint authenticates nobody to our server. The API still trades a
 * username and password for a JWT and there is no biometric factor on the
 * server side, so all the sensor can honestly do is release a credential that
 * is already on this phone: the JWT is sealed with an AES-256/GCM key that
 * lives in the Android Keystore, and the Keystore hands that key to nobody
 * until a `BIOMETRIC_STRONG` authentication has succeeded. Without a matching
 * finger the stored bytes are inert — a fingerprint guarding a readable copy of
 * the token would protect nothing.
 *
 * The password is never written down anywhere, by this class or any other.
 */
class BiometricAuthManager(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "sportday_biometric_signin_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }

    // ---------------------------------------------------------------- sensing

    /**
     * What the phone can actually do, which decides whether fingerprint sign-in
     * is offered at all. `BIOMETRIC_STRONG` only: a Class 2 face unlock cannot
     * be bound to a Keystore key, so it cannot guard the stored credential.
     */
    fun availability(): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.NO_STRONG_BIOMETRIC
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.SECURITY_UPDATE_REQUIRED
            else -> BiometricAvailability.UNKNOWN
        }

    /** The one sentence the student reads for each of those. */
    fun availabilityMessage(availability: BiometricAvailability): String = when (availability) {
        BiometricAvailability.AVAILABLE ->
            ""
        BiometricAvailability.NO_HARDWARE ->
            "This phone has no fingerprint or face sensor, so fingerprint sign-in cannot be used. Sign in with your password."
        BiometricAvailability.HARDWARE_UNAVAILABLE ->
            "The fingerprint sensor is not available at the moment. Try again, or sign in with your password."
        BiometricAvailability.NOT_ENROLLED ->
            "No fingerprint or face is set up on this phone, so the saved sign-in cannot be released. Add one in Settings, or sign in with your password."
        BiometricAvailability.NO_STRONG_BIOMETRIC ->
            "This phone's face or fingerprint unlock is not strong enough to protect a saved sign-in. Sign in with your password."
        BiometricAvailability.SECURITY_UPDATE_REQUIRED ->
            "Android needs a security update before fingerprint sign-in can be used on this phone. Sign in with your password."
        BiometricAvailability.UNKNOWN ->
            "Fingerprint sign-in is not available on this phone. Sign in with your password."
    }

    /**
     * Where a student with no fingerprint enrolled is sent. `ACTION_FINGERPRINT_ENROLL`
     * on the older releases, the biometric enrolment screen on API 30+.
     */
    fun enrolIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_FINGERPRINT_ENROLL)
        }

    // ------------------------------------------------------------------ keys

    /**
     * A key for a credential that is being stored right now.
     *
     * Any older key is destroyed first: after a finger is added or removed, a
     * new credential must never be sealed in a way the old set of fingers could
     * still open, and the old ciphertext must never be openable by the new.
     */
    fun encryptCipher(): Cipher {
        deleteKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // The key is useless until a finger has been accepted.
            .setUserAuthenticationRequired(true)
            // And it dies with the fingers it was made for, so a token sealed for
            // an old set can never be released by a new one.
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        generator.init(spec)
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, generator.generateKey()) }
    }

    /**
     * A cipher that can only be used after the matching fingerprint has been
     * accepted. `Unusable(invalidated = true)` is the "a finger was enrolled
     * after this credential was stored" case: the Keystore has destroyed the
     * key and the student has to fall back to the password.
     */
    fun decryptCipher(iv: ByteArray): CipherOutcome {
        return try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = store.getKey(KEY_ALIAS, null) as? SecretKey
                ?: return CipherOutcome.Unusable(invalidated = false)
            CipherOutcome.Ready(
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                }
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            CipherOutcome.Unusable(invalidated = true)
        } catch (e: UnrecoverableKeyException) {
            CipherOutcome.Unusable(invalidated = true)
        } catch (e: GeneralSecurityException) {
            CipherOutcome.Unusable(invalidated = false)
        }
    }

    /** Called when the credential is turned off, forgotten or found to be dead. */
    fun deleteKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // A key that cannot be reached is no better than one that is gone.
        }
    }

    // ----------------------------------------------------------------- bytes

    /** Runs after the fingerprint has been accepted, with the unlocked cipher. */
    fun seal(cipher: Cipher, token: String): SealedToken =
        SealedToken(cipher.iv, cipher.doFinal(token.toByteArray(Charsets.UTF_8)))

    /** `null` when the blob does not belong to this key, e.g. after tampering. */
    fun open(cipher: Cipher, sealed: SealedToken): String? =
        try {
            String(cipher.doFinal(sealed.ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        }

    // ---------------------------------------------------------------- prompt

    /** Offered at launch, and whenever the student asks to unlock again. */
    fun unlockPromptInfo(): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock SportDay")
            .setSubtitle("Your saved sign-in is released by your fingerprint")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            // Required, and wanted: biometric-only prompts must offer the way out.
            .setNegativeButtonText("Use password")
            .setConfirmationRequired(false)
            .build()

    /** Offered once, after a password login, to store the credential. */
    fun enablePromptInfo(): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Turn on fingerprint sign-in")
            .setSubtitle("Confirm your fingerprint to seal this sign-in on the phone")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Not now")
            .setConfirmationRequired(false)
            .build()

    /** One sentence per thing that actually happens, instead of one for all of them. */
    fun describeError(errorCode: Int): String = when (errorCode) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
            "You chose to sign in with your password."
        BiometricPrompt.ERROR_USER_CANCELED ->
            "Fingerprint sign-in was cancelled. Sign in with your password, or tap Use fingerprint to try again."
        BiometricPrompt.ERROR_CANCELED ->
            "The fingerprint prompt was dismissed by Android. Try again, or sign in with your password."
        BiometricPrompt.ERROR_LOCKOUT ->
            "Too many fingerprints did not match, so the sensor is locked for 30 seconds. Wait and try again, or sign in with your password."
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
            "The fingerprint sensor is locked out until you unlock the phone with its PIN, pattern or password. Sign in with your SportDay password below."
        BiometricPrompt.ERROR_NO_BIOMETRICS ->
            "No fingerprint or face is enrolled on this phone any more, so the saved sign-in was deleted. Sign in with your password."
        BiometricPrompt.ERROR_HW_NOT_PRESENT ->
            "This phone has no fingerprint or face sensor. Sign in with your password."
        BiometricPrompt.ERROR_HW_UNAVAILABLE ->
            "The fingerprint sensor is unavailable right now. Try again, or sign in with your password."
        BiometricPrompt.ERROR_TIMEOUT ->
            "The fingerprint prompt timed out. Tap Use fingerprint to try again, or sign in with your password."
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
            "Android needs a security update before fingerprint sign-in can be used. Sign in with your password."
        BiometricPrompt.ERROR_UNABLE_TO_PROCESS ->
            "The fingerprint sensor could not read that. Try again, or sign in with your password."
        else ->
            "Fingerprint sign-in did not work (code $errorCode). Sign in with your password."
    }
}

/** What the phone can do about biometrics, one value per distinct message. */
enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NOT_ENROLLED,
    NO_STRONG_BIOMETRIC,
    SECURITY_UPDATE_REQUIRED,
    UNKNOWN
}

/** A JWT as it sits on disk: an IV and a GCM-sealed blob, both inert without the Keystore key. */
class SealedToken(val iv: ByteArray, val ciphertext: ByteArray)

/** The non-secret half of a stored credential — enough to greet the student by name. */
data class BiometricIdentity(
    val username: String,
    val fullName: String,
    val role: String,
    val userId: Long
)

/** Whether a prompt can be shown, and why not when it cannot. */
sealed class CipherOutcome {
    class Ready(val cipher: Cipher) : CipherOutcome()

    /** No key, or the key a new fingerprint destroyed. */
    class Unusable(val invalidated: Boolean) : CipherOutcome()
}
