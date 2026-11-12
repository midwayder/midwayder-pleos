# MidWayDer · Pleos Connect 프로토타입 (Kotlin)

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect)용 Android 앱. 차량 가로 화면에서
**현재 위치는 GPS, 목적지와 경유지은 말로** 입력하면 MidWayDer 서버가 "가장 효율적인" 경유지를
추가 운전시간 순으로 골라 지도와 목록으로 보여 주고, 선택한 곳을 내비 경유지로 넘긴다.

경유지 순위·이탈비용 계산은 기존 MidWayDer 서버(`/api/locations`, `/api/search`)가 맡는다. 이 앱은
차량 플랫폼 연결(위치·음성·내비)과 화면만 담당한다.

## 화면 흐름 (음성 전용)

차량용이라 텍스트 입력이 없다. 안내 음성이 끝나면 자동으로 듣고, 목록에서는 번호를 말하거나 눌러 고른다.

1. **목적지를 말해주세요** — 지도 없음. 말하면 서버에서 후보를 찾는다. 후보가 하나면 바로 다음, 여럿이면 번호 목록.
2. **경유지를 말해주세요** — "다이소", "커피"처럼 이름이나 종류. 브랜드·종류 단어가 그대로 경유어가 되고, 어느 지점이 좋은지는 서버가 고른다.
3. **지도 + 순위 목록** — 검색이 끝나면 바로 지도. 왼쪽에 "1번 다이소 코엑스몰점 · 약 1분 이내 추가"처럼 추가 운전시간 순 목록, 1번이 기본 선택(파란 배경 강조). 상위 3곳을 음성으로 읽어 준다. 번호를 말하거나 누르면 목록 강조와 지도 마커가 함께 바뀐다. "보내 줘"라고 말하거나 버튼으로 내비에 전달. "다른 거"는 경유지 다시 말하기, "처음부터"는 목적지부터.

못 알아들으면 한 번 자동으로 다시 듣고, 그다음은 "다시 말하기" 버튼. 어느 화면에서든 "처음부터".

개발용: debug 빌드에서만 `adb shell "am start -n com.midwayder.pleos/.MainActivity --es debug_speech '강남역|1|다이소|1'"` 로 가짜 발화를 순서대로 넣어 마이크 없이 흐름을 돌려 볼 수 있다. 화면에 "디버그 입력 · 음성 아님"으로 표시되며 release 에는 없다.

## 구조

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 단계형 화면(말하기 · 목적지 후보 · 지도+순위 목록)과 음성 흐름, 번호 음성 선택 |
| `CarPlatform.kt` | 플랫폼 인터페이스: 현재 위치 · 내비 목적지 · 음성 인식 · 음성 안내 · 경유지 삽입 |
| `AndroidFallbackPlatform.kt` | 일반 Android 기기 구현. LocationManager(GPS/네트워크), SpeechRecognizer(ko-KR), TextToSpeech, 네이버지도·티맵 URL → 없으면 카카오맵 웹 |
| `PleosPlatform.kt` | Pleos Connect 구현. FusedLocation(위치)·NaviHelper(경유지+목적지 경로 요청). 음성은 폴백의 Android 표준 API 를 그대로 쓴다. Pleos 내비 서비스(`ai.pleos.playground.service.navi`)가 있는 기기에서만 선택된다 |
| `MidWayDerApi.kt`, `Models.kt` | 서버 계약(CarPlay Swift 프로토타입과 동일). 서버가 준 순서를 재정렬하지 않는다 |
| `RouteMap.kt` | osmdroid 지도에 경로 폴리라인·A/B·후보 마커를 그린다(야간 톤) |

### Pleos SDK 매핑

| CarPlatform | Pleos SDK | Android 폴백 |
|---|---|---|
| `currentLocation` | **연결됨** FusedLocation v1.1.3 `getLastKnownLocation` | `LocationManager` |
| `navigationDestination` | 예정: NaviHelper v3.0.5 `getDestinationInfo` → `onDestinationInfo` | 없음(음성·텍스트 입력) |
| `startListening` / `stopListening` | 예정: SpeechToText v3.3.1 `initialize` → `setLocale` → `request(HYBRID)` / `stop` | `SpeechRecognizer` ko-KR |
| `speak` | 예정: TextToSpeech v3.3.1 `speak` | `TextToSpeech` |
| `insertWaypoint` | **연결됨** NaviHelper `requestRouteWithWaypoints(RouteInfo(B), [RequestWaypointInfo(C, FIRST)])` → `onRouteStarted` / `onError` | 네이버지도·티맵 URL scheme, 카카오맵 웹 |

