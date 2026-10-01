package com.dozycoffee.auth.server.application.port.outbound.audit

import com.dozycoffee.auth.server.domain.audit.AuditEvent

/**
 * 감사 로그를 남깁니다 (AUD-01).
 *
 * 호출한 UseCase의 트랜잭션 안에서 기록하므로 업무가 롤백되면 기록도 함께 사라집니다. 에러 응답으로 끝나도 남아야 하는 기록
 * (`LOGIN_FAILED`, `ACCOUNT_LOCKED`)은 architecture.md §9의 트랜잭션 규칙을 따릅니다.
 */
interface RecordAuditLogPort {
    fun record(event: AuditEvent)
}
