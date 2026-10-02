package com.dozycoffee.auth.server.adapter.inbound.web

import com.dozycoffee.auth.core.Realm
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** 경로의 `{realm}` (api/conventions.md §1). */
object RealmPaths {
    /**
     * 경로 값을 realm으로 바꿉니다. 모르는 값이거나 그 엔드포인트가 받지 않는 realm이면 `404 NOT_FOUND`입니다.
     *
     * @param allowed 이 엔드포인트가 지금 받는 realm
     */
    fun resolve(
        value: String,
        allowed: Set<Realm>,
    ): Realm = Realm.fromPathValueOrNull(value)?.takeIf { it in allowed } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
}
