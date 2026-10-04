package com.dozycoffee.auth.server.application.port.outbound.credential

import java.util.UUID

/** ACC-04 비활성화할 때 principal의 비밀번호를 지웁니다 (data-model.md §3.5). */
interface DeletePasswordCredentialPort {
    /** 지웠으면 `true`, 비밀번호가 없었으면(초대 수락 전 직원, system client) `false`입니다. */
    fun deletePasswordCredential(principalId: UUID): Boolean
}
