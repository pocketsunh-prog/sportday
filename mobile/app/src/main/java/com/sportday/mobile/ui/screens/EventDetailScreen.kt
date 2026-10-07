@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sportday.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.sportday.mobile.data.model.EventDTO
import com.sportday.mobile.data.model.QuotaDTO
import com.sportday.mobile.data.repository.ResultsPdfDownloader
import com.sportday.mobile.data.repository.rememberPdfDownloader
import com.sportday.mobile.data.repository.rememberRepository
import com.sportday.mobile.ui.components.*
import kotlinx.coroutines.launch

/**
 * One event, and the one decision about it: enter, or leave.
 *
 * The screen is built around the two facts a student needs before tapping —
 * **am I in it** (from `GET /api/enrollments/my`, which also carries the heat and
 * lane once the draw has run) and **how much allowance is left** (from
 * `GET /api/enrollments/my/quota`) — and around the rule that when the server
 * refuses, *its* sentence is what appears. `POST /api/enrollments/{id}` answers a
 * full event with "This event is full (30 entries).", a student over their
 * allowance with "You have already entered 2 徑項 event(s), which is the maximum
 * (徑項: 2).", and a closed one with "This event is closed — it has been disabled
 * by the organiser." Those are shown word for word; none of them becomes
 * "Something went wrong".
 */
