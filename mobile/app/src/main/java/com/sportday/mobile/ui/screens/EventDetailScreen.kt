package com.sportday.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sportday.mobile.data.model.EventDTO
import com.sportday.mobile.data.repository.rememberRepository
import com.sportday.mobile.data.repository.rememberTokenManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(eventId: Long, onBack: () -> Unit) {
    var event by remember { mutableStateOf<EventDTO?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isEnrolled by remember { mutableStateOf(false) }
    var isEnrolling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repository = rememberRepository()
    val tokenManager = rememberTokenManager()

    LaunchedEffect(eventId) {
        try {
            val response = repository.getEvent(eventId)
            if (response.isSuccessful) {
                event = response.body()
                val checkResponse = repository.checkEnrollment(eventId)
                if (checkResponse.isSuccessful) {
                    isEnrolled = checkResponse.body() ?: false
                }
            } else {
                errorMessage = "Failed to load event"
            }
        } catch (e: Exception) {
            errorMessage = "Error: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            errorMessage != null -> {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(text = errorMessage!!, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            event != null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = event!!.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    DetailRow("Type", event!!.type)
                    DetailRow("Date", event!!.eventDate)
                    DetailRow("Location", event!!.location ?: "N/A")
                    DetailRow("Max Participants", event!!.maxParticipants.toString())
                    DetailRow("Status", if (event!!.enabled) "Enabled" else "Disabled")
                    if (!event!!.description.isNullOrBlank()) {
                        Text(text = event!!.description!!, style = MaterialTheme.typography.bodyLarge)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isEnrolled) {
                        Button(
                            onClick = {
                                isEnrolling = true
                                scope.launch {
                                    try {
                                        val response = repository.cancelEnrollment(eventId)
                                        if (response.isSuccessful) {
                                            isEnrolled = false
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "Error: ${e.message}"
                                    } finally {
                                        isEnrolling = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            enabled = !isEnrolling
                        ) {
                            Text("Cancel Enrollment")
                        }
                        Text("You are enrolled in this event", color = MaterialTheme.colorScheme.secondary)
                    } else {
                        Button(
                            onClick = {
                                isEnrolling = true
                                scope.launch {
                                    try {
                                        val response = repository.enroll(eventId)
                                        if (response.isSuccessful) {
                                            isEnrolled = true
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "Error: ${e.message}"
                                    } finally {
                                        isEnrolling = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isEnrolling
                        ) {
                            Text("Enroll")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row {
        Text(text = "$label: ", fontWeight = FontWeight.Bold, modifier = Modifier.width(140.dp))
        Text(text = value)
    }
}
