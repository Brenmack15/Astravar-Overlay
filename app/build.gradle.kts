plugins {
    id("com.android.application"); kotlin("android"); kotlin("plugin.compose")
    kotlin("plugin.serialization"); id("com.google.devtools.ksp")
}
android {
    namespace = "com.yazsras.astravar"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yazsras.astravar"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("ASTRAVAR_VERSION_CODE").map(String::toInt).getOrElse(2)
        versionName = providers.environmentVariable("ASTRAVAR_VERSION_NAME").getOrElse("1.1.0")
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
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-test" }
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
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
