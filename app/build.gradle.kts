import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val apiOrigin = (project.findProperty("midwayder.apiOrigin") as String?)?.trim()?.trimEnd('/')
    ?: "https://midwayder.up.railway.app"
// 시연 영상용 고정 출발지 "lat,lng,라벨". 비우면 기기 위치를 쓴다. 화면 라벨에 "데모 고정"이 붙는다.
val demoOrigin = (project.findProperty("midwayder.demoOrigin") as String?)?.trim().orEmpty()

android {
    namespace = "com.midwayder.pleos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.midwayder.pleos"
        minSdk = 28 // Pleos Connect 최소 API
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_ORIGIN", "\"$apiOrigin\"")
        buildConfigField("String", "DEMO_ORIGIN", "\"$demoOrigin\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 프로토타입·공모전 제출용. 스토어 배포 서명이 아니라 로컬 debug 인증서로 서명한다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 지도 타일(OpenStreetMap). API 키 없이 쓰는 프로토타입용. 경로·마커는 서버 응답으로 그린다.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Pleos Connect SDK. Pleos 내비 서비스가 있는 기기에서만 PleosPlatform 이 선택된다.
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
}
