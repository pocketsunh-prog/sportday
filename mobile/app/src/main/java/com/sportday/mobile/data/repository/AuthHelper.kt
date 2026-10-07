package com.sportday.mobile.data.repository

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberTokenManager(): TokenManager {
    val context = LocalContext.current
    return remember { TokenManager(context) }
}

@Composable
fun rememberRepository(): SportDayRepository {
    return remember { SportDayRepository() }
}

/** The fingerprint/face half of sign-in: Keystore, availability and the prompt. */
@Composable
fun rememberBiometricAuthManager(): BiometricAuthManager {
    val context = LocalContext.current
    return remember { BiometricAuthManager(context) }
}
