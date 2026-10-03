plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "it.cienfcpoc"
    compileSdk = 36
    defaultConfig {
        applicationId = "it.cienfcpoc"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        buildConfigField("String", "GIT_SHA", "\"${providers.environmentVariable("GITHUB_SHA").orElse("LOCAL").get().take(8)}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            pickFirsts += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/NOTICE.md"
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jmrtd:jmrtd:0.8.8")
    implementation("net.sf.scuba:scuba-sc-android:0.0.26")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
}
