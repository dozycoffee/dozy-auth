# 0026. 에러 응답은 RFC 9457 Problem Details에 code와 traceId를 더한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

앱은 에러 문구가 아니라 안정적인 값으로 분기해야 합니다. Spring이 Problem Details를 기본 지원합니다.

## 결정

- 모든 에러는 RFC 9457 형식이며 `code`(SCREAMING_SNAKE_CASE)와 `traceId`를 추가합니다.
- 서비스 토큰 발급만 OAuth 2.0 표준 에러 형식을 따릅니다.
- 이 형식은 Auth의 규칙이며, 다른 서비스에 강제하지 않습니다(스타터의 401·403만 같은 형식).

## 검토한 대안

- 자체 에러 형식: 표준 도구를 쓸 수 없습니다.

## 결과

- 관련 문서: [api/conventions.md §4](../api/conventions.md#4-에러-응답)
