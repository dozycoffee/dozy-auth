package com.dozycoffee.auth.starter

import com.nimbusds.jose.util.JSONObjectUtils
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals

/**
 * 직접 쓴 설정 메타데이터(`META-INF/spring-configuration-metadata.json`)가 설정 클래스([DozyAuthProperties], [DozyAuthClientProperties])와
 * 어긋나지 않는지 확인합니다.
 */
class ConfigurationMetadataTest {
    @Test
    fun `메타데이터의 속성 목록은 설정 클래스의 속성과 같음`() {
        val json = checkNotNull(javaClass.getResource("/META-INF/spring-configuration-metadata.json")).readText()
        val documented =
            JSONObjectUtils
                .parse(json)["properties"]
                .let { it as List<*> }
                .map { (it as Map<*, *>)["name"] as String }
                .toSet()

        val declared = declared("dozy.auth", DozyAuthProperties::class) + declared("dozy.auth.client", DozyAuthClientProperties::class)

        assertEquals(declared, documented)
    }

    private fun declared(
        prefix: String,
        type: KClass<*>,
    ): Set<String> =
        checkNotNull(type.primaryConstructor)
            .parameters
            .map { "$prefix." + checkNotNull(it.name).replace(Regex("[A-Z]")) { m -> "-" + m.value.lowercase() } }
            .toSet()
}
