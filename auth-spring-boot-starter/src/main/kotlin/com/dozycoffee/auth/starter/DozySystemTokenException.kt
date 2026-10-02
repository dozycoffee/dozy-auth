package com.dozycoffee.auth.starter

/**
 * system token을 발급받지 못해 서비스 간 호출을 보내지 않았을 때 던집니다 (starter.md §6).
 *
 * 원인은 [error]와 [cause]에 있습니다. 메시지에는 client secret과 토큰 원문을 넣지 않습니다 (SEC-03).
 */
public class DozySystemTokenException internal constructor(
    /** 토큰 엔드포인트의 OAuth 오류 코드(`invalid_client` 등). 응답을 받지 못했으면 원인 예외의 이름입니다. */
    public val error: String,
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)
