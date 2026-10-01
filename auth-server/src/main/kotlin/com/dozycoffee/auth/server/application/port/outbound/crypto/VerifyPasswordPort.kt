package com.dozycoffee.auth.server.application.port.outbound.crypto

import com.dozycoffee.auth.server.domain.credential.PasswordHash
import com.dozycoffee.auth.server.domain.credential.RawPassword

/** 비밀번호가 저장된 해시와 맞는지 확인합니다 (PWD-04, LGN-01). */
interface VerifyPasswordPort {
    /**
     * [hash]의 인코딩 문자열에 든 파라미터로 검증하므로, 기본 파라미터와 다르게 만든 기존 해시도 검증됩니다.
     *
     * [hash]가 `null`(계정이나 비밀번호가 없음)이면 가짜 해시로 검증해 비슷한 시간을 쓴 뒤 `false`를 돌려줍니다.
     * 응답 시간으로 계정 존재 여부가 드러나지 않게 하기 위해서입니다 (LGN-02).
     */
    fun verify(
        password: RawPassword,
        hash: PasswordHash?,
    ): Boolean
}
