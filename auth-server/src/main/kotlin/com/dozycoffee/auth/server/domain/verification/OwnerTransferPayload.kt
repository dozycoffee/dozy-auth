package com.dozycoffee.auth.server.domain.verification

import java.util.UUID

/**
 * `OWNER_TRANSFER` verification의 `payload` (data-model.md §3.6, GOV-09).
 *
 * 수락할 때 양도를 요청한 owner가 지금도 owner인지 다시 확인하기 위해 요청한 owner의 id를 남깁니다.
 */
object OwnerTransferPayload {
    /** 양도를 요청한 owner의 principal id. */
    const val REQUESTED_BY = "requestedBy"

    fun of(requestedBy: UUID): Map<String, String> = mapOf(REQUESTED_BY to requestedBy.toString())

    /** [payload]에서 요청한 owner의 id를 읽습니다. 없거나 형식이 틀리면 `null`입니다. */
    fun requestedBy(payload: Map<String, String>): UUID? = payload[REQUESTED_BY]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
