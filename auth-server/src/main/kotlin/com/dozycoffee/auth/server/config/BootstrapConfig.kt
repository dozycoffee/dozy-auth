package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.inbound.startup.OwnerBootstrapListener
import com.dozycoffee.auth.server.adapter.inbound.startup.OwnerBootstrapProperties
import com.dozycoffee.auth.server.application.port.inbound.system.BootstrapOwnerUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 기동 작업 조립 (architecture.md §2 기동 작업, configuration.md §4). `dozy.auth.bootstrap.enabled=false`이면 등록하지 않습니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OwnerBootstrapProperties::class)
class BootstrapConfig {
    @Bean
    @ConditionalOnBooleanProperty("dozy.auth.bootstrap.enabled", matchIfMissing = true)
    fun ownerBootstrapListener(
        bootstrapOwner: BootstrapOwnerUseCase,
        properties: OwnerBootstrapProperties,
    ): OwnerBootstrapListener = OwnerBootstrapListener(bootstrapOwner, properties)
}
