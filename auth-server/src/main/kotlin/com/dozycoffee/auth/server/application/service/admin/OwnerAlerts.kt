package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadOwnerPort
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerNotificationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.OwnerAlertPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * owner 즉시 알림 (AUD-01, AUD-02, AUD-03). 즉시 알림 대상이 될 수 있는 기록을 남긴 관리 UseCase가 감사 기록을 마친 뒤 **요청마다 한 번** 부릅니다.
 *
 * - 대상인지는 [OwnerAlertPolicy]가 정합니다. 한 요청이 여러 기록을 남기면(초대 + `ROLE_GRANTED` 등) 모두 넘겨 알림은 한 통만 보냅니다.
 *   메일의 action은 요청의 주 기록입니다 (예: `auth` role을 포함한 초대는 `EMPLOYEE_INVITED`).
 * - 받는 사람은 그 트랜잭션의 마지막 상태에서 owner인 직원입니다. owner 본인이 한 작업도 보냅니다. 양도 수락(`OWNER_TRANSFERRED`)은
 *   이미 새 owner가 owner이므로 새 owner가 받고, 이전 owner는 양도 완료 메일을 받습니다.
 * - `ACTIVE` owner가 없으면(부트스트랩 초대 수락 전 등) 보내지 않고 경고 로그만 남깁니다.
 * - 메일은 커밋 뒤에 보내므로 롤백되면 보내지 않고, 실패해도 다시 시도하지 않습니다 (architecture.md §9.3).
 */
@Component
class OwnerAlerts(
    private val loadOwner: LoadOwnerPort,
    private val loadEmployee: LoadEmployeePort,
    private val sendMail: SendMailPort,
) {
    /** [main]은 요청의 주 기록, [accompanying]은 같은 요청에서 함께 남긴 기록입니다. */
    fun notifyIfRequired(
        main: AuditEvent,
        accompanying: List<AuditEvent> = emptyList(),
    ) {
        if (!OwnerAlertPolicy.requiresImmediateAlert(main, accompanying)) return
        val owner = loadOwner.findOwnerId()?.let { loadEmployee.findEmployeeById(it) }
        if (owner == null || owner.account.status != AccountStatus.ACTIVE) {
            log.warn("ACTIVE owner가 없어 owner 알림을 보내지 않습니다: action={}", main.action)
            return
        }
        sendMail.send(OwnerNotificationMail(owner.profile.email, main.action, main.occurredAt))
    }

    private companion object {
        val log = LoggerFactory.getLogger(OwnerAlerts::class.java)
    }
}
