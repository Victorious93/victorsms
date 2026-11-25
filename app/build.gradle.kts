plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes ONLY from environment variables / CI secrets.
// If any are missing, the release signingConfig is not created and release builds stay unsigned.
val releaseStoreFile: String? = System.getenv("VICTORSMS_RELEASE_STORE_FILE")
val releaseStorePassword: String? = System.getenv("VICTORSMS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("VICTORSMS_RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("VICTORSMS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.victorious.sms"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.victorious.sms"
        minSdk = 26
        targetSdk = 36
        versionCode = 300
        versionName = "0.3.0"
    }

    signingConfigs {
        getByName("debug") {
            // Project-owned debug key (NOT a release key). See README.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }

    packaging { resources { excludes += listOf("META-INF/NOTICE*", "META-INF/LICENSE*", "META-INF/DEPENDENCIES", "META-INF/*.md", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") } }

    // Two editions (see docs/EDITIONS.md):
    //   standard — Apache-2.0 sources + proprietary Twilio Voice SDK (alias calls over Twilio); no SIP engine.
    //   voip     — Linphone SDK (GPLv3) own-SIP-server calling; NO Twilio Voice SDK. Binaries of this edition are GPLv3.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") { dimension = "edition" }
        create("voip") { dimension = "edition"; applicationIdSuffix = ".voip"; versionNameSuffix = "-voip" }
    }

    // One APK per ABI (the Matrix Rust SDK ships large native libs for every ABI).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    // Ship docs/MANUAL.md as an asset for the in-app Help screen.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("docs"))
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    // Robolectric resolves its SDK jars at test runtime, outside Gradle's mavenCentral().
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.matrix.sdk.android)
    implementation(project(":mail-core"))   // IMAP/SMTP, outbox, sanitiser (Angus Mail, jsoup)
    implementation(project(":comms-core"))  // conversation archive, import engine, automation gate (pure Kotlin)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    // Alias calls: Twilio Voice SDK (Twilio TOS licence) + FCM for incoming-call pushes (Apache-2.0).
    "standardImplementation"(libs.twilio.voice.android)
    "standardImplementation"(libs.firebase.messaging)
    "voipImplementation"("org.linphone:linphone-sdk-android:5.3.+")   // GPLv3; repo: download.linphone.org/maven_repository
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    // Android SQLite on the JVM (native mode) to verify the archive adapter; not a device.
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Compose UI tests rendered by Robolectric on the JVM (not a device).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Real org.json on the JVM (android.jar stubs throw in local unit tests).
    testImplementation("org.json:json:20250517")
}
