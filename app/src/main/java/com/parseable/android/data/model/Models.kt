package com.parseable.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    val size: String? = null,
    val format: String? = null,
    @SerialName("lifetime_count")
    val lifetimeCount: Long? = null,
    @SerialName("lifetime_size")
    val lifetimeSize: String? = null,
    @SerialName("deleted_count")
    val deletedCount: Long? = null,
    @SerialName("deleted_size")
    val deletedSize: String? = null,
)

@Serializable
data class StorageStats(
    val size: String? = null,
    val format: String? = null,
    @SerialName("lifetime_size")
    val lifetimeSize: String? = null,
    @SerialName("deleted_size")
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
    val license: String? = null,
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
 * Alert configuration
 */
@Serializable
data class Alert(
    val id: String? = null,
    val version: String? = null,
    val name: String? = null,
    val message: String? = null,
    val rule: JsonObject? = null,
    val targets: List<JsonObject> = emptyList(),
    val stream: String? = null,
    val enabled: Boolean? = null,
)

/**
 * Result wrapper for API calls
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
}
