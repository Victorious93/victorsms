pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://download.linphone.org/maven_repository") { content { includeGroup("org.linphone") } }
    }
}
rootProject.name = "VictorSMS"
include(":app")
include(":mail-core")
