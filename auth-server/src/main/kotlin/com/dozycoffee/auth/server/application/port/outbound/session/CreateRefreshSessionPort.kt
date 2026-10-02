package com.dozycoffee.auth.server.application.port.outbound.session

import com.dozycoffee.auth.server.domain.session.NewRefreshSession
import com.dozycoffee.auth.server.domain.session.RefreshSession

/** 로그인할 때 refresh 세션을 만듭니다 (SES-01). id는 DB가 만들며 access token의 `sid`가 됩니다. */
interface CreateRefreshSessionPort {
    /** 저장한 세션. 아직 교체·폐기 전입니다. */
    fun createSession(session: NewRefreshSession): RefreshSession
}
