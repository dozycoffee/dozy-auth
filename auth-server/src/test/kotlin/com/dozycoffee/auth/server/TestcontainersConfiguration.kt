package com.dozycoffee.auth.server

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = SHARED_POSTGRES

    private companion object {
        /**
         * 테스트 JVM 하나에서 컨테이너를 한 번만 띄웁니다. 스프링 컨텍스트가 여러 개여도 같은 컨테이너를 씁니다.
         * 종료는 Testcontainers(Ryuk)가 JVM 종료 때 정리합니다.
         * 로컬에서 컨테이너를 실행 사이에도 남기려면 `~/.testcontainers.properties`에 `testcontainers.reuse.enable=true`를 둡니다.
         */
        val SHARED_POSTGRES: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18")).withReuse(true)
    }
}
