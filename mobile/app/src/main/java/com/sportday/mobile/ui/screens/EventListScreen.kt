@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import com.sportday.mobile.data.api.ApiErrors
import com.sportday.mobile.data.model.EventDTO
import com.sportday.mobile.data.model.QuotaDTO
import com.sportday.mobile.data.repository.rememberRepository
import com.sportday.mobile.ui.components.EmptyState
import com.sportday.mobile.ui.components.ErrorCard
import com.sportday.mobile.ui.components.LoadingIndicator
import com.sportday.mobile.ui.components.QuotaCard
import com.sportday.mobile.ui.components.StatePill
import com.sportday.mobile.ui.components.prettyDate

/**
 * The programme, and the way in to an event.
 *
 * The list answers the three questions a student has before tapping anything:
 * *what is this event* (type, division, grade, date, place, how many are in it),
 * *am I already in it* — from `GET /api/enrollments/my`, the server's answer, not
 * a guess — and *how many entries have I left*, from
 * `GET /api/enrollments/my/quota`, again the server's numbers.
 */
@Composable
fun EventListScreen(
    onEventClick: (Long) -> Unit,
    onNavigateToEnrollments: () -> Unit,
    onNavigateToResults: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onLogout: () -> Unit
) {
    var events by remember { mutableStateOf<List<EventDTO>>(emptyList()) }
    var myEventIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var quota by remember { mutableStateOf<QuotaDTO?>(null) }
    var openEventsOnly by remember { mutableStateOf(true) }
    var category by remember { mutableStateOf<String?>(null) }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isOffline by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }

    val repository = rememberRepository()

    suspend fun load() {
        isLoading = true
        errorMessage = null
        try {
            val eventsResponse = repository.getEvents(onlyEnabled = openEventsOnly, category = category)
            if (!eventsResponse.isSuccessful) {
                // The server's own words — an unknown category, an expired session.
                errorMessage = ApiErrors.message(eventsResponse)
                isOffline = false
                events = emptyList()
                return
            }
            events = eventsResponse.body().orEmpty()

            // Which of these the student is already in. A failure here is not fatal:
            // the list still stands, it just cannot badge the rows.
            myEventIds = try {
                val mine = repository.getMyEnrollments()
                if (mine.isSuccessful) {
                    mine.body().orEmpty().mapNotNull { it.eventId }.toSet()
                } else {
                    emptySet()
                }
            } catch (e: Exception) {
                emptySet()
            }

            quota = try {
                val quotaResponse = repository.getMyQuota()
                if (quotaResponse.isSuccessful) quotaResponse.body() else null
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            errorMessage = ApiErrors.throwableMessage(e)
            isOffline = true
            events = emptyList()
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(openEventsOnly, category, reloadToken) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Programme") },
                actions = {
                    IconButton(onClick = { reloadToken++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    TextButton(onClick = onLogout) { Text("Logout") }
                }
            )
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
                    label = { Text("My Entries") }
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
            QuotaCard(quota = quota)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = openEventsOnly,
                    onClick = { openEventsOnly = !openEventsOnly },
                    label = { Text("Open for entry") },
                    leadingIcon = if (openEventsOnly) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryChip("All", null, category) { category = it }
                CategoryChip("Track", "TRACK", category) { category = it }
                CategoryChip("Field", "FIELD", category) { category = it }
                CategoryChip("Relay", "RELAY", category) { category = it }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                isLoading && events.isEmpty() -> LoadingIndicator()

                errorMessage != null -> ErrorCard(
                    message = errorMessage!!,
                    isOffline = isOffline,
                    onRetry = { reloadToken++ }
                )

                events.isEmpty() -> EmptyState(
                    icon = Icons.Default.EventBusy,
                    title = if (openEventsOnly) "No events open for entry" else "No events yet",
                    message = if (openEventsOnly) {
                        "Either nothing is on the programme yet, or the organiser has closed it. " +
                            "Turn off \"Open for entry\" to see events that are closed."
                    } else {
                        "The organiser has not put any events on the programme yet."
                    },
                    actionLabel = if (openEventsOnly) "Show closed events too" else null,
                    onAction = if (openEventsOnly) ({ openEventsOnly = false }) else null
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(events, key = { it.id }) { event ->
                        EventCard(
                            event = event,
                            isEntered = myEventIds.contains(event.id),
                            quota = quota,
                            onClick = { onEventClick(event.id) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    value: String?,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(label) }
    )
}

/**
 * One event on the programme.
 *
 * Every figure on it comes from the server: `typeLabel` / `sexLabel` /
 * `gradeLabel` are its printable names, `enrolledCount` and `maxParticipants`
 * are its own count, and `maxEntriesPerStudent` is what the settings allow — so
 * the card and the refusal that may follow it are quoting the same source.
 */
@Composable
fun EventCard(
    event: EventDTO,
    isEntered: Boolean,
    quota: QuotaDTO?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
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
                if (isEntered) {
                    StatePill("ENTERED", MaterialTheme.colorScheme.secondary)
                } else if (!event.enabled) {
                    StatePill("CLOSED", MaterialTheme.colorScheme.error)
                } else if (event.maxParticipants != null && event.maxParticipants > 0 &&
                    (event.enrolledCount ?: 0) >= event.maxParticipants
                ) {
                    StatePill("FULL", MaterialTheme.colorScheme.error)
                }
            }

            val descriptor = listOfNotNull(event.typeLabel, event.sexLabel, event.gradeLabel ?: event.formLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (descriptor.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = descriptor,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Date: ${prettyDate(event.eventDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!event.location.isNullOrBlank()) {
                Text(
                    text = "Place: ${event.location}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (event.carriesStandard == true && !event.standardLabel.isNullOrBlank()) {
                Text(
                    text = "Standard: ${event.standardLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            EntriesLine(event.enrolledCount, event.maxParticipants, event.maxEntriesPerStudent)

            // A relay whose teams are not built yet. The sentence is the server's
            // own readinessReason, which is also what a mark or print attempt is
            // refused with.
            if (event.relay == true && event.relayReady == false && !event.readinessReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.readinessReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // The allowance, stated as a fact about the numbers the server gave us.
            // It is deliberately not worded as a refusal — the refusal is whatever
            // the server says when the student actually taps Enter.
            val left = quota?.remainingFor(event.category)
            if (!isEntered && left != null && left <= 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your ${event.categoryLabel ?: event.category} allowance is already used up " +
                        "(${quota.summary}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun EntriesLine(enrolled: Int?, max: Int?, maxPerStudent: Int?) {
    val count = enrolled ?: 0
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (max != null && max > 0) "$count / $max entered" else "$count entered",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            if (maxPerStudent != null && maxPerStudent > 0) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "up to $maxPerStudent per student",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (max != null && max > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (count.toFloat() / max).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
