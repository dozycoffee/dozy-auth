package com.dozycoffee.auth.server.domain.audit

import com.dozycoffee.auth.core.RoleCode

/**
 * AUD-01 owner 즉시 알림 대상 판단. 표의 "owner 알림"이 "즉시"인 기록이 대상입니다.
 *
 * - 항상: `ROLE_DELETED`, `AUDIENCE_CREATED`, `SYSTEM_CLIENT_REGISTERED`, `CLIENT_SECRET_ROTATED`, owner 양도 세 action
 * - `ROLE_GRANTED`, `ROLE_REVOKED`: `detail.roles`에 `auth` audience role이 있을 때 (AUD-02 admin 임명·해임)
 * - 직원 초대(`EMPLOYEE_INVITED`)는 `detail`이 없으므로 함께 남긴 `ROLE_GRANTED`로 판단합니다 (AUD-08). system client 등록도 같습니다.
 *
 * 한 요청이 여러 기록을 남기면 [requiresImmediateAlert]에 요청의 주 기록과 함께 남긴 기록을 넘겨 한 번만 판단합니다.
 */
object OwnerAlertPolicy {
    /** owner·admin 같은 system role이 속한 audience (domain.md §1). */
    const val AUTH_AUDIENCE: String = "auth"

    private val ALWAYS =
        setOf(
            AuditAction.ROLE_DELETED,
            AuditAction.AUDIENCE_CREATED,
            AuditAction.SYSTEM_CLIENT_REGISTERED,
            AuditAction.CLIENT_SECRET_ROTATED,
            AuditAction.OWNER_TRANSFER_REQUESTED,
            AuditAction.OWNER_TRANSFER_CANCELLED,
            AuditAction.OWNER_TRANSFERRED,
        )

    private val WHEN_AUTH_ROLE = setOf(AuditAction.ROLE_GRANTED, AuditAction.ROLE_REVOKED)

    /** 기록 하나가 즉시 알림 대상인지. */
    fun requiresImmediateAlert(event: AuditEvent): Boolean =
        when (event.action) {
            in ALWAYS -> true
            in WHEN_AUTH_ROLE -> hasAuthRole(event.detail["roles"])
            else -> false
        }

    /** 한 요청의 주 기록 [main]과 함께 남긴 기록 [accompanying] 중 하나라도 즉시 알림 대상인지. */
    fun requiresImmediateAlert(
        main: AuditEvent,
        accompanying: List<AuditEvent>,
    ): Boolean = requiresImmediateAlert(main) || accompanying.any { requiresImmediateAlert(it) }

    private fun hasAuthRole(roles: Any?): Boolean =
        roles is List<*> && roles.any { it is String && RoleCode.parseOrNull(it)?.audience == AUTH_AUDIENCE }
}
