package com.dozycoffee.auth.server.domain.account

import java.time.Instant
import java.util.UUID

/**
 * 관리 화면에 보여 줄 직원 계정과 생성·수정 시각 (api/admin.md 직원 목록·상세).
 *
 * @property createdAt 계정(principal)을 만든 시각
 * @property updatedAt 계정이나 profile이 마지막으로 바뀐 시각. 둘 중 늦은 시각입니다
 */
data class EmployeeRecord(
    val employee: Employee,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val id: UUID get() = employee.account.id
}
