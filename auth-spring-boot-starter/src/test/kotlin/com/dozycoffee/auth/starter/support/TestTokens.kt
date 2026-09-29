package com.dozycoffee.auth.starter.support

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 스타터 테스트의 입력 데이터와 토큰 만들기.
 *
 * claim 이름과 형식은 token.md §3의 문자열을 그대로 씁니다. 검사할 값만 바꿔 넘깁니다.
 */
object TestTokens {
    const val ISSUER_BASE = "https://auth.dozycoffee.com"

    val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    val EMPLOYEE_ID: UUID = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")
    val PARTNER_ID: UUID = UUID.fromString("0199a3c5-1d4f-7a8b-b2c6-5e9f0a3d7c21")
    const val SESSION_ID = "8c1d4f5f-2b9c-4e8a-a4d1-c9d3f2b7a6e0"

    /** 직원 토큰 claim. 바꿀 값만 [overrides]로 넘기고, 값이 null이면 claim을 뺍니다. */
    fun employeeClaims(vararg overrides: Pair<String, Any?>): Map<String, Any> =
        claims(
            "iss" to "$ISSUER_BASE/realms/internal",
            "sub" to "employee:$EMPLOYEE_ID",
            "aud" to listOf("sample", "other"),
            "principalType" to "employee",
            "principalId" to EMPLOYEE_ID.toString(),
            "roles" to listOf("sample:item_manager", "other:item_editor"),
            "iat" to NOW.epochSecond,
            "exp" to NOW.plusSeconds(600).epochSecond,
            "jti" to "5f2b9c1e-8a4d-4c1e-9d3f-2b7a6e0c1d4f",
            "sid" to SESSION_ID,
            *overrides,
        )

    fun partnerClaims(vararg overrides: Pair<String, Any?>): Map<String, Any> =
        claims(
            "iss" to "$ISSUER_BASE/realms/partner",
            "sub" to "partner:$PARTNER_ID",
            "aud" to listOf("store"),
            "principalType" to "partner",
            "principalId" to PARTNER_ID.toString(),
            "roles" to emptyList<String>(),
            "iat" to NOW.epochSecond,
            "exp" to NOW.plusSeconds(600).epochSecond,
            "jti" to "6a3c0d2f-9b5e-4d2f-8e4a-3c8b7f1d2e5a",
            "sid" to SESSION_ID,
            *overrides,
        )

    fun sign(
        claims: Map<String, Any>,
        key: RSAKey = TestKeys.CURRENT,
        type: String? = "at+jwt",
    ): String {
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID(key.keyID)
                .apply { type?.let { type(JOSEObjectType(it)) } }
                .build()
        return JWSObject(header, Payload(claims)).apply { sign(RSASSASigner(key)) }.serialize()
    }

    private fun claims(vararg entries: Pair<String, Any?>): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        for ((name, value) in entries) if (value == null) result.remove(name) else result[name] = value
        return result
    }
}
