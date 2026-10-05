package com.dozycoffee.auth.server.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

/**
 * `prod` 프로필의 로그 형식 (configuration.md §4, §10). 로그 설정은 JVM 전체에 걸리므로 서버를 띄우지 않고, `prod` 설정 파일의 형식으로
 * Spring Boot의 구조화 로그 인코더를 만들어 확인합니다. 요청 로그에 trace id가 들어가는 것은 `ObservabilityApiTest`가 확인합니다.
 */
class ProdLoggingTest {
    @Test
    fun `prod 프로필 로그는 한 줄 JSON이고 MDC의 trace id를 담음`() {
        val format = prodProperty("logging.structured.format.console")
        assertEquals("ecs", format)

        val line = encode(format, mapOf("traceId" to TRACE_ID, "spanId" to SPAN_ID), "서비스 토큰 발급: result=issued")

        assertEquals(1, line.trimEnd().lines().size, line)
        val json = jsonMapper.readValue(line, Map::class.java)
        assertEquals("서비스 토큰 발급: result=issued", json["message"])
        assertEquals("INFO", (json["log"] as Map<*, *>)["level"])
        assertEquals(TRACE_ID, json["traceId"])
        assertEquals(SPAN_ID, json["spanId"])
    }

    private fun prodProperty(name: String): String =
        YamlPropertySourceLoader()
            .load("prod", ClassPathResource("application-prod.yaml"))
            .firstNotNullOf { it.getProperty(name) }
            .toString()

    private fun encode(
        format: String,
        mdc: Map<String, String>,
        message: String,
    ): String {
        val context = LoggerContext()
        context.putObject(Environment::class.java.name, MockEnvironment().withProperty("spring.application.name", "dozy-auth"))
        val encoder =
            StructuredLogEncoder().apply {
                this.context = context
                setFormat(format)
                start()
            }
        val event =
            LoggingEvent().apply {
                loggerName = "com.dozycoffee.auth.server.Test"
                level = Level.INFO
                this.message = message
                threadName = "test"
                timeStamp = 0
                setMDCPropertyMap(mdc)
            }
        return String(encoder.encode(event), Charsets.UTF_8)
    }

    private companion object {
        const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val SPAN_ID = "00f067aa0ba902b7"
        val jsonMapper: JsonMapper = JsonMapper.builder().build()
    }
}
