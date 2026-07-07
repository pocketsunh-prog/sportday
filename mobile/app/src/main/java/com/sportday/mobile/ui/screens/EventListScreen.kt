package com.sportday.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
fun EventListScreen(
    onEventClick: (Long) -> Unit,
    onNavigateToEnrollments: () -> Unit,
    onNavigateToResults: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onLogout: () -> Unit
) {
    var events by remember { mutableStateOf<List<EventDTO>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showOnlyEnabled by remember { mutableStateOf(true) }
    var userRole by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val repository = rememberRepository()
    val tokenManager = rememberTokenManager()

    LaunchedEffect(showOnlyEnabled) {
        try {
            isLoading = true
            val response = repository.getEvents(showOnlyEnabled)
            if (response.isSuccessful) {
                events = response.body() ?: emptyList()
                errorMessage = null
            } else {
                errorMessage = "Failed to load events"
            }
        } catch (e: Exception) {
            errorMessage = "Error: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        userRole = tokenManager.getRole()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Events") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Event, contentDescription = null) },
                    label = { Text("Events") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToEnrollments,
                    icon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                    label = { Text("My Enrollments") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToResults,
                    icon = { Icon(Icons.Default.EmojiEvents, contentDescription = null) },
                    label = { Text("Results") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToProfile,
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Profile") }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = showOnlyEnabled,
                    onClick = { showOnlyEnabled = !showOnlyEnabled },
                    label = { Text("Show only enabled") },
                    leadingIcon = if (showOnlyEnabled) { { Icon(Icons.Default.Check, contentDescription = null) } } else null
                )
            }

            when {
                isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                errorMessage != null -> ErrorMessageCard(errorMessage!!)
                events.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No events available") }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(events, key = { it.id }) { event ->
                            EventCard(event = event, onClick = { onEventClick(event.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorMessageCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(text = message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
fun EventCard(event: EventDTO, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (event.enabled) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text("Enabled", modifier = Modifier.padding(horizontal = 4.dp)) }
                } else {
                    Badge(containerColor = MaterialTheme.colorScheme.error) { Text("Disabled", modifier = Modifier.padding(horizontal = 4.dp)) }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Type: ${event.type}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = "Date: ${event.eventDate}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!event.location.isNullOrBlank()) {
                Text(text = "Location: ${event.location}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text = "Max Participants: ${event.maxParticipants}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.enrolledCount != null) {
                Text(text = "Enrolled: ${event.enrolledCount}/${event.maxParticipants}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
