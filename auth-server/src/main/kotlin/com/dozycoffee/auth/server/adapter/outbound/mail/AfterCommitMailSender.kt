package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.server.application.port.outbound.mail.Mail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 메일 발송 시점과 실패 처리를 맡는 [SendMailPort] 구현입니다 (architecture.md §9.3). 실제 발송은 [delegate]가 합니다.
 *
 * - 트랜잭션 안에서 호출하면 커밋 뒤([TransactionSynchronization.afterCommit])에 보냅니다. 롤백되면 보내지 않습니다.
 *   트랜잭션을 열지는 않고, 호출한 UseCase의 트랜잭션에 동기화만 등록합니다.
 * - 트랜잭션 밖에서 호출하면 바로 보냅니다.
 * - 발송은 [executor]에서 합니다. 요청 응답이 SMTP 지연을 기다리지 않고, 메일을 보냈는지가 응답 시간으로 드러나지 않습니다
 *   (예: 비밀번호 찾기는 계정이 있을 때만 메일을 보냄).
 * - 실패하면 메일 종류와 예외만 경고 로그로 남기고 다시 시도하지 않습니다. 본문과 링크는 남기지 않습니다 (SEC-03).
 *   대기열이 가득 차 받지 못한 메일도 같습니다.
 */
class AfterCommitMailSender(
    internal val delegate: SendMailPort,
    private val executor: Executor,
) : SendMailPort,
    AutoCloseable {
    override fun send(mail: Mail) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = dispatch(mail)
                },
            )
        } else {
            dispatch(mail)
        }
    }

    private fun dispatch(mail: Mail) {
        try {
            executor.execute { deliver(mail) }
        } catch (e: RejectedExecutionException) {
            log.warn("메일 발송 대기열이 가득 차 보내지 못했습니다: kind={}", mail.kind, e)
        }
    }

    private fun deliver(mail: Mail) {
        try {
            delegate.send(mail)
        } catch (e: Exception) {
            log.warn("메일 발송 실패: kind={}", mail.kind, e)
        }
    }

    /** 종료할 때 대기 중인 메일을 잠시 기다려 보냅니다. */
    override fun close() {
        if (executor !is ExecutorService) return
        executor.shutdown()
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("종료 전에 보내지 못한 메일이 있습니다: {}건", executor.shutdownNow().size)
        }
    }

    private val Mail.kind: String
        get() = this::class.simpleName.orEmpty()

    private companion object {
        val log = LoggerFactory.getLogger(AfterCommitMailSender::class.java)

        const val SHUTDOWN_TIMEOUT_SECONDS = 10L
    }
}
