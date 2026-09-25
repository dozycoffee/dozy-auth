pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // 로컬에 없는 JDK(라이브러리용 17, 서버용 21)를 Gradle이 자동으로 내려받게 함
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dozy-auth"

include(
    "auth-core",
    "auth-server",
    "auth-spring-boot-starter",
    "auth-test",
)
