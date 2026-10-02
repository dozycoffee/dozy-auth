package com.dozycoffee.auth.server.domain.audit

import java.util.UUID

/** 감사 대상의 종류 (`audit_log.target_type`, docs/data-model.md §3.11). */
enum class AuditTargetType {
    PRINCIPAL,
    ROLE,
    AUDIENCE,
    SESSION,
}

/**
 * 감사 대상. 다른 도메인은 id로만 참조합니다.
 *
 * 종류마다 id 타입이 달라 문자열로 둡니다 (principal·세션은 UUID, role·audience는 bigint). 만들 때는 종류별 함수를 씁니다.
 */
data class AuditTarget(
    val type: AuditTargetType,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "감사 대상 id가 비어 있음" }
        require(id.length <= ID_MAX_LENGTH) { "감사 대상 id가 ${ID_MAX_LENGTH}자를 넘음" }
    }

    companion object {
        /** `audit_log.target_id` 길이 (varchar(50)). */
        const val ID_MAX_LENGTH: Int = 50

        fun principal(id: UUID): AuditTarget = AuditTarget(AuditTargetType.PRINCIPAL, id.toString())

        fun session(id: UUID): AuditTarget = AuditTarget(AuditTargetType.SESSION, id.toString())

        fun role(id: Long): AuditTarget = AuditTarget(AuditTargetType.ROLE, id.toString())

        fun audience(id: Long): AuditTarget = AuditTarget(AuditTargetType.AUDIENCE, id.toString())
    }
}
