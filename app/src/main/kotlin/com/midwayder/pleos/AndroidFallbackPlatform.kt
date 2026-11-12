package com.midwayder.pleos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.CancellationSignal
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 일반 Android 기기(개발용 폰) 폴백. GMS 에 의존하지 않도록 LocationManager 를 쓴다.
 * Pleos Connect 에 GMS 가 없어도 같은 코드가 돈다.
 */
class AndroidFallbackPlatform(private val context: Context) : CarPlatform {
    override val description = "Android 폴백 · 기기 GPS · Android 음성 인식(ko-KR) · 내비 앱 연결"
    override val waypointActionLabel = "내비 앱으로 보내기"

    private var recognizer: SpeechRecognizer? = null
    private var cancelling = false
    private val main = Handler(Looper.getMainLooper())
    /** 0 초기화 중 · 1 사용 가능 · -1 사용 불가 */
    private var ttsState = 0
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private var speechDone: (() -> Unit)? = null
    private var speechTimeout: Runnable? = null
    private var utterance = ""
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                ttsState = if (status == TextToSpeech.SUCCESS) 1 else -1
                if (ttsState == 1) {
                    tts?.language = Locale.KOREAN
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { main.post { if (utteranceId == utterance) finishSpeech() } }
                        @Deprecated("API 21 시그니처") override fun onError(utteranceId: String?) { main.post { if (utteranceId == utterance) finishSpeech() } }
                        override fun onError(utteranceId: String?, errorCode: Int) { main.post { if (utteranceId == utterance) finishSpeech() } }
                    })
                }
                pendingSpeech?.let { (text, done) -> pendingSpeech = null; speak(text, done) }
            }
        }
    }

    // ---- 위치 ------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): Place {
        demoOrigin()?.let { return it }
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) throw IllegalStateException("위치 권한을 허용해야 현재 위치를 출발지로 쓸 수 있어요.")
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) throw IllegalStateException("위치 서비스가 꺼져 있어요. 설정에서 켜 주세요.")

        val now = System.currentTimeMillis()
        providers.firstNotNullOfOrNull { provider ->
            manager.getLastKnownLocation(provider)?.takeIf { now - it.time <= 120_000 && GeoPoint(it.latitude, it.longitude).isKorean }
        }?.let { return place(it) }

        for (provider in providers) {
            val fix = withTimeoutOrNull(if (provider == LocationManager.NETWORK_PROVIDER) 8_000 else 15_000) { request(manager, provider) }
            if (fix != null && GeoPoint(fix.latitude, fix.longitude).isKorean) return place(fix)
        }
        // 새 위치를 못 받으면 15분 이내의 마지막 기록을 쓰되, 오래된 기록임을 라벨에 남긴다.
        providers.mapNotNull { manager.getLastKnownLocation(it) }
            .filter { now - it.time <= 15 * 60_000 && GeoPoint(it.latitude, it.longitude).isKorean }
            .maxByOrNull { it.time }
            ?.let { return place(it, ageMinutes = ((now - it.time) / 60_000).toInt()) }
        throw IllegalStateException("현재 위치를 확인하지 못했어요. 하늘이 보이는 곳에서 다시 시도해 주세요.")
    }

    @SuppressLint("MissingPermission")
    private suspend fun request(manager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { if (continuation.isActive) continuation.resume(it) }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { if (continuation.isActive) continuation.resume(location) }
                @Deprecated("API 28 호환") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) { if (continuation.isActive) continuation.resume(null) }
            }
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private fun place(location: Location, ageMinutes: Int = 0): Place {
        val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
        val kind = when {
            mock -> "시뮬레이션"
            location.provider == LocationManager.GPS_PROVIDER -> "GPS"
            else -> "기기 위치"
        }
        val age = if (ageMinutes > 0) " · ${ageMinutes}분 전 기록" else ""
        return Place("현재 위치 ($kind$age)", GeoPoint(location.latitude, location.longitude), source = "gps")
    }

    /** 빌드 플래그로 고정한 데모 출발지. 영상 녹화 때 실제 위치가 드러나지 않게 쓰며, 라벨로 구분한다. */
    private fun demoOrigin(): Place? {
        val parts = BuildConfig.DEMO_ORIGIN.split(",").map { it.trim() }
        if (parts.size < 2) return null
        val point = GeoPoint(parts[0].toDoubleOrNull() ?: return null, parts[1].toDoubleOrNull() ?: return null)
        if (!point.isKorean) return null
        val label = parts.getOrNull(2)?.ifBlank { null } ?: "데모 출발지"
        return Place("$label (데모 고정 출발지)", point, source = "search")
    }

    override suspend fun navigationDestination(): Place? = null

    // ---- 음성 인식 --------------------------------------------------------------------------

    override fun startListening(listener: SpeechListener) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("이 기기에는 음성 인식 서비스가 없어요. 텍스트로 입력해 주세요.")
            return
        }
        stopListening()
        cancelling = false
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                release(speech)
                if (text.isEmpty()) listener.onError("말을 알아듣지 못했어요. 다시 말씀해 주세요.") else listener.onResult(text)
            }
            override fun onError(error: Int) {
                val wasCancelling = cancelling
                release(speech)
                if (!wasCancelling) listener.onError(speechError(error))
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        speech.startListening(intent)
    }

    override fun stopListening() {
        val speech = recognizer ?: return
        cancelling = true
        runCatching { speech.cancel() }
        release(speech)
    }

    private fun release(speech: SpeechRecognizer) {
        if (recognizer === speech) recognizer = null
        runCatching { speech.destroy() }
    }

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "말을 알아듣지 못했어요. 다시 말씀해 주세요."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 들리지 않았어요."
        SpeechRecognizer.ERROR_AUDIO -> "마이크를 사용할 수 없어요."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "음성 인식 서버에 연결하지 못했어요."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 아직 바빠요. 잠시 후 다시 눌러 주세요."
        SpeechRecognizer.ERROR_CLIENT -> "음성 인식이 중단됐어요."
        else -> "음성 인식 오류 ($code)"
    }

    // ---- 음성 안내 --------------------------------------------------------------------------

    override fun speak(text: String, onDone: (() -> Unit)?) {
        // 끊긴 이전 안내의 완료 콜백이 다음 단계를 시작하면 안 되므로 버린다.
        speechTimeout?.let(main::removeCallbacks); speechTimeout = null; speechDone = null
        when (ttsState) {
            0 -> { pendingSpeech = text to onDone; return }
            -1 -> { onDone?.let { main.post(it) }; return }
        }
        speechDone = onDone
        utterance = "midwayder-${System.nanoTime()}"
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance) != TextToSpeech.SUCCESS) { finishSpeech(); return }
        // 엔진이 완료 이벤트를 주지 않을 때의 상한
        speechTimeout = Runnable { finishSpeech() }.also { main.postDelayed(it, 2_500L + text.length * 180L) }
    }

    private fun finishSpeech() {
        speechTimeout?.let(main::removeCallbacks); speechTimeout = null
        val done = speechDone ?: return
        speechDone = null
        done()
    }

    // ---- 내비 연결 --------------------------------------------------------------------------

    /** 네이버지도 → 티맵 순으로 경유지 C 와 목적지 B 를 함께 전달한다. 둘 다 없으면 카카오맵 웹으로 C 까지 길찾기. */
    override fun insertWaypoint(activity: Activity, origin: Place, stop: Stop, destination: Place): String {
        val naver = Uri.parse("nmap://route/car").buildUpon()
            .appendQueryParameter("appname", activity.packageName)
            .appendQueryParameter("slat", origin.coordinate.lat.toString()).appendQueryParameter("slng", origin.coordinate.lng.toString()).appendQueryParameter("sname", origin.label)
            .appendQueryParameter("v1lat", stop.coordinate.lat.toString()).appendQueryParameter("v1lng", stop.coordinate.lng.toString()).appendQueryParameter("v1name", stop.name)
            .appendQueryParameter("dlat", destination.coordinate.lat.toString()).appendQueryParameter("dlng", destination.coordinate.lng.toString()).appendQueryParameter("dname", destination.label)
            .build()
        val via = JSONArray().put(JSONObject()
            .put("pkey", "").put("poiId", "").put("navSeq", "").put("poiName", stop.name)
            .put("navX", stop.coordinate.lng.toString()).put("navY", stop.coordinate.lat.toString())
            .put("centerX", "").put("centerY", "").put("address", "").put("rpFlag", 0))
        val tmap = Uri.parse("tmap://route").buildUpon()
            .appendQueryParameter("startx", origin.coordinate.lng.toString()).appendQueryParameter("starty", origin.coordinate.lat.toString()).appendQueryParameter("startname", origin.label)
            .appendQueryParameter("goalx", destination.coordinate.lng.toString()).appendQueryParameter("goaly", destination.coordinate.lat.toString()).appendQueryParameter("goalname", destination.label)
            .appendQueryParameter("vialist", via.toString())
            .build()
        for ((name, uri) in listOf("네이버지도" to naver, "티맵" to tmap)) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (intent.resolveActivity(activity.packageManager) == null) continue
            return try {
                activity.startActivity(intent)
                "$name 로 보냈어요 · 경유지 ${stop.name} → 목적지 ${destination.label}"
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
        val web = Uri.parse("https://map.kakao.com/link/to/${Uri.encode(stop.name)},${stop.coordinate.lat},${stop.coordinate.lng}")
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, web))
            "내비 앱이 없어 카카오맵 웹으로 ${stop.name}까지 길찾기를 열었어요. Pleos 에서는 NaviHelper 가 경유지를 바로 넣어요."
        } catch (_: ActivityNotFoundException) {
            "길찾기를 열 수 있는 앱이 없어요."
        }
    }

    override fun release() {
        stopListening()
        runCatching { tts?.stop(); tts?.shutdown() }
    }
}
