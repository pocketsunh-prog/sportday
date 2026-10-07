@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sportday.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sportday.mobile.data.api.ApiErrors
import com.sportday.mobile.data.model.EnrollmentDTO
import com.sportday.mobile.data.model.QuotaDTO
import com.sportday.mobile.data.repository.rememberRepository
import com.sportday.mobile.ui.components.*
import kotlinx.coroutines.launch

/**
 * What the student is entered in.
 *
 * The rows are the server's `EnrollmentDTO`, flattened — the event's own type,
 * division, grade, date and place travel beside the athlete's class, house and
 * heat, so a row can answer "where do I have to be, and in which heat?" without a
 * second call. Withdrawing is here as well as on the event, because a student who
 * has changed their mind is looking at this screen, not at the programme.
 */
@Composable
fun MyEnrollmentsScreen(
    onBack: () -> Unit,
    onEventClick: (Long) -> Unit,
    onBrowseEvents: () -> Unit
) {
    var entries by remember { mutableStateOf<List<EnrollmentDTO>>(emptyList()) }
    var quota by remember { mutableStateOf<QuotaDTO?>(null) }
    var includeWithdrawn by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isOffline by remember { mutableStateOf(false) }

    /** A refusal from a withdrawal, shown against the row it belongs to. */
    var actionError by remember { mutableStateOf<String?>(null) }
    var pendingWithdrawal by remember { mutableStateOf<EnrollmentDTO?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val repository = rememberRepository()

    suspend fun load() {
        isLoading = true
        errorMessage = null
        try {
            val response = if (includeWithdrawn) {
                repository.getMyEnrollmentHistory()
            } else {
                repository.getMyEnrollments()
            }
            if (!response.isSuccessful) {
                errorMessage = ApiErrors.message(response)
                isOffline = false
                entries = emptyList()
                return
            }
            entries = response.body().orEmpty()
            quota = try {
                val quotaResponse = repository.getMyQuota()
                if (quotaResponse.isSuccessful) quotaResponse.body() else null
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            errorMessage = ApiErrors.throwableMessage(e)
            isOffline = true
            entries = emptyList()
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(includeWithdrawn, reloadToken) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Entries") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { reloadToken++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            QuotaCard(quota = quota)

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = includeWithdrawn,
                    onClick = { includeWithdrawn = !includeWithdrawn },
                    label = { Text("Include withdrawn") }
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            if (actionError != null) {
                ErrorCard(message = actionError!!, isOffline = isOffline)
            }

            val confirmed = entries.filter { it.status == null || it.status == "CONFIRMED" }

            when {
                isLoading && entries.isEmpty() -> LoadingIndicator()

                errorMessage != null -> ErrorCard(
                    message = errorMessage!!,
                    isOffline = isOffline,
                    onRetry = { reloadToken++ }
                )

                confirmed.isEmpty() && entries.isEmpty() -> EmptyState(
                    icon = Icons.Default.BookmarkBorder,
                    title = "No entries yet",
                    message = "You are not entered in anything. Pick an event from the programme " +
                        "and tap Enter this event.",
                    actionLabel = "Browse the programme",
                    onAction = onBrowseEvents
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.id }) { entry ->
                        EnrollmentCard(
                            entry = entry,
                            onOpenEvent = { entry.eventId?.let(onEventClick) },
                            onWithdraw = { pendingWithdrawal = entry }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    val pending = pendingWithdrawal
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingWithdrawal = null },
            title = { Text("Withdraw?") },
            text = {
                Text(
                    "You will be withdrawn from ${pending.eventName ?: "this event"} and the place " +
                        "freed in your allowance. You can enter again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingWithdrawal = null
                    scope.launch {
                        actionError = null
                        try {
                            val eventId = pending.eventId
                            if (eventId == null) {
                                actionError = "This entry names no event, so it cannot be withdrawn here."
                                return@launch
                            }
                            val response = repository.cancelEnrollment(eventId)
                            if (response.isSuccessful) {
                                actionError = null
                                reloadToken++
                            } else {
                                actionError = ApiErrors.message(response)
                                isOffline = false
                            }
                        } catch (e: Exception) {
                            actionError = ApiErrors.throwableMessage(e)
                            isOffline = true
                        }
                    }
                }) { Text("Withdraw") }
            },
            dismissButton = {
                TextButton(onClick = { pendingWithdrawal = null }) { Text("Keep it") }
            }
        )
    }
}

/** One entry: the event, the heat, and the way out of it. */
@Composable
private fun EnrollmentCard(
    entry: EnrollmentDTO,
    onOpenEvent: () -> Unit,
    onWithdraw: () -> Unit
) {
    val withdrawn = entry.status != null && entry.status != "CONFIRMED"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(enabled = entry.eventId != null, onClick = onOpenEvent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.eventName ?: "Event ${entry.eventId ?: "?"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (withdrawn) {
                    StatePill("WITHDRAWN", MaterialTheme.colorScheme.outline)
                } else {
                    StatePill("ENTERED", MaterialTheme.colorScheme.secondary)
                }
            }

            val descriptor = listOfNotNull(entry.eventTypeLabel, entry.sexLabel, entry.grade)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (descriptor.isNotBlank()) {
                Text(
                    text = descriptor,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Date: ${prettyDate(entry.eventDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!entry.location.isNullOrBlank()) {
                Text(
                    text = "Place: ${entry.location}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.heatLabel ?: "Heat: not drawn yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!entry.relayTeamLabel.isNullOrBlank()) {
                Text(
                    text = "Relay team: ${entry.relayTeamLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!entry.heatDisplayMark.isNullOrBlank()) {
                // The server's own spelling of the heat mark.
                Text(
                    text = "Heat result: ${entry.heatDisplayMark}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = "Entered on ${prettyDateTime(entry.enrolledAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!withdrawn) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenEvent,
                        modifier = Modifier.weight(1f),
                        enabled = entry.eventId != null
                    ) { Text("Open event") }
                    TextButton(
                        onClick = onWithdraw,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Withdraw") }
                }
            }
        }
    }
}
