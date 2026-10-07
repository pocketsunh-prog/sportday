package com.sportday.mobile.data.api

import com.google.gson.Gson
import com.sportday.mobile.data.model.ApiErrorBody
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turning a failed call into the sentence a person should read.
 *
 * The rule this exists to keep: **when the server said something, that is what
 * the screen shows.** The server's `GlobalExceptionHandler` writes
 * `{"status":409,"message":"You have already entered 2 徑項 event(s), which is
 * the maximum (徑項: 2)."}` for a quota refusal, and a closed event, a full
 * event and a not-ready relay each arrive as their own sentence. None of them
 * may be replaced with "Something went wrong" — the athlete needs to know which
 * one it was in order to do anything about it.
 *
 * Only when the body carries no sentence at all does this compose one, and even
 * then it says what actually happened.
 */
object ApiErrors {

    private val gson = Gson()

    /** The message to show for a non-2xx response, server's words first. */
    fun message(response: Response<*>): String {
        val code = response.code()

        // Read the body *once* — an OkHttp error body is a one-shot stream.
        val raw = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }

        val fromServer = parseMessage(raw)
        if (!fromServer.isNullOrBlank()) {
            return fromServer
        }

        // No sentence from the server. Say what the status means rather than
        // leaving the athlete with a bare number.
        return when (code) {
            400 -> "The server rejected the request (400)."
            401 -> "Your session has expired — sign in again."
            403 -> "The server refused this (403): this account is not allowed to do that."
            404 -> "The server has no such record (404) — it may have been removed."
            405 -> "The server does not allow that request (405)."
            409 -> "The server refused the change (409)."
            413 -> "That file is too large for the server (413)."
            500, 502, 503, 504 -> "The server hit a problem (HTTP $code). Try again in a moment."
            else -> "The server refused the request (HTTP $code)."
        }
    }

    /**
     * The message to show when the call never reached the server — no network,
     * wrong address, or a timeout. Nothing was refused here, so this is the one
     * case where the sentence is ours.
     */
    fun throwableMessage(t: Throwable): String = when (t) {
        is UnknownHostException -> offlineMessage()
        is ConnectException -> offlineMessage()
        is SocketTimeoutException ->
            "The SportDay server at ${ApiClient.getBaseUrl()} took too long to answer. " +
                "Check the address under Server settings and try again."
        is IOException -> offlineMessage()
        else -> t.message?.takeIf { it.isNotBlank() }
            ?: "Could not reach the SportDay server at ${ApiClient.getBaseUrl()}."
    }

    private fun offlineMessage(): String =
        "No connection to the SportDay server at ${ApiClient.getBaseUrl()}. " +
            "Check the phone's network and the address under Server settings."

    /** The `message` field of the server's error body, or null. */
    private fun parseMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            gson.fromJson(raw, ApiErrorBody::class.java)?.message
        } catch (e: Exception) {
            // A 4xx that is not the usual shape — an HTML error page, say. If it
            // looks like plain text, it is still more use than a status code.
            raw.takeIf { it.length in 1..300 && !it.trimStart().startsWith("<") }?.trim()
        }
    }
}
