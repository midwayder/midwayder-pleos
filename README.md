# MidWayDer (미드웨이더) · Pleos Connect

**가는 길에 들르기 가장 편한 경유지를, 추가 운전시간이 적은 순서로 찾아 차량 내비에 바로 넣어 주는 차량용 앱.**

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. 팀명 `미드웨이더`, 앱 이름 `MidWayDer`.

운전 중 "강남역 가는 길에 다이소 들르고 싶다"고 할 때, 지금 위치에서 가장 가까운 곳이 아니라
**경로에서 덜 벗어나 시간이 가장 적게 더 드는 곳**을 1순위로 보여 줍니다. 고른 곳은 NaviHelper SDK 로
Pleos 내비에 경유지로 넘어가고, 길안내는 차량 내비가 맡습니다.

## APK 위치

[`apk/미드웨이더_MidWayDer_release.apk`](apk/미드웨이더_MidWayDer_release.apk) (release 빌드, 약 40MB, ABI arm64-v8a · x86_64)

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 (Android 14, API 34) |
| 화면 규격 | 2560 × 1440, 160dpi, 가로 |
| 패키지 | `com.midwayder.pleos` (versionName 0.1.0) |
| 인터넷 | **필요** — 목적지 검색과 경유지 순위 계산을 MidWayDer 서버(HTTPS)에서 받습니다 |
| 사용 SDK | Pleos FusedLocation v1.1.3, NaviHelper v3.0.5 |

## 실행 방법 (APK 설치)

에뮬레이터를 켠 뒤 아래 순서로 실행합니다.

```sh
# 1) 에뮬레이터 기본 GPS 는 미국이라 국내 좌표로 지정 (경도 위도 순서, 예: 판교)
adb emu geo fix 127.1112 37.3948

# 2) 권한(-g)을 미리 허용하며 설치
adb install -r -g "apk/미드웨이더_MidWayDer_release.apk"

# 3) 실행 (앱 서랍의 MidWayDer 아이콘으로 실행해도 됩니다)
adb shell am start -n com.midwayder.pleos/.MainActivity
```

- `-g` 없이 설치하면 첫 실행 때 위치(`pleos.car.permission.FUSED_LOCATION`)·마이크 권한을 묻습니다. 허용해 주세요.
- 국내 좌표를 지정하지 않으면 현재 위치가 국외로 잡혀 국내 경로를 계산할 수 없습니다.
- 경유지를 넘겨받을 Pleos 내비 앱(`ai.umos.maps.android.navigation.app`)이 에뮬레이터에 있어야 합니다(기본 포함).

## 사용 흐름

차량용이라 키보드 입력 없이 말과 터치로 진행합니다.

1. **"목적지를 말해주세요"** — 예: "강남역". 후보가 여럿이면 번호 목록이 나오고, 번호를 말하거나 눌러서 고릅니다.
2. **"경유지를 말해주세요"** — 예: "다이소", "커피". 이름이나 종류를 말하면 어느 지점이 좋은지는 앱이 고릅니다.
3. **지도 + 경유지 순위** — 왼쪽에 "추가 운전시간"이 적은 순으로 목록, 오른쪽 지도에 경로와 번호 마커. 1번이 기본 선택이며 번호를 말하거나 눌러 바꿉니다.
4. **"내비로 보내기"** (또는 "보내 줘") — 목적지와 고른 경유지가 Pleos 내비에 "경유지 1개 포함" 경로로 들어가 안내가 시작됩니다.

"다른 거"는 경유지 다시 말하기, "처음부터"는 목적지부터 다시 시작합니다. 시스템 야간 모드에 맞춰 다크 화면으로 바뀝니다.

> 음성 인식·안내는 현재 Android 표준 API(SpeechRecognizer ko-KR, TextToSpeech)를 씁니다. 에뮬레이터 환경에 따라 마이크 입력이 동작하지 않을 수 있으며, 그 경우 목록은 터치로 고를 수 있습니다.

## Pleos SDK 연동

| 기능 | Pleos SDK | 상태 |
|---|---|---|
| 현재 위치 | FusedLocation `getLastKnownLocation` | 연결됨 |
| 경유지 + 목적지 경로 요청 | NaviHelper `requestRouteWithWaypoints` → `onRouteStarted` / `onError` | 연결됨 |
| 음성 인식 · 음성 안내 | SpeechToText · TextToSpeech | 예정 (현재 Android 표준 API) |

Pleos SDK 는 공개 Maven(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 받습니다(`settings.gradle.kts`).
Pleos 내비 서비스가 없는 일반 Android 기기에서는 Android 위치 API 와 지도 앱 연결로 대체 동작합니다.

## 소스 구조

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 단계형 화면(말하기 · 목적지 후보 · 지도+순위)과 음성·터치 흐름 |
| `CarPlatform.kt` | 플랫폼 인터페이스: 현재 위치 · 음성 인식 · 음성 안내 · 경유지 삽입 |
| `PleosPlatform.kt` | Pleos Connect 구현 (FusedLocation · NaviHelper) |
| `AndroidFallbackPlatform.kt` | 일반 Android 기기 구현 |
| `MidWayDerApi.kt`, `Models.kt` | 서버 요청·응답 모델. 서버가 준 순위를 그대로 표시 |
| `RouteMap.kt` | MapLibre 지도(OpenFreeMap 벡터 타일, Pleos 내비 톤 낮/밤 스타일)에 경로·마커 표시 |

## 직접 빌드

JDK 21, Android SDK(Platform 36 · Build Tools 36). AGP 8.12.0 · Kotlin 2.1.20 · Gradle 9.3.1.

```sh
./gradlew :app:assembleRelease
# 결과: app/build/outputs/apk/release/app-release.apk
```

- release 빌드도 로컬 debug 인증서로 서명합니다(스토어 배포 서명 아님).
- 서버 키 등 비밀값은 앱에 들어 있지 않습니다. 장소·경로 API 호출은 모두 서버에서 합니다.
- 앱은 자체 길안내(turn-by-turn)를 하지 않습니다. 경유지 삽입 뒤 안내는 차량 내비가 맡습니다.

## 지도 출처

© OpenStreetMap contributors (ODbL), 지도 타일 OpenFreeMap.
