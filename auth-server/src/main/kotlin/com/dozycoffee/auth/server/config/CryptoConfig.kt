package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.outbound.crypto.Argon2Properties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** 비밀번호 해시 파라미터 (configuration.md §6). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Argon2Properties::class)
class CryptoConfig
