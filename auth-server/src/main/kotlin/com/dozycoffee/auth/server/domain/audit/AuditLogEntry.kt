package com.dozycoffee.auth.server.domain.audit

/** 저장된 감사 로그 한 건. [id]는 `audit_log.id`입니다. */
data class AuditLogEntry(
    val id: Long,
    val event: AuditEvent,
)
