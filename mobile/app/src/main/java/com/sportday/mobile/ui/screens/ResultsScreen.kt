@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sportday.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
// The app ships a thin `OutlinedTextField` wrapper of its own; this screen wants
// the Material one (readOnly + trailingIcon), and an explicit import outranks a
// star import.
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sportday.mobile.data.api.ApiErrors
import com.sportday.mobile.data.model.EventDTO
import com.sportday.mobile.data.model.EventResultDTO
import com.sportday.mobile.data.model.EventStandingsDTO
import com.sportday.mobile.data.model.PlacingDTO
import com.sportday.mobile.data.repository.ResultsPdfDownloader
import com.sportday.mobile.data.repository.rememberPdfDownloader
import com.sportday.mobile.data.repository.rememberRepository
import com.sportday.mobile.data.repository.rememberTokenManager
import com.sportday.mobile.ui.components.*
import kotlinx.coroutines.launch

/**
 * A student's own results, and the sheet that goes with them.
 *
 * **The mark is the server's string.** `GET /api/results/user/{me}` answers with
 * `displayMark` already written — `14.123s` for a sprint, `1.04.123s` for the
 * 400M and over and for a relay, `18.12M` for a field event, `ABS`/`DQ` where
 * there was no mark — and this screen prints that string and nothing else. It
 * never re-derives the shape of a stopwatch time; the one home of `M.SS.mmm` is
 * `StopwatchTime` on the server, and a second formatter here would drift from it.
 *
 * **The placing is not on the result row**, so it comes from
 * `GET /api/events/{id}/standings`, which is also where `points` and the
 * school-record flag live (the web app's past-event view uses the same call).
 * A standings call that fails leaves the mark showing and simply omits the place
 * rather than failing the whole screen.
 *
 * ## What the By-event filters narrow, and where they run
 *
 * They narrow **the events the picker offers**, not the rows of a standings
 * table. A standings table is already a single thing: `GET
 * /api/events/{id}/standings` answers with one `sex`, one `category` and one
 * event, and every placing in it is that event's — so there is nothing left to
 * narrow inside it, and a chip there could not change a single row.
 * `GET /api/events/past`, which is the list this tab picks from, takes **no
 * query parameters at all**; the facets that do exist server-side (`sex` and
 * `category` on `GET /api/events`) live on a different endpoint, whose answer is
 * the whole programme rather than the events that have been run. Grade is not a
 * list filter anywhere on the server. So all three facets are applied here, over
 * the list the picker already fetches — which is what the web app's own
 * past-event view does with the same `GET /events/past` call.
 *
 * Because the filters can empty the picker, what is applied is always on screen
 * and the empty state names it: a blank list with no explanation reads as
 * missing data rather than as a filter doing its job.
 */
