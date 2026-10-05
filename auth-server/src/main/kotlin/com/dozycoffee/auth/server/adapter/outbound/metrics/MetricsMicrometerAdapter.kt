package com.dozycoffee.auth.server.adapter.outbound.metrics

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.application.port.outbound.metrics.RateLimitKind
import com.dozycoffee.auth.server.application.port.outbound.metrics.RecordMetricsPort
import com.dozycoffee.auth.server.application.port.outbound.metrics.TokenIssueKind
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 운영 지표를 Micrometer counter로 셉니다. 이름과 태그는 configuration.md §10의 표와 같습니다.
 *
 * counter는 처음 셀 때 만들어집니다. 한 번도 일어나지 않은 조합은 `/actuator/prometheus`에 나오지 않습니다.
 */
@Component
class MetricsMicrometerAdapter(
    private val registry: MeterRegistry,
) : RecordMetricsPort {
    override fun loginSucceeded(realm: Realm) {
        registry.counter(LOGIN_SUCCEEDED, REALM, realm.pathValue).increment()
    }

    override fun loginFailed(
        realm: Realm,
        reason: String,
    ) {
        registry.counter(LOGIN_FAILED, REALM, realm.pathValue, REASON, reason).increment()
    }

    override fun tokenIssued(
        kind: TokenIssueKind,
        realm: Realm,
    ) {
        registry.counter(TOKEN_ISSUED, KIND, kind.tagValue(), REALM, realm.pathValue).increment()
    }

    override fun refreshReuseDetected(realm: Realm) {
        registry.counter(REFRESH_REUSE_DETECTED, REALM, realm.pathValue).increment()
    }

    override fun rateLimitRejected(limit: RateLimitKind) {
        registry.counter(RATE_LIMIT_REJECTED, LIMIT, limit.tagValue()).increment()
    }

    private fun Enum<*>.tagValue(): String = name.lowercase()

    companion object {
        const val LOGIN_SUCCEEDED = "dozy.auth.login.succeeded"
        const val LOGIN_FAILED = "dozy.auth.login.failed"
        const val TOKEN_ISSUED = "dozy.auth.token.issued"
        const val REFRESH_REUSE_DETECTED = "dozy.auth.refresh.reuse.detected"
        const val RATE_LIMIT_REJECTED = "dozy.auth.ratelimit.rejected"

        private const val REALM = "realm"
        private const val REASON = "reason"
        private const val KIND = "kind"
        private const val LIMIT = "limit"
    }
}
