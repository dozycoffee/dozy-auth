package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.server.TestcontainersConfiguration
import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * 영속성 어댑터(`adapter/outbound/persistence`) 테스트용 애노테이션 (testing.md §1).
 *
 * - 실제 PostgreSQL 18(Testcontainers)에 Flyway 마이그레이션을 적용한 DB를 씁니다.
 * - DataSource, Flyway, Exposed 자동 설정만 켭니다. 웹·보안·서명 키는 올리지 않습니다.
 * - 각 테스트는 트랜잭션 안에서 실행하고 끝나면 되돌립니다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(classes = [PersistenceTestConfiguration::class])
@ActiveProfiles("test")
@Transactional
annotation class PersistenceAdapterTest

@Configuration(proxyBeanMethods = false)
@Import(TestcontainersConfiguration::class)
@ImportAutoConfiguration(
    ServiceConnectionAutoConfiguration::class,
    DataSourceAutoConfiguration::class,
    TransactionAutoConfiguration::class,
    FlywayAutoConfiguration::class,
    ExposedAutoConfiguration::class,
)
class PersistenceTestConfiguration
