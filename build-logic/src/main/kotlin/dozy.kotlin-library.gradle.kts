// auth-core, auth-spring-boot-starter, auth-test: 서비스 호환을 위해 JVM 17 타깃

plugins {
    id("dozy.kotlin-base")
    `java-library`
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}
