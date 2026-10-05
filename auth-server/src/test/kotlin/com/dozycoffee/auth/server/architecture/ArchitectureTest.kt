package com.dozycoffee.auth.server.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * 헥사고날 구조 규칙 (docs/architecture.md §6, §7, ADR-0023, ADR-0025).
 *
 * 테스트 코드는 규칙 대상이 아니므로 production 소스만 검사합니다.
 */
class ArchitectureTest {
    private val serverFiles: List<KoFileDeclaration>
        get() = Konsist.scopeFromProduction(SERVER_MODULE).files

    /**
     * 같은 계층 안의 참조는 항상 허용합니다. 계층 패키지에 파일이 없으면(경로 오타 포함) `assertArchitecture`가 실패합니다.
     *
     * Konsist의 `dependsOn`은 허용만 선언하고 나머지를 막지 않으므로, 허용하지 않은 계층은 `doesNotDependOn`으로 막습니다.
     */
    @Test
    fun `계층은 정해진 방향으로만 의존`() {
        Konsist.scopeFromProduction(SERVER_MODULE).assertArchitecture {
            val domain = Layer("domain", "$DOMAIN..")
            val portInbound = Layer("application.port.inbound", "$ROOT.application.port.inbound..")
            val portOutbound = Layer("application.port.outbound", "$ROOT.application.port.outbound..")
            val service = Layer("application.service", "$SERVICE..")
            val adapterInbound = Layer("adapter.inbound", "$ROOT.adapter.inbound..")
            val adapterOutbound = Layer("adapter.outbound", "$ROOT.adapter.outbound..")
            val config = Layer("config", "$ROOT.config..")
            val layers = setOf(domain, portInbound, portOutbound, service, adapterInbound, adapterOutbound, config)

            // 계층 → 의존해도 되는 계층 (architecture.md §6.1)
            val allowed =
                mapOf(
                    portInbound to setOf(domain),
                    portOutbound to setOf(domain),
                    service to setOf(portInbound, portOutbound, domain),
                    adapterInbound to setOf(portInbound, domain),
                    adapterOutbound to setOf(portOutbound, domain),
                    config to layers - config,
                )

            domain.dependsOnNothing()
            allowed.forEach { (layer, targets) ->
                layer.dependsOn(targets)
                val forbidden = layers - layer - targets
                if (forbidden.isNotEmpty()) layer.doesNotDependOn(forbidden)
            }
        }
    }

    @Test
    fun `도메인 하위 패키지끼리 서로 import하지 않음`() {
        serverFiles
            .filter { it.packageName.startsWith("$DOMAIN.") }
            .assertFalse(testName = "도메인 간 import") { file ->
                val ownDomain = file.packageName.removePrefix("$DOMAIN.").substringBefore('.')
                file.imports.any { import ->
                    val rest = import.name.removePrefix("$DOMAIN.")
                    // domain 바로 아래 공통 타입(예: AuthException)은 허용하고, 다른 하위 패키지만 막는다
                    import.name.startsWith("$DOMAIN.") && rest.contains('.') && rest.substringBefore('.') != ownDomain
                }
            }
    }

    @Test
    fun `domain은 Spring과 Exposed를 import하지 않음`() {
        serverFiles
            .filter { it.packageName.isInPackage(DOMAIN) }
            .assertFalse(testName = "domain의 기술 의존") { file ->
                file.imports.any { it.name.startsWithAny(SPRING, EXPOSED) }
            }
    }

    @Test
    fun `application은 Exposed, 웹 클래스, Micrometer를 import하지 않음`() {
        serverFiles
            .filter { it.packageName.isInPackage("$ROOT.application") }
            .assertFalse(testName = "application의 기술 의존") { file ->
                file.imports.any { it.name.startsWithAny(EXPOSED, MICROMETER, *WEB) }
            }
    }

    @Test
    fun `@Transactional은 application service에서만 사용`() {
        serverFiles
            .filter { file -> file.imports.any { it.name in TRANSACTIONAL } }
            .assertTrue(testName = "@Transactional 위치") { it.packageName.isInPackage(SERVICE) }
    }

    @Test
    fun `UseCase·Port·Adapter 같은 접미사가 붙은 클래스는 정해진 패키지에 있음`() {
        val scope = Konsist.scopeFromProduction(SERVER_MODULE)
        val declarations =
            scope.classes().map { it.name to it.packagee?.name.orEmpty() } +
                scope.interfaces().map { it.name to it.packagee?.name.orEmpty() } +
                scope.objects().map { it.name to it.packagee?.name.orEmpty() }

        val violations =
            declarations.mapNotNull { (name, packageName) ->
                val expected = NAMING.entries.firstOrNull { name.endsWith(it.key) }?.value ?: return@mapNotNull null
                if (packageName.isInPackage(expected)) null else "$packageName.$name → $expected"
            }

        check(violations.isEmpty()) { "이름 접미사와 위치가 맞지 않습니다 (architecture.md §7):\n${violations.joinToString("\n")}" }
    }

    @Test
    fun `auth-core는 Kotlin·Java 표준 라이브러리와 자기 패키지만 import`() {
        Konsist
            .scopeFromProduction(CORE_MODULE)
            .files
            .assertTrue(testName = "auth-core 의존") { file ->
                file.imports.all { it.name.startsWithAny("kotlin.", "java.", "com.dozycoffee.auth.core.") }
            }
    }

    private companion object {
        const val SERVER_MODULE = "auth-server"
        const val CORE_MODULE = "auth-core"

        const val ROOT = "com.dozycoffee.auth.server"
        const val DOMAIN = "$ROOT.domain"
        const val SERVICE = "$ROOT.application.service"

        const val SPRING = "org.springframework."
        const val EXPOSED = "org.jetbrains.exposed."

        /** 지표는 `RecordMetricsPort`로 남깁니다 (architecture.md §9). */
        const val MICROMETER = "io.micrometer."
        val WEB = arrayOf("org.springframework.web.", "org.springframework.http.", "jakarta.servlet.")
        val TRANSACTIONAL =
            setOf("org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional")

        /** 이름 접미사 → 있어야 할 패키지 (architecture.md §7). */
        val NAMING =
            mapOf(
                "UseCase" to "$ROOT.application.port.inbound",
                "Command" to "$ROOT.application.port.inbound",
                "Port" to "$ROOT.application.port.outbound",
                "Adapter" to "$ROOT.adapter.outbound",
                "Table" to "$ROOT.adapter.outbound.persistence",
                "Controller" to "$ROOT.adapter.inbound.web",
            )

        val KoFileDeclaration.packageName: String
            get() = packagee?.name.orEmpty()

        fun String.isInPackage(parent: String): Boolean = this == parent || startsWith("$parent.")

        fun String.startsWithAny(vararg prefixes: String): Boolean = prefixes.any { startsWith(it) }
    }
}
