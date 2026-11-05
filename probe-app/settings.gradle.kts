// probe-app 独立 settings（可选入口：cd probe-app && gradle assembleDebug）
// 注意：本工程同时挂在根 settings.gradle.kts（include(":probe-app")），根构建内此文件不生效；
//       作为独立工程时本文件是根项目 settings（单项目，无 :app 子模块）。
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
rootProject.name = "probe-app"
