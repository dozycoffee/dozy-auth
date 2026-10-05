package com.dozycoffee.auth.server.domain.authorization

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.domain.ForbiddenException
import java.util.UUID

/**
 * owner 양도 요청·취소 규칙 (GOV-09, GOV-14). 양도 대상의 계정 정보는 `account` 도메인의 객체 대신 필요한 값만 받습니다
 * (architecture.md §6.2).
 *
 * 요청한 사람이 owner인지는 토큰이 아니라 DB의 현재 role로 구한 [Manager.grade]로 판단합니다 (GOV-14).
 */
object OwnerTransferPolicy {
    /**
     * GOV-09 양도를 요청할 수 있는지 검사합니다. 순서: owner인지(`FORBIDDEN`) → 대상(`INVALID_STATE`) → 진행 중인 양도(`INVALID_STATE`).
     * 대상이 없는 경우(`NOT_FOUND`)는 호출하는 쪽이 이 검사 전에 응답합니다.
     *
     * @param transferInProgress 살아 있는 `OWNER_TRANSFER`가 있는지
     * @throws ForbiddenException 요청한 사람이 owner가 아님
     * @throws OwnerTransferNotAllowedException 대상이 자기 자신이거나 `ACTIVE` 직원이 아님, 진행 중인 양도가 있음
     */
    fun checkCanRequest(
        requester: Manager,
        targetId: UUID,
        targetType: PrincipalType,
        targetStatus: TargetStatus,
        transferInProgress: Boolean,
    ) {
        checkOwner(requester)
        if (targetId == requester.id) throw OwnerTransferNotAllowedException("자기 자신에게 양도할 수 없습니다.")
        if (targetType != PrincipalType.EMPLOYEE || targetStatus != TargetStatus.ACTIVE) {
            throw OwnerTransferNotAllowedException("양도 대상은 ACTIVE 직원이어야 합니다.")
        }
        if (transferInProgress) throw OwnerTransferNotAllowedException("진행 중인 owner 양도가 있습니다.")
    }

    /**
     * GOV-09 양도를 취소할 수 있는지 검사합니다. 현재 owner만 취소합니다.
     *
     * @throws ForbiddenException 요청한 사람이 owner가 아님
     */
    fun checkCanCancel(requester: Manager) = checkOwner(requester)

    private fun checkOwner(requester: Manager) {
        if (requester.grade != AdminGrade.OWNER) throw ForbiddenException()
    }
}
