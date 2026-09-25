plugins {
    id("dozy.spring-library")
}

description = "서비스의 토큰 검증·인가 자동 설정"

dependencies {
    api(project(":auth-core"))
    api(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.autoconfigure)
}
