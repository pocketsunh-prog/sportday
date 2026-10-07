@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sportday.mobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sportday.mobile.data.model.QuotaDTO

@Composable
fun SportDayTopBar(title: String, onLogout: (() -> Unit)? = null) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        actions = {
            if (onLogout != null) {
                TextButton(onClick = onLogout) {
                    Text("Logout")
                }
            }
        }
    )
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * A failure, as the server put it.
 *
 * [message] is whatever the server's error body carried — a quota refusal, a
 * closed event, a 403 — and must be shown word for word. [onRetry] adds the one
 * action that can help; [isOffline] only changes the icon, never the words.
 */
@Composable
fun ErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    isOffline: Boolean = false,
    retryLabel: String = "Try again"
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isOffline) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isOffline) "Can't reach the server" else "The server refused this",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text(retryLabel) }
            }
        }
    }
}

/** Kept for the screens that only want the sentence, in a plain card. */
@Composable
fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    ErrorCard(message = message, modifier = modifier)
}

/**
 * A screen with nothing on it. Every list gets one: an empty screen with no
 * words on it cannot be told apart from a crash.
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** `Grade` / `Division` / `Entries` — one line of an event's facts. */
@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(132.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

/** A small rounded label — `Entered`, `Full`, `Record`, `ABS`. */
@Composable
fun StatePill(
    text: String,
    container: Color,
    content: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Surface(color = container, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * The entry allowance, straight from `GET /api/enrollments/my/quota`.
 *
 * The numbers are the server's; the phone does not count entries itself, so a
 * screen can never disagree with the refusal it is about to get.
 */
@Composable
fun QuotaCard(
    quota: QuotaDTO?,
    modifier: Modifier = Modifier,
    highlightCategory: String? = null
) {
    if (quota == null) return

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Your entry allowance",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            QuotaLine("Track", quota.trackUsed, quota.trackMax, highlightCategory == "TRACK")
            Spacer(modifier = Modifier.height(6.dp))
            QuotaLine("Field", quota.fieldUsed, quota.fieldMax, highlightCategory == "FIELD")
        }
    }
}

@Composable
private fun QuotaLine(label: String, used: Int, max: Int, highlight: Boolean) {
    val fraction = if (max <= 0) 0f else (used.toFloat() / max).coerceIn(0f, 1f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.width(56.dp)
            )
            Text(
                text = "$used of $max entered",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (used >= max) "None left" else "${max - used} left",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (used >= max) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isPassword: Boolean = false
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * `2025-06-12T10:30:00` reads as `2025-06-12 10:30`.
 *
 * Presentation only — no number is re-derived here. A mark is always printed
 * from the server's own `displayMark`.
 */
fun prettyDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return iso.replace('T', ' ').take(16)
}

/** `2025-06-12`, or `—` when the server sent nothing. */
fun prettyDate(iso: String?): String =
    if (iso.isNullOrBlank()) "—" else iso.take(10)

/** `412 KB`, for telling the student what was saved. */
fun prettyBytes(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else "${(bytes / 1024).coerceAtLeast(1)} KB"

/**
 * A results PDF that is on the phone.
 *
 * Without this the file would be saved and the student would have no way to open
 * it — the app's own directories are not browsable on a modern Android. Open
 * hands it to the phone's PDF viewer; Share lets it leave the phone.
 */
@Composable
fun SavedPdfCard(
    pdf: com.sportday.mobile.data.repository.ResultsPdfDownloader.SavedPdf,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Results PDF saved",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${pdf.location} · ${prettyBytes(pdf.bytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}