@Composable
fun EventDetailScreen(eventId: Long, onBack: () -> Unit) {
    var event by remember { mutableStateOf<EventDTO?>(null) }
    var myEntry by remember { mutableStateOf<EnrollmentDTO?>(null) }
    var quota by remember { mutableStateOf<QuotaDTO?>(null) }

    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isOffline by remember { mutableStateOf(false) }

    // What the server said to the last Enter / Withdraw, word for word.
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionNotice by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var confirmWithdraw by remember { mutableStateOf(false) }

    var pdf by remember { mutableStateOf<ResultsPdfDownloader.SavedPdf?>(null) }
    var pdfError by remember { mutableStateOf<String?>(null) }
    var isDownloadingPdf by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val repository = rememberRepository()
    val downloader = rememberPdfDownloader()

    suspend fun load() {
        isLoading = true
        loadError = null
        try {
            val response = repository.getEvent(eventId)
            if (!response.isSuccessful) {
                loadError = ApiErrors.message(response)
                isOffline = false
                return
            }
            event = response.body()
            isOffline = false

            // The student's own entries — the server's answer to "am I in this?".
            // A failure here leaves the buttons working; the enrol call itself is
            // what the server judges.
            myEntry = try {
                val mine = repository.getMyEnrollments()
                if (mine.isSuccessful) mine.body().orEmpty().firstOrNull { it.eventId == eventId } else null
            } catch (e: Exception) {
                null
            }

            quota = try {
                val quotaResponse = repository.getMyQuota()
                if (quotaResponse.isSuccessful) quotaResponse.body() else null
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            loadError = ApiErrors.throwableMessage(e)
            isOffline = true
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(eventId, reloadToken) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event") },
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
        when {
            isLoading && event == null -> LoadingIndicator(Modifier.padding(padding))

            loadError != null -> Column(Modifier.fillMaxSize().padding(padding)) {
                ErrorCard(
                    message = loadError!!,
                    isOffline = isOffline,
                    onRetry = { reloadToken++ }
                )
            }

            event == null -> EmptyState(
                icon = Icons.Default.EventBusy,
                title = "Event not found",
                message = "The server has no event with this number. It may have been removed."
            )

            else -> {
                val current = event!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (myEntry != null) {
                            StatePill("YOU ARE ENTERED", MaterialTheme.colorScheme.secondary)
                        }
                        if (!current.enabled) {
                            StatePill("CLOSED", MaterialTheme.colorScheme.error)
                        } else if (current.maxParticipants != null && current.maxParticipants > 0 &&
                            (current.enrolledCount ?: 0) >= current.maxParticipants
                        ) {
                            StatePill("FULL", MaterialTheme.colorScheme.error)
                        }
                    }

                    if (!current.description.isNullOrBlank()) {
                        Text(
                            text = current.description,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SectionTitle("The event")
                            DetailRow("Type", current.typeLabel ?: current.type ?: "—")
                            DetailRow("Category", current.categoryLabel ?: current.category ?: "—")
                            DetailRow("Division", current.sexLabel ?: current.sex ?: "—")
                            DetailRow("Grade", current.gradeLabel ?: current.formLabel ?: "—")
                            if (current.relay == true) {
                                DetailRow(
                                    "Relay",
                                    listOfNotNull(
                                        current.relayTeamKindLabel,
                                        current.relayTeamSize?.let { "$it legs" }
                                    ).joinToString(" · ").ifBlank { "Yes" }
                                )
                            }
                            DetailRow("Date", prettyDate(current.eventDate))
                            DetailRow("Place", current.location ?: "—")
                            if (current.carriesStandard == true && !current.standardLabel.isNullOrBlank()) {
                                DetailRow("Standard", current.standardLabel)
                            }
                            DetailRow(
                                "Entries",
                                if (current.maxParticipants != null && current.maxParticipants > 0) {
                                    "${current.enrolledCount ?: 0} of ${current.maxParticipants} places taken"
                                } else {
                                    "${current.enrolledCount ?: 0} entered"
                                }
                            )
                            if (current.maxEntriesPerStudent != null && current.maxEntriesPerStudent > 0) {
                                DetailRow("Allowed each", "up to ${current.maxEntriesPerStudent} of this category")
                            }
                        }
                    }

                    if (myEntry != null) {
                        val entry = myEntry!!
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                SectionTitle("Your entry")
                                DetailRow("Entered as", entry.name ?: "—")
                                val klass = listOfNotNull(entry.className, entry.grade?.let { "Grade $it" })
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                if (klass.isNotBlank()) DetailRow("Class", klass)
                                if (!entry.house.isNullOrBlank()) DetailRow("House", entry.house!!)
                                if (!entry.relayTeamLabel.isNullOrBlank()) {
                                    DetailRow("Relay team", entry.relayTeamLabel!!)
                                }
                                DetailRow("Entered on", prettyDateTime(entry.enrolledAt))
                                DetailRow(
                                    "Heat",
                                    entry.heatLabel
                                        ?: "Not drawn yet — heats are published before the day"
                                )
                                if (!entry.heatDisplayMark.isNullOrBlank()) {
                                    // The server's own spelling of the heat mark.
                                    DetailRow("Heat result", entry.heatDisplayMark!!)
                                }
                            }
                        }
                    }

                    QuotaCard(quota = quota, highlightCategory = current.category)

                    if (actionError != null) {
                        ErrorCard(
                            message = actionError!!,
                            isOffline = isOffline,
                            onRetry = null
                        )
                    }
                    if (actionNotice != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(text = actionNotice!!, modifier = Modifier.padding(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (myEntry != null) {
                        Button(
                            onClick = { confirmWithdraw = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            enabled = !isSubmitting
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Withdraw from this event")
                        }
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    isSubmitting = true
                                    actionError = null
                                    actionNotice = null
                                    try {
                                        val response = repository.enroll(eventId)
                                        if (response.isSuccessful) {
                                            myEntry = response.body()
                                            actionNotice =
                                                "You are entered in ${current.name}."
                                            isOffline = false
                                            // The count and the allowance both moved.
                                            val refreshed = repository.getEvent(eventId)
                                            if (refreshed.isSuccessful) event = refreshed.body()
                                            val quotaResponse = repository.getMyQuota()
                                            if (quotaResponse.isSuccessful) quota = quotaResponse.body()
                                        } else {
                                            // The quota refusal, "this event is full",
                                            // "this event is closed", "already entered" —
                                            // the server's sentence, untouched.
                                            actionError = ApiErrors.message(response)
                                            isOffline = false
                                        }
                                    } catch (e: Exception) {
                                        actionError = ApiErrors.throwableMessage(e)
                                        isOffline = true
                                    } finally {
                                        isSubmitting = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            enabled = !isSubmitting
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Enter this event")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    // ---- Results, once they exist ------------------------------
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SectionTitle("Results")
                        Text(
                            text = "The results sheet for this event, as the board prints it — " +
                                "place, name, class, house, the mark as it reads and the points.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isDownloadingPdf = true
                                pdfError = null
                                try {
                                    val response = repository.downloadEventResultsPdf(eventId)
                                    when (val result = downloader.save(
                                        response,
                                        ResultsPdfDownloader.fileNameFor(
                                            current.name,
                                            fallback = "event-$eventId"
                                        )
                                    )) {
                                        is ResultsPdfDownloader.PdfResult.Saved -> pdf = result.pdf
                                        // Usually the server's own 409, "This event has
                                        // no results recorded yet, so there is nothing to
                                        // print." — shown as it stands.
                                        is ResultsPdfDownloader.PdfResult.Failed -> pdfError = result.message
                                    }
                                } finally {
                                    isDownloadingPdf = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        enabled = !isDownloadingPdf
                    ) {
                        if (isDownloadingPdf) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isDownloadingPdf) "Fetching the PDF…" else "Download results sheet (PDF)")
                    }

                    if (pdfError != null) {
                        ErrorCard(message = pdfError!!, isOffline = false)
                    }
                    pdf?.let { saved ->
                        SavedPdfCard(
                            pdf = saved,
                            onOpen = {
                                val failure = downloader.open(saved)
                                if (failure != null) pdfError = failure
                            },
                            onShare = {
                                val failure = downloader.share(saved, "Send the results sheet")
                                if (failure != null) pdfError = failure
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    if (confirmWithdraw) {
        AlertDialog(
            onDismissRequest = { confirmWithdraw = false },
            title = { Text("Withdraw?") },
            text = {
                Text(
                    "Your entry in ${event?.name ?: "this event"} will be cancelled and the " +
                        "place in your allowance freed. You can enter again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWithdraw = false
                    scope.launch {
                        isSubmitting = true
                        actionError = null
                        actionNotice = null
                        try {
                            val response = repository.cancelEnrollment(eventId)
                            if (response.isSuccessful) {
                                myEntry = null
                                actionNotice = "You have withdrawn from ${event?.name ?: "the event"}."
                                val refreshed = repository.getEvent(eventId)
                                if (refreshed.isSuccessful) event = refreshed.body()
                                val quotaResponse = repository.getMyQuota()
                                if (quotaResponse.isSuccessful) quota = quotaResponse.body()
                            } else {
                                actionError = ApiErrors.message(response)
                            }
                        } catch (e: Exception) {
                            actionError = ApiErrors.throwableMessage(e)
                            isOffline = true
                        } finally {
                            isSubmitting = false
                        }
                    }
                }) { Text("Withdraw") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWithdraw = false }) { Text("Keep my entry") }
            }
        )
    }
}
