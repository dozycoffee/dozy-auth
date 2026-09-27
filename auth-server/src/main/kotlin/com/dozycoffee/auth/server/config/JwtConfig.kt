package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.out.jwt.SigningKeyLoader
import com.dozycoffee.auth.server.adapter.out.jwt.SigningKeyProperties
import com.dozycoffee.auth.server.adapter.out.jwt.SigningKeys
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/** 서명 키 조립. 키 문제는 기동 시점에 실패시킵니다 (configuration.md §2). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SigningKeyProperties::class)
class JwtConfig {
    @Bean
    fun signingKeys(
        properties: SigningKeyProperties,
        clock: Clock,
        environment: Environment,
    ): SigningKeys {
        check(!(properties.autoGenerate && environment.matchesProfiles("prod"))) {
            "prod 프로필에서는 서명 키 자동 생성(dozy.auth.signing.auto-generate)을 켤 수 없습니다"
        }
        return SigningKeyLoader(clock).load(properties)
    }
}