Pleos SDK 는 가입 없이 공개 Maven(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 받는다(`settings.gradle.kts`). 권한 `pleos.car.permission.FUSED_LOCATION`(dangerous, 실행 중 요청)·`NAVI_ROUTE_SEARCH`(normal)를 선언했다. FusedLocation AAR 이 `android.hardware.type.automotive` 를 required 로 넣어서 앱 매니페스트에서 `required="false"` 로 덮어써 폰 설치를 유지한다.
GMS(play-services)에 의존하지 않는다. Pleos Connect 에 GMS 가 없어도 같은 코드가 돈다.

## 빌드·설치

이 Mac 기준(JDK 21 Homebrew, Android SDK `~/Library/Android/sdk`, Platform 36·Build Tools 36). AGP 8.12.0 · Kotlin 2.1.20 · Gradle 9.3.1.

```sh
cd pleos
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug
"$HOME/Library/Android/sdk/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$HOME/Library/Android/sdk/platform-tools/adb" shell am start -n com.midwayder.pleos/.MainActivity
```

- 검색 서버는 기본 `https://midwayder.up.railway.app`. 바꾸려면 `-Pmidwayder.apiOrigin=https://...` (에뮬레이터에서 로컬 서버는 `http://10.0.2.2:3000`, 단 cleartext 는 manifest 에서 막혀 있다).
- 시연 영상용 고정 출발지: `-Pmidwayder.demoOrigin="37.5547,126.9707,서울역"`. 기기 위치 대신 이 좌표를 쓰고 라벨에 "데모 고정 출발지"가 붙는다. 집 위치가 영상에 드러나지 않게 하려는 용도다.
- `assembleRelease` 도 로컬 debug 인증서로 서명한다. 스토어 배포 서명이 아니다.
- 첫 실행에서 위치·마이크 권한을 묻는다. 권한 요청은 앱 안에서만 하고, 음성 인식은 마이크 버튼을 눌렀을 때만 시작한다.

## 2026-11-08~09 폰 검증

Lenovo L71091(Android 13, arm64)에서 debug 발화(`강남역|1|다이소|2`)로 끝까지 확인했다: 목적지 말하기 → 후보 8곳 → 경유지 말하기 → 지도 + "다이소" 순위 목록(서버 응답 등급 그대로 표시, 2번 선택 강조, A → C → B 경로와 번호 마커) → 내비 앱 없음 → 카카오맵 웹 폴백.
실제 마이크 입력과 TTS 는 2026-11-09 사용자가 폰에서 직접 말해 정상 동작을 확인했다(Android 음성 인식 ko-KR · Android TTS. Pleos STT/TTS 가 아니다). 텍스트 UI 로 먼저 검증했을 때는 같은 출발지·강남역 2호선·starbucks 로 정밀 추천 26곳을 받았다.

## 2026-11-10 Pleos Connect 에뮬레이터 검증

AVD `Pleos_Connect_v2_1`(Pleos Connect v2.1.0, API 34)에서 확인했다.

```sh
~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_v2_1 -no-snapshot -gpu host
adb -s emulator-5554 emu geo fix 127.1112 37.4012   # 기본 GPS 가 미국(마운틴뷰)이라 국내로 지정해야 위치를 쓴다
adb -s emulator-5554 install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell "am start -n com.midwayder.pleos/.MainActivity --es debug_speech '강남역|1|다이소|1|보내 줘'"
```

FusedLocation 으로 판교 좌표를 받고, 강남역 2호선 · 다이소 순위 1번(다이소 교대역점)을 고른 뒤 NaviHelper 가 Pleos 내비에 "경유지 1개 포함" 경로를 넣어 안내를 시작했다(`onRouteStarted` 20분·17km). 고정 출발지(`demoOrigin`) 없이 빌드해야 차량 위치와 출발지가 맞는다. 녹화는 `docs/contest/hmg-appdev-2026/demo/pleos-emulator-navihelper.mp4`. 음성은 디버그 입력이며 Pleos STT/TTS 는 아직 쓰지 않는다.

## 하지 않는 것

- Kakao REST 키를 앱에 넣지 않는다. 운영자 연락처·실명도 넣지 않는다.
- 자체 길안내(turn-by-turn)를 하지 않는다. 경유지 삽입 뒤 안내는 차량 내비 몫이다.
