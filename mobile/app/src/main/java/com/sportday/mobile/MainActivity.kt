package com.sportday.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sportday.mobile.data.api.ApiClient
import com.sportday.mobile.ui.navigation.SportDayNavHost
import com.sportday.mobile.ui.theme.SportDayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.initialize(applicationContext)
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
