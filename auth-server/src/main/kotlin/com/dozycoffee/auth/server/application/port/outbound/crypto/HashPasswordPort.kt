package com.dozycoffee.auth.server.application.port.outbound.crypto

import com.dozycoffee.auth.server.domain.credential.PasswordHash
import com.dozycoffee.auth.server.domain.credential.RawPassword

/** 비밀번호를 argon2id로 해시합니다 (PWD-04, SEC-01). 규칙 검사(`PasswordPolicy`)는 호출하는 쪽이 먼저 합니다. */
interface HashPasswordPort {
    /** 새 salt로 만든 인코딩 문자열을 돌려줍니다. 같은 비밀번호도 매번 다른 해시가 됩니다. */
    fun hash(password: RawPassword): PasswordHash
}
