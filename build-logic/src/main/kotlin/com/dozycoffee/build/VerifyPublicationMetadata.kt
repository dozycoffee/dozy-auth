package com.dozycoffee.build

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 배포할 POM과 Gradle 모듈 메타데이터가 계약대로인지 확인합니다 (starter.md §1).
 *
 * - Spring Boot BOM을 싣지 않습니다. 실리면 서비스의 Spring 버전을 바꿉니다.
 * - Auth 라이브러리끼리는 같은 버전을 의존합니다 (세 모듈 한 버전).
 * - Kotlin 의존성(kotlin-stdlib, kotlin-reflect)은 버전이 있습니다. Boot BOM이 배포에 없으므로 버전 없이 나가면 서비스가 받을 수 없습니다.
 */
abstract class VerifyPublicationMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pomFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleFile: RegularFileProperty

    @get:Input
    abstract val expectedVersion: Property<String>

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        problems += checkPom()
        problems += checkModule()
        if (problems.isNotEmpty()) {
            throw GradleException("배포 메타데이터가 계약과 다릅니다:\n" + problems.joinToString("\n") { "- $it" })
        }
    }

    private fun checkPom(): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile.get().asFile)
        val problems = mutableListOf<String>()
        if (document.getElementsByTagName("dependencyManagement").length > 0) {
            problems += "POM에 dependencyManagement(BOM)가 있습니다"
        }
        val dependencies = document.getElementsByTagName("dependency")
        for (i in 0 until dependencies.length) {
            val dependency = dependencies.item(i) as Element
            problems += checkDependency("POM", dependency.text("groupId"), dependency.text("artifactId"), dependency.text("version"))
        }
        return problems
    }

    @Suppress("UNCHECKED_CAST")
    private fun checkModule(): List<String> {
        val module = JsonSlurper().parse(moduleFile.get().asFile) as Map<String, Any?>
        val problems = mutableListOf<String>()
        for (variant in module["variants"] as List<Map<String, Any?>>) {
            val variantName = variant["name"]
            for (dependency in variant["dependencies"] as List<Map<String, Any?>>? ?: emptyList()) {
                val group = dependency["group"] as String
                val name = dependency["module"] as String
                val version = (dependency["version"] as Map<String, Any?>?)?.get("requires") as String?
                val category = (dependency["attributes"] as Map<String, Any?>?)?.get("org.gradle.category")
                if (category == "platform" || category == "enforced-platform") {
                    problems += ".module($variantName)에 BOM 의존성이 있습니다: $group:$name"
                }
                problems += checkDependency(".module($variantName)", group, name, version)
            }
        }
        return problems
    }

    private fun checkDependency(
        where: String,
        group: String?,
        name: String?,
        version: String?,
    ): List<String> =
        when {
            group == AUTH_GROUP && version != expectedVersion.get() ->
                listOf("$where: $group:$name 의 버전이 ${expectedVersion.get()}가 아닙니다 ($version)")
            group == KOTLIN_GROUP && version.isNullOrBlank() ->
                listOf("$where: $group:$name 에 버전이 없습니다")
            else -> emptyList()
        }

    private fun Element.text(tag: String): String? = getElementsByTagName(tag).item(0)?.textContent?.trim()

    private companion object {
        const val AUTH_GROUP = "com.dozycoffee.auth"
        const val KOTLIN_GROUP = "org.jetbrains.kotlin"
    }
}
