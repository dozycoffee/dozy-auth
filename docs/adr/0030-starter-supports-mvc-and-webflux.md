# 0030. 스타터는 Spring MVC와 WebFlux를 모두 지원한다

- 상태: 채택
- 날짜: 2026-09-28

## 맥락

WMS, Catalog, Store는 모두 WebFlux와 Kotlin 코루틴을 씁니다. 스타터 명세는 서비스가 Spring MVC라고 가정하고 있었습니다(`RestClient`, MockMvc). [ADR-0021](0021-kotlin-spring-boot-mvc.md)의 Spring MVC는 Auth 서버 자신에 대한 결정입니다.

Auth 서버(Spring MVC)도 관리·내부 API의 토큰 검증에 스타터를 쓸 예정입니다.

## 결정

- 스타터는 Spring MVC와 WebFlux 자동 설정을 모두 둡니다. 앱 종류에 맞는 쪽만 켜집니다.
- 검증 규칙, 서명 검증, JWKS 조회, 권한 변환, 설정, 에러 응답 본문은 두 스택이 같은 코드를 씁니다. 스택별로 다른 것은 Spring Security와 연결하는 부분뿐입니다.
- WebFlux에서는 블로킹 JWKS 조회가 이벤트 루프를 막지 않도록 서명 검증을 `boundedElastic` 스케줄러에서 실행합니다.
- 인가 도구의 SpEL 사용법(`@dozyAuth.isType('PARTNER')`)은 두 스택에서 같습니다. WebFlux용 `dozyAuth`는 `Mono<Boolean>`을 돌려줍니다.
- 서비스 컨트롤러가 코루틴(`suspend`)이든 Reactor(`Mono`/`Flux`)든 같은 자동 설정으로 동작합니다. 가이드 예시는 코루틴으로 씁니다.

## 검토한 대안

- WebFlux 전용: 코드가 한 벌이지만 Auth 서버(Spring MVC)가 스타터를 쓸 수 없습니다.
- Spring MVC 전용: 서비스가 모두 WebFlux라 쓸 곳이 없습니다.
- WebFlux용 JWKS 조회를 Spring의 `NimbusReactiveJwtDecoder.withJwkSetUri`로: 재조회 간격 제한을 두 스택이 다르게 구현하게 됩니다.

## 결과

- 두 스택의 연결 코드와 테스트를 함께 유지합니다. 검증 규칙 테스트는 두 스택에서 같은 테스트를 돌립니다.
- 서비스 간 호출 클라이언트(starter.md §6)와 테스트 도구(starter.md §7)도 WebFlux 서비스에서 쓸 수 있어야 합니다.
- 관련 문서: [starter.md](../starter.md)
