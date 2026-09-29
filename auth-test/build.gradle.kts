plugins {
    id("dozy.spring-library")
}

description = "서비스 테스트 지원 (@WithDozyPrincipal, 테스트용 토큰)"

dependencies {
    api(project(":auth-core"))
    api(project(":auth-spring-boot-starter"))
    api(libs.spring.boot.starter.security.test)
    implementation(libs.spring.boot.autoconfigure)

    // 서비스가 가진 것을 그대로 쓴다 (Spring MVC 또는 WebFlux)
    compileOnly(libs.spring.boot.starter.webmvc)
    compileOnly(libs.spring.boot.starter.webflux)

    testImplementation(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.kotlinx.coroutines.reactor)
}
