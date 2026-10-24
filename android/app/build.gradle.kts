plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val defaultPairingOrigin = "https://vault.axewhyzedlabs.co.in"
val configuredDebugOrigin = providers.gradleProperty("vaultDebugServerOrigin").orNull ?: defaultPairingOrigin

android {
    namespace = "app.vault.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.vault.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PAIRING_SERVER_ORIGIN", "\"$defaultPairingOrigin\"")
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("VAULT_KEYSTORE_PATH") ?: providers.gradleProperty("vaultKeystorePath").orNull
            val storePass = System.getenv("VAULT_KEYSTORE_PASSWORD") ?: providers.gradleProperty("vaultKeystorePassword").orNull
            val keyAlias = System.getenv("VAULT_KEY_ALIAS") ?: providers.gradleProperty("vaultKeyAlias").orNull
            val keyPass = System.getenv("VAULT_KEY_PASSWORD") ?: providers.gradleProperty("vaultKeyPassword").orNull

            if (keystorePath != null && file(keystorePath).exists() && !storePass.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = storePass
                this.keyAlias = keyAlias
                this.keyPassword = keyPass
            } else {
                initWith(getByName("debug"))
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "PAIRING_SERVER_ORIGIN", "\"$configuredDebugOrigin\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.work:work-testing:2.10.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("org.json:json:20240303")
}
