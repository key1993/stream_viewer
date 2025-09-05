pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // FFmpegKit repository (scoped to com.arthenica only to avoid conflicts)
        maven {
            url = uri("https://repo.ffmpegkit.org/maven")
            content {
                includeGroup("com.arthenica")
            }
        }
    }
}

rootProject.name = "StreamViewer"
include(":app")
 