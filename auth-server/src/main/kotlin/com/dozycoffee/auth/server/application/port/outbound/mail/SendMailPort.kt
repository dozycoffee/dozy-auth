package com.dozycoffee.auth.server.application.port.outbound.mail

/**
 * 메일을 보냅니다. 메일 종류와 값([Mail])만 받고, 문구·템플릿·링크 주소는 메일 어댑터가 정합니다 (architecture.md §8).
 *
 * 트랜잭션 안에서 호출하면 **커밋된 뒤에** 보내고, 롤백되면 보내지 않습니다. 발송은 요청 스레드가 아닌 별도 스레드에서 하며,
 * 실패해도 호출한 쪽에 예외를 던지지 않습니다 (architecture.md §9.3). 그래서 UseCase는 발송 결과에 의존하지 않습니다.
 */
interface SendMailPort {
    fun send(mail: Mail)
}
