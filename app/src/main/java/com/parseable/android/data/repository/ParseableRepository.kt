package com.parseable.android.data.repository

import com.parseable.android.data.api.ParseableApiClient
import com.parseable.android.data.model.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private class CacheEntry<T>(
    val data: T,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun isValid(ttlMs: Long): Boolean =
        System.currentTimeMillis() - timestamp < ttlMs
}

@Singleton
class ParseableRepository @Inject constructor(
    private val apiClient: ParseableApiClient,
) {
    companion object {
        private const val STREAMS_TTL_MS = 30_000L    // 30s
        private const val SCHEMA_TTL_MS = 120_000L    // 2min
        private const val STATS_TTL_MS = 30_000L      // 30s
        private const val ABOUT_TTL_MS = 300_000L     // 5min
    }

    private var streamsCache: CacheEntry<List<LogStream>>? = null
    private var aboutCache: CacheEntry<AboutInfo>? = null
    private val schemaCache = ConcurrentHashMap<String, CacheEntry<StreamSchema>>()
    private val statsCache = ConcurrentHashMap<String, CacheEntry<StreamStats>>()

    fun configure(config: ServerConfig) {
        apiClient.configure(config)
        invalidateAll()
    }

    val isConfigured: Boolean get() = apiClient.isConfigured

    fun invalidateAll() {
        streamsCache = null
        aboutCache = null
        schemaCache.clear()
        statsCache.clear()
    }

    suspend fun testConnection(): ApiResult<String> = apiClient.checkLiveness()

    suspend fun getAbout(): ApiResult<AboutInfo> {
        aboutCache?.let { if (it.isValid(ABOUT_TTL_MS)) return ApiResult.Success(it.data) }
        return apiClient.getAbout().also { result ->
            if (result is ApiResult.Success) {
                aboutCache = CacheEntry(result.data)
            }
        }
    }

    suspend fun listStreams(forceRefresh: Boolean = false): ApiResult<List<LogStream>> {
        if (!forceRefresh) {
            streamsCache?.let { if (it.isValid(STREAMS_TTL_MS)) return ApiResult.Success(it.data) }
        }
        return apiClient.listStreams().also { result ->
            if (result is ApiResult.Success) {
                streamsCache = CacheEntry(result.data)
            }
        }
    }

    suspend fun getStreamSchema(stream: String, forceRefresh: Boolean = false): ApiResult<StreamSchema> {
        if (!forceRefresh) {
            schemaCache[stream]?.let { if (it.isValid(SCHEMA_TTL_MS)) return ApiResult.Success(it.data) }
        }
        return apiClient.getStreamSchema(stream).also { result ->
            if (result is ApiResult.Success) {
                schemaCache[stream] = CacheEntry(result.data)
            }
        }
    }

    suspend fun getStreamStats(stream: String, forceRefresh: Boolean = false): ApiResult<StreamStats> {
        if (!forceRefresh) {
            statsCache[stream]?.let { if (it.isValid(STATS_TTL_MS)) return ApiResult.Success(it.data) }
        }
        return apiClient.getStreamStats(stream).also { result ->
            if (result is ApiResult.Success) {
                statsCache[stream] = CacheEntry(result.data)
            }
        }
    }

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

    suspend fun deleteStream(stream: String): ApiResult<String> {
        invalidateAll()
        return apiClient.deleteStream(stream)
    }

    suspend fun listAlerts(): ApiResult<List<Alert>> = apiClient.listAlerts()

    suspend fun listUsers(): ApiResult<List<JsonObject>> = apiClient.listUsers()
}
