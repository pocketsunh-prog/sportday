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
