package com.midwayder.pleos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.midwayder.pleos.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

/**
 * 음성만으로 진행하는 차량 화면. 설명 문구 없이 질문 한 줄·인식된 말·조작 버튼만 둔다.
 * 1 "목적지를 말해주세요" → (후보가 여럿이면 목록) → 2 "경유지를 말해주세요" → 3 지도 + 왼쪽 순위 목록 → 번호로 고르고 내비로 보낸다.
 * 안내와 오류는 음성으로 알린다. 안내 음성이 끝나면 자동으로 듣는다.
 */
class MainActivity : AppCompatActivity() {
    private enum class Step { DESTINATION, DESTINATION_PICK, WAYPOINT, MAP }

    private lateinit var binding: ActivityMainBinding
    private lateinit var platform: CarPlatform
    private lateinit var routeMap: RouteMap
    private val api = MidWayDerApi()
    private val placeAdapter = RowAdapter { position -> candidates.getOrNull(position)?.let { chooseDestination(it) } }
    private val stopAdapter = RowAdapter { position -> pick(position, announce = true) }

    private var step = Step.DESTINATION
    private var origin: Place? = null
    private var destination: Place? = null
    private var candidates: List<Place> = emptyList()
    private var result: SearchResult? = null
    private var selected = -1
    private var listening = false
    private var retried = false
    private var job: Job? = null
    private var locationJob: Job? = null

    /** 디버그 빌드에서 adb 로 넣는 가짜 발화("강남역|1|다이소|2"). 마이크 없는 개발 Mac 에서 흐름을 돌려 보는 용도. */
    private val debugSpeech = ArrayDeque<String>()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        platform = CarPlatforms.create(this)
        routeMap = RouteMap(binding.map)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = placeAdapter
        binding.stops.layoutManager = LinearLayoutManager(this)
        binding.stops.adapter = stopAdapter
        listOf(binding.mic, binding.listMic, binding.mapMic).forEach { it.setOnClickListener { toggleListening() } }
        binding.restartIcon.setOnClickListener { restart() }
        binding.insert.setOnClickListener { insertSelected() }
        binding.insert.text = platform.waypointActionLabel
        readDebugSpeech(intent)

