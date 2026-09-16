pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroup("com.google.gms")
                includeGroup("com.google.firebase")
                includeGroup("com.google.mlkit")
                includeGroup("com.google.testing.platform")
            }
        }
    }
}

rootProject.name = "arl-target"
include(":app")
