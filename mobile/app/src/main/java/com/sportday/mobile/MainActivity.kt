package com.sportday.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sportday.mobile.data.api.ApiClient
import com.sportday.mobile.ui.navigation.SportDayNavHost
import com.sportday.mobile.ui.theme.SportDayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FragmentActivity, not ComponentActivity: `androidx.biometric.BiometricPrompt`
 * shows its prompt through the FragmentManager on API 24-27 and takes a
 * FragmentActivity everywhere. Everything the app already did with the activity
 * (setContent, enableEdgeToEdge) still works unchanged.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.initialize(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            ApiClient.loadBaseUrl()
        }
        enableEdgeToEdge()
        setContent {
            SportDayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SportDayNavHost()
                }
            }
        }
    }
}
