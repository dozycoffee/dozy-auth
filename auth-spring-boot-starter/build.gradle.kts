plugins {
    id("dozy.spring-library")
}

description = "서비스의 토큰 검증·인가 자동 설정"

dependencies {
    api(project(":auth-core"))
    api(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.autoconfigure)

    // 서비스가 가진 것을 그대로 쓴다 (servlet 웹 앱, 선택적으로 Micrometer Tracing)
    compileOnly(libs.spring.boot.starter.webmvc)
    compileOnly(libs.micrometer.tracing)

    testImplementation(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.micrometer.tracing)
}
