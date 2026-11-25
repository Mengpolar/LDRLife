pluginManagement {
    repositories {
        // 插件解析以官方仓库优先（CI 环境可靠）；aliyun 仅作国内网络兜底
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 依赖解析同样官方仓库优先（GitHub runner 访问 aliyun 会 502）；aliyun 作国内网络兜底
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}
rootProject.name = "LDRLife"
include(":app")
