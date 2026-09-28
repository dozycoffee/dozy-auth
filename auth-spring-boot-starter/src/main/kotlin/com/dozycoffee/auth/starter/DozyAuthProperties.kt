package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.Jwks
import com.dozycoffee.auth.core.Realm
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 스타터 설정 (starter.md §2). 필수 속성이 없으면 기동에 실패합니다.
 *
 * 생성자 바인딩은 Kotlin 기본값을 쓰려면 `kotlin-reflect`가 필요해서, setter 바인딩을 씁니다.
 * 서비스 간 호출 설정(`dozy.auth.client.*`)은 아직 제공하지 않습니다.
 */
@ConfigurationProperties(prefix = "dozy.auth")
public class DozyAuthProperties : InitializingBean {
    /** 이 서비스의 audience (`wms`, `catalog`, `store`). */
    public var audience: String = ""

    /** 받을 realm 목록 (token.md §6 서비스별 허용 realm). */
    public var acceptedRealms: Set<Realm> = emptySet()

    /** Auth 주소. 허용 issuer는 `{base}/realms/{realm}`. */
    public var issuerBaseUri: String = ""

    /** JWKS 주소. 비우면 `{issuer-base-uri}/.well-known/jwks.json`. */
    public var jwkSetUri: String? = null

    /** `exp`·`iat` 검증에서 허용하는 시계 오차 (domain.md §2 `policy.clock-skew`). */
    public var clockSkew: Duration = Duration.ofSeconds(30)

    /** 인증 없이 허용할 경로 패턴. */
    public var publicPaths: List<String> = emptyList()

    /** `@PreAuthorize` 활성화. */
    public var methodSecurity: Boolean = true

    override fun afterPropertiesSet() {
        require(audience.isNotBlank()) { "dozy.auth.audience를 설정해야 합니다 (예: wms)" }
        require(acceptedRealms.isNotEmpty()) { "dozy.auth.accepted-realms를 설정해야 합니다 (예: [internal])" }
        require(issuerBaseUri.startsWith("https://") || issuerBaseUri.startsWith("http://")) {
            "dozy.auth.issuer-base-uri를 http 또는 https 주소로 설정해야 합니다: '$issuerBaseUri'"
        }
        require(!clockSkew.isNegative) { "dozy.auth.clock-skew는 0 이상이어야 합니다: $clockSkew" }
    }

    /** 실제로 쓰는 JWKS 주소. */
    internal val resolvedJwkSetUri: String
        get() = jwkSetUri ?: "${issuerBaseUri.trimEnd('/')}${Jwks.PATH}"

    /** 허용하는 issuer와 그 realm. */
    internal val acceptedIssuers: Map<String, Realm>
        get() = acceptedRealms.associateBy { it.issuer(issuerBaseUri) }
}
