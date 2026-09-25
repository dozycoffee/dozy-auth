package com.dozycoffee.auth.server.architecture

import com.lemonappdev.konsist.api.Konsist
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
     * Konsist의 `assertArchitecture`는 파일이 없는 계층이 있으면 실패하므로, 계층이 채워지기 전에도 동작하도록 import를 직접 검사합니다.
     */
    @Test
    fun `§6_1 계층은 정해진 방향으로만 의존`() {
        val violations =
            serverFiles.flatMap { file ->
                val from = layerOf(file.packageName) ?: return@flatMap emptyList()
                if (from == Layer.CONFIG) return@flatMap emptyList()

                file.imports.mapNotNull { import ->
                    val to = layerOf(import.name) ?: return@mapNotNull null
                    if (to == from || to in from.allowed) null else "${file.packageName} (${from.name}) → ${import.name} (${to.name})"
                }
            }

        check(violations.isEmpty()) { "계층 의존 규칙 위반 (architecture.md §6.1):\n${violations.joinToString("\n")}" }
    }

    @Test
    fun `§6_2 도메인 하위 패키지끼리 import하지 않음`() {
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
    fun `§6_1 domain은 Spring과 Exposed를 import하지 않음`() {
        serverFiles
            .filter { it.packageName.isInPackage(DOMAIN) }
            .assertFalse(testName = "domain의 기술 의존") { file ->
                file.imports.any { it.name.startsWithAny(SPRING, EXPOSED) }
            }
    }

    @Test
    fun `§6_1 application은 Exposed와 웹 클래스를 import하지 않음`() {
        serverFiles
            .filter { it.packageName.isInPackage("$ROOT.application") }
            .assertFalse(testName = "application의 기술 의존") { file ->
                file.imports.any { it.name.startsWithAny(EXPOSED, *WEB) }
            }
    }

    @Test
    fun `§6_3 @Transactional은 application service에만`() {
        serverFiles
            .filter { file -> file.imports.any { it.name in TRANSACTIONAL } }
            .assertTrue(testName = "@Transactional 위치") { it.packageName.isInPackage(SERVICE) }
    }

    @Test
    fun `§7 이름 접미사별 위치`() {
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
    fun `§6_3 auth-core는 Kotlin·Java 표준 라이브러리와 자기 패키지만 import`() {
        Konsist
            .scopeFromProduction(CORE_MODULE)
            .files
            .assertTrue(testName = "auth-core 의존") { file ->
                file.imports.all { it.name.startsWithAny("kotlin.", "java.", "com.dozycoffee.auth.core.") }
            }
    }

    /** auth-server 계층과 의존해도 되는 계층 (architecture.md §6.1). 같은 계층 안의 참조는 항상 허용합니다. */
    private enum class Layer(
        val packageName: String,
        allowed: () -> Set<Layer>,
    ) {
        DOMAIN("$ROOT.domain", { emptySet() }),
        PORT_IN("$ROOT.application.port.in", { setOf(DOMAIN) }),
        PORT_OUT("$ROOT.application.port.out", { setOf(DOMAIN) }),
        SERVICE("$ROOT.application.service", { setOf(PORT_IN, PORT_OUT, DOMAIN) }),
        ADAPTER_IN("$ROOT.adapter.in", { setOf(PORT_IN, DOMAIN) }),
        ADAPTER_OUT("$ROOT.adapter.out", { setOf(PORT_OUT, DOMAIN) }),
        CONFIG("$ROOT.config", { Layer.entries.toSet() }),
        ;

        val allowed: Set<Layer> by lazy(allowed)
    }

    private companion object {
        const val SERVER_MODULE = "auth-server"
        const val CORE_MODULE = "auth-core"

        const val ROOT = "com.dozycoffee.auth.server"
        const val DOMAIN = "$ROOT.domain"
        const val SERVICE = "$ROOT.application.service"

        const val SPRING = "org.springframework."
        const val EXPOSED = "org.jetbrains.exposed."
        val WEB = arrayOf("org.springframework.web.", "org.springframework.http.", "jakarta.servlet.")
        val TRANSACTIONAL =
            setOf("org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional")

        /** 이름 접미사 → 있어야 할 패키지 (architecture.md §7). */
        val NAMING =
            mapOf(
                "UseCase" to "$ROOT.application.port.in",
                "Command" to "$ROOT.application.port.in",
                "Port" to "$ROOT.application.port.out",
                "Adapter" to "$ROOT.adapter.out",
                "Table" to "$ROOT.adapter.out.persistence",
                "Controller" to "$ROOT.adapter.in.web",
            )

        val KoFileDeclaration.packageName: String
            get() = packagee?.name.orEmpty()

        /** 가장 구체적으로 맞는 계층. 계층 밖(루트 패키지, 외부 라이브러리)이면 `null`. */
        fun layerOf(name: String): Layer? = Layer.entries.filter { name.isInPackage(it.packageName) }.maxByOrNull { it.packageName.length }

        fun String.isInPackage(parent: String): Boolean = this == parent || startsWith("$parent.")

        fun String.startsWithAny(vararg prefixes: String): Boolean = prefixes.any { startsWith(it) }
    }
}
