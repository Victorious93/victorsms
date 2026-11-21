plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Bytecode for Android's Java 17 level without requiring a JDK 17 toolchain on the build machine (any JDK >= 17 works).
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    // Mail stack decision: see docs/MAIL.md. Angus Mail is the Eclipse implementation of Jakarta Mail (EPL-2.0 / GPLv2+CE).
    api(libs.angus.mail)
    implementation(libs.jsoup)
    // org.json is part of Android; compile against it and use the real library only in JVM tests.
    compileOnly("org.json:json:20250517")
    testImplementation("org.json:json:20250517")
    testImplementation(libs.greenmail)
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }
