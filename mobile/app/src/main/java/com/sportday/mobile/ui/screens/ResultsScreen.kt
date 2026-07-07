package com.sportday.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sportday.mobile.data.model.EventResultDTO
import com.sportday.mobile.data.repository.rememberRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(onBack: () -> Unit) {
    var results by remember { mutableStateOf<List<EventResultDTO>>(emptyList()) }
    var events by remember { mutableStateOf<List<com.sportday.mobile.data.model.EventDTO>>(emptyList()) }
    var selectedEventId by remember { mutableStateOf<Long?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val repository = rememberRepository()

    LaunchedEffect(Unit) {
        try {
            val eventsResponse = repository.getEvents(true)
            if (eventsResponse.isSuccessful) {
                events = eventsResponse.body() ?: emptyList()
                if (events.isNotEmpty()) {
                    selectedEventId = events.first().id
                }
            }
        } catch (e: Exception) {
            errorMessage = "Error: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(selectedEventId) {
        if (selectedEventId != null) {
            try {
                val response = repository.getResultsByEvent(selectedEventId!!)
                if (response.isSuccessful) {
                    results = response.body() ?: emptyList()
                }
            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Event selector
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                val selectedEvent = events.find { it.id == selectedEventId }
                OutlinedTextField(
                    value = selectedEvent?.name ?: "Select Event",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    events.forEach { event ->
                        DropdownMenuItem(
                            text = { Text(event.name) },
                            onClick = {
                                selectedEventId = event.id
                                expanded = false
                            }
                        )
                    }
                }
            }

            when {
                isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                errorMessage != null -> Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(text = errorMessage!!, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
                results.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results for this event")
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(results.withIndex().toList()) { (index, result) ->
                            ResultCard(result = result, rank = index + 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResultCard(result: EventResultDTO, rank: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = when (rank) {
                    1 -> MaterialTheme.colorScheme.tertiary
                    2 -> MaterialTheme.colorScheme.secondary
                    3 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.width(50.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = result.fullName ?: result.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = "Mark: ${result.mark}${if (!result.unit.isNullOrBlank()) " ${result.unit}" else ""}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                if (!result.notes.isNullOrBlank()) {
                    Text(text = result.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
