package com.dozycoffee.auth.server.adapter.inbound.startup

import com.dozycoffee.auth.server.application.port.inbound.system.BootstrapOwnerCommand
import com.dozycoffee.auth.server.application.port.inbound.system.BootstrapOwnerOutcome
import com.dozycoffee.auth.server.application.port.inbound.system.BootstrapOwnerUseCase
import com.dozycoffee.auth.server.domain.authorization.OwnerAlreadyAssignedException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * 기동이 끝나면 owner 부트스트랩을 실행합니다 (GOV-11). 결과는 로그로만 남기며, 이메일과 초대 토큰은 로그에 쓰지 않습니다 (SEC-03).
 *
 * - owner가 없는데 `BOOTSTRAP_OWNER_EMAIL`이 없으면 기동에 실패합니다. 관리할 사람이 없는 상태로 운영이 뜨는 것을 막기 위해서입니다.
 * - owner가 없는데 그 이메일을 다른 직원이 쓰고 있으면 오류 로그만 남기고 기동은 계속합니다. 로그인과 토큰 발급은 owner 없이도 되므로
 *   서비스 전체를 멈추지 않고, 인프라 관리자가 복구 절차(GOV-12)나 다른 이메일로 해결합니다.
 * - 다른 인스턴스가 잠금 밖에서 먼저 owner를 만들었으면(GOV-10 `INVALID_STATE`) 이미 처리된 것으로 보고 기동을 계속합니다.
 */
class OwnerBootstrapListener(
    private val bootstrapOwner: BootstrapOwnerUseCase,
    private val properties: OwnerBootstrapProperties,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() = run()

    internal fun run() {
        val result =
            try {
                bootstrapOwner.bootstrap(BootstrapOwnerCommand(properties.email))
            } catch (e: OwnerAlreadyAssignedException) {
                log.info("owner 부트스트랩: 다른 곳에서 먼저 owner가 지정되어 건너뜁니다")
                return
            }
        if (result.configuredEmailIgnored) {
            log.info("owner 부트스트랩: owner가 이미 있어 BOOTSTRAP_OWNER_EMAIL 설정을 무시합니다")
        }
        when (result.outcome) {
            BootstrapOwnerOutcome.OWNER_INVITED -> log.info("owner 부트스트랩: owner 계정을 만들고 초대 메일을 보냈습니다")
            BootstrapOwnerOutcome.INVITATION_REISSUED -> log.info("owner 부트스트랩: 수락 전 owner의 초대가 만료되어 다시 보냈습니다")
            BootstrapOwnerOutcome.INVITATION_LIVE -> log.info("owner 부트스트랩: owner 초대가 아직 유효해 아무것도 하지 않습니다")
            BootstrapOwnerOutcome.OWNER_ACTIVE -> log.debug("owner 부트스트랩: owner가 있어 아무것도 하지 않습니다")
            BootstrapOwnerOutcome.OWNER_EMAIL_IN_USE ->
                log.error("owner 부트스트랩: BOOTSTRAP_OWNER_EMAIL을 이미 다른 직원이 쓰고 있어 owner를 만들지 않았습니다")
            BootstrapOwnerOutcome.OWNER_EMAIL_MISSING ->
                throw IllegalStateException("owner가 없습니다. BOOTSTRAP_OWNER_EMAIL(dozy.auth.bootstrap.owner-email)을 설정해야 합니다")
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(OwnerBootstrapListener::class.java)
    }
}
