package com.dozycoffee.auth.server.application.port.outbound.account

import com.dozycoffee.auth.server.domain.Page
import com.dozycoffee.auth.server.domain.PageRequest
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.EmployeeRecord
import java.util.UUID

/** 관리 화면의 직원 목록·상세를 조회합니다 (api/admin.md §1). role은 role 포트로 따로 조회합니다 (architecture.md §8). */
interface LoadEmployeeRecordsPort {
    /** 직원이 아니거나 없으면 `null`입니다. */
    fun findEmployeeRecord(id: UUID): EmployeeRecord?

    /**
     * [findEmployeeRecord]와 같지만 트랜잭션이 끝날 때까지 그 직원의 principal·profile 행을 잠급니다 (`SELECT ... FOR UPDATE`).
     * 관리자의 변경(정보 수정, 초대 재발송·취소)은 이것으로 읽어, 같은 직원에 대한 변경이 동시에 오면 차례로 처리합니다.
     * 예를 들어 초대 취소로 파기한 개인정보를 동시에 온 정보 수정이 다시 쓰지 못합니다.
     */
    fun lockEmployeeRecord(id: UUID): EmployeeRecord?

    /** [criteria]에 맞는 직원을 생성 최신순으로 한 페이지 돌려줍니다. 같은 시각이면 id 역순입니다. */
    fun searchEmployeeRecords(
        criteria: EmployeeSearchCriteria,
        page: PageRequest,
    ): Page<EmployeeRecord>
}

/**
 * 직원 목록 조건. 모두 `null`이면 조건 없이 전부입니다.
 *
 * @property principalIds 이 id 중에서만 찾습니다. role 조건은 role 포트로 그 role을 가진 principal을 먼저 구해 넘깁니다
 * @property query 이름 또는 이메일에 이 문자열이 들어간 직원 (대소문자 무시). `%`, `_`도 글자 그대로 찾습니다.
 *   검색어는 로그에 남기지 않으므로(SEC-03) [toString]에서 가립니다
 */
data class EmployeeSearchCriteria(
    val status: AccountStatus? = null,
    val principalIds: Set<UUID>? = null,
    val query: String? = null,
) {
    init {
        require(query == null || query.isNotBlank()) { "검색어가 비어 있습니다" }
    }

    override fun toString(): String =
        "EmployeeSearchCriteria(status=$status, principalIds=${principalIds?.size}, query=${query?.let { "***" }})"
}
