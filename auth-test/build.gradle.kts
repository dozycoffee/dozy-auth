plugins {
    id("dozy.spring-library")
}

description = "서비스 테스트 지원 (@WithDozyPrincipal, 테스트용 토큰)"

dependencies {
    api(project(":auth-core"))
    api(project(":auth-spring-boot-starter"))
    api(libs.spring.boot.starter.security.test)
}
