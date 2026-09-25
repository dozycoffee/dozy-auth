// auth-spring-boot-starter, auth-test: Spring을 쓰는 라이브러리 (JVM 17 타깃, Spring Boot BOM)

plugins {
    id("dozy.kotlin-library")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = the<VersionCatalogsExtension>().named("libs")

// Spring Boot BOM은 이 모듈을 빌드·테스트할 때만 쓴다.
// api/implementation에 걸면 배포 메타데이터에 실려 사용 서비스의 Spring 버전까지 바꾸므로,
// 배포되지 않는 classpath 구성에만 연결한다.
val springBootBom = configurations.dependencyScope("springBootBom")

dependencies {
    springBootBom(platform(libs.findLibrary("spring-boot-dependencies").get()))
}

listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach {
    configurations.named(it) { extendsFrom(springBootBom.get()) }
}
