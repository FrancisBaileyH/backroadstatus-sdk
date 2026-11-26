package com.backroadstatus.sdk.composer

import com.backroadstatus.sdk.core.BackroadStatusError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a raw submit-time error into something the user can act on. Shared
 * between the composer's inline error card and the Pending Bulletins row
 * subtitle so both surfaces describe the same failure in the same words.
 *
 * API error bodies are usually JSON like `{"detail": "..."}` — pull that
 * out; fall back to a status-code-based message, then the raw message.
 */
fun friendlySubmitError(error: Throwable): String {
    if (error is BackroadStatusError.ApiError) {
        error.body?.let { body ->
            extractErrorDetail(body)?.let { return it }
        }
        return when (error.status) {
            401, 403 -> "You're not authorized to submit this report. Try signing in again."
            in 400..499 -> "The server couldn't accept this report. Please check your inputs and try again."
            in 500..599 -> "The server ran into a problem. Please try again shortly."
            else -> "Couldn't reach the server. Check your connection and try again."
        }
    }
    if (error is BackroadStatusError.NotAuthenticated) return "You're not signed in."
    if (error is BackroadStatusError.SimulatedOffline) return "You're offline."
    if (error is BackroadStatusError) return error.message ?: error.localizedMessage ?: "Unknown error."
    return error.localizedMessage ?: error.message ?: "Unknown error."
}

/**
 * Same contract as [friendlySubmitError], but for a previously-stored
 * `lastError` string pulled off a queued/failed submission. We don't have
 * the original [Throwable] by then, so we do a lightweight JSON probe and
 * otherwise return the string verbatim.
 */
fun friendlyLastError(raw: String): String {
    extractErrorDetail(raw)?.takeIf { it.isNotEmpty() }?.let { return it }
    return raw
}

private val probingJson = Json { ignoreUnknownKeys = true }

private fun extractErrorDetail(body: String): String? {
    val element = runCatching { probingJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val obj = element as? JsonObject ?: return null

    val base = listOf("detail", "message", "error")
        .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } }

    val fieldErrors = (obj["errors"] as? JsonArray).orEmpty().mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val field = entry["field"]?.jsonPrimitive?.contentOrNull
        val message = entry["message"]?.jsonPrimitive?.contentOrNull
        when {
            !field.isNullOrEmpty() && !message.isNullOrEmpty() -> "$field: $message"
            !message.isNullOrEmpty() -> message
            else -> null
        }
    }

    if (base == null && fieldErrors.isEmpty()) return null
    return buildString {
        if (base != null) append(base)
        if (fieldErrors.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            fieldErrors.joinTo(this, separator = "\n") { "• $it" }
        }
    }
}
