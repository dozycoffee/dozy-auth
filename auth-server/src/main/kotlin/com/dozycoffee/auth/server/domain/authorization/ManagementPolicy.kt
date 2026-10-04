package com.dozycoffee.auth.server.domain.authorization

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.ForbiddenException
import java.util.UUID

/** 관리 작업을 하는 principal (요청한 직원). */
data class Manager(
    val id: UUID,
    val grade: AdminGrade,
)

/**
 * 관리 작업의 대상 principal. `account` 도메인의 `Account`를 받지 않고 규칙에 필요한 값만 받습니다 (architecture.md §6.2).
 *
 * @property grade 대상이 가진 role로 구한 등급 ([AdminGrade.of])
 * @property status 대상의 계정 상태
 */
data class ManagedTarget(
    val id: UUID,
    val principalType: PrincipalType,
    val grade: AdminGrade,
    val status: TargetStatus,
)

/**
 * 관리 대상의 계정 상태. 이름은 `AccountStatus`(ACC-01)와 같으며 UseCase가 이름으로 바꿔 넘깁니다.
 *
 * `authorization`은 `account`를 import할 수 없어(architecture.md §6.3) 상태를 따로 둡니다. 상태 전이(ACC-01)는 이 값으로 판단하지 않습니다.
 */
enum class TargetStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    DEACTIVATED,
}

/** role 부여·회수를 뺀 계정 변경 작업 (GOV-02). role 부여·회수는 [ManagementPolicy.checkCanGrant]·[ManagementPolicy.checkCanRevoke]로 검사합니다. */
enum class ManagementAction(
    /** 계정을 쓸 수 없게 만드는 작업인지. owner에게는 누구도 할 수 없습니다 (GOV-03). */
    val disablesAccount: Boolean,
) {
    /** 직원·파트너 정보 수정. */
    UPDATE_PROFILE(false),

    /** 계정 정지. */
    SUSPEND(true),

    /** 정지 해제. */
    REACTIVATE(false),

    /** 계정 비활성화. */
    DEACTIVATE(true),

    /** 초대 재발송. */
    RESEND_INVITATION(false),

    /** 초대 취소. `PENDING` 직원을 비활성화하는 것이므로(ACC-06) GOV-03 대상입니다. */
    CANCEL_INVITATION(true),

    /** 비밀번호 재설정 메일 발송. */
    SEND_PASSWORD_RESET(false),
}

/**
 * owner·admin 보호 규칙 (docs/domain.md §8 GOV-02~07, GOV-13).
 *
 * 관리 API의 필요 role 검사(GOV-14)는 웹 계층이 먼저 하지만, 등급이 [AdminGrade.NONE]인 [Manager]가 오면 여기서도 `FORBIDDEN`입니다.
 * 대상이 있는지(`NOT_FOUND`)와 계정 상태 전이(ACC-01)는 UseCase와 `account` 도메인이 판단합니다.
 *
 * 여러 규칙을 함께 어기면 GOV-15의 순서로 처음 어긴 규칙의 예외를 던집니다.
 */
object ManagementPolicy {
    /**
     * role 부여·회수를 뺀 계정 변경 작업을 검사합니다.
     *
     * @throws ForbiddenException 관리 등급이 없음
     * @throws ProtectedAccountException owner를 정지·비활성화(GOV-03), admin이 owner·admin을 변경(GOV-02)
     */
    fun checkCanManage(
        manager: Manager,
        target: ManagedTarget,
        action: ManagementAction,
    ) {
        checkIsManager(manager)
        if (action.disablesAccount && target.grade == AdminGrade.OWNER) throw ProtectedAccountException()
        checkNotProtected(manager, target)
    }

