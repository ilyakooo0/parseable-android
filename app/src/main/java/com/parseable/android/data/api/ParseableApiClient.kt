package com.parseable.android.data.api

import com.parseable.android.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Singleton
class ParseableApiClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val secureClient: OkHttpClient = buildClient(allowInsecure = false)
    private val insecureClient: OkHttpClient by lazy { buildClient(allowInsecure = true) }

    @Volatile
    private var baseUrl: String = ""
    @Volatile
    private var authHeader: String = ""
    @Volatile
    private var allowInsecure: Boolean = false

    private val client: OkHttpClient
        get() = if (allowInsecure) insecureClient else secureClient

    private fun buildClient(allowInsecure: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (allowInsecure) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }

    fun configure(config: ServerConfig) {
        val url = config.serverUrl.trimEnd('/')
        val credentials = "${config.username}:${config.password}"
        val auth = "Basic " + android.util.Base64.encodeToString(
            credentials.toByteArray(),
            android.util.Base64.NO_WRAP
        )
        // Write all volatile fields together; order matters for readers
        this.allowInsecure = !config.useTls
        this.authHeader = auth
        this.baseUrl = url
    }

    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && authHeader.isNotEmpty()

    private fun buildRequest(path: String): Request.Builder {
        return Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", authHeader)
    }

    private suspend fun executeRequest(request: Request): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    ApiResult.Success(body)
                } else {
                    ApiResult.Error(
                        message = body.ifEmpty { response.message },
                        code = response.code
                    )
                }
            } catch (e: IOException) {
                ApiResult.Error(message = e.message ?: "Network error")
            } catch (e: Exception) {
                ApiResult.Error(message = e.message ?: "Unknown error")
            }
        }

    /**
     * GET /api/v1/about
     */
    suspend fun getAbout(): ApiResult<AboutInfo> {
        val request = buildRequest("/api/v1/about").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(json.decodeFromString<AboutInfo>(result.data))
                } catch (e: Exception) {
                    ApiResult.Error("Failed to parse about info: ${e.message}")
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/liveness - used to test connectivity
     */
    suspend fun checkLiveness(): ApiResult<String> {
        val request = buildRequest("/api/v1/liveness").get().build()
        return executeRequest(request)
    }

    /**
     * GET /api/v1/logstream - list all log streams
     */
    suspend fun listStreams(): ApiResult<List<LogStream>> {
        val request = buildRequest("/api/v1/logstream").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    val streams = json.decodeFromString<List<LogStream>>(result.data)
                    ApiResult.Success(streams)
                } catch (e: Exception) {
                    // Try alternate format: list of objects with "name" key
                    try {
                        val arr = json.parseToJsonElement(result.data).jsonArray
                        val streams = arr.map { element ->
                            when (element) {
                                is JsonPrimitive -> LogStream(name = element.content)
                                is JsonObject -> LogStream(
                                    name = element["name"]?.jsonPrimitive?.content ?: ""
                                )
                                else -> LogStream(name = element.toString())
                            }
                        }
                        ApiResult.Success(streams)
                    } catch (e2: Exception) {
                        ApiResult.Error("Failed to parse streams: ${e2.message}")
                    }
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/logstream/{stream}/schema
     */
    suspend fun getStreamSchema(stream: String): ApiResult<StreamSchema> {
        val request = buildRequest("/api/v1/logstream/$stream/schema").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(json.decodeFromString<StreamSchema>(result.data))
                } catch (e: Exception) {
                    ApiResult.Error("Failed to parse schema: ${e.message}")
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/logstream/{stream}/stats
     */
    suspend fun getStreamStats(stream: String): ApiResult<StreamStats> {
        val request = buildRequest("/api/v1/logstream/$stream/stats").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(json.decodeFromString<StreamStats>(result.data))
                } catch (e: Exception) {
                    ApiResult.Error("Failed to parse stats: ${e.message}")
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/logstream/{stream}/info
     */
    suspend fun getStreamInfo(stream: String): ApiResult<JsonObject> {
        val request = buildRequest("/api/v1/logstream/$stream/info").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(json.parseToJsonElement(result.data).jsonObject)
                } catch (e: Exception) {
                    ApiResult.Error("Failed to parse stream info: ${e.message}")
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/logstream/{stream}/retention
     */
    suspend fun getStreamRetention(stream: String): ApiResult<List<RetentionConfig>> {
        val request = buildRequest("/api/v1/logstream/$stream/retention").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(json.decodeFromString<List<RetentionConfig>>(result.data))
                } catch (e: Exception) {
                    try {
                        val single = json.decodeFromString<RetentionConfig>(result.data)
                        ApiResult.Success(listOf(single))
                    } catch (e2: Exception) {
                        ApiResult.Success(emptyList())
                    }
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * POST /api/v1/query - query logs with SQL
     */
    suspend fun queryLogs(
        query: String,
        startTime: String,
        endTime: String,
    ): ApiResult<List<JsonObject>> {
        val requestBody = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("query", query)
                put("startTime", startTime)
                put("endTime", endTime)
            }
        )

        val request = buildRequest("/api/v1/query")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    val elements = json.parseToJsonElement(result.data).jsonArray
                    val objects = elements.map { it.jsonObject }
                    ApiResult.Success(objects)
                } catch (e: Exception) {
                    ApiResult.Error("Failed to parse query results: ${e.message}")
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * DELETE /api/v1/logstream/{stream}
     */
    suspend fun deleteStream(stream: String): ApiResult<String> {
        val request = buildRequest("/api/v1/logstream/$stream").delete().build()
        return executeRequest(request)
    }

    /**
     * GET /api/v1/alerts
     */
    suspend fun listAlerts(): ApiResult<List<Alert>> {
        val request = buildRequest("/api/v1/alerts").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    // The response could be the alerts array directly or wrapped
                    val element = json.parseToJsonElement(result.data)
                    val alertsArray = when (element) {
                        is JsonArray -> element
                        is JsonObject -> element["alerts"]?.jsonArray ?: JsonArray(emptyList())
                        else -> JsonArray(emptyList())
                    }
                    val alerts = alertsArray.map { json.decodeFromJsonElement<Alert>(it) }
                    ApiResult.Success(alerts)
                } catch (e: Exception) {
                    ApiResult.Success(emptyList())
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/user - list users
     */
    suspend fun listUsers(): ApiResult<List<JsonObject>> {
        val request = buildRequest("/api/v1/user").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    val elements = json.parseToJsonElement(result.data).jsonArray
                    ApiResult.Success(elements.map { it.jsonObject })
                } catch (e: Exception) {
                    ApiResult.Success(emptyList())
                }
            }
            is ApiResult.Error -> result
        }
    }
}
