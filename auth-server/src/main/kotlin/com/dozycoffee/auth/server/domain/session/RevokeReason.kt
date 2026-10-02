package com.dozycoffee.auth.server.domain.session

/** refresh 세션 폐기 사유 (SES-06). 이름은 DB의 `revoke_reason` 값과 같습니다. */
enum class RevokeReason {
    /** 로그아웃. */
    LOGOUT,

    /** SES-03 재사용 탐지. */
    REUSE_DETECTED,

    /** PWD-07 비밀번호 재설정. */
    PASSWORD_RESET,

    /** PWD-06 로그인 상태의 비밀번호 변경. */
    PASSWORD_CHANGED,

    /** ACC-03 계정 정지. */
    ACCOUNT_SUSPENDED,

    /** ACC-04 계정 비활성화. */
    ACCOUNT_DEACTIVATED,

    /** GOV-09 owner 양도. */
    OWNER_TRANSFERRED,

    /** GOV-12 owner 수동 복구. */
    OWNER_RECOVERY,
}
