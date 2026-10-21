plugins {
    id("com.android.application"); kotlin("android"); kotlin("plugin.compose")
    kotlin("plugin.serialization"); id("com.google.devtools.ksp")
}
// The old CI debug signer cannot be recovered from its APK. This explicit build
// installs beside the existing UI Preview, release and Inventory Beta.
val remoteStatTest = providers.gradleProperty("astravarRemoteStatTest").map(String::toBooleanStrict).getOrElse(false)
android {
    namespace = "com.yazsras.astravar"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yazsras.astravar"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.environmentVariable("ASTRAVAR_VERSION_CODE").map(String::toInt).getOrElse(37)
        // Public Firebase client identifiers, supplied by the owner-approved project at build time.
        // No service-account key or signing material belongs in Android resources or source control.
        fun firebaseConfig(name: String) = providers.gradleProperty(name).orElse(providers.environmentVariable(name)).getOrElse("")
        listOf("ASTRAVAR_FIREBASE_APP_ID", "ASTRAVAR_FIREBASE_PROJECT_ID", "ASTRAVAR_FIREBASE_SENDER_ID", "ASTRAVAR_FIREBASE_API_KEY").forEach { name ->
            val value = firebaseConfig(name)
            require(value.none { it == '\n' || it == '\r' || it == '\"' || it == '\\' }) { "Invalid Firebase client configuration." }
            buildConfigField("String", name, "\"$value\"")
        }
        if (listOf("ASTRAVAR_FIREBASE_APP_ID", "ASTRAVAR_FIREBASE_PROJECT_ID", "ASTRAVAR_FIREBASE_SENDER_ID", "ASTRAVAR_FIREBASE_API_KEY").all { firebaseConfig(it).isNotBlank() }) {
            // FirebaseInitProvider reconstructs the same default app after process death. Messaging
            // auto-init stays disabled in the manifest until authenticated campaign enrollment.
            resValue("string", "google_app_id", firebaseConfig("ASTRAVAR_FIREBASE_APP_ID"))
            resValue("string", "project_id", firebaseConfig("ASTRAVAR_FIREBASE_PROJECT_ID"))
            resValue("string", "gcm_defaultSenderId", firebaseConfig("ASTRAVAR_FIREBASE_SENDER_ID"))
            resValue("string", "google_api_key", firebaseConfig("ASTRAVAR_FIREBASE_API_KEY"))
        }
        versionName = providers.environmentVariable("ASTRAVAR_VERSION_NAME").getOrElse("2.3.0")
    }
    signingConfigs {
        create("owner") {
            val key = providers.environmentVariable("ASTRAVAR_KEYSTORE").orNull
            if (key != null) {
                storeFile = file(key)
                storePassword = providers.environmentVariable("ASTRAVAR_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ASTRAVAR_KEY_ALIAS").getOrElse("astravar")
                keyPassword = providers.environmentVariable("ASTRAVAR_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".v2uidebug"; versionNameSuffix = "-ui-rebuild"
            manifestPlaceholders["astravarDebugLabel"] = "Astravar"
            if (remoteStatTest) {
                applicationIdSuffix = ".v2statdebug"; versionNameSuffix = "-remote-stat-test"
                manifestPlaceholders["astravarDebugLabel"] = "Astravar"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("owner")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
// Robolectric retains framework/font caches per test classloader. Isolate classes so
// the complete UI suite remains bounded locally and on the standard Actions runner.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "1536m"
    maxParallelForks = 1
    forkEvery = 1
    providers.gradleProperty("astravarScreenshots").orNull?.let {
        systemProperty("astravar.screenshots", it)
    }
}
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(providers.environmentVariable("ASTRAVAR_KEYSTORE").isPresent && providers.environmentVariable("ASTRAVAR_STORE_PASSWORD").isPresent && providers.environmentVariable("ASTRAVAR_KEY_PASSWORD").isPresent) {
            "Release signing identity is required. Provision owner signing through the documented secure environment; unsigned releases are not deliverables."
        }
    }
}
dependencies {
    implementation(project(":rules"))
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("com.google.firebase:firebase-messaging:24.1.2")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
