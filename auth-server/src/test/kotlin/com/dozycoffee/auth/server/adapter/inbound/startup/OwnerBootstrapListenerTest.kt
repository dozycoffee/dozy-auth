package com.dozycoffee.auth.server.adapter.inbound.startup

import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerCommand
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerOutcome
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerResult
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerUseCase
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.authorization.OwnerAlreadyAssignedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** GOV-11 기동 리스너가 부트스트랩 결과에 따라 기동을 계속하거나 멈춤. */
class OwnerBootstrapListenerTest {
    private val useCase = mockk<BootstrapOwnerUseCase>()

    @Test
    fun `설정 이메일을 그대로 넘김`() {
        every { useCase.bootstrap(any()) } returns BootstrapOwnerResult(BootstrapOwnerOutcome.OWNER_INVITED)

        listener(ownerEmail = "owner@dozycoffee.test").run()

        verify { useCase.bootstrap(BootstrapOwnerCommand(Email("owner@dozycoffee.test"))) }
    }

    @Test
    fun `설정 이메일이 비어 있으면 설정하지 않은 것으로 넘김`() {
        every { useCase.bootstrap(any()) } returns BootstrapOwnerResult(BootstrapOwnerOutcome.OWNER_ACTIVE)

        listener(ownerEmail = " ").run()

        verify { useCase.bootstrap(BootstrapOwnerCommand(null)) }
    }

    @Test
    fun `설정 이메일 형식이 틀리면 기동 실패`() {
        assertFailsWith<IllegalArgumentException> { OwnerBootstrapProperties(ownerEmail = "not-an-email") }
    }

    @Test
    fun `owner가 없는데 설정 이메일이 없으면 기동 실패`() {
        every { useCase.bootstrap(any()) } returns BootstrapOwnerResult(BootstrapOwnerOutcome.OWNER_EMAIL_MISSING)

        assertFailsWith<IllegalStateException> { listener(ownerEmail = null).run() }
    }

    @ParameterizedTest
    @EnumSource(BootstrapOwnerOutcome::class, names = ["OWNER_EMAIL_MISSING"], mode = EnumSource.Mode.EXCLUDE)
    fun `그 밖의 결과는 기동을 계속함`(outcome: BootstrapOwnerOutcome) {
        every { useCase.bootstrap(any()) } returns BootstrapOwnerResult(outcome, configuredEmailIgnored = true)

        assertNull(runCatching { listener(ownerEmail = "owner@dozycoffee.test").run() }.exceptionOrNull())
    }

    @Test
    fun `GOV-10 다른 곳에서 먼저 owner를 지정해 INVALID_STATE이면 기동을 계속함`() {
        every { useCase.bootstrap(any()) } throws OwnerAlreadyAssignedException()

        assertNull(runCatching { listener(ownerEmail = "owner@dozycoffee.test").run() }.exceptionOrNull())
    }

    private fun listener(ownerEmail: String?) = OwnerBootstrapListener(useCase, OwnerBootstrapProperties(ownerEmail = ownerEmail))
}
