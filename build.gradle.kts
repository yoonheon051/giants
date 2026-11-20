plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    // shared 모듈 (안드로이드·아이폰 공통 로직)
    id("org.jetbrains.kotlin.multiplatform") version "2.1.21" apply false
    id("com.android.library") version "8.10.1" apply false
}
