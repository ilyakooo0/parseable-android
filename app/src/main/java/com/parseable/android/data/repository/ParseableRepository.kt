package com.parseable.android.data.repository

import com.parseable.android.data.api.ParseableApiClient
import com.parseable.android.data.model.*
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParseableRepository @Inject constructor(
    private val apiClient: ParseableApiClient,
) {
    fun configure(config: ServerConfig) {
        apiClient.configure(config)
    }

    val isConfigured: Boolean get() = apiClient.isConfigured

    suspend fun testConnection(): ApiResult<String> = apiClient.checkLiveness()

    suspend fun getAbout(): ApiResult<AboutInfo> = apiClient.getAbout()

    suspend fun listStreams(): ApiResult<List<LogStream>> = apiClient.listStreams()

    suspend fun getStreamSchema(stream: String): ApiResult<StreamSchema> =
        apiClient.getStreamSchema(stream)

    suspend fun getStreamStats(stream: String): ApiResult<StreamStats> =
        apiClient.getStreamStats(stream)

    suspend fun getStreamInfo(stream: String): ApiResult<JsonObject> =
        apiClient.getStreamInfo(stream)

    suspend fun getStreamRetention(stream: String): ApiResult<List<RetentionConfig>> =
        apiClient.getStreamRetention(stream)

    suspend fun queryLogs(
        stream: String,
        startTime: String,
        endTime: String,
        filterSql: String = "",
        limit: Int = 1000,
    ): ApiResult<List<JsonObject>> {
        val whereClause = if (filterSql.isNotBlank()) " WHERE $filterSql" else ""
        val sql = "SELECT * FROM \"$stream\"$whereClause ORDER BY p_timestamp DESC LIMIT $limit"
        return apiClient.queryLogs(sql, startTime, endTime)
    }

    suspend fun queryLogsRaw(
        sql: String,
        startTime: String,
        endTime: String,
    ): ApiResult<List<JsonObject>> = apiClient.queryLogs(sql, startTime, endTime)

    suspend fun deleteStream(stream: String): ApiResult<String> =
        apiClient.deleteStream(stream)

    suspend fun listAlerts(): ApiResult<List<Alert>> = apiClient.listAlerts()

    suspend fun listUsers(): ApiResult<List<JsonObject>> = apiClient.listUsers()
}