@Composable
fun ResultsScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }

    // ---- my results -------------------------------------------------------
    var myUserId by remember { mutableStateOf<Long?>(null) }
    var myResults by remember { mutableStateOf<List<EventResultDTO>>(emptyList()) }
    var standingsByEvent by remember { mutableStateOf<Map<Long, EventStandingsDTO>>(emptyMap()) }
    var isLoadingMine by remember { mutableStateOf(true) }
    var mineError by remember { mutableStateOf<String?>(null) }

    // ---- by event ---------------------------------------------------------
    var events by remember { mutableStateOf<List<EventDTO>>(emptyList()) }
    var selectedEventId by remember { mutableStateOf<Long?>(null) }
    var selectedStandings by remember { mutableStateOf<EventStandingsDTO?>(null) }
    var isLoadingEvent by remember { mutableStateOf(false) }
    var eventError by remember { mutableStateOf<String?>(null) }

    // The picker's three facets. `null` is "All" — no filter — and each is one of
    // the event's own facts, so a chip can only ever name an event the server
    // could have sent: `A`/`B`/`C`, `MALE`/`FEMALE`, `TRACK`/`FIELD`/`RELAY`.
    var gradeFilter by remember { mutableStateOf<String?>(null) }
    var sexFilter by remember { mutableStateOf<String?>(null) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }

    // ---- shared -----------------------------------------------------------
    var isOffline by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf<ResultsPdfDownloader.SavedPdf?>(null) }
    var pdfError by remember { mutableStateOf<String?>(null) }
    var isDownloadingPdf by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val repository = rememberRepository()
    val downloader = rememberPdfDownloader()
    val tokenManager = rememberTokenManager()

    suspend fun loadMine() {
        isLoadingMine = true
        mineError = null
        try {
            val userId = tokenManager.getUserId()
            myUserId = userId
            if (userId == null) {
                mineError = "This phone has no signed-in user. Sign out and sign in again."
                return
            }
            val response = repository.getResultsByUser(userId)
            if (!response.isSuccessful) {
                mineError = ApiErrors.message(response)
                isOffline = false
                myResults = emptyList()
                return
            }
            myResults = response.body().orEmpty()
            isOffline = false

            // One standings call per event the student actually has a result in —
            // never more than a handful, and each one brings the placing, the
            // points and the record flag. A failure is not fatal: the marks stand.
            val placings = mutableMapOf<Long, EventStandingsDTO>()
            myResults.mapNotNull { it.eventId }.distinct().forEach { eventId ->
                try {
                    val standings = repository.getStandings(eventId)
                    if (standings.isSuccessful) {
                        standings.body()?.let { placings[eventId] = it }
                    }
                } catch (e: Exception) {
                    // Leave this event's place blank.
                }
            }
            standingsByEvent = placings
        } catch (e: Exception) {
            mineError = ApiErrors.throwableMessage(e)
            isOffline = true
            myResults = emptyList()
        } finally {
            isLoadingMine = false
        }
    }

    suspend fun loadEvents() {
        try {
            // Past events first — results are things that have happened.
            val past = repository.getPastEvents()
            val list = if (past.isSuccessful && !past.body().isNullOrEmpty()) {
                past.body().orEmpty()
            } else {
                repository.getEvents(onlyEnabled = false).body().orEmpty()
            }
            events = list
            if (events.none { it.id == selectedEventId }) {
                selectedEventId = events.firstOrNull()?.id
            }
        } catch (e: Exception) {
            events = emptyList()
        }
    }

    suspend fun loadEvent(eventId: Long) {
        isLoadingEvent = true
        eventError = null
        try {
            val standings = repository.getStandings(eventId)
            if (standings.isSuccessful) {
                selectedStandings = standings.body()
                isOffline = false
            } else {
                eventError = ApiErrors.message(standings)
                isOffline = false
                selectedStandings = null
            }
        } catch (e: Exception) {
            eventError = ApiErrors.throwableMessage(e)
            isOffline = true
            selectedStandings = null
        } finally {
            isLoadingEvent = false
        }
    }

    // The events the chips leave. Each facet is a field the server already put on
    // the event, so this narrows data the phone holds rather than asking a
    // question the programme endpoint cannot answer — see the screen's comment.
    val filteredEvents = remember(events, gradeFilter, sexFilter, categoryFilter) {
        events.filter { event ->
            (gradeFilter == null || event.grade == gradeFilter) &&
                (sexFilter == null || event.sex == sexFilter) &&
                (categoryFilter == null || event.category == categoryFilter)
        }
    }
    /** `Track · A · Boys`, or null when nothing is applied. */
    val appliedFilters = filtersAsText(gradeFilter, sexFilter, categoryFilter)
    val isFiltered = appliedFilters != null

    LaunchedEffect(reloadToken) {
        loadMine()
        loadEvents()
    }

    // Keep the picked event inside the list the chips leave. A filter that hides
    // the current pick moves to the first event that survives it, and one that
    // hides everything picks nothing — so no standings stay on screen belonging
    // to an event the user can no longer see.
    LaunchedEffect(filteredEvents) {
        if (filteredEvents.none { it.id == selectedEventId }) {
            selectedEventId = filteredEvents.firstOrNull()?.id
        }
    }

    LaunchedEffect(selectedEventId) {
        val id = selectedEventId
        if (id == null) {
            selectedStandings = null
            eventError = null
        } else {
            loadEvent(id)
        }
    }

    /** Fetches a PDF and saves it, keeping the server's refusal when there is one. */
    fun fetchPdf(name: String, call: suspend () -> retrofit2.Response<okhttp3.ResponseBody>) {
        scope.launch {
            isDownloadingPdf = true
            pdfError = null
            try {
                when (val result = downloader.save(call(), name)) {
                    is ResultsPdfDownloader.PdfResult.Saved -> pdf = result.pdf
                    is ResultsPdfDownloader.PdfResult.Failed -> pdfError = result.message
                }
            } catch (e: Exception) {
                pdfError = ApiErrors.throwableMessage(e)
                isOffline = true
            } finally {
                isDownloadingPdf = false
            }
        }
    }

    val selectedEvent = events.firstOrNull { it.id == selectedEventId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Results") },
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
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("My results") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("By event") }
                )
            }

            if (tab == 1) {
                EventFilterBar(
                    grade = gradeFilter,
                    sex = sexFilter,
                    category = categoryFilter,
                    matched = filteredEvents.size,
                    total = events.size,
                    appliedFilters = appliedFilters,
                    onGrade = { gradeFilter = it },
                    onSex = { sexFilter = it },
                    onCategory = { categoryFilter = it },
                    onClear = {
                        gradeFilter = null
                        sexFilter = null
                        categoryFilter = null
                    }
                )
                EventPicker(
                    events = filteredEvents,
                    selectedEventId = selectedEventId,
                    emptyLabel = if (isFiltered) {
                        "No events match $appliedFilters"
                    } else {
                        "No events to show"
                    },
                    onSelect = { selectedEventId = it }
                )
            }

            // One place for the PDF, whichever tab is showing, so the file the
            // student just saved is never scrolled off the screen.
            OutlinedButton(
                onClick = {
                    val id = selectedEventId
                    if (tab == 0) {
                        fetchPdf(
                            ResultsPdfDownloader.fileNameFor(
                                "sportday-programme",
                                fallback = "sportday-programme",
                                suffix = "results"
                            )
                        ) { repository.downloadProgrammeResultsPdf() }
                    } else if (id != null) {
                        fetchPdf(
                            ResultsPdfDownloader.fileNameFor(
                                selectedEvent?.name,
                                fallback = "event-$id"
                            )
                        ) { repository.downloadEventResultsPdf(id) }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                enabled = !isDownloadingPdf && (tab == 0 || selectedEventId != null)
            ) {
                if (isDownloadingPdf) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        isDownloadingPdf -> "Fetching the PDF…"
                        tab == 0 -> "Download the whole programme (PDF)"
                        else -> "Download this event's results (PDF)"
                    }
                )
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
                        val failure = downloader.share(saved, "Send the results")
                        if (failure != null) pdfError = failure
                    }
                )
            }

            if (tab == 0) {
                MyResultsTab(
                    results = myResults,
                    userId = myUserId,
                    standingsByEvent = standingsByEvent,
                    isLoading = isLoadingMine,
                    errorMessage = mineError,
                    isOffline = isOffline,
                    onRetry = { reloadToken++ },
                    onDownloadSheet = { result ->
                        val id = result.eventId
                        if (id != null) {
                            fetchPdf(
                                ResultsPdfDownloader.fileNameFor(
                                    result.eventName,
                                    fallback = "event-$id"
                                )
                            ) { repository.downloadEventResultsPdf(id) }
                        }
                    }
                )
            } else if (events.isNotEmpty() && filteredEvents.isEmpty() && appliedFilters != null) {
                // The chips, not the results, are what emptied the tab, so the
                // screen says which of them did it rather than showing a table of
                // nothing that could be mistaken for an event with no marks.
                EmptyState(
                    icon = Icons.Default.FilterAlt,
                    title = "No events match $appliedFilters",
                    message = "The programme has ${events.size} past events, and none of them " +
                        "are $appliedFilters. Clear a filter to widen the list.",
                    actionLabel = "Clear filters",
                    onAction = {
                        gradeFilter = null
                        sexFilter = null
                        categoryFilter = null
                    }
                )
            } else {
                EventResultsTab(
                    standings = selectedStandings,
                    isLoading = isLoadingEvent,
                    errorMessage = eventError,
                    isOffline = isOffline,
                    onRetry = { selectedEventId?.let { id -> scope.launch { loadEvent(id) } } }
                )
            }
        }
    }
}

