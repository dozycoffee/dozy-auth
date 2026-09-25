// auth-server: Spring Boot 앱, JDK 21

plugins {
    id("dozy.kotlin-base")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
}

dependencies {
    val springBootBom = platform(libs.findLibrary("spring-boot-dependencies").get())
    "implementation"(springBootBom)
    "developmentOnly"(springBootBom)
}

// compose.yaml과 .local/ 을 저장소 루트 기준으로 찾도록
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootDir
}
