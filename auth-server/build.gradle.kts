plugins {
    id("dozy.spring-app")
}

description = "Dozy Auth 서버"

dependencies {
    implementation(project(":auth-core"))

    // 웹·보안
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bucket4j.core)

    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)

    // 데이터
    implementation(platform(libs.exposed.bom))
    implementation(libs.bundles.exposed)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // 메일
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.thymeleaf)

    // 운영
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.prometheus)
    developmentOnly(libs.spring.boot.docker.compose)

    // 테스트
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.springmockk)
    testImplementation(libs.konsist)
}
