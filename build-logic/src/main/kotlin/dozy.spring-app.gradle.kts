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

// 컨테이너 이미지(auth-server/Dockerfile)가 버전과 관계없이 같은 경로에서 실행 jar를 찾도록.
// build가 함께 만드는 *-plain.jar는 의존성이 없는 jar라 이미지에 쓰지 않는다
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "auth-server.jar"
}
