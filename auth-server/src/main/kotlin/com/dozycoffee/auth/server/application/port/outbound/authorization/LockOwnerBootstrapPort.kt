package com.dozycoffee.auth.server.application.port.outbound.authorization

/**
 * GOV-11 인스턴스 여러 대가 동시에 기동해도 부트스트랩이 한 번만 실행되게 하는 잠금입니다.
 *
 * 호출한 트랜잭션이 끝날 때(커밋·롤백)까지 잠금을 쥐고, 다른 인스턴스의 같은 호출은 그때까지 기다립니다. 트랜잭션 안에서만 호출합니다.
 * 기다린 쪽은 잠금을 얻은 뒤 앞 트랜잭션이 커밋한 owner를 보게 됩니다.
 */
interface LockOwnerBootstrapPort {
    fun lockOwnerBootstrap()
}
