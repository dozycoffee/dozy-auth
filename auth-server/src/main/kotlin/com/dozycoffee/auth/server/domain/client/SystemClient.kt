package com.dozycoffee.auth.server.domain.client

import com.dozycoffee.auth.server.domain.SecretHash
import java.time.Instant
import java.util.UUID

/**
 * 서비스 간 호출용 계정의 client 정보 (domain.md §9, data-model.md §3.4). principal type은 `system`이며 계정 상태는 principal이 가집니다.
 *
 * @property principalId 토큰의 `principalId`
 * @property clientId CLI-01 형식. 비활성화되면 `deleted-{id}`로 바뀌므로 문자열로 둡니다 (ACC-04)
 * @property secretHash secret의 SHA-256 (CLI-02). 비활성화되면 `null` (ACC-04)
 * @property name 표시용 이름
 * @property secretRotatedAt 마지막 secret 발급 시각
 */
data class SystemClient(
    val principalId: UUID,
    val clientId: String,
    val secretHash: SecretHash?,
    val name: String,
    val secretRotatedAt: Instant,
    val createdAt: Instant,
) {
    companion object {
        /** client가 없을 때 비교할 해시. 어떤 원문의 해시와도 일치하지 않는다고 봅니다. */
        private val NO_MATCH = SecretHash("0".repeat(64))

        /**
         * 제시한 secret의 해시로 client를 인증합니다 (CLI-02, CLI-03). 맞으면 [client]를, 아니면 `null`을 돌려줍니다.
         *
         * client가 없거나 해시가 지워진 경우(ACC-04)에도 상수 시간 비교를 한 번 해서 결과에 따라 걸리는 시간이 달라지지 않게 합니다 (SEC-05).
         * 계정 상태는 보지 않습니다. `ACTIVE`인지는 UseCase가 계정으로 확인합니다.
         */
        fun authenticate(
            client: SystemClient?,
            presented: SecretHash,
        ): SystemClient? {
            val stored = client?.secretHash
            val matched = (stored ?: NO_MATCH).matches(presented)
            return if (stored != null && matched) client else null
        }
    }
}
