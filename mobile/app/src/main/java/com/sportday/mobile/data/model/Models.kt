package com.sportday.mobile.data.model

/**
 * The shapes the SportDay server actually sends.
 *
 * Every field a screen might not find is nullable: Gson leaves an absent JSON key
 * at its Kotlin default, and a non-null type with no default would then throw
 * inside `toString()` — a blank line is a far better answer than a crash.
 *
 * The server also sends the *formatted* strings beside the raw ones
 * (`typeLabel`, `categoryLabel`, `displayMark`, `standardLabel`, `groupLabel`).
 * Those are what the screens read, so the phone never spells a mark or an enum
 * name a second time — see `MarkFormatter` / `StopwatchTime` on the server, the
 * one home of "1.04.123".
 */

data class AuthRequest(
    val username: String,
    val password: String
)

data class AuthResponse(
    val token: String,
    val username: String,
    val role: String,
    val fullName: String,
    val userId: Long
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String,
    val fullName: String? = null,
    val age: Int? = null,
    val gender: String? = null
)

/**
 * `GET /api/events` — one event on the programme (the server's `EventDTO`).
 *
 * `typeLabel` / `categoryLabel` / `sexLabel` / `gradeLabel` are the printable
 * names; `type` and the raw enum names are kept only for filtering a list the
 * phone already has.
 */
