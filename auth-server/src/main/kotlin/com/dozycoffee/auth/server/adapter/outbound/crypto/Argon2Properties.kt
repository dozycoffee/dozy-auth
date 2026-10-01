package com.dozycoffee.auth.server.adapter.outbound.crypto

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 새 비밀번호 해시의 argon2id 파라미터 (configuration.md §6, ADR-0027).
 *
 * 기본값은 해시·검증 한 번이 100~300ms가 되도록 측정해서 정했습니다. 바꿔도 기존 해시는 자기 인코딩 문자열의 파라미터로 검증됩니다.
 *
 * @property memoryKib 메모리 비용 (KiB)
 * @property iterations 반복 횟수
 * @property parallelism 병렬도 (lane 수)
 * @property saltLength salt 바이트 수
 * @property hashLength 해시 출력 바이트 수
 */
@ConfigurationProperties("dozy.auth.password-hash")
data class Argon2Properties(
    val memoryKib: Int = 19_456,
    val iterations: Int = 5,
    val parallelism: Int = 1,
    val saltLength: Int = 16,
    val hashLength: Int = 32,
) {
    init {
        require(parallelism >= 1) { "parallelism은 1 이상이어야 합니다" }
        require(memoryKib >= MIN_MEMORY_KIB_PER_LANE * parallelism) { "memory-kib는 parallelism의 8배 이상이어야 합니다" }
        require(iterations >= 1) { "iterations는 1 이상이어야 합니다" }
        require(saltLength >= MIN_SALT_LENGTH) { "salt-length는 $MIN_SALT_LENGTH 이상이어야 합니다" }
        require(hashLength >= MIN_HASH_LENGTH) { "hash-length는 $MIN_HASH_LENGTH 이상이어야 합니다" }
    }

    private companion object {
        /** argon2(RFC 9106)의 lane당 최소 메모리 (KiB). */
        const val MIN_MEMORY_KIB_PER_LANE = 8

        /** RFC 9106 권장 salt 길이 (바이트). */
        const val MIN_SALT_LENGTH = 16

        /** 해시 출력 최소 길이 (바이트). */
        const val MIN_HASH_LENGTH = 16
    }
}