    /**
     * [roles]를 [target]에게 부여할 수 있는지 검사합니다. 하나라도 어기면 전체를 거부합니다 (GOV-08).
     * 직원 초대·system client 등록에서 함께 부여하는 role도 이 검사를 씁니다. 이때 대상은 새로 만들 계정입니다.
     *
     * @throws ForbiddenException 관리 등급이 없음, `auth:owner` 부여 또는 owner가 아닌데 `auth:admin` 부여(GOV-05),
     *   system client에 system role 부여(GOV-06)
     * @throws SelfGrantNotAllowedException 자기 자신에게 부여 (GOV-04)
     * @throws ProtectedAccountException admin이 owner·admin에게 부여 (GOV-02)
     * @throws RoleNotGrantableException 대상이 employee·system이 아님(GOV-06), `SUSPENDED`·`DEACTIVATED`(GOV-07)
     */
    fun checkCanGrant(
        manager: Manager,
        target: ManagedTarget,
        roles: Collection<RoleCode>,
    ) {
        require(roles.isNotEmpty()) { "부여할 role이 없습니다." }
        checkIsManager(manager)
        roles.forEach { checkCanHandleRole(manager, it) }
        if (target.id == manager.id) throw SelfGrantNotAllowedException()
        checkNotProtected(manager, target)
        checkPrincipalTypeAccepts(target.principalType, roles)
        if (target.status !in GRANTABLE_STATUSES) throw RoleNotGrantableException("정지되었거나 비활성화된 계정에는 role을 부여할 수 없습니다.")
    }

    /**
     * [role]을 [target]에게서 회수할 수 있는지 검사합니다. 대상의 상태와 principal type은 보지 않습니다.
     * 가지지 않은 role을 회수해도 성공이므로(GOV-08) 가졌는지도 보지 않습니다.
     *
     * @throws ForbiddenException 관리 등급이 없음, `auth:owner` 회수 또는 owner가 아닌데 `auth:admin` 회수 (GOV-05)
     * @throws ProtectedAccountException admin이 owner·admin에게서 회수 (GOV-02)
     */
    fun checkCanRevoke(
        manager: Manager,
        target: ManagedTarget,
        role: RoleCode,
    ) {
        checkIsManager(manager)
        checkCanHandleRole(manager, role)
        checkNotProtected(manager, target)
    }

    /**
     * role 정의를 수정·삭제할 수 있는지 검사합니다.
     *
     * @throws ForbiddenException system role (GOV-13)
     */
    fun checkCanModifyRoleDefinition(role: Role) {
        if (role.isSystem) throw ForbiddenException("system role은 수정하거나 삭제할 수 없습니다.")
    }

    private fun checkIsManager(manager: Manager) {
        if (manager.grade == AdminGrade.NONE) throw ForbiddenException()
    }

    /** GOV-05 */
    private fun checkCanHandleRole(
        manager: Manager,
        role: RoleCode,
    ) {
        if (role == SystemRoles.OWNER) throw ForbiddenException("auth:owner는 양도로만 바꿀 수 있습니다.")
        if (role == SystemRoles.ADMIN && manager.grade != AdminGrade.OWNER) throw ForbiddenException("auth:admin은 owner만 부여·회수할 수 있습니다.")
    }

    /** GOV-02. owner는 모든 계정을, admin은 등급 없는 계정만 변경합니다. 자기 자신도 마찬가지입니다. */
    private fun checkNotProtected(
        manager: Manager,
        target: ManagedTarget,
    ) {
        if (manager.grade == AdminGrade.ADMIN && target.grade != AdminGrade.NONE) throw ProtectedAccountException()
    }

    /** GOV-06 */
    private fun checkPrincipalTypeAccepts(
        type: PrincipalType,
        roles: Collection<RoleCode>,
    ) {
        when (type) {
            PrincipalType.EMPLOYEE -> {}

            PrincipalType.SYSTEM -> {
                if (roles.any { it in SystemRoles.ALL }) throw ForbiddenException("system client에는 일반 role만 부여할 수 있습니다.")
            }

            PrincipalType.PARTNER, PrincipalType.CUSTOMER -> {
                throw RoleNotGrantableException("이 계정 종류에는 role을 부여할 수 없습니다.")
            }
        }
    }

    /** GOV-07 */
    private val GRANTABLE_STATUSES = setOf(TargetStatus.PENDING, TargetStatus.ACTIVE)
}
