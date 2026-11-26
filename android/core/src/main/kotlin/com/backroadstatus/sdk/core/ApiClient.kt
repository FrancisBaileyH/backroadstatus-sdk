package com.backroadstatus.sdk.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hands back a fresh bearer token. `forceRefresh` is set when the server
 * rejects the previous token with 401 — [ApiClient] retries each request
 * exactly once with `forceRefresh = true` so stale/revoked access tokens
 * get rotated transparently instead of surfacing as auth errors.
 */
typealias TokenProvider = suspend (forceRefresh: Boolean) -> String

internal class ApiClient(
    private val client: OkHttpClient = defaultClient,
) {
    companion object {
        private const val TAG = "BackroadStatus"
        private val defaultClient: OkHttpClient = OkHttpClient.Builder().build()
        private val jsonMediaType = "application/json".toMediaType()
        private val jpegMediaType = "image/jpeg".toMediaType()
        internal val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    /**
     * Fetches canonical geometry for every road intersecting the given
     * slippy-map tile at `z`. The server returns a flat GeoJSON
     * FeatureCollection — same shape as `/paths/{id}` but with multiple
     * road geometries and their Point road-features intermixed.
     */
    suspend fun bulkPaths(
        issuer: String,
        tokenProvider: TokenProvider,
        z: Int,
        x: Int,
        y: Int,
    ): List<RoadDetail> {
        val url = integrationsV1(issuer) + "/paths/geometry/$z/$x/$y.json"
        val (bodyString, http) = fetchWithAuthRetry(tokenProvider) { token ->
            Request.Builder()
                .url(url)
                .get()
                .applyCommonHeaders(token)
                .build()
        }
        if (!http.isSuccessful) {
            throw BackroadStatusError.ApiError(http.code, bodyString)
        }
        val root = json.parseToJsonElement(bodyString).jsonObject
        val features = root["features"]?.jsonArray ?: JsonArray(emptyList())
        return decodeRoads(features)
    }

    suspend fun me(tokenProvider: TokenProvider, issuer: String): MeResponse {
        val url = "${integrationsV1(issuer)}/me"
        return getDecoded(url, tokenProvider)
    }

    suspend fun createBulletin(
        tokenProvider: TokenProvider,
        issuer: String,
        input: CreateBulletinInput,
    ): Bulletin {
        val url = "${integrationsV1(issuer)}/bulletins"
        val bodyString = json.encodeToString(CreateBulletinInput.serializer(), input)
        return postDecoded(url, tokenProvider, bodyString)
    }

    suspend fun road(tokenProvider: TokenProvider, issuer: String, id: String): RoadDetail {
        val url = "${integrationsV1(issuer)}/paths/$id"
        val (bodyString, http) = fetchWithAuthRetry(tokenProvider) { token ->
            Request.Builder().url(url).get().applyCommonHeaders(token).build()
        }
        if (!http.isSuccessful) throw BackroadStatusError.ApiError(http.code, bodyString)
        val root = json.parseToJsonElement(bodyString).jsonObject
        val features = root["features"]?.jsonArray ?: JsonArray(emptyList())
        return decodeRoad(features, fallbackId = id)
            ?: throw BackroadStatusError.ApiError(200, "Response did not contain a road geometry")
    }

    suspend fun uploadDraftPhoto(
        tokenProvider: TokenProvider,
        issuer: String,
        data: ByteArray,
        fileName: String,
    ): DraftPhoto {
        val url = "${integrationsV1(issuer)}/draft/upload"
        val boundary = "BackroadStatus-${UUID.randomUUID()}"
        val body = multipartBody(boundary, "file", fileName, data)
        val (bodyString, http) = fetchWithAuthRetry(tokenProvider) { token ->
            Request.Builder()
                .url(url)
                .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
        }
        if (!http.isSuccessful) {
            Log.w(TAG, "${http.code} $url — body: ${bodyString.take(500)}")
            throw BackroadStatusError.ApiError(http.code, bodyString)
        }
        return try {
            json.decodeFromString(DraftPhoto.serializer(), bodyString)
        } catch (t: Throwable) {
            throw BackroadStatusError.ApiError(http.code, "Decode failed: ${t.message}. Body preview: ${bodyString.take(500)}")
        }
    }

    // MARK: Request helpers

    private fun integrationsV1(issuer: String): String {
        val trimmed = issuer.trimEnd('/')
        return "$trimmed/api/integrations/v1"
    }

    private fun Request.Builder.applyCommonHeaders(token: String): Request.Builder {
        header("Authorization", "Bearer $token")
        header("Accept", "application/json")
        return this
    }

    private suspend inline fun <reified T> getDecoded(
        url: String,
        noinline tokenProvider: TokenProvider,
    ): T = sendDecoded(url, tokenProvider) { token ->
        Request.Builder().url(url).get().applyCommonHeaders(token).build()
    }

    private suspend inline fun <reified T> postDecoded(
        url: String,
        noinline tokenProvider: TokenProvider,
        bodyString: String,
    ): T = sendDecoded(url, tokenProvider) { token ->
        Request.Builder()
            .url(url)
            .post(bodyString.toRequestBody(jsonMediaType))
            .applyCommonHeaders(token)
            .build()
    }

    private suspend inline fun <reified T> sendDecoded(
        url: String,
        noinline tokenProvider: TokenProvider,
        noinline makeRequest: (String) -> Request,
    ): T {
        val (bodyString, http) = fetchWithAuthRetry(tokenProvider, makeRequest)
        if (!http.isSuccessful) {
            Log.w(TAG, "${http.code} $url — body: ${bodyString.take(1000)}")
            throw BackroadStatusError.ApiError(http.code, bodyString)
        }
        return try {
            json.decodeFromString(bodyString)
        } catch (t: Throwable) {
            Log.w(TAG, "Decode failed for $url: ${t.message}; preview: ${bodyString.take(500)}")
            throw BackroadStatusError.ApiError(
                http.code,
                "Decode failed: ${t.message}. Body preview: ${bodyString.take(500)}",
            )
        }
    }

    /**
     * Builds a request with the current access token and runs it; if the
     * server returns 401, forces a token refresh and retries exactly once.
     *
     * Covers two failure modes we can't tell apart from the client:
     * (1) AppAuth handing back a stale access token because its freshness
     * check short-circuited, and (2) the server revoking the token
     * mid-session before its expiry. In both cases a forced refresh is the
     * correct response.
     *
     * Only one retry attempt — if the refreshed token also gets a 401, the
     * caller surfaces it (typically means the refresh token itself is dead
     * and [Auth] will have already cleared local state).
     */
    private suspend fun fetchWithAuthRetry(
        tokenProvider: TokenProvider,
        makeRequest: (String) -> Request,
    ): Pair<String, Response> {
        if (BackroadStatus.simulateOffline) {
            throw BackroadStatusError.SimulatedOffline
        }
        val firstToken = tokenProvider(false)
        val firstReq = makeRequest(firstToken)
        val first = execute(firstReq)
        if (first.second.code != 401) return first

        Log.i(TAG, "401 ${firstReq.url} — forcing token refresh and retrying once")
        first.second.close()
        val refreshedToken = tokenProvider(true)
        val retryReq = makeRequest(refreshedToken)
        return execute(retryReq)
    }

    private suspend fun execute(request: Request): Pair<String, Response> =
        withContext(Dispatchers.IO) {
            val response = suspendCancellableCoroutine<Response> { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        cont.resume(response)
                    }
                })
            }
            val body = response.body?.string().orEmpty()
            body to response
        }

    private fun multipartBody(
        boundary: String,
        fieldName: String,
        fileName: String,
        data: ByteArray,
    ): ByteArray {
        val newline = "\r\n"
        val prefix = buildString {
            append("--").append(boundary).append(newline)
            append("Content-Disposition: form-data; name=\"").append(fieldName)
                .append("\"; filename=\"").append(fileName).append("\"").append(newline)
            append("Content-Type: image/jpeg").append(newline).append(newline)
        }.toByteArray(Charsets.UTF_8)
        val suffix = "$newline--$boundary--$newline".toByteArray(Charsets.UTF_8)
        val out = ByteArray(prefix.size + data.size + suffix.size)
        System.arraycopy(prefix, 0, out, 0, prefix.size)
        System.arraycopy(data, 0, out, prefix.size, data.size)
        System.arraycopy(suffix, 0, out, prefix.size + data.size, suffix.size)
        return out
    }

    // MARK: GeoJSON FeatureCollection decoding

    /**
     * Groups a flat bulk-response feature list into per-road bundles
     * (road geometry + its Point features) and decodes each as a
     * [RoadDetail]. Features whose owning road is missing from the
     * response are dropped.
     */
    internal fun decodeRoads(features: JsonArray): List<RoadDetail> {
        val featuresByRoad = linkedMapOf<String, MutableList<JsonObject>>()
        for (f in features) {
            val feature = f.jsonObject
            val geometry = feature["geometry"]?.jsonObject ?: continue
            val properties = feature["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val type = geometry["type"]?.jsonPrimitive?.contentOrNull
            val roadId = when (type) {
                "LineString", "MultiLineString" -> properties["id"]?.jsonPrimitive?.contentOrNull
                "Point" -> properties["pathId"]?.jsonPrimitive?.contentOrNull
                else -> null
            } ?: continue
            featuresByRoad.getOrPut(roadId) { ArrayList() }.add(feature)
        }
        return featuresByRoad.mapNotNull { (id, bundle) ->
            decodeRoad(JsonArray(bundle), fallbackId = id)
        }
    }

    /**
     * Turns a per-road slice of GeoJSON features (one MultiLineString /
     * LineString plus any Point road-features belonging to the same road)
     * into a [RoadDetail]. Returns null if there's no road geometry in the
     * slice — callers decide whether that's an error (single-road
     * endpoint) or something to skip (bulk endpoint).
     */
    internal fun decodeRoad(features: JsonArray, fallbackId: String): RoadDetail? {
        val roadFeature = features.firstOrNull { el ->
            val t = el.jsonObject["geometry"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
            t == "MultiLineString" || t == "LineString"
        }?.jsonObject ?: return null

        val roadGeometry = roadFeature["geometry"]?.jsonObject ?: return null
        val roadProperties = roadFeature["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val roadType = roadGeometry["type"]?.jsonPrimitive?.contentOrNull

        val coordinates: List<List<Double>>
        val conditions: List<RoadConditionPoint>
        when (roadType) {
            "LineString" -> {
                coordinates = parseLineCoords(roadGeometry["coordinates"])
                conditions = emptyList()
            }
            "MultiLineString" -> {
                val segments = parseMultiLineCoords(roadGeometry["coordinates"])
                coordinates = flatten(segments)
                val names = roadProperties["conditions"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
                conditions = conditions(segments, names)
            }
            else -> return null
        }

        val roadFeatures: List<RoadFeatureDetail> = features.mapNotNull { el ->
            val f = el.jsonObject
            val g = f["geometry"]?.jsonObject ?: return@mapNotNull null
            if (g["type"]?.jsonPrimitive?.contentOrNull != "Point") return@mapNotNull null
            val coord = parsePointCoord(g["coordinates"]) ?: return@mapNotNull null
            if (coord.size < 2) return@mapNotNull null
            val props = f["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val fid = props["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = props["name"]?.jsonPrimitive?.contentOrNull
            val displayName = props["displayName"]?.jsonPrimitive?.contentOrNull ?: name ?: "Feature"
            val desc = props["description"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
            RoadFeatureDetail(
                id = fid,
                type = name ?: "UNKNOWN",
                displayName = displayName,
                description = desc,
                position = listOf(coord[0], coord[1]),
            )
        }

        return RoadDetail(
            id = roadProperties["id"]?.jsonPrimitive?.contentOrNull ?: fallbackId,
            name = roadProperties["name"]?.jsonPrimitive?.contentOrNull ?: "",
            coordinates = coordinates,
            features = roadFeatures,
            conditions = conditions,
        )
    }

    private fun parsePointCoord(el: JsonElement?): List<Double>? {
        val arr = el?.jsonArray ?: return null
        return arr.map { it.jsonPrimitive.content.toDouble() }
    }

    private fun parseLineCoords(el: JsonElement?): List<List<Double>> {
        val arr = el?.jsonArray ?: return emptyList()
        return arr.map { pt -> pt.jsonArray.map { it.jsonPrimitive.content.toDouble() } }
    }

    private fun parseMultiLineCoords(el: JsonElement?): List<List<List<Double>>> {
        val arr = el?.jsonArray ?: return emptyList()
        return arr.map { line ->
            line.jsonArray.map { pt ->
                pt.jsonArray.map { it.jsonPrimitive.content.toDouble() }
            }
        }
    }

    /** Concatenates MultiLineString segments, skipping shared seam points. */
    private fun flatten(segments: List<List<List<Double>>>): List<List<Double>> {
        val flat = ArrayList<List<Double>>()
        for (segment in segments) {
            for (coord in segment) {
                if (flat.lastOrNull() != coord) flat.add(coord)
            }
        }
        return flat
    }

    /** Pairs each segment's last point with the corresponding condition name. */
    private fun conditions(
        segments: List<List<List<Double>>>,
        names: List<String>,
    ): List<RoadConditionPoint> {
        val count = minOf(segments.size, names.size)
        val result = ArrayList<RoadConditionPoint>(count)
        for (i in 0 until count) {
            val last = segments[i].lastOrNull() ?: continue
            if (last.size < 2) continue
            result.add(RoadConditionPoint(point = last, condition = names[i]))
        }
        return result
    }
}
