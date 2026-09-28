package com.dozycoffee.auth.starter

import org.springframework.security.core.annotation.AuthenticationPrincipal

/**
 * 컨트롤러 인자에 현재 [com.dozycoffee.auth.core.AuthenticatedPrincipal]을 넣습니다 (starter.md §4).
 *
 * Spring Security의 [AuthenticationPrincipal]을 메타 애노테이션으로 씁니다. 인증 정보가 없으면 `null`입니다.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@AuthenticationPrincipal
public annotation class CurrentPrincipal