/**
 * The chips, as label-to-value. Every value is one the server itself puts on an
 * event — `EventDTO.grade`, `.sex` and `.category` — so a chip can only ever
 * name a distinction the programme draws.
 */
private val GRADE_OPTIONS = listOf("A" to "A", "B" to "B", "C" to "C")
private val SEX_OPTIONS = listOf("Boys" to "MALE", "Girls" to "FEMALE")
private val CATEGORY_OPTIONS = listOf("Track" to "TRACK", "Field" to "FIELD", "Relay" to "RELAY")

/**
 * `Track · A · Boys` — the filters as the chips spell them, or null when none is
 * applied. The summary line and both empty states read from this one function, so
 * the sentence on screen and the chips above it can never disagree.
 */
private fun filtersAsText(grade: String?, sex: String?, category: String?): String? {
    val parts = listOfNotNull(
        CATEGORY_OPTIONS.firstOrNull { it.second == category }?.first,
        grade,
        SEX_OPTIONS.firstOrNull { it.second == sex }?.first
    )
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/**
 * The three facets the picker can be narrowed by, and what is applied.
 *
 * The line beneath them always says how many events the chips leave, and offers
 * the way back out. A list that is short or empty with nothing on screen to
 * explain it reads as missing data rather than as a filter doing its job.
 */
@Composable
private fun EventFilterBar(
    grade: String?,
    sex: String?,
    category: String?,
    matched: Int,
    total: Int,
    appliedFilters: String?,
    onGrade: (String?) -> Unit,
    onSex: (String?) -> Unit,
    onCategory: (String?) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        FilterChipRow("Grade", GRADE_OPTIONS, grade, onGrade)
        FilterChipRow("Sex", SEX_OPTIONS, sex, onSex)
        FilterChipRow("Category", CATEGORY_OPTIONS, category, onCategory)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (appliedFilters == null) {
                    "$total past events"
                } else {
                    "$matched of $total events · $appliedFilters"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (appliedFilters != null) {
                TextButton(onClick = onClear) { Text("Clear filters") }
            }
        }
    }
}

