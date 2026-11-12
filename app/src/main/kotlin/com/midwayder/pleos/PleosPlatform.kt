package com.midwayder.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Connect 구현. 위치는 FusedLocation SDK, 경유지 전달은 NaviHelper SDK 를 쓴다.
 * 음성 인식·안내는 아직 Pleos SpeechToText/TextToSpeech 로 바꾸지 않았고 Android 표준 API(폴백)를 그대로 쓴다.
 * 경로 시간·거리 조회 API 는 문서에 없으므로 이탈비용 계산은 지금처럼 MidWayDer 서버가 맡는다.
 */
class PleosPlatform(activity: Activity) : CarPlatform {
    override val description = "Pleos Connect · FusedLocation · NaviHelper · 음성은 Android 표준 API"
    override val waypointActionLabel = "내비 경유지로 삽입"
    override val extraPermissions = listOf(FUSED_LOCATION, NAVI_ROUTE_SEARCH)

    private val context = activity.applicationContext
    private val speech = AndroidFallbackPlatform(activity)
    private val main = Handler(Looper.getMainLooper())
    private val fused = FusedLocation(context).also { it.initialize() }
    private val navi = NaviHelper(context)
    private val naviEvents = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: ai.pleos.playground.navi.data.RouteStartInfo) { Log.i(TAG, "onRouteStarted $routeStartInfo") }
        override fun onRoutingCompleted() { Log.i(TAG, "onRoutingCompleted") }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "NaviHelper onError $errorCode")
            main.post { speech.speak("내비가 경로를 만들지 못했어요. ($errorCode)") }
        }
    }

    init {
        navi.initialize(
            { Log.i(TAG, "NaviHelper connected"); navi.addListener(naviEvents) },
            { Log.w(TAG, "NaviHelper 연결 실패", it) },
        )
    }

    override suspend fun currentLocation(): Place {
        // 시연 영상용 고정 출발지는 폴백과 같은 규칙으로 쓴다.
        if (BuildConfig.DEMO_ORIGIN.isNotBlank()) return speech.currentLocation()
        val supported = fused.isSupported()
        Log.i(TAG, "FusedLocation isSupported=$supported")
        if (!supported) throw IllegalStateException("이 차량은 Pleos 위치 서비스를 지원하지 않아요.")
        var last: String? = null
        repeat(3) { attempt ->
            if (attempt > 0) delay(1_000)
            val outcome = withTimeoutOrNull(5_000) { lastKnown() }
            val data = outcome?.getOrNull()
            Log.i(TAG, "FusedLocation attempt=$attempt data=$data error=${outcome?.exceptionOrNull()}")
            if (data != null) {
                val point = GeoPoint(data.latitude, data.longitude)
                if (point.isKorean) return Place("현재 위치 (Pleos FusedLocation)", point, source = "gps")
                last = "현재 위치가 국내 범위 밖이에요."
            } else {
                last = outcome?.exceptionOrNull()?.message ?: "위치 응답이 없어요."
            }
        }
        throw IllegalStateException("현재 위치를 확인하지 못했어요. $last")
    }

    private suspend fun lastKnown(): Result<LocationData?> = suspendCancellableCoroutine { continuation ->
        fused.getLastKnownLocation(object : FusedLocationListener {
            override fun onChanged(location: LocationData?) { if (continuation.isActive) continuation.resume(Result.success(location)) }
            override fun onError(exception: Exception) { if (continuation.isActive) continuation.resume(Result.failure(exception)) }
        })
    }

    /** MainActivity 가 아직 내비 목적지 읽기를 쓰지 않는다. */
    override suspend fun navigationDestination(): Place? = null

    override fun startListening(listener: SpeechListener) = speech.startListening(listener)
    override fun stopListening() = speech.stopListening()
    override fun speak(text: String, onDone: (() -> Unit)?) = speech.speak(text, onDone)

    /** 목적지 B 와 경유지 C 를 한 번에 요청한다. 결과는 NaviHelperEventListener 로 온다. */
    override fun insertWaypoint(activity: Activity, origin: Place, stop: Stop, destination: Place): String {
        val request = RouteWithWaypointsRequest(
            RouteInfo(
                latitude = destination.coordinate.lat,
                longitude = destination.coordinate.lng,
                poiName = destination.label,
                poiId = NO_POI_ID,
                poiSubId = NO_POI_SUB_ID,
                address = destination.address ?: "",
            ),
            listOf(
                RequestWaypointInfo(
                    latitude = stop.coordinate.lat,
                    longitude = stop.coordinate.lng,
                    poiName = stop.name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = NO_POI_ID,
                    poiSubId = NO_POI_SUB_ID,
                    address = stop.address,
                ),
            ),
        )
        return try {
            navi.requestRouteWithWaypoints(request)
            "Pleos 내비에 경유지 ${stop.name}, 목적지 ${destination.label} 경로를 요청했어요."
        } catch (error: Exception) {
            Log.w(TAG, "requestRouteWithWaypoints 실패", error)
            "Pleos 내비에 경로를 보내지 못했어요. ${error.message.orEmpty()}"
        }
    }

    override fun release() {
        runCatching { navi.removeListener(naviEvents); navi.release() }
        runCatching { fused.release() }
        speech.release()
    }

    companion object {
        private const val TAG = "MidWayDerPleos"
        private const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        private const val NAVI_ROUTE_SEARCH = "pleos.car.permission.NAVI_ROUTE_SEARCH"
        private const val NAVI_SERVICE = "ai.pleos.playground.service.navi"
        // 우리 장소 ID 는 Kakao 기준이라 Pleos 내비 POI ID 로 넘기지 않는다. 좌표·이름으로만 요청한다.
        private const val NO_POI_ID = ""
        private const val NO_POI_SUB_ID = "0"

        /** Pleos 내비 서비스가 설치된 기기(Pleos Connect 차량·에뮬레이터)에서만 선택한다. 일반 폰은 폴백. */
        fun isAvailable(context: Context): Boolean = runCatching {
            context.packageManager.getPackageInfo(NAVI_SERVICE, 0); true
        }.getOrDefault(false)
    }
}
