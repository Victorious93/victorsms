// Victorious Framework — VictorSMS communications core.
// Pure Kotlin/JVM (no android.*): conversation archive schema + migrations, import engine, incremental merge,
// search/context retrieval and the automation safety gate. The Android app supplies a SqlDb adapter and
// the real senders; everything here is unit-tested on the JVM against real SQLite (sqlite-jdbc, test only).
plugins { alias(libs.plugins.kotlin.jvm) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile> { options.release.set(17) }
dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.sqlite.jdbc)
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
