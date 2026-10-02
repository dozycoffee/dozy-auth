package com.dozycoffee.auth.server.application.port.outbound.account

import com.dozycoffee.auth.server.domain.account.Account
import java.util.UUID

/** 타입과 관계없이 principal을 조회합니다. 갱신(SES-05)처럼 계정 상태만 필요할 때 씁니다. */
interface LoadAccountPort {
    fun findAccountById(id: UUID): Account?
}
