package com.parseable.android.data.api

import timber.log.Timber
import com.parseable.android.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private data class ClientConfig(
    val baseUrl: String,
    val authHeader: String,
    val allowInsecure: Boolean,
)

@Singleton
class ParseableApiClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val sharedPool = okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES)
    private val sharedDispatcher = okhttp3.Dispatcher()

    private val secureClient: OkHttpClient = buildClient(allowInsecure = false)
    private val insecureClient: OkHttpClient by lazy { buildClient(allowInsecure = true) }

    @Volatile
    private var config: ClientConfig = ClientConfig("", "", false)

    private val client: OkHttpClient
        get() = if (config.allowInsecure) insecureClient else secureClient

    private fun buildClient(allowInsecure: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .dispatcher(sharedDispatcher)
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

    fun configure(serverConfig: ServerConfig) {
        val url = serverConfig.serverUrl.trimEnd('/')
        val credentials = "${serverConfig.username}:${serverConfig.password}"
        val auth = "Basic " + android.util.Base64.encodeToString(
            credentials.toByteArray(),
            android.util.Base64.NO_WRAP
        )
        config = ClientConfig(
            baseUrl = url,
            authHeader = auth,
            allowInsecure = !serverConfig.useTls,
        )
    }

    val isConfigured: Boolean get() = config.baseUrl.isNotEmpty() && config.authHeader.isNotEmpty()

    fun clearConfig() {
        config = ClientConfig("", "", false)
    }

    private fun encodePathSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun buildRequest(path: String): Request.Builder {
        val snapshot = config
        return Request.Builder()
            .url("${snapshot.baseUrl}$path")
            .header("Authorization", snapshot.authHeader)
    }

    private suspend fun executeRequest(request: Request): ApiResult<String> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        ApiResult.Success(body)
                    } else {
                        ApiResult.Error(
                            message = body.ifEmpty { response.message },
                            code = response.code
                        )
                    }
                }
            } catch (e: SocketTimeoutException) {
                ApiResult.Error(message = "Connection timed out")
            } catch (e: UnknownHostException) {
                ApiResult.Error(message = "Unable to resolve host \"${e.message}\"")
            } catch (e: SSLException) {
                ApiResult.Error(message = "SSL error: ${e.message}. Check server certificate.")
            } catch (e: IOException) {
                ApiResult.Error(message = e.message ?: "Network error")
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error")
                ApiResult.Error(message = "Unexpected error: ${e.javaClass.simpleName}")
            }
        }

    private fun <T> parseResponse(body: String, description: String, parse: () -> T): ApiResult<T> {
        return try {
            ApiResult.Success(parse())
        } catch (e: SerializationException) {
            ApiResult.Error("Invalid response format from server")
        } catch (e: IllegalArgumentException) {
            ApiResult.Error("Unexpected response format for $description")
        }
    }

    /**
     * GET /api/v1/about
     */
    suspend fun getAbout(): ApiResult<AboutInfo> {
        val request = buildRequest("/api/v1/about").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> parseResponse(result.data, "about info") {
                json.decodeFromString<AboutInfo>(result.data)
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
                        val parsed = json.parseToJsonElement(result.data)
                        val arr = (parsed as? JsonArray) ?: throw IllegalArgumentException("Expected JSON array")
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
        val encoded = encodePathSegment(stream)
        val request = buildRequest("/api/v1/logstream/$encoded/schema").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> parseResponse(result.data, "schema") {
                json.decodeFromString<StreamSchema>(result.data)
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/logstream/{stream}/stats
     */
    suspend fun getStreamStats(stream: String): ApiResult<StreamStats> {
        val encoded = encodePathSegment(stream)
        val request = buildRequest("/api/v1/logstream/$encoded/stats").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> parseResponse(result.data, "stats") {
                json.decodeFromString<StreamStats>(result.data)
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/logstream/{stream}/info
     */
    suspend fun getStreamInfo(stream: String): ApiResult<JsonObject> {
        val encoded = encodePathSegment(stream)
        val request = buildRequest("/api/v1/logstream/$encoded/info").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> parseResponse(result.data, "stream info") {
                val element = json.parseToJsonElement(result.data)
                (element as? JsonObject)
                    ?: throw IllegalArgumentException("Expected JSON object, got ${element::class.simpleName}")
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * GET /api/v1/logstream/{stream}/retention
     */
    suspend fun getStreamRetention(stream: String): ApiResult<List<RetentionConfig>> {
        val encoded = encodePathSegment(stream)
        val request = buildRequest("/api/v1/logstream/$encoded/retention").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> {
                try {
                    ApiResult.Success(json.decodeFromString<List<RetentionConfig>>(result.data))
                } catch (_: Exception) {
                    parseResponse(result.data, "retention config") {
                        listOf(json.decodeFromString<RetentionConfig>(result.data))
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
            is ApiResult.Success -> parseResponse(result.data, "query results") {
                val element = json.parseToJsonElement(result.data)
                val elements = (element as? JsonArray)
                    ?: throw IllegalArgumentException("Expected JSON array, got ${element::class.simpleName}")
                elements.mapNotNull { it as? JsonObject }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * DELETE /api/v1/logstream/{stream}
     */
    suspend fun deleteStream(stream: String): ApiResult<String> {
        val encoded = encodePathSegment(stream)
        val request = buildRequest("/api/v1/logstream/$encoded").delete().build()
        return executeRequest(request)
    }

    /**
     * GET /api/v1/alerts
     */
    suspend fun listAlerts(): ApiResult<List<Alert>> {
        val request = buildRequest("/api/v1/alerts").get().build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> parseResponse(result.data, "alerts") {
                val element = json.parseToJsonElement(result.data)
                val alertsArray = when (element) {
                    is JsonArray -> element
                    is JsonObject -> element["alerts"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                alertsArray.mapNotNull { alertElement ->
                    try {
                        json.decodeFromJsonElement<Alert>(alertElement)
                    } catch (e: Exception) {
                        Timber.w(e, "Skipping malformed alert entry")
                        null
                    }
                }
            }
            is ApiResult.Error -> result
        }
    }

    /**
     * DELETE /api/v1/alerts/{alertId}
     */
    suspend fun deleteAlert(alertId: String): ApiResult<String> {
        val encoded = encodePathSegment(alertId)
        val request = buildRequest("/api/v1/alerts/$encoded").delete().build()
        return executeRequest(request)
    }

    /**
     * POST /api/v1/alerts
     */
    suspend fun createAlert(alert: Alert): ApiResult<Alert> {
        val requestBody = json.encodeToString(Alert.serializer(), alert)
            .toRequestBody("application/json".toMediaType())
        val request = buildRequest("/api/v1/alerts")
            .post(requestBody)
            .build()
        return when (val result = executeRequest(request)) {
            is ApiResult.Success -> parseResponse(result.data, "alert") {
                json.decodeFromString<Alert>(result.data)
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
            is ApiResult.Success -> parseResponse(result.data, "users") {
                val element = json.parseToJsonElement(result.data)
                val elements = (element as? JsonArray)
                    ?: throw IllegalArgumentException("Expected JSON array, got ${element::class.simpleName}")
                elements.mapNotNull { it as? JsonObject }
            }
            is ApiResult.Error -> result
        }
    }
}