data class EventDTO(
    val id: Long,
    val name: String,
    val description: String? = null,
    /** Enum name, e.g. `RUN_100M`. */
    val type: String? = null,
    /** Printable event name, e.g. `100M`. */
    val typeLabel: String? = null,
    /** `TRACK`, `FIELD` or `RELAY`. */
    val category: String? = null,
    val categoryLabel: String? = null,
    /** `MALE` or `FEMALE` — the division. */
    val sex: String? = null,
    val sexLabel: String? = null,
    /** `A`, `B` or `C` — the one grade that competes. */
    val grade: String? = null,
    val gradeLabel: String? = null,
    val form: String? = null,
    val formLabel: String? = null,
    /** e.g. `64.123 s` — the qualifying mark, for the events that carry one. */
    val standard: java.math.BigDecimal? = null,
    val carriesStandard: Boolean? = null,
    val standardLabel: String? = null,
    val eventDate: String? = null,
    val location: String? = null,
    val maxParticipants: Int? = null,
    /** `s` for a track event, `M` for a field one. */
    val defaultUnit: String? = null,
    val relay: Boolean? = null,
    /** `FORM` or `HOUSE` — how a relay's teams are divided. Null is normal. */
    val relayTeamKind: String? = null,
    val relayTeamKindLabel: String? = null,
    /** Legs in a team — four for a 4x100M or a 4x400M. */
    val relayTeamSize: Int? = null,
    /** False while a relay's teams are short — see [readinessReason]. */
    val relayReady: Boolean? = null,
    /** The server's own sentence for why a relay cannot be run yet. */
    val readinessReason: String? = null,
    val enabled: Boolean = false,
    val createdAt: String? = null,
    /** How many are entered. */
    val enrolledCount: Int? = null,
    /** How many of this category one student may enter. */
    val maxEntriesPerStudent: Int? = null
) {
    /** `100M · Boys · B Grade`, skipping whatever the server left out. */
    val headline: String
        get() = listOfNotNull(typeLabel ?: name, sexLabel, gradeLabel ?: formLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /** `12 / 30 entered`, or null when the server did not say. */
    val entriesLabel: String?
        get() {
            val count = enrolledCount ?: return null
            val max = maxParticipants
            return if (max == null || max <= 0) "$count entered" else "$count / $max entered"
        }

    /** True when the event is open and has room, as far as the list can tell. */
    val appearsOpen: Boolean
        get() = enabled && (maxParticipants == null || maxParticipants <= 0 ||
                (enrolledCount ?: 0) < maxParticipants)
}

/**
 * `POST /api/enrollments/{eventId}` and `GET /api/enrollments/my` — one entry,
 * flattened (the server's `EnrollmentDTO`). Note it is *not* a nested
 * `{ user, event }`: the server lifts the event's own fields up beside the
 * athlete's.
 */
data class EnrollmentDTO(
    val id: Long,
    val eventId: Long? = null,
    val eventName: String? = null,
    val eventType: String? = null,
    val eventTypeLabel: String? = null,
    val category: String? = null,
    val categoryLabel: String? = null,
    val defaultUnit: String? = null,
    val sex: String? = null,
    val sexLabel: String? = null,
    val eventDate: String? = null,
    val location: String? = null,
    /** The heat the athlete has been drawn into, once the draw has run. */
    val groupId: Long? = null,
    val groupNumber: Int? = null,
    val groupLabel: String? = null,
    val lane: Int? = null,
    val sheetSize: String? = null,
    val studentId: Long? = null,
    val userId: Long? = null,
    val studentRef: String? = null,
    /** The athlete's name, from the register. */
    val name: String? = null,
    val grade: String? = null,
    val className: String? = null,
    val form: String? = null,
    val house: String? = null,
    val houseCode: String? = null,
    /** `CONFIRMED` or `CANCELLED`. */
    val status: String? = null,
    val enrolledAt: String? = null,
    val heatMark: java.math.BigDecimal? = null,
    val heatOutcome: String? = null,
    /** The heat performance as it reads — `11.86s`, `1.04.123s`. */
    val heatDisplayMark: String? = null,
    /** The relay team this athlete runs for — `1A`, `C Grade Yellow`. */
    val relayTeamLabel: String? = null
) {
    /** `100M · Boys · B Grade` for the event this entry is in. */
    val eventHeadline: String
        get() = listOfNotNull(eventTypeLabel ?: eventName, sexLabel, grade)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    /** `Heat 2 · Lane 5`, or null before the draw has run. */
    val heatLabel: String?
        get() {
            val group = groupLabel ?: groupNumber?.let { "Heat $it" }
            return when {
                group != null && lane != null -> "$group · Lane $lane"
                group != null -> group
                lane != null -> "Lane $lane"
                else -> null
            }
        }
}

/**
 * `GET /api/enrollments/my/quota` — how much of the entry allowance is left.
 * The server owns these numbers (`EnrollmentService.Quota`); the phone only
 * reads them, and the refusal the server sends when one runs out is shown as it
 * stands.
 */
data class QuotaDTO(
    val trackUsed: Int = 0,
    val trackMax: Int = 0,
    val trackRemaining: Int = 0,
    val fieldUsed: Int = 0,
    val fieldMax: Int = 0,
    val fieldRemaining: Int = 0
) {
    val isLoaded: Boolean get() = trackMax > 0 || fieldMax > 0

    /** `Track 1/2 · Field 0/1`. */
    val summary: String get() = "Track $trackUsed/$trackMax · Field $fieldUsed/$fieldMax"

    /** How many more entries of this category are allowed. */
    fun remainingFor(category: String?): Int? = when (category) {
        "TRACK" -> trackRemaining
        "FIELD" -> fieldRemaining
        else -> null
    }
}

/**
 * `GET /api/results/event/{id}` and `GET /api/results/user/{id}` (the server's
 * `EventResultDTO`).
 *
 * [displayMark] is the whole point of this DTO: the server has already written
 * `14.123s`, `1.04.123s`, `18.12M` — or `ABS`/`DQ` — so the phone prints that
 * string and never re-derives the shape of a stopwatch time.
 */
data class EventResultDTO(
    val id: Long,
    val userId: Long? = null,
    val username: String? = null,
    val fullName: String? = null,
    val eventId: Long? = null,
    val eventName: String? = null,
    /** The relay team this row is a time *for*; null on an individual event. */
    val teamId: Long? = null,
    val teamLabel: String? = null,
    /** `HEAT` or `FINAL`. */
    val stage: String? = null,
    val mark: Double? = null,
    val unit: String? = null,
    /** `RESULT`, `ABS` or `DQ`. */
    val outcome: String? = null,
    /** `14.123s`, `1.04.123s`, `18.12M`, or `ABS` / `DQ`. */
    val displayMark: String? = null,
    val notes: String? = null,
    /** A field athlete's three attempts, a missed one left absent. */
    val attempts: List<Double?>? = null,
    /** True when this performance is the current school record. */
    val newRecord: Boolean? = null,
    val recordedAt: String? = null
) {
    /** The mark as it should be printed — the server's string, or a dash. */
    val markText: String get() = displayMark ?: mark?.toString() ?: "—"

    val isNoMark: Boolean get() = outcome == "ABS" || outcome == "DQ"

    val stageLabel: String?
        get() = when (stage) {
            "HEAT" -> "Heat"
            "FINAL" -> "Final"
            else -> null
        }
}

/**
 * `GET /api/events/{eventId}/standings` (the server's
 * `ChampionsDTO.EventStandingsDTO`) — who finished where, and what each place is
 * worth. This is where a *placing* comes from; a result row carries a mark but
 * not a position.
 */
data class EventStandingsDTO(
    val eventId: Long? = null,
    val eventName: String? = null,
    val eventType: String? = null,
    val eventTypeLabel: String? = null,
    val category: String? = null,
    val categoryLabel: String? = null,
    val sex: String? = null,
    val sexLabel: String? = null,
    val eventDate: String? = null,
    /** `FINAL` or `HEAT` — whichever decided the points. */
    val scoringStage: String? = null,
    val relay: Boolean = false,
    val hasFinal: Boolean = false,
    val sheetSize: String? = null,
    val placings: List<PlacingDTO> = emptyList()
) {
    /** The placing of one athlete, or null when they did not place. */
    fun placingFor(userId: Long?): PlacingDTO? =
        if (userId == null) null else placings.firstOrNull { it.userId == userId }
}

/** One athlete's (or one relay team's) finishing position in one event. */
data class PlacingDTO(
    val place: Int = 0,
    val userId: Long? = null,
    val studentRef: String? = null,
    val name: String? = null,
    val teamId: Long? = null,
    val teamLabel: String? = null,
    val grade: String? = null,
    val className: String? = null,
    val form: String? = null,
    val house: String? = null,
    val houseCode: String? = null,
    val mark: Double? = null,
    val unit: String? = null,
    /** `14.123s`, `1.04.123s` — the server's own spelling of the mark. */
    val displayMark: String? = null,
    val points: Int = 0,
    /** True when this placing also holds the school record. */
    val schoolRecord: Boolean = false
) {
    /** The team's name on a relay, the athlete's otherwise. */
    val displayName: String? get() = name ?: teamLabel

    /** `#1`, or null when the row carries no place. */
    val placeLabel: String? get() = if (place > 0) "#$place" else null
}

data class UserDTO(
    val id: Long,
    val username: String,
    val email: String,
    val fullName: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val role: String,
    val enabled: Boolean,
    val createdAt: String? = null
)

data class RecordResultRequest(
    val userId: Long,
    val eventId: Long,
    val mark: String,
    val unit: String?,
    val notes: String?
)

/**
 * The body the server's `GlobalExceptionHandler` sends with every 4xx/5xx:
 * `{ timestamp, status, error, message }`. [message] is the sentence the school
 * wrote — "You have already entered 2 徑項 event(s)…" — and is shown as it
 * stands rather than being replaced with "Something went wrong".
 */
data class ApiErrorBody(
    val timestamp: String? = null,
    val status: Int? = null,
    val error: String? = null,
    val message: String? = null
)
