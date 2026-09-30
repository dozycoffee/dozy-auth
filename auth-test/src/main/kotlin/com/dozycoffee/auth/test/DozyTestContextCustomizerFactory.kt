package com.dozycoffee.auth.test

import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import org.springframework.test.context.MergedContextConfiguration

/**
 * Spring 테스트가 컨텍스트를 만들 때만 [DozyTestConfiguration]을 등록합니다 (`META-INF/spring.factories`).
 *
 * `@SpringBootTest`, `@WebFluxTest`, `@WebMvcTest` 등 Spring TestContext로 만든 컨텍스트에만 적용되고,
 * 애플리케이션을 직접 실행한 컨텍스트에는 적용되지 않습니다.
 */
internal class DozyTestContextCustomizerFactory : ContextCustomizerFactory {
    override fun createContextCustomizer(
        testClass: Class<*>,
        configAttributes: List<ContextConfigurationAttributes>,
    ): ContextCustomizer = DozyTestContextCustomizer()
}

/** 모든 인스턴스가 같은 설정을 등록하므로 서로 같다고 봅니다. 테스트 컨텍스트 캐시가 이 값을 비교합니다. */
internal class DozyTestContextCustomizer : ContextCustomizer {
    override fun customizeContext(
        context: ConfigurableApplicationContext,
        mergedConfig: MergedContextConfiguration,
    ) {
        val registry = context as? BeanDefinitionRegistry ?: return
        AnnotatedBeanDefinitionReader(registry).register(DozyTestConfiguration::class.java)
    }

    override fun equals(other: Any?): Boolean = other is DozyTestContextCustomizer

    override fun hashCode(): Int = DozyTestContextCustomizer::class.hashCode()
}
