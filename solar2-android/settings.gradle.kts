pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "solar2-android"

include(":core")

// Le module :app (interface Android) exige le SDK Android. Android Studio crée local.properties
// (sdk.dir) à l'ouverture du projet ; sans SDK, seul :core (Kotlin pur, testable avec un simple
// JDK) est configuré, ce qui permet de lancer ./gradlew :core:test partout.
val sdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (sdkAvailable) include(":app")
