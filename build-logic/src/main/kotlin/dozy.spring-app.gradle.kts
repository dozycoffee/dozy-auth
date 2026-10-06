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

// 서버 버전 정보 (ADR-0032, configuration.md §12.1). META-INF/build-info.properties에 넣어 기동 로그와 build_info 지표가 읽는다.
// CI가 -PserverVersion=sha-{7자리} -PserverRevision={커밋 SHA 전체}로 넘기고, 로컬 빌드는 local·unknown이다.
// 릴리스는 이미지를 다시 빌드하지 않고 태그만 붙이므로 semver(X.Y.Z)는 여기 들어가지 않는다.
// 빌드 시각은 넣지 않는다. 같은 커밋이면 같은 jar가 나와 Gradle·이미지 캐시가 그대로 쓰이게 하기 위해서다
springBoot {
    buildInfo {
        excludes = setOf("time")
        properties {
            version = providers.gradleProperty("serverVersion").orElse("local")
            additional.put("revision", providers.gradleProperty("serverRevision").orElse("unknown"))
        }
    }
}
