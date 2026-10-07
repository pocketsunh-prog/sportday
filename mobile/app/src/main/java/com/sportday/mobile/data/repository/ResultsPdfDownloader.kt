package com.sportday.mobile.data.repository

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.sportday.mobile.data.api.ApiErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.io.InputStream

/**
 * Saves a results PDF somewhere the student can actually open it, and hands back
 * something to open it *with*.
 *
 * **Where it goes.** On Android 10 (API 29) and up the file is inserted into
 * `MediaStore.Downloads`, so it lands in the phone's public
 * `Download/SportDay/` folder and shows up in Files — and, because it is created
 * by this app, **no storage permission is needed at all**. On API 24–28
 * `MediaStore.Downloads` does not exist; writing to the public Downloads folder
 * there would need `WRITE_EXTERNAL_STORAGE`, which this app deliberately does
 * not ask for, so the file goes to the app's own external files directory and is
 * served to a viewer through a `FileProvider`. `minSdk` is 24 and `targetSdk`
 * is 35, so no `WRITE_EXTERNAL_STORAGE` is declared: it is ignored from API 29
 * and would be a permission the app cannot use.
 *
 * **Why not `DownloadManager`.** `DownloadManager` never hands back the
 * response body of a refusal, and these endpoints refuse with a sentence that
 * matters — a 409 "This event has no results recorded yet, so there is nothing
 * to print." from `PdfResultService`, a 403 for an account that may not read the
 * sheet. Enqueuing would turn every one of those into the same generic
 * "download failed". Fetching through the authenticated Retrofit client and
 * writing the bytes here keeps the server's own sentence intact — which is the
 * rule the results screens have to obey.
 */
class ResultsPdfDownloader(private val context: Context) {

    /** A PDF that is on the phone, with the URI to open or share it by. */
    data class SavedPdf(
        val uri: Uri,
        /** Where it went, for the screen to tell the student, e.g. `Download/SportDay/x.pdf`. */
        val location: String,
        val bytes: Long
    )

    sealed interface PdfResult {
        data class Saved(val pdf: SavedPdf) : PdfResult

        /** Nothing was saved. [message] is ready to show, and is usually the server's own. */
        data class Failed(val message: String) : PdfResult
    }

