// 모든 모듈 공통: Kotlin, ktlint, JUnit, MockK

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = the<VersionCatalogsExtension>().named("libs")

group = "com.dozycoffee.auth"
// 라이브러리 배포 때만 -PreleaseVersion=0.1.0 으로 넘긴다 (배포 워크플로가 태그에서 읽음). 평소 빌드는 SNAPSHOT
version = providers.gradleProperty("releaseVersion").getOrElse("0.0.1-SNAPSHOT")

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("kotlin-test-junit5").get())
    "testImplementation"(libs.findLibrary("mockk").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
