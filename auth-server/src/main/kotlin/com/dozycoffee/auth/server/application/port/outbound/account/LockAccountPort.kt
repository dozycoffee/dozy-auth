package com.dozycoffee.auth.server.application.port.outbound.account

import com.dozycoffee.auth.server.domain.account.Account
import java.util.UUID

/**
 * 타입과 관계없이 principal을 잠그고 조회합니다. 같은 계정에 대한 관리 작업(role 부여·회수 등)이 동시에 오면 차례로 처리하기 위해서입니다.
 *
 * 호출한 트랜잭션이 끝날 때까지 잠금을 쥡니다. 트랜잭션 안에서만 호출합니다.
 */
interface LockAccountPort {
    /** 없으면 `null`입니다. */
    fun lockAccountById(id: UUID): Account?
}
