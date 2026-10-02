package com.dozycoffee.auth.server.domain.audit

/**
 * 감사 로그 action (AUD-01). DB(`audit_log.action`)와 감사 로그 조회 응답에는 [name] 그대로 씁니다.
 *
 * 기록 시점과 owner 알림 여부는 domain.md AUD-01 표가 기준입니다.
 */
enum class AuditAction {
    // 로그인
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    ACCOUNT_LOCKED,

    // 세션 폐기 (로그아웃, 재사용 탐지만. AUD-08)
    SESSION_REVOKED,

    // 비밀번호
    PASSWORD_CHANGED,
    PASSWORD_RESET,
    PASSWORD_RESET_REQUESTED,

    // 계정 생성
    EMPLOYEE_INVITED,
    INVITATION_ACCEPTED,
    PARTNER_SIGNED_UP,
    EMAIL_VERIFIED,

    // 정보 수정
    PROFILE_UPDATED,

    // 상태 변경
    ACCOUNT_SUSPENDED,
    ACCOUNT_REACTIVATED,
    ACCOUNT_DEACTIVATED,

    // role 부여·회수
    ROLE_GRANTED,
    ROLE_REVOKED,

    // role 정의
    ROLE_DEFINED,
    ROLE_UPDATED,
    ROLE_DELETED,

    // audience
    AUDIENCE_CREATED,

    // system client
    SYSTEM_CLIENT_REGISTERED,
    CLIENT_SECRET_ROTATED,

    // owner 양도
    OWNER_TRANSFER_REQUESTED,
    OWNER_TRANSFER_CANCELLED,
    OWNER_TRANSFERRED,
}
