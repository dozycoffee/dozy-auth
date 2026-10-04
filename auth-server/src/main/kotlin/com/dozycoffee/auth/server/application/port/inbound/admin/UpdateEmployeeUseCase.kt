package com.dozycoffee.auth.server.application.port.inbound.admin

import java.util.UUID

/** 관리자의 직원 정보 수정 (api/admin.md 직원 정보 수정, ACC-07). 이메일은 바꾸지 않습니다. */
interface UpdateEmployeeUseCase {
    /**
     * 보낸 필드만 바꿉니다. 바뀐 값이 있을 때만 저장하고 감사 로그 `PROFILE_UPDATED`(바뀐 필드 이름만, AUD-07)를 남깁니다.
     *
     * @return 수정한 뒤의 직원 상세
     * @throws com.dozycoffee.auth.server.domain.account.EmployeeNotFoundException 없는 직원
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException admin이 owner·admin 계정 수정 (GOV-02)
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException `DEACTIVATED` 계정
     */
    fun updateEmployee(command: UpdateEmployeeCommand): EmployeeDetail
}

/**
 * 직원 정보 수정 요청. 각 필드는 보내지 않았으면 [FieldPatch.Keep]입니다.
 *
 * @property managerId 요청한 관리자 (직원)
 * @property phone `FieldPatch.Set(null)`이면 삭제
 * @property address `FieldPatch.Set(null)`이면 삭제
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class UpdateEmployeeCommand(
    val managerId: UUID,
    val principalId: UUID,
    val name: FieldPatch<String> = FieldPatch.Keep,
    val phone: FieldPatch<String?> = FieldPatch.Keep,
    val address: FieldPatch<String?> = FieldPatch.Keep,
    val ip: String? = null,
    val userAgent: String? = null,
) {
    /** 개인정보 값은 가립니다. */
    override fun toString(): String =
        "UpdateEmployeeCommand(managerId=$managerId, principalId=$principalId, " +
            "name=${name.describe()}, phone=${phone.describe()}, address=${address.describe()})"

    private fun FieldPatch<*>.describe(): String = if (this is FieldPatch.Set) "***" else "Keep"
}

/** 부분 수정(PATCH)의 한 필드. 보내지 않은 필드([Keep])와 `null`로 보낸 필드(`Set(null)`)를 구분합니다. */
sealed interface FieldPatch<out T> {
    /** 보내지 않음. 현재 값을 그대로 둡니다. */
    data object Keep : FieldPatch<Nothing>

    /** [value]로 바꿉니다. */
    data class Set<out T>(
        val value: T,
    ) : FieldPatch<T>
}

/** [current]에 이 변경을 적용한 값. */
fun <T> FieldPatch<T>.applyTo(current: T): T =
    when (this) {
        FieldPatch.Keep -> current
        is FieldPatch.Set -> value
    }
