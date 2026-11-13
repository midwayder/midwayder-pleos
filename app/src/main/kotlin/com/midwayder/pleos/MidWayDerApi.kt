package com.midwayder.pleos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: String, message: String) : Exception(message)

/**
 * MidWayDer 검색 서버 클라이언트. Kakao 키는 서버에만 있고 앱은 공개 API 두 개만 부른다.
 * 요청 형식은 tools/carplay-preview/Sources/APIClient.swift 와 같다.
 */
class MidWayDerApi(origin: String = BuildConfig.API_ORIGIN, private val contestToken: String = BuildConfig.CONTEST_TOKEN) {
    private val origin = origin.trimEnd('/')

    /** 목적지 후보 조회. 한국 밖 좌표는 버린다. */
    suspend fun locations(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length !in 2..100) throw ApiException("INVALID_QUERY", "장소 이름은 2~100자로 입력하세요.")
        val array = JSONArray(post("api/locations", JSONObject().put("query", q).put("limit", 8)))
        (0 until array.length()).map { Place.from(array.getJSONObject(it)) }
            .filter { it.coordinate.isKorean && it.label.isNotBlank() }
    }

    /** 경유지 검색. tier 를 비우면 서버가 허용 등급(정밀/간편)을 고른다. */
    suspend fun search(origin: Place, destination: Place, keyword: String, tier: String? = null): SearchResult = withContext(Dispatchers.IO) {
        if (!origin.coordinate.isKorean || !destination.coordinate.isKorean || origin.coordinate == destination.coordinate) {
            throw ApiException("INVALID_REQUEST", "한국 내 서로 다른 출발지와 목적지가 필요해요.")
        }
        val k = keyword.trim()
        if (k.length !in 1..100) throw ApiException("INVALID_KEYWORD", "경유지 검색어를 1~100자로 입력하세요.")
        val body = JSONObject()
            .put("origin", origin.toJson())
            .put("destination", destination.toJson())
            .put("keyword", k)
            .put("mode", "car")
        tier?.let { body.put("tier", it) }
        // 공모전 비밀값이 있으면 시연 전용 경로(항상 정밀 추천)로, 없으면 일반 경로로 보낸다.
        val response = if (contestToken.isNotBlank()) post("api/contest-search", body, mapOf(CONTEST_HEADER to contestToken)) else post("api/search", body)
        val result = SearchResult.from(JSONObject(response))
        val valid = result.hasValidOrder && result.durationS >= 0 && result.distanceM >= 0 &&
            result.polyline.all { it.isKorean } &&
            result.stops.all { it.id.isNotBlank() && it.name.isNotBlank() && it.coordinate.isKorean && it.extraDurationS >= 0 && it.extraDistanceM >= 0 }
        if (!valid) throw ApiException("MALFORMED", "서버 응답 형식 또는 후보 순서가 올바르지 않아요.")
        result
    }

    private fun post(path: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String {
        val connection = (URL("$origin/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 32_000 // 서버 전체 예산 28초보다 조금 길게
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                throw ApiException(
                    error?.optString("code")?.ifBlank { null } ?: "HTTP_$status",
                    error?.optString("message")?.ifBlank { null } ?: "서버가 요청을 처리하지 못했어요 (HTTP $status).",
                )
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONTEST_HEADER = "X-MidWayDer-Contest"
    }
}
