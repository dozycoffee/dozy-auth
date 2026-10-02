package com.dozycoffee.auth.server.domain.audit

import com.dozycoffee.auth.core.Realm
import java.time.Instant

/**
 * 감사 로그에 남길 사건 하나 (docs/data-model.md §3.11).
 *
 * **기록 단위 (AUD-08)**: 요청 하나에 action 하나가 기본입니다. 여러 role을 한 번에 부여하면 `ROLE_GRANTED` 한 건에
 * `detail.roles`로 담고, 다른 작업과 함께 폐기된 세션은 그 작업의 action에 `detail.revokedSessions`(개수)로 남깁니다.
 * 계정을 찾지 못한 로그인 실패는 [loginFailedForUnknownAccount]로 만듭니다.
 *
 * **detail (AUD-07)**: 비밀번호, 토큰 원문, 수정 전후 개인정보 값을 넣지 않습니다. 정보 수정은 바뀐 필드 이름만 남깁니다
 * (예: `detail.fields = ["name", "phone"]`). 값을 검사할 수는 없으므로 지키는 책임은 만드는 쪽에 있고, 여기서는 이런 값을
 * 담기 쉬운 키 이름([FORBIDDEN_DETAIL_KEYS])과 JSON으로 바꿀 수 없는 값만 거부합니다.
 *
 * @property actor 행동한 principal. 시스템 작업과 계정을 찾지 못한 로그인 실패는 `null`
 * @property target 대상. 대상이 없으면 `null`
 * @property detail JSON 객체로 저장할 값. 값은 `String`, `Boolean`, `Int`, `Long`, `Double`, `null`, 그리고 이 값들을 담은
 *   `List`와 `Map<String, *>`만 씁니다. 다시 읽으면 숫자는 JSON 숫자로 돌아오므로 작은 `Long`은 `Int`가 됩니다.
 *   비어 있으면 저장하지 않습니다 (`NULL`).
 * @property ip 요청한 클라이언트 주소. 요청이 없는 작업은 `null`
 * @property userAgent 요청의 `User-Agent`. 저장할 때 컬럼 길이에 맞춰 자릅니다
 */
data class AuditEvent(
    val occurredAt: Instant,
    val action: AuditAction,
    val actor: AuditActor?,
    val target: AuditTarget?,
    val detail: Map<String, Any?> = emptyMap(),
    val ip: String? = null,
    val userAgent: String? = null,
) {
    init {
        requireValidDetail(detail, path = "detail")
    }

    companion object {
        /**
         * AUD-07 detail에 쓰지 않는 키 이름 (대소문자 무시, 중첩된 객체 포함). 비밀번호, 토큰 원문, 개인정보 값을 담기 쉬운 이름입니다.
         * 바뀐 필드 이름을 값으로 남기는 것(`"fields": ["email"]`)은 막지 않습니다.
         */
        val FORBIDDEN_DETAIL_KEYS: Set<String> =
            setOf(
                "password",
                "currentPassword",
                "newPassword",
                "token",
                "accessToken",
                "refreshToken",
                "secret",
                "clientSecret",
                "email",
                "phone",
            )

        private val forbiddenKeysLowercase = FORBIDDEN_DETAIL_KEYS.map { it.lowercase() }.toSet()

        /**
         * AUD-08 계정을 찾지 못한 `LOGIN_FAILED`. 행위자와 대상은 없고 detail에는 realm(경로 값, 예: `internal`)만 남깁니다.
         * 입력한 이메일은 남기지 않습니다.
         */
        fun loginFailedForUnknownAccount(
            occurredAt: Instant,
            realm: Realm,
            ip: String?,
            userAgent: String?,
        ): AuditEvent =
            AuditEvent(
                occurredAt = occurredAt,
                action = AuditAction.LOGIN_FAILED,
                actor = null,
                target = null,
                detail = mapOf("realm" to realm.pathValue),
                ip = ip,
                userAgent = userAgent,
            )

        /**
         * AUD-08 찾은 계정의 로그인 기록 (`LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `ACCOUNT_LOCKED`). 행위자와 대상은 모두 그 계정이고,
         * detail에는 realm(경로 값)과 [extraDetail]을 남깁니다.
         *
         * - `LOGIN_SUCCEEDED`: `detail.sessionId` (새 refresh 세션 id)
         * - `LOGIN_FAILED`: `detail.reason` (응답한 에러 코드. 예: `INVALID_CREDENTIALS`, `ACCOUNT_SUSPENDED`)
         */
        fun login(
            occurredAt: Instant,
            action: AuditAction,
            principal: AuditActor,
            realm: Realm,
            ip: String?,
            userAgent: String?,
            extraDetail: Map<String, Any?> = emptyMap(),
        ): AuditEvent {
            require(action in LOGIN_ACTIONS) { "로그인 action이 아님: $action" }
            return AuditEvent(
                occurredAt = occurredAt,
                action = action,
                actor = principal,
                target = AuditTarget.principal(principal.id),
                detail = mapOf("realm" to realm.pathValue) + extraDetail,
                ip = ip,
                userAgent = userAgent,
            )
        }

        private val LOGIN_ACTIONS = setOf(AuditAction.LOGIN_SUCCEEDED, AuditAction.LOGIN_FAILED, AuditAction.ACCOUNT_LOCKED)

        // 예외 메시지에는 키 이름과 위치만 쓰고 값은 쓰지 않습니다 (SEC-03).
        private fun requireValidDetail(
            value: Any?,
            path: String,
        ) {
            when (value) {
                null, is String, is Boolean, is Int, is Long, is Double -> Unit
                is List<*> -> value.forEachIndexed { index, item -> requireValidDetail(item, "$path[$index]") }
                is Map<*, *> ->
                    value.forEach { (key, item) ->
                        require(key is String) { "$path 의 키는 문자열이어야 함" }
                        require(key.lowercase() !in forbiddenKeysLowercase) { "AUD-07 $path 에 쓸 수 없는 키: $key" }
                        requireValidDetail(item, "$path.$key")
                    }
                else -> throw IllegalArgumentException("$path 에 JSON으로 저장할 수 없는 값의 타입: ${value::class.simpleName}")
            }
        }
    }
}
