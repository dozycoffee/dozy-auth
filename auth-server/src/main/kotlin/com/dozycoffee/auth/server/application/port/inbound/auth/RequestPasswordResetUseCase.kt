package com.dozycoffee.auth.server.application.port.inbound.auth

import com.dozycoffee.auth.core.Realm

/** 비밀번호를 잊은 사용자에게 재설정 메일을 보냅니다 (api/account.md 비밀번호 찾기, LGN-04, VER-01 `PASSWORD_RESET`). */
interface RequestPasswordResetUseCase {
    /**
     * 계정 존재 여부, 상태, 이메일 단위 요청 제한(api/conventions.md §8)과 관계없이 예외 없이 끝납니다. 메일을 보냈는지는 결과로
     * 드러내지 않습니다 (LGN-04).
     */
    fun requestPasswordReset(command: RequestPasswordResetCommand)
}

/**
 * 비밀번호 찾기 요청.
 *
 * @property realm 요청 경로의 realm (`internal`만. 파트너는 partner realm 작업에서 추가)
 * @property email 사용자가 입력한 로그인 이메일. 형식이 틀리면 없는 계정과 같게 처리합니다
 */
data class RequestPasswordResetCommand(
    val realm: Realm,
    val email: String,
)
