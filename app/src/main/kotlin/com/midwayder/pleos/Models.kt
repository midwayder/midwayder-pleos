package com.midwayder.pleos

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

data class GeoPoint(val lat: Double, val lng: Double) {
    /** 서버와 같은 한국 좌표 범위. 밖이면 응답·위치를 쓰지 않는다. */
    val isKorean: Boolean get() = lat.isFinite() && lng.isFinite() && lat in 32.0..39.7 && lng in 124.0..132.0

    fun toJson(): JSONObject = JSONObject().put("lat", lat).put("lng", lng)

    companion object {
        fun from(json: JSONObject) = GeoPoint(json.getDouble("lat"), json.getDouble("lng"))
    }
}

/** /api/locations 응답이자 /api/search 요청의 출발지·목적지. source: gps | search | navigation */
data class Place(val label: String, val coordinate: GeoPoint, val address: String? = null, val source: String = "search") {
    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("coordinate", coordinate.toJson())
        .put("source", source)
        .also { json -> address?.let { json.put("address", it) } }

    companion object {
        fun from(json: JSONObject) = Place(
            label = json.getString("label").trim(),
            coordinate = GeoPoint.from(json.getJSONObject("coordinate")),
            address = json.optString("address").takeIf { it.isNotBlank() },
            source = json.optString("source").ifBlank { "search" },
        )
    }
}

/** 서버가 추가 운전시간 → 추가 거리 → provider ID 순으로 정렬해 준 경유지 후보. 순서를 바꾸지 않는다. */
data class Stop(
    val id: String,
    val name: String,
    val address: String,
    val coordinate: GeoPoint,
    val extraDurationS: Double,
    val extraDistanceM: Double,
) {
    /** 화면 표기. 간편(simple) 응답은 추정치라 범위로 쓴다. */
    fun costText(tier: String?): String {
        if (tier == "simple") {
            val minutes = (extraDurationS.coerceIn(0.0, 1_000_000.0) / 60).toInt()
            val duration = if (minutes == 0) "약 1분 이내 추가" else "약 ${minutes}~${minutes + 2}분 추가"
            return "$duration · 약 +${extraDistanceM.coerceIn(0.0, 1_000_000_000.0).toInt()}m"
        }
        val s = extraDurationS.coerceIn(-1e9, 1e9).roundToInt()
        val m = extraDistanceM.coerceIn(-1e9, 1e9).roundToInt()
        return "${if (s < 0) "−" else "+"}${abs(s) / 60}분 ${abs(s) % 60}초 · ${if (m < 0) "−" else "+"}${abs(m)}m"
    }

    /** 순위 목록 오른쪽에 크게 쓰는 추가 시간: "+1분 20초", "+0분", "+2~4분". */
    fun shortCost(tier: String?): String {
        val s = extraDurationS.coerceIn(0.0, 1_000_000.0).roundToInt()
        val m = s / 60
        val sec = s % 60
        if (tier == "simple") return if (m == 0) "+1분 이내" else "+${m}~${m + 2}분"
        return when {
            s == 0 -> "+0분"
            m == 0 -> "+${sec}초"
            sec == 0 -> "+${m}분"
            else -> "+${m}분 ${sec}초"
        }
    }

    /** 음성 안내용 문장 조각. */
    fun speechText(tier: String?): String {
        val minutes = (extraDurationS.coerceAtLeast(0.0) / 60).toInt()
        val seconds = extraDurationS.coerceAtLeast(0.0).roundToInt() % 60
        return when {
            tier == "simple" && minutes == 0 -> "약 1분 이내로 더 걸려요"
            tier == "simple" -> "약 ${minutes}분에서 ${minutes + 2}분 더 걸려요"
            minutes == 0 && seconds == 0 -> "추가 시간이 없어요"
            minutes == 0 -> "${seconds}초 더 걸려요"
            else -> "${minutes}분 ${seconds}초 더 걸려요"
        }
    }

    companion object {
        fun from(json: JSONObject): Stop {
            val place = json.getJSONObject("place")
            val detour = json.getJSONObject("detour")
            return Stop(
                id = place.getString("id"),
                name = place.getString("name").trim(),
                address = place.optString("address"),
                coordinate = GeoPoint.from(place.getJSONObject("coordinate")),
                extraDurationS = detour.getDouble("extraDurationS"),
                extraDistanceM = detour.getDouble("extraDistanceM"),
            )
        }
    }
}

data class SearchResult(
    val durationS: Double,
    val distanceM: Double,
    val polyline: List<GeoPoint>,
    val stops: List<Stop>,
    /** precise | simple | null(서버 기본) */
    val tier: String?,
    /** complete | partial | null */
    val completion: String?,
    val warnings: List<String>,
) {
    val tierLabel: String get() = if (tier == "simple") "간편 추천 · 추정" else "정밀 추천"

    /** 서버 순서가 계약과 다르면 조용히 재정렬하지 않고 거부한다. */
    val hasValidOrder: Boolean
        get() = stops.zipWithNext().all { (a, b) ->
            when {
                a.extraDurationS != b.extraDurationS -> a.extraDurationS < b.extraDurationS
                a.extraDistanceM != b.extraDistanceM -> a.extraDistanceM < b.extraDistanceM
                else -> true // provider ID 순서는 서버 소관
            }
        }

    companion object {
        fun from(json: JSONObject): SearchResult {
            val route = json.getJSONObject("route")
            val line = route.getJSONArray("polyline")
            return SearchResult(
                durationS = route.getDouble("durationS"),
                distanceM = route.getDouble("distanceM"),
                polyline = (0 until line.length()).map { GeoPoint.from(line.getJSONObject(it)) },
                stops = json.getJSONArray("waypoints").let { arr -> (0 until arr.length()).map { Stop.from(arr.getJSONObject(it)) } },
                tier = json.optString("tier").takeIf { it.isNotBlank() },
                completion = json.opt("completion").let { if (it is JSONObject) it.optString("text").ifBlank { it.optString("status") } else it?.toString() }
                    ?.takeIf { it.isNotBlank() && it != "null" },
                warnings = messages(json.opt("warnings")),
            )
        }

        private fun messages(value: Any?): List<String> = when (value) {
            is JSONArray -> (0 until value.length()).flatMap { messages(value.opt(it)) }
            is JSONObject -> listOf(value.optString("message").ifBlank { value.toString() })
            is String -> listOf(value)
            else -> emptyList()
        }
    }
}
