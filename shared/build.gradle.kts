import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * 안드로이드·아이폰이 함께 쓰는 로직: 네이버 응답 파싱, 경기 데이터 요청, 자르기 계산, 데모 경기.
 * 화면·알림·백그라운드 작업처럼 OS 기능에 묶인 코드는 각 앱(app/, 나중에 iosApp/)에 둔다.
 * iOS 타깃은 Mac(Xcode)에서만 빌드되고, 윈도우에서는 안드로이드 쪽만 빌드·테스트된다.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // 아이폰 실기기 + 시뮬레이터(애플 실리콘·인텔 Mac). Xcode에서 쓸 프레임워크 이름은 Shared
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // 앱 코드도 경기 JSON(JsonObject)과 날짜(LocalDate)를 직접 다루므로 api로 노출
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            implementation("io.ktor:ktor-client-core:3.1.3")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-android:3.1.3")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.1.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "com.example.lottelock.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
