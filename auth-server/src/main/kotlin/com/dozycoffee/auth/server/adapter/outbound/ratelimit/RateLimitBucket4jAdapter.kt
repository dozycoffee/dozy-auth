package com.dozycoffee.auth.server.adapter.outbound.ratelimit

import com.dozycoffee.auth.server.application.port.outbound.ratelimit.ConsumeRateLimitPort
import com.dozycoffee.auth.server.application.port.outbound.ratelimit.RateLimitKey
import com.dozycoffee.auth.server.application.port.outbound.ratelimit.RateLimitResult
import com.dozycoffee.auth.server.domain.RateLimit
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import io.github.bucket4j.Bucket
import io.github.bucket4j.TimeMeter
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/**
 * 요청 제한 카운터를 인스턴스 메모리의 Bucket4j 버킷으로 셉니다 (ADR-0024).
 *
 * - 버킷은 [RateLimit] 한도만큼 담고 기간에 걸쳐 고르게 다시 채웁니다 (greedy refill).
 * - 시간은 주입한 [Clock]으로 잽니다. 테스트는 시계를 옮겨 다시 채워지는 것을 확인합니다.
 * - **메모리 상한:** 버킷은 마지막으로 쓴 뒤 한도 기간이 지나면 지웁니다. 그때는 버킷이 이미 가득 찬 상태라 지워도 결과가 같습니다.
 *   버킷 수가 [MAX_BUCKETS]를 넘으면 오래 쓰지 않은 것부터 지웁니다. 많은 주소에서 한꺼번에 요청하면 일부 버킷이 일찍 지워져
 *   한도가 느슨해질 수 있지만, 메모리가 끝없이 늘지 않는 쪽을 택합니다.
 * - 이메일은 원문 대신 소문자 주소의 SHA-256으로 보관합니다. 키가 로그나 힙 덤프에 주소로 남지 않게 하기 위해서입니다 (SEC-03).
 */
@Component
class RateLimitBucket4jAdapter(
    private val clock: Clock,
) : ConsumeRateLimitPort {
    private val timeMeter =
        object : TimeMeter {
            override fun currentTimeNanos(): Long = TimeUnit.MILLISECONDS.toNanos(clock.millis())

            override fun isWallClockBased(): Boolean = true
        }

    private val buckets: Cache<BucketKey, Bucket> =
        Caffeine
            .newBuilder()
            .ticker { timeMeter.currentTimeNanos() }
            .expireAfter(IdleForLimitPeriod)
            .maximumSize(MAX_BUCKETS)
            .executor(Runnable::run)
            .build()

    override fun tryConsume(
        key: RateLimitKey,
        limit: RateLimit,
    ): RateLimitResult {
        val bucket = buckets.get(BucketKey(key.storageKey(), limit)) { newBucket(limit) }
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        return if (probe.isConsumed) RateLimitResult.Allowed else RateLimitResult.Limited(Duration.ofNanos(probe.nanosToWaitForRefill))
    }

    /** 모든 버킷을 지웁니다. 테스트가 서로의 카운터에 영향을 주지 않게 할 때만 씁니다. */
    internal fun clear() {
        buckets.invalidateAll()
    }

    /** 지금 메모리에 있는 버킷 수. 만료된 버킷을 먼저 정리합니다. 테스트에서 메모리 상한을 확인할 때 씁니다. */
    internal fun bucketCount(): Long {
        buckets.cleanUp()
        return buckets.estimatedSize()
    }

    private fun newBucket(limit: RateLimit): Bucket =
        Bucket
            .builder()
            .addLimit { it.capacity(limit.capacity.toLong()).refillGreedy(limit.capacity.toLong(), limit.period) }
            .withCustomTimePrecision(timeMeter)
            .build()

    private fun RateLimitKey.storageKey(): String =
        when (this) {
            is RateLimitKey.ClientIp -> "ip:$ip"
            is RateLimitKey.MailRecipient -> "mail:${sha256(email.lookupKey)}"
            is RateLimitKey.PasswordConfirmation -> "password-confirmation:$principalId"
        }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    /** 같은 키라도 한도가 다르면 다른 버킷입니다. */
    private data class BucketKey(
        val key: String,
        val limit: RateLimit,
    )

    /** 마지막으로 쓴 뒤 한도 기간이 지나면 만료 (버킷이 가득 찬 상태). */
    private object IdleForLimitPeriod : Expiry<BucketKey, Bucket> {
        override fun expireAfterCreate(
            key: BucketKey,
            value: Bucket,
            currentTime: Long,
        ): Long = key.limit.period.toNanos()

        override fun expireAfterUpdate(
            key: BucketKey,
            value: Bucket,
            currentTime: Long,
            currentDuration: Long,
        ): Long = key.limit.period.toNanos()

        override fun expireAfterRead(
            key: BucketKey,
            value: Bucket,
            currentTime: Long,
            currentDuration: Long,
        ): Long = key.limit.period.toNanos()
    }

    internal companion object {
        /** 버킷 수 상한. 버킷 하나가 수백 바이트라 수십 MB 안에 들어옵니다. */
        const val MAX_BUCKETS: Long = 100_000
    }
}
