# 0031. Auth 서버의 토큰 검증은 스타터를 쓰고, 에러 응답은 서버가 만든다

- 상태: 채택
- 날짜: 2026-10-02

## 맥락

Auth 서버는 토큰을 발급하면서, 본인 API(`/realms/{realm}` 아래), 관리 API(`/admin/**`), 내부 API(`/internal/**`)에서 자기 토큰을 받는 리소스 서버이기도 합니다. 서비스는 스타터로 토큰을 검증합니다([token.md §6](../token.md#6-검증-규칙)). 서버가 검증을 따로 구현하면 같은 규칙이 두 곳에 생기고, 어긋나도 잡지 못합니다.

경로마다 검사가 다릅니다([api/conventions.md §2](../api/conventions.md#2-인증-방식)). 본인 API는 role이 없는 직원(`aud`가 빈 배열)도 써야 하므로 `aud`를 보지 않고, `iss`의 realm이 경로의 realm과 같아야 합니다. 관리·내부 API는 `aud`에 `auth`가 있어야 합니다.

서버의 401·403은 컨트롤러·`@PreAuthorize`의 에러와 같은 처리기(`GlobalExceptionHandler`)가 Problem Details로 응답합니다([ADR-0026](0026-problem-details-errors.md)).

## 결정

- `auth-server`가 `auth-spring-boot-starter`에 의존합니다.
- **검증은 스타터가 맡습니다.** 서버는 스타터의 디코더(`DozyJwtDecoders.create`, `createWithoutAudienceCheck`)와 권한 변환기를 씁니다.
  - 관리·내부 API: `create` (token.md §6 전부)
  - 본인 API: `createWithoutAudienceCheck` (8번 `aud`만 제외). `iss`의 realm과 경로 realm 비교, system token 거부는 서버의 필터 체인이 합니다.
  - 공개키는 HTTP로 받지 않고 서버가 JWKS에 게시하는 메모리 키를 `JWKSource`로 넘깁니다.
  - 설정은 스타터 속성(`dozy.auth.audience: auth`, `accepted-realms: [internal]`, `issuer-base-uri`)이고, `issuer-base-uri`는 발급하는 `iss`에도 같은 속성을 씁니다 ([configuration.md §7](../configuration.md#7-토큰-검증)).
- **필터 체인과 에러 응답은 서버가 만듭니다.** 경로별 `SecurityFilterChain`을 서버가 구성하고, 401·403은 서버의 처리기가 응답합니다. 서버가 이 빈들을 정의하므로 스타터의 기본 디코더, 필터 체인, 401·403 처리기는 만들어지지 않습니다.
- 서비스(스타터)와 서버의 401·403 본문은 필드가 같아야 하며, 테스트로 비교합니다.

## 검토한 대안

- 서버가 Nimbus로 직접 검증: 의존이 적지만 검증 규칙이 두 벌이 되고, 서버 테스트도 규칙을 흉내 내야 합니다.
- 스타터의 기본 필터 체인과 401·403 처리기까지 사용: 경로마다 다른 검증기와 공개 경로를 표현할 수 없고, 서버의 다른 에러(도메인 예외, 검증 오류)와 응답을 만드는 곳이 둘로 나뉩니다.
- 서버의 JWKS 주소를 HTTP로 조회: 자기 자신을 호출하게 되어 기동 순서와 장애가 얽힙니다.

## 결과

- 검증 규칙을 바꾸면 스타터 한 곳만 고치고, 서버와 서비스에 함께 적용됩니다. 스타터의 공개 API를 바꾸면 서버도 컴파일 대상이 됩니다.
- 서버의 토큰 발급 통합 테스트는 서비스와 같은 스타터 검증기로 확인합니다.
- owner 양도 수락(`POST /admin/owner/transfer/accept`)은 `/admin/**` 아래지만 `aud`를 검사하지 않는 예외라, owner 양도 작업에서 관리 API 체인보다 앞선 체인에 연결합니다.
- 관련 문서: [architecture.md §4](../architecture.md#4-모듈), [configuration.md §7](../configuration.md#7-토큰-검증)
