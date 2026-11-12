package com.midwayder.pleos

import android.app.Activity

interface SpeechListener {
    fun onPartial(text: String)
    fun onResult(text: String)
    fun onError(message: String)
}

/**
 * 차량 플랫폼이 제공하는 다섯 가지: 현재 위치, 내비 목적지, 음성 인식, 음성 안내, 경유지 삽입.
 * Pleos Connect 에서는 Pleos SDK, 일반 Android 기기에서는 표준 API 로 채운다. 검색 로직은 플랫폼과 무관하다.
 */
interface CarPlatform {
    /** 화면 부제에 보여 주는 한 줄 설명. 어떤 경로로 동작 중인지 숨기지 않는다. */
    val description: String

    /** 경유지 확정 버튼 문구. */
    val waypointActionLabel: String

    /** 표준 위치·마이크 외에 실행 중 요청할 플랫폼 권한. */
    val extraPermissions: List<String> get() = emptyList()

    /** 최근(120초 이내) 한국 내 현재 위치. 없으면 예외 메시지로 이유를 알린다. */
    suspend fun currentLocation(): Place

    /** 차량 내비에 이미 설정된 목적지. 없거나 지원하지 않으면 null. */
    suspend fun navigationDestination(): Place?

    fun startListening(listener: SpeechListener)
    fun stopListening()

    /** 안내 음성. onDone 은 말하기가 끝난 뒤(TTS 를 쓸 수 없으면 즉시) 메인 스레드에서 한 번 불린다. 새 speak 가 오면 이전 콜백은 버린다. */
    fun speak(text: String, onDone: (() -> Unit)? = null)

    /** 선택한 경유지 C 와 목적지 B 를 내비에 넘긴다. 결과 안내 문구를 돌려준다. */
    fun insertWaypoint(activity: Activity, origin: Place, stop: Stop, destination: Place): String

    fun release()
}

object CarPlatforms {
    fun create(activity: Activity): CarPlatform =
        if (PleosPlatform.isAvailable(activity)) PleosPlatform(activity) else AndroidFallbackPlatform(activity)
}
