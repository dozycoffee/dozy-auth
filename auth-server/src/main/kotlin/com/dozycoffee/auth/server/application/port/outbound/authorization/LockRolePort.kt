package com.dozycoffee.auth.server.application.port.outbound.authorization

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.authorization.Role

/**
 * role 부여와 role 정의 삭제(GOV-13)가 동시에 오면 차례로 처리하도록 role 정의를 잠그고 조회합니다.
 *
 * 부여는 공유 잠금, 삭제는 배타 잠금을 쥡니다. 삭제가 먼저면 부여는 삭제가 끝난 뒤 role이 없는 것(`NOT_FOUND`)으로 보고,
 * 부여가 먼저면 삭제는 부여가 끝난 뒤 새 부여까지 센 principal 수로 `ROLE_IN_USE`·일괄 회수를 판단합니다. 그래서 부여가
 * 외래 키 위반(500)으로 끝나거나 삭제가 남은 부여 때문에 실패하지 않습니다. role 이름·설명 수정은 막지 않습니다.
 *
 * 잠금은 호출한 트랜잭션이 끝날 때까지 쥐므로 트랜잭션 안에서만 호출합니다.
 */
interface LockRolePort {
    /** [codes] 중 정의된 role을 부여용으로 잠급니다. 없는 code는 결과에서 빠지므로 호출하는 쪽이 개수로 `NOT_FOUND`를 판단합니다. */
    fun lockRolesForGrant(codes: Collection<RoleCode>): List<Role>

    /** role을 삭제용으로 잠급니다. 없으면 `null`입니다. */
    fun lockRoleForDelete(id: Long): Role?
}
