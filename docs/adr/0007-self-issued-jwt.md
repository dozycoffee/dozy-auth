# 0007. 토큰 발급은 Spring Security와 Nimbus로 직접 구현한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

직접 로그인 API([ADR-0006](0006-direct-login-api.md))에서는 OAuth 인가 서버의 프로토콜 엔드포인트가 필요 없습니다.

## 결정

- JWT 서명은 Nimbus JOSE+JWT(Spring Security의 `NimbusJwtEncoder`/`Decoder`)로 합니다.
- 서비스 토큰 발급만 OAuth 2.0 client credentials 형식을 따릅니다. 서비스가 표준 OAuth2 Client를 쓸 수 있게 하기 위해서입니다.
- Spring Authorization Server는 PKCE 확장 때 도입합니다.

## 검토한 대안

- jjwt: Spring Security와 통합이 약하고 스타터의 검증 측과 라이브러리가 달라집니다.
- Spring Authorization Server: 쓰지 않을 엔드포인트와 설정이 많습니다.

## 결과

- 발급과 검증이 같은 라이브러리(Nimbus)를 씁니다.
- 로그인, 갱신, 세션 관리는 직접 구현합니다.