    /**
     * Streams [response] to a file named [fileName].
     *
     * The response is consumed exactly once, on a background thread, so a refusal
     * body can be read for its sentence and a success body copied straight to
     * disk without the whole PDF ever being held in memory.
     */
    suspend fun save(response: Response<ResponseBody>, fileName: String): PdfResult =
        withContext(Dispatchers.IO) {
            if (!response.isSuccessful) {
                // The server's own sentence for a 403/404/409. Show it as it stands.
                return@withContext PdfResult.Failed(ApiErrors.message(response))
            }
            val body = response.body()
                ?: return@withContext PdfResult.Failed(
                    "The server answered with no file. Try again, or check the results on the web app."
                )

            try {
                body.byteStream().use { stream ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveViaMediaStore(stream, fileName)
                    } else {
                        saveViaFileProvider(stream, fileName)
                    }
                }
            } catch (e: Exception) {
                PdfResult.Failed(ApiErrors.throwableMessage(e))
            }
        }

    /** Android 10+ — the public Downloads folder, through MediaStore. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(stream: InputStream, fileName: String): PdfResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_PDF)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
            )
            // Hide the half-written file from other apps until it is complete.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: return PdfResult.Failed("The phone would not make a place to save the file.")

        try {
            val written = resolver.openOutputStream(uri)?.use { out -> stream.copyCounting(out) }
                ?: return PdfResult.Failed("The phone would not open the file for writing.")

            if (written.notPdf) {
                resolver.delete(uri, null, null)
                return PdfResult.Failed(NOT_A_PDF)
            }

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            return PdfResult.Saved(
                SavedPdf(uri, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$fileName", written.bytes)
            )
        } catch (e: Exception) {
            // A half-written row would otherwise linger in the Downloads list.
            runCatching { resolver.delete(uri, null, null) }
            return PdfResult.Failed(ApiErrors.throwableMessage(e))
        }
    }

    /**
     * API 24–28 — the app's own external files directory. Always writable, needs
     * no permission on any version, and is served out through a `FileProvider`.
     */
    private fun saveViaFileProvider(stream: InputStream, fileName: String): PdfResult {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, FOLDER) }
            ?: File(context.filesDir, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) {
            return PdfResult.Failed("The phone would not make a folder to save the file in.")
        }

        val target = File(dir, fileName)
        val written = try {
            target.outputStream().use { out -> stream.copyCounting(out) }
        } catch (e: Exception) {
            runCatching { target.delete() }
            return PdfResult.Failed(ApiErrors.throwableMessage(e))
        }

        if (written.notPdf) {
            runCatching { target.delete() }
            return PdfResult.Failed(NOT_A_PDF)
        }

        return PdfResult.Saved(
            SavedPdf(uriFor(target), "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$fileName", written.bytes)
        )
    }

    /** The URI a viewer or a share target may read this app-private file by. */
    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Hands the PDF to whatever the phone opens PDFs with.
     *
     * @return null when the viewer opened, or a sentence to show when no app on
     *         the phone could take it.
     */
    fun open(pdf: SavedPdf): String? = launch(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(pdf.uri, MIME_PDF)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "No app on this phone can open a PDF. Use Share to send it somewhere that can."
    )

    /**
     * The share sheet, so the file can leave the phone — mail it to yourself,
     * put it in Drive, send it to a teacher.
     *
     * @return null when the chooser opened, or a sentence to show when there was
     *         nothing to share with.
     */
    fun share(pdf: SavedPdf, title: String): String? = launch(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(MIME_PDF)
                .putExtra(Intent.EXTRA_STREAM, pdf.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            title
        ),
        "No app on this phone can share a file."
    )

    private fun launch(intent: Intent, failure: String): String? {
        // Compose's LocalContext is the Activity, but guard anyway: an intent
        // started from anything else needs its own task.
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            failure
        } catch (e: SecurityException) {
            failure
        }
    }

    private class Counting(val bytes: Long, val notPdf: Boolean)

    /**
     * Copies the stream, counting the bytes and checking the first four are
     * `%PDF`. A 200 that is not a PDF means something between the app and the
     * server answered for it — a login page, a proxy — and saving that as
     * "results.pdf" would be worse than saying so.
     */
    private fun InputStream.copyCounting(out: java.io.OutputStream): Counting {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        var checked = false
        var notPdf = false
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            if (!checked) {
                checked = true
                notPdf = read < 4 || !(buffer[0] == '%'.code.toByte() &&
                    buffer[1] == 'P'.code.toByte() &&
                    buffer[2] == 'D'.code.toByte() &&
                    buffer[3] == 'F'.code.toByte())
            }
            out.write(buffer, 0, read)
            total += read
        }
        out.flush()
        return Counting(total, notPdf)
    }

    companion object {
        const val MIME_PDF = "application/pdf"

        /** How the folder is named in the phone's Downloads. */
        const val FOLDER = "SportDay"

        private const val NOT_A_PDF =
            "The server did not answer with a PDF — it may have sent an error page instead. " +
                "Nothing was saved."

        /**
         * A file name made from the event's name, the way the web app names it:
         * `Boys 100M · B Grade` becomes `boys-100m-b-grade`. Letters and digits
         * of any script survive, so a Chinese event name keeps its name.
         */
        fun fileNameFor(eventName: String?, fallback: String, suffix: String = "results"): String {
            val slug = eventName.orEmpty()
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .split('-')
                .filter { it.isNotEmpty() }
                .joinToString("-")
                .take(60)
            val stem = slug.ifBlank { fallback }
            return "$stem-$suffix.pdf"
        }
    }
}
