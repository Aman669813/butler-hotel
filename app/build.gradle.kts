// ============================================================
//  Hotel Butler — device app  (app/build.gradle.kts)
//  Built-in Kotlin IS active in this project (AGP provides the Kotlin
//  compiler), so we must NOT apply org.jetbrains.kotlin.android — doing
//  so throws "extension 'kotlin' already registered". The compose +
//  serialization Kotlin plugins are applied on top of built-in Kotlin.
//  jvmTarget defaults to android.compileOptions.targetCompatibility (17).
// ============================================================
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.risiga.hotelbutler"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.risiga.hotelbutler"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-demo"
        buildConfigField("String", "DEVICE_CODE", "\"ROOM-301\"")
        buildConfigField("String", "SUPABASE_URL", "\"https://boioqssdrthhdlpztuis.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJvaW9xc3NkcnRoaGRscHp0dWlzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODA1NDkyMDksImV4cCI6MjA5NjEyNTIwOX0.c9V5hwI1GhWMaKDS-VKAH-GUxZR2RzTYNbkvHyv7EZE\"")
        buildConfigField("String", "SARVAM_API_KEY", "\"\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"\"")
        buildConfigField("Boolean", "DEMO_MODE", "true")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.0"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}