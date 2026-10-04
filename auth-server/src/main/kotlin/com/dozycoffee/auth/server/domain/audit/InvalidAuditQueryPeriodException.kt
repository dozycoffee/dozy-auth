package com.dozycoffee.auth.server.domain.audit

import com.dozycoffee.auth.server.domain.AuthException

/** AUD-04 감사 로그 조회 기간이 올바르지 않음 (`400 VALIDATION_FAILED`, api/admin.md 감사 로그 조회). */
class InvalidAuditQueryPeriodException(
    message: String,
) : AuthException("VALIDATION_FAILED", 400, message)