        val missing = (listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO) + platform.extraPermissions)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) start() else permissions.launch(missing.toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (readDebugSpeech(intent)) restart()
    }

    private fun readDebugSpeech(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val lines = intent?.getStringExtra("debug_speech")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return false
        debugSpeech.clear()
        debugSpeech.addAll(lines)
        return lines.isNotEmpty()
    }

    override fun onResume() { super.onResume(); binding.map.onResume() }
    override fun onPause() { super.onPause(); binding.map.onPause() }
    override fun onDestroy() {
        job?.cancel()
        locationJob?.cancel()
        platform.release()
        binding.map.onDetach()
        super.onDestroy()
    }

    // ---- 흐름 ----------------------------------------------------------------------------

    private fun start() {
        refreshLocation()
        beginDestination()
    }

    private fun restart() {
        job?.cancel()
        beginDestination()
    }

    private fun refreshLocation() {
        locationJob?.cancel()
        locationJob = lifecycleScope.launch { origin = runCatching { platform.currentLocation() }.getOrNull() }
    }

    private fun beginDestination() {
        destination = null; candidates = emptyList(); result = null; selected = -1
        show(Step.DESTINATION)
        prompt("목적지를 말해주세요", "목적지를 말해 주세요.")
    }

    private fun beginWaypoint(speech: String) {
        show(Step.WAYPOINT)
        prompt("경유지를 말해주세요", speech)
    }

    /** 질문을 보여 주고 말한 뒤, 같은 단계에 있으면 자동으로 듣기 시작한다. */
    private fun prompt(text: String, speech: String) {
        binding.prompt.text = text
        binding.transcript.text = ""
        speakThenListen(speech)
    }

    private fun speakThenListen(speech: String) {
        val at = step
        platform.speak(speech) { if (step == at && !isFinishing) listen() }
    }

    // ---- 음성 ----------------------------------------------------------------------------

    private fun toggleListening() {
        if (listening) {
            platform.stopListening()
            setListening(false)
        } else listen()
    }

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        val at = step
        setListening(true)
        debugSpeech.removeFirstOrNull()?.let { fake ->
            binding.root.postDelayed({
                if (step != at) return@postDelayed
                setListening(false)
                transcript("$fake (디버그 입력)")
                handleSpeech(fake)
            }, 900)
            return
        }
        platform.startListening(object : SpeechListener {
            override fun onPartial(text: String) { if (step == at) transcript(text) }
            override fun onResult(text: String) {
                if (step != at) return
                setListening(false); retried = false
                transcript(text)
                handleSpeech(text)
            }
            override fun onError(message: String) {
                if (step != at) return
                setListening(false)
                if (!retried) {
                    retried = true
                    binding.root.postDelayed({ if (step == at && !listening) listen() }, 700)
                } else platform.speak(message)
            }
        })
    }

    private fun handleSpeech(text: String) {
        when (step) {
            Step.DESTINATION -> lookupDestination(text)
            Step.DESTINATION_PICK -> parseIndex(text, candidates.size)?.let { chooseDestination(candidates[it]) }
                ?: if (isFiller(text)) askNumber() else lookupDestination(text)
            Step.WAYPOINT -> searchStops(text)
            Step.MAP -> {
                val index = parseIndex(text, result?.stops?.size ?: 0)
                when {
                    index != null -> pick(index, announce = true)
                    Regex("보내|삽입|시작|안내|네|응|좋아|그래|출발").containsMatchIn(text) -> insertSelected()
                    Regex("처음|목적지").containsMatchIn(text) -> restart()
                    Regex("다른|바꿔|다시").containsMatchIn(text) -> beginWaypoint("다른 경유지를 말해 주세요.")
                    isFiller(text) -> askNumber()
                    else -> searchStops(text) // 새 경유어로 다시 검색
                }
            }
        }
    }

    private fun askNumber() = speakThenListen("번호를 말해 주세요.")

    // ---- 목적지 ---------------------------------------------------------------------------

    private fun lookupDestination(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        job = lifecycleScope.launch {
            show(Step.DESTINATION)
            binding.prompt.text = "목적지를 찾고 있어요"
            transcript(q)
            runCatching { api.locations(q) }
                .onSuccess { places ->
                    candidates = places
                    when (places.size) {
                        0 -> prompt("목적지를 말해주세요", "‘$q’ 장소를 찾지 못했어요. 다시 말해 주세요.")
                        1 -> chooseDestination(places[0])
                        else -> {
                            show(Step.DESTINATION_PICK)
                            binding.listTitle.text = q
                            placeAdapter.submit(places.mapIndexed { i, p -> Row(p.label, p.address.orEmpty(), rank = i + 1) })
                            speakThenListen("목적지 후보가 ${places.size}곳 있어요. 번호를 말해 주세요.")
                        }
                    }
                }
                .onFailure { prompt("목적지를 말해주세요", "목적지 검색에 실패했어요. 다시 말해 주세요.") }
        }
    }

    private fun chooseDestination(place: Place) {
        destination = place
        candidates = emptyList()
        beginWaypoint("목적지 ${place.label}. 경유지를 말해 주세요.")
    }

    // ---- 경유지 → 지도 --------------------------------------------------------------------

    private fun searchStops(text: String) {
        val k = text.trim()
        if (k.isEmpty()) return
        val dest = destination ?: run { beginDestination(); return }
        job?.cancel()
        job = lifecycleScope.launch {
            show(Step.WAYPOINT)
            binding.prompt.text = "찾고 있어요"
            transcript(k)
            locationJob?.join()
            val from = origin ?: runCatching { platform.currentLocation() }
                .getOrElse { prompt("경유지를 말해주세요", "현재 위치를 확인하지 못했어요. ${it.message.orEmpty()}"); return@launch }
                .also { origin = it }
            runCatching { api.search(from, dest, k) }
                .onSuccess { found ->
                    result = found
                    if (found.stops.isEmpty()) {
                        prompt("경유지를 말해주세요", "‘$k’ 경유지를 찾지 못했어요. 다른 이름이나 종류를 말해 주세요.")
                        return@onSuccess
                    }
                    showMap()
                }
                .onFailure { prompt("경유지를 말해주세요", "경유지 검색에 실패했어요. 다시 말해 주세요.") }
        }
    }

    /** 검색이 끝나면 바로 지도. 왼쪽에 순위 목록, 1번이 기본 선택. 상위 3곳을 읽어 준다. */
    private fun showMap() {
        val found = result ?: return
        show(Step.MAP)
        pick(0, announce = false)
        val top = found.stops.take(3).mapIndexed { i, s -> "${i + 1}번 ${s.name}, ${s.speechText(found.tier)}" }
        speakThenListen("가장 효율적인 곳은 ${top.joinToString(". ")}. 번호를 말하거나, 보내 줘라고 말해 주세요.")
    }

    private fun pick(index: Int, announce: Boolean) {
        val found = result ?: return
        val from = origin ?: return
        val dest = destination ?: return
        val stop = found.stops.getOrNull(index) ?: return
        selected = index
        stopAdapter.submit(found.stops.mapIndexed { i, s -> Row(s.name, s.shortCost(found.tier), rank = i + 1, selected = i == index) })
        binding.stops.scrollToPosition(index)
        routeMap.showResult(from, dest, found, index)
        if (announce) speakThenListen("${index + 1}번 ${stop.name}. 보내 줘라고 말하면 내비로 보내요.")
    }

    private fun insertSelected() {
        val found = result ?: return
        val stop = found.stops.getOrNull(selected) ?: return
        val from = origin ?: return
        val dest = destination ?: return
        platform.speak(platform.insertWaypoint(this, from, stop, dest))
    }

    // ---- 번호 인식 ------------------------------------------------------------------------

    /** "1", "1번", "첫 번째", "둘", "삼번이요" → 0 기반 index. 범위를 벗어나면 null. */
    private fun parseIndex(text: String, size: Int): Int? {
        if (size <= 0) return null
        val t = text.replace(" ", "")
        val number = Regex("^(\\d{1,2})(번째|번)?(으로|이요|요)?$").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: KOREAN_NUMBERS.entries.firstOrNull { (word, _) -> Regex("^$word(번째|번)?(으로|이요|요)?$").matches(t) }?.value
        return number?.takeIf { it in 1..size }?.minus(1)
    }

    private fun isFiller(text: String) = text.replace(" ", "") in setOf("네", "예", "응", "어", "음", "아니", "아니요", "뭐")

    // ---- 표시 ----------------------------------------------------------------------------

    private fun show(next: Step) {
        if (step != next) {
            platform.stopListening()
            setListening(false)
            retried = false
        }
        step = next
        binding.promptView.visibility = if (next == Step.DESTINATION || next == Step.WAYPOINT) View.VISIBLE else View.GONE
        binding.listView.visibility = if (next == Step.DESTINATION_PICK) View.VISIBLE else View.GONE
        binding.mapScreen.visibility = if (next == Step.MAP) View.VISIBLE else View.GONE
        binding.restartIcon.visibility = if (next == Step.DESTINATION) View.GONE else View.VISIBLE
    }

    private fun setListening(active: Boolean) {
        listening = active
        listOf(binding.mic, binding.listMic, binding.mapMic).forEach {
            it.isActivated = active
            it.text = if (active) "■" else "🎤"
        }
    }

    /** 인식된 말은 말하기 화면에서만 보여 준다. 목록·지도 화면은 선택 결과 자체가 피드백이다. */
    private fun transcript(text: String) {
        if (step == Step.DESTINATION || step == Step.WAYPOINT) binding.transcript.text = text
    }

    private companion object {
        val KOREAN_NUMBERS = linkedMapOf(
            "첫" to 1, "하나" to 1, "일" to 1,
            "두" to 2, "둘" to 2, "이" to 2,
            "세" to 3, "셋" to 3, "삼" to 3,
            "네" to 4, "넷" to 4, "사" to 4,
            "다섯" to 5, "오" to 5,
            "여섯" to 6, "육" to 6,
            "일곱" to 7, "칠" to 7,
            "여덟" to 8, "팔" to 8,
        )
    }
}
