package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.GetJwksUseCase
import com.dozycoffee.auth.server.application.port.outbound.jwt.LoadJwksPort
import org.springframework.stereotype.Service

@Service
class GetJwksService(
    private val loadJwks: LoadJwksPort,
) : GetJwksUseCase {
    override fun getJwks(): Map<String, Any> = loadJwks.load()
}
