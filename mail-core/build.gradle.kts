plugins { alias(libs.plugins.kotlin.jvm) }
// Compiled for Java 17 bytecode (the app's target) with whatever JDK >= 17 runs Gradle.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile> { options.release.set(17) }
dependencies {
    api(libs.angus.mail)          // Eclipse Angus Mail (Jakarta Mail impl): IMAP + authenticated SMTP, TLS
    implementation(libs.jsoup)    // allow-list HTML sanitiser for untrusted mail
    testImplementation(libs.greenmail)
    testImplementation("junit:junit:4.13.2")
}
