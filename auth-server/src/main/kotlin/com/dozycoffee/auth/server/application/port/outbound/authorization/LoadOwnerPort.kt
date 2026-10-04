package com.dozycoffee.auth.server.application.port.outbound.authorization

import java.util.UUID

/** owner(`auth:owner`를 가진 principal)를 조회합니다. owner는 많아야 한 명입니다 (GOV-10). */
interface LoadOwnerPort {
    /** owner의 principal id. 아직 없으면(부트스트랩 전, GOV-11) `null`입니다. */
    fun findOwnerId(): UUID?
}
