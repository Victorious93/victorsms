pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        if (providers.gradleProperty("withLinphone").isPresent) maven("https://download.linphone.org/maven_repository")
    }
}
rootProject.name = "VictorSMS"
include(":app")
include(":mail-core")
