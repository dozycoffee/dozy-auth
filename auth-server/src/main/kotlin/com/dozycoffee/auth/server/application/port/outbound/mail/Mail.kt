package com.dozycoffee.auth.server.application.port.outbound.mail

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.audit.AuditAction
import java.time.Instant

/**
 * 보낼 메일의 종류와 값 (architecture.md §8). 문구와 템플릿은 메일 어댑터가 가집니다.
 *
 * 토큰이 필요한 메일은 링크 대신 토큰 원문([OpaqueSecret])을 받습니다. 링크는 어댑터가 앱 화면 주소
 * (`AUTH_APP_URL_*`, api/account.md의 링크 표)로 만들고, 토큰은 그 주소에만 넣습니다 (VER-05).
 * [OpaqueSecret]의 `toString`은 원문을 가리므로 메일 객체를 로그에 남겨도 토큰이 나오지 않습니다 (SEC-03).
 */
sealed interface Mail {
    /** 받는 주소. */
    val to: Email
}

/**
 * 직원 초대 (`EMPLOYEE_INVITATION`, ADR-0010의 owner 부트스트랩 초대 포함). 링크는 관리 콘솔의 초대 수락 화면입니다.
 *
 * @property name 초대받은 직원 이름
 * @property expiresAt 초대 링크 만료 시각 (`policy.invitation-ttl`)
 */
data class EmployeeInvitationMail(
    override val to: Email,
    val name: String,
    val token: OpaqueSecret,
    val expiresAt: Instant,
) : Mail

/**
 * 비밀번호 재설정 (`PASSWORD_RESET`). 본인 요청과 관리자 발송이 같은 메일입니다. 링크는 [realm]에 맞는 앱의 재설정 화면입니다.
 *
 * @property realm 계정의 realm. 메일 링크 기준 주소가 있는 `internal`, `partner`만 받습니다
 * @property expiresAt 링크 만료 시각 (`policy.password-reset-ttl`)
 */
data class PasswordResetMail(
    override val to: Email,
    val realm: Realm,
    val token: OpaqueSecret,
    val expiresAt: Instant,
) : Mail {
    init {
        require(realm == Realm.INTERNAL || realm == Realm.PARTNER) { "비밀번호 재설정 메일을 보낼 수 없는 realm입니다" }
    }
}

/**
 * owner 양도 수락 요청 (`OWNER_TRANSFER`, GOV-09). 양도 **대상** 직원에게 보냅니다. 링크는 관리 콘솔의 양도 수락 화면입니다.
 *
 * @property name 양도 대상 직원 이름
 * @property expiresAt 수락 링크 만료 시각 (`policy.owner-transfer-ttl`)
 */
data class OwnerTransferRequestMail(
    override val to: Email,
    val name: String,
    val token: OpaqueSecret,
    val expiresAt: Instant,
) : Mail

/**
 * owner 양도 완료 (AUD-03). **이전** owner에게 보냅니다.
 *
 * @property newOwnerName 새 owner 이름
 * @property transferredAt 양도가 완료된 시각
 */
data class OwnerTransferCompletedMail(
    override val to: Email,
    val newOwnerName: String,
    val transferredAt: Instant,
) : Mail

/**
 * owner 즉시 알림 (AUD-01의 "즉시", AUD-03). 자세한 내용은 감사 로그에서 보므로 action과 시각만 담습니다.
 *
 * @property action 알림 대상 action
 * @property occurredAt 발생 시각
 */
data class OwnerNotificationMail(
    override val to: Email,
    val action: AuditAction,
    val occurredAt: Instant,
) : Mail
