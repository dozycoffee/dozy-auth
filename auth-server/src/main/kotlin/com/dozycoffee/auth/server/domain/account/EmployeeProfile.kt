package com.dozycoffee.auth.server.domain.account

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.domain.Email
import java.util.UUID

/**
 * 본사 직원의 계정 정보 (data-model.md §3.2). 이메일은 로그인 ID이며 수정 API로 바꾸지 않습니다 (ACC-07).
 *
 * 형식 검증은 요청 검증(`VALIDATION_FAILED`)이 맡고, 여기서는 저장할 수 없는 길이만 막습니다.
 */
data class EmployeeProfile(
    val principalId: UUID,
    val email: Email,
    val name: String,
    val phone: String?,
    val address: String?,
) {
    init {
        require(name.length <= NAME_MAX_LENGTH) { "이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다" }
        require(phone == null || phone.length <= PHONE_MAX_LENGTH) { "전화번호는 ${PHONE_MAX_LENGTH}자 이하여야 합니다" }
        require(address == null || address.length <= ADDRESS_MAX_LENGTH) { "주소는 ${ADDRESS_MAX_LENGTH}자 이하여야 합니다" }
    }

    /**
     * ACC-04 비활성화할 때 개인정보를 파기한 profile. 이메일은 principal마다 달라 유일성을 지키고,
     * 원래 이메일이 비므로 같은 이메일로 다시 초대할 수 있습니다 (ACC-05).
     */
    fun scrubbed(): EmployeeProfile =
        copy(email = Email("deleted+$principalId@invalid.local"), name = SCRUBBED_NAME, phone = null, address = null)

    companion object {
        const val NAME_MAX_LENGTH: Int = 50
        const val PHONE_MAX_LENGTH: Int = 20
        const val ADDRESS_MAX_LENGTH: Int = 255

        /** ACC-04 파기한 profile의 이름. */
        const val SCRUBBED_NAME: String = "탈퇴 사용자"
    }
}

/** 직원 계정: principal과 직원 profile. 로그인(LGN-01)과 직원 관리에서 함께 조회합니다. */
data class Employee(
    val account: Account,
    val profile: EmployeeProfile,
) {
    init {
        require(account.type == PrincipalType.EMPLOYEE) { "직원 계정이 아닙니다" }
        require(account.id == profile.principalId) { "계정과 profile의 principal이 다릅니다" }
    }
}
