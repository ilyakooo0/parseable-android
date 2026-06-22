package com.parseable.android.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deserializes a value that some Parseable builds return as a JSON string ("12345") and other
 * builds return as a JSON number (12345) into a [String], so stats parsing doesn't fail outright
 * on the numeric form. Applied to the size fields below, which are rendered via [formatBytes]
 * (itself tolerant of both a raw byte count and a pre-formatted string).
 */
object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        // JsonNull is itself a JsonPrimitive whose .content is the literal string "null". For the
        // nullable fields this serializer is applied to, the compiler-generated nullable wrapper
        // already intercepts a JSON null before deserialize() runs, so this never fires today. The
        // guard exists so that if this serializer is ever attached to a non-null field, a JSON null
        // surfaces as "" (rendered as absent) rather than the misleading literal text "null".
        if (element is JsonNull) return ""
        return (element as? JsonPrimitive)?.content ?: element.toString()
    }
}

/**
 * Server connection configuration stored in DataStore.
 */
data class ServerConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val useTls: Boolean = true,
)

/**
 * Represents a log stream entry returned by GET /api/v1/logstream
 */
@Serializable
data class LogStream(
    val name: String,
)

/**
 * Query request body sent to POST /api/v1/query
 */
@Serializable
data class QueryRequest(
    val query: String,
    val startTime: String,
    val endTime: String,
)

/**
 * Stream stats from GET /api/v1/logstream/{name}/stats
 */
@Serializable
data class StreamStats(
    val ingestion: IngestionStats? = null,
    val storage: StorageStats? = null,
    val stream: String? = null,
    val time: String? = null,
)

@Serializable
data class IngestionStats(
    val count: Long? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    val size: String? = null,
    val format: String? = null,
    @SerialName("lifetime_count")
    val lifetimeCount: Long? = null,
    @SerialName("lifetime_size")
    @Serializable(with = FlexibleStringSerializer::class)
    val lifetimeSize: String? = null,
    @SerialName("deleted_count")
    val deletedCount: Long? = null,
    @SerialName("deleted_size")
    @Serializable(with = FlexibleStringSerializer::class)
    val deletedSize: String? = null,
)

@Serializable
data class StorageStats(
    @Serializable(with = FlexibleStringSerializer::class)
    val size: String? = null,
    val format: String? = null,
    @SerialName("lifetime_size")
    @Serializable(with = FlexibleStringSerializer::class)
    val lifetimeSize: String? = null,
    @SerialName("deleted_size")
    @Serializable(with = FlexibleStringSerializer::class)
    val deletedSize: String? = null,
)

/**
 * Schema field info from GET /api/v1/logstream/{name}/schema
 */
@Serializable
data class StreamSchema(
    val fields: List<SchemaField> = emptyList(),
)

@Serializable
data class SchemaField(
    val name: String,
    @SerialName("data_type")
    val dataType: JsonElement? = null,
    val nullable: Boolean? = null,
    val dict_id: Long? = null,
    val dict_is_ordered: Boolean? = null,
    val metadata: JsonObject? = null,
)

/**
 * Retention config from GET /api/v1/logstream/{name}/retention
 */
@Serializable
data class RetentionConfig(
    val description: String? = null,
    val duration: String? = null,
    val action: String? = null,
)

/**
 * About info from GET /api/v1/about
 */
@Serializable
data class AboutInfo(
    val version: String? = null,
    val commit: String? = null,
    @SerialName("deploymentId")
    val deploymentId: String? = null,
    val license: JsonObject? = null,
    val mode: String? = null,
    val staging: String? = null,
    val store: JsonObject? = null,
    @SerialName("grpcPort")
    val grpcPort: Int? = null,
    val analytics: JsonObject? = null,
    @SerialName("queryEngine")
    val queryEngine: String? = null,
)

/**
 * Alert summary returned by GET /api/v1/alerts.
 *
 * The global alerts endpoint returns summary objects with fields like `title`, `state`,
 * `severity`, and `datasets`. The per-stream endpoint uses `name` and `alerts[]` nesting.
 * Both shapes are supported via optional fields.
 */
@Serializable
data class Alert(
    val id: String? = null,
    val version: String? = null,
    // Per-stream alert format uses "name"; global summary uses "title"
    val name: String? = null,
    val title: String? = null,
    val message: String? = null,
    val rule: JsonObject? = null,
    val targets: List<JsonObject> = emptyList(),
    // Per-stream alert format
    val stream: String? = null,
    val enabled: Boolean? = null,
    // Global summary fields
    val state: String? = null,
    val severity: String? = null,
    @SerialName("alertType")
    val alertType: String? = null,
    @SerialName("notificationState")
    val notificationState: String? = null,
    val created: String? = null,
    @SerialName("lastTriggeredAt")
    val lastTriggeredAt: String? = null,
    val tags: List<String> = emptyList(),
    val datasets: List<String> = emptyList(),
) {
    /** Best-effort display name from either response format. */
    val displayName: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: "Unnamed Alert"

    /** Whether the alert is active, derived from either response format. */
    val isEnabled: Boolean get() = when {
        state != null -> !state.equals("Disabled", ignoreCase = true)
        enabled != null -> enabled
        else -> true
    }

    /** Stream(s) this alert is configured for. */
    val streamDisplay: String? get() = when {
        datasets.isNotEmpty() -> datasets.joinToString(", ")
        stream != null -> stream
        else -> null
    }
}

/**
 * Result wrapper for API calls
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>() {
        val isUnauthorized: Boolean get() = code == 401
        val isNotFound: Boolean get() = code == 404
        val isServerError: Boolean get() = code in 500..599
        // Only genuine transport failures use code 0. A successful HTTP response whose body
        // failed to parse carries PARSE_ERROR_CODE so it isn't mislabeled as a network error.
        val isNetworkError: Boolean get() = code == 0

        val userMessage: String get() = when {
            code == 401 -> "Session expired. Please log in again."
            code == 403 -> "Permission denied."
            code == 404 -> "Resource not found. It may have been deleted."
            code == 429 -> "Too many requests. Please wait and try again."
            code in 500..599 -> "Server error ($code). Please try again later."
            code == 0 -> when {
                message.contains("timeout", ignoreCase = true) -> "Connection timed out. Check your network."
                message.contains("Unable to resolve host", ignoreCase = true) -> "No internet connection."
                message.contains("Connection refused", ignoreCase = true) -> "Server is unreachable."
                else -> "Network error: $message"
            }
            else -> message
        }
    }

    companion object {
        /**
         * Code used for client-side response-parsing failures: the HTTP request succeeded
         * but the body was malformed/unexpected. Distinct from 0 (network/transport failure)
         * so [Error.isNetworkError] and [Error.userMessage] don't show a misleading
         * "Network error" for what is actually a server-data problem.
         */
        const val PARSE_ERROR_CODE = -1
    }
}
