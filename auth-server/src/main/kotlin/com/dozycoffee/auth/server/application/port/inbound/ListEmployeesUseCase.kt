package com.dozycoffee.auth.server.application.port.inbound

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.Page
import com.dozycoffee.auth.server.domain.PageRequest
import com.dozycoffee.auth.server.domain.account.AccountStatus
import java.time.Instant
import java.util.UUID

/** 관리 화면의 직원 목록 (api/admin.md 직원 목록). 필요 role 검사(GOV-14)는 웹 계층이 합니다. */
interface ListEmployeesUseCase {
    /** 조건에 맞는 직원을 생성 최신순으로 한 페이지 돌려줍니다. 없는 role로 찾으면 빈 목록입니다. */
    fun listEmployees(command: ListEmployeesCommand): Page<EmployeeSummary>
}

/**
 * 직원 목록 조건. `null`인 조건은 보지 않습니다.
 *
 * @property role 이 role을 가진 직원만
 * @property query 이름 또는 이메일 검색어. 로그에 남기지 않으므로(SEC-03) [toString]에서 가립니다
 */
data class ListEmployeesCommand(
    val status: AccountStatus? = null,
    val role: RoleCode? = null,
    val query: String? = null,
    val page: PageRequest = PageRequest(),
) {
    init {
        require(query == null || query.isNotBlank()) { "검색어가 비어 있습니다" }
    }

    override fun toString(): String = "ListEmployeesCommand(status=$status, role=$role, query=${query?.let { "***" }}, page=$page)"
}

/**
 * 직원 목록의 한 항목.
 *
 * @property roles `{audience}:{code}` 목록
 */
data class EmployeeSummary(
    val principalId: UUID,
    val email: String,
    val name: String,
    val status: AccountStatus,
    val roles: List<String>,
    val createdAt: Instant,
)
