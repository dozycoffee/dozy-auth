plugins {
    id("dozy.spring-library")
}

description = "서비스의 토큰 검증·인가 자동 설정"

dependencies {
    api(project(":auth-core"))
    api(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.autoconfigure)
    // system token 클라이언트 (starter.md §6). 공개 API에 타입을 드러내지 않으므로 서비스 컴파일 classpath에 싣지 않는다.
    // Spring Boot의 OAuth2 Client 자동 설정 모듈이 아니라 Spring Security 라이브러리만 가져가 서비스 설정을 바꾸지 않는다.
    implementation(libs.spring.security.oauth2.client)
    // 설정 클래스의 생성자 바인딩이 Kotlin 기본값을 쓰려면 필요
    implementation(libs.kotlin.reflect)

    // 서비스가 가진 것을 그대로 쓴다 (Spring MVC 또는 WebFlux, 선택적으로 Micrometer Tracing)
    compileOnly(libs.spring.boot.starter.webmvc)
    compileOnly(libs.spring.boot.starter.webflux)
    compileOnly(libs.micrometer.tracing)

    testImplementation(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.kotlinx.coroutines.reactor)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.micrometer.tracing)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.spring.boot.webclient)
    // 서비스가 Jackson 2를 쓸 때의 401·403 본문 확인 (Boot 4 기본은 Jackson 3)
    testImplementation(libs.jackson2.databind)
}