/**
 * One facet as a row of chips: `All`, then the values the server can send. The
 * row scrolls rather than clipping, so a longer label can never push a value off
 * the screen where it could not be tapped.
 */
@Composable
private fun FilterChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") }
        )
        options.forEach { (text, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text) }
            )
        }
    }
}

@Composable
private fun EventPicker(
    events: List<EventDTO>,
    selectedEventId: Long?,
    emptyLabel: String,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        val selected = events.firstOrNull { it.id == selectedEventId }
        OutlinedTextField(
            value = selected?.name ?: if (events.isEmpty()) emptyLabel else "Select an event",
            onValueChange = {},
            readOnly = true,
            enabled = events.isNotEmpty(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = events.isNotEmpty())
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            events.forEach { event ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(event.name)
                            Text(
                                text = prettyDate(event.eventDate),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onSelect(event.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MyResultsTab(
    results: List<EventResultDTO>,
    userId: Long?,
    standingsByEvent: Map<Long, EventStandingsDTO>,
    isLoading: Boolean,
    errorMessage: String?,
    isOffline: Boolean,
    onRetry: () -> Unit,
    onDownloadSheet: (EventResultDTO) -> Unit
) {
    when {
        isLoading && results.isEmpty() -> LoadingIndicator()

        errorMessage != null -> ErrorCard(message = errorMessage, isOffline = isOffline, onRetry = onRetry)

        results.isEmpty() -> EmptyState(
            icon = Icons.Default.EmojiEvents,
            title = "No results yet",
            message = "Nothing has been recorded against your name. Once the marks for an event " +
                "you entered are keyed in, they appear here — with your mark, your placing and " +
                "whether it was a school record."
        )

        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { result ->
                val standings = result.eventId?.let { standingsByEvent[it] }
                val placing = standings?.placingFor(userId)
                MyResultCard(
                    result = result,
                    placing = placing,
                    fieldSize = standings?.placings?.size,
                    scoringStage = standings?.scoringStage,
                    onDownloadSheet = { onDownloadSheet(result) }
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/**
 * One of the student's own results.
 *
 * [EventResultDTO.displayMark] is printed exactly as it arrived. The placing, the
 * points and the record come from the event's standings when they were
 * available.
 */
@Composable
private fun MyResultCard(
    result: EventResultDTO,
    placing: PlacingDTO?,
    fieldSize: Int?,
    scoringStage: String?,
    onDownloadSheet: () -> Unit
) {
    val isRecord = result.newRecord == true || placing?.schoolRecord == true

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.eventName ?: "Event ${result.eventId ?: "?"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val subtitle = listOfNotNull(
                        result.stageLabel,
                        result.teamLabel?.let { "Team $it" }
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    // The server's own spelling of the mark. Never re-derived.
                    Text(
                        text = result.markText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (result.isNoMark) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    placing?.placeLabel?.let { place ->
                        Text(
                            text = if (fieldSize != null && fieldSize > 0) "$place of $fieldSize" else place,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (isRecord || result.isNoMark || placing != null && placing.points > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isRecord) {
                        StatePill("SCHOOL RECORD", MaterialTheme.colorScheme.tertiary)
                    }
                    if (result.isNoMark) {
                        StatePill(result.outcome ?: "NO MARK", MaterialTheme.colorScheme.error)
                    }
                    if (placing != null && placing.points > 0) {
                        StatePill("${placing.points} pts", MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (!result.attempts.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    // The attempts are the raw numbers the server sent; there is no
                    // per-attempt display string, so none is invented here.
                    text = "Attempts: " + result.attempts.joinToString(" · ") { it?.toString() ?: "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!result.notes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = result.notes, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("Recorded ")
                    append(prettyDateTime(result.recordedAt))
                    if (scoringStage != null) {
                        append(" · points decided by the ")
                        append(scoringStage.lowercase())
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onDownloadSheet, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download this event's sheet (PDF)")
            }
        }
    }
}

@Composable
private fun EventResultsTab(
    standings: EventStandingsDTO?,
    isLoading: Boolean,
    errorMessage: String?,
    isOffline: Boolean,
    onRetry: () -> Unit
) {
    when {
        isLoading && standings == null -> LoadingIndicator()

        errorMessage != null -> ErrorCard(message = errorMessage, isOffline = isOffline, onRetry = onRetry)

        standings == null || standings.placings.isEmpty() -> EmptyState(
            icon = Icons.Default.EmojiEvents,
            title = "No results for this event",
            message = "Nothing has been recorded for this event yet. The results sheet prints " +
                "once there are marks to print."
        )

        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = buildString {
                        append(standings.eventTypeLabel ?: standings.eventName ?: "Event")
                        listOfNotNull(standings.sexLabel, standings.categoryLabel)
                            .filter { it.isNotBlank() }
                            .forEach { append(" · ").append(it) }
                        append(" · points from the ")
                        append((standings.scoringStage ?: "heat").lowercase())
                        if (standings.relay) append(" · relay, scored by team")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            items(standings.placings) { placing ->
                StandingRow(placing = placing, relay = standings.relay)
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/** One line of an event's placings table. */
@Composable
private fun StandingRow(placing: PlacingDTO, relay: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = when (placing.place) {
                            1 -> MaterialTheme.colorScheme.tertiary
                            2 -> MaterialTheme.colorScheme.secondary
                            3 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = placing.place.toString(),
                    fontWeight = FontWeight.Bold,
                    color = if (placing.place in 1..3) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // On a relay the server names the row by its team; on an individual
                // event, by the athlete.
                Text(
                    text = placing.displayName ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                val detail = if (relay) {
                    listOfNotNull(placing.house, placing.className)
                } else {
                    listOfNotNull(placing.className, placing.grade?.let { "Grade $it" }, placing.house)
                }
                val detailText = detail.filter { it.isNotBlank() }.joinToString(" · ")
                if (detailText.isNotBlank()) {
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (placing.schoolRecord) {
                        StatePill("RECORD", MaterialTheme.colorScheme.tertiary)
                    }
                    if (placing.points > 0) {
                        StatePill("${placing.points} pts", MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(
                // The server's own spelling of the mark.
                text = placing.displayMark ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
