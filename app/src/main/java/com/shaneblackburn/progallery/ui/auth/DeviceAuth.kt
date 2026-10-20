package com.shaneblackburn.progallery.ui.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Device authentication (fingerprint/face, or the screen-lock PIN/pattern/password as
 * fallback) used to reveal hidden albums.
 */
object DeviceAuth {

    sealed interface Result {
        data object Success : Result
        data object Cancelled : Result
        data class Unavailable(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun availability(context: Context): Result? =
        when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                Result.Unavailable("Set a screen lock (PIN, pattern or password) in Android Settings first.")
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                Result.Unavailable("Device authentication isn't available right now.")
            else -> Result.Unavailable("Device authentication isn't supported on this device.")
        }

    fun authenticate(context: Context, title: String, subtitle: String, onResult: (Result) -> Unit) {
        availability(context)?.let { onResult(it); return }
        val activity = context.findFragmentActivity() ?: run {
            onResult(Result.Unavailable("Authentication needs an activity")); return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(Result.Success)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    onResult(if (cancelled) Result.Cancelled else Result.Failed(errString.toString()))
                }
                // onAuthenticationFailed = a single bad attempt; the prompt stays open, so ignore.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
}
