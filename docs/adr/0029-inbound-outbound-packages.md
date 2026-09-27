# 0029. 방향을 나타내는 계층 패키지는 inbound·outbound로 쓴다

- 상태: 채택
- 날짜: 2026-09-27

## 맥락

[ADR-0023](0023-hexagonal-layer-first.md)의 계층 패키지를 `application/port/in`, `adapter/in`처럼 `in`/`out`으로 이름 지었습니다. 그런데 `in`은 Kotlin 예약어라 패키지 이름에 쓰려면 `` `in` ``처럼 백틱이 필요하고, ktlint의 `package-name` 규칙은 백틱이 들어간 패키지 이름을 허용하지 않습니다.

## 결정

- `application/port/in` → `application/port/inbound`, `application/port/out` → `application/port/outbound`
- `adapter/in` → `adapter/inbound`, `adapter/out` → `adapter/outbound`
- `out`은 예약어가 아니지만 이름을 맞추기 위해 함께 바꿉니다.
- 다른 ADR 본문에 남아 있는 `in`/`out` 경로(예: [ADR-0022](0022-exposed-dsl.md)의 `adapter/out/persistence`)는 이 규칙으로 읽습니다.

## 검토한 대안

- `in`만 `inbound`로 변경: 수정은 적지만 `adapter.inbound`와 `adapter.out`이 비대칭입니다.
- `in` 유지 + ktlint `package-name` 규칙 끄기: 모든 import에 백틱이 붙고, 패키지 이름의 다른 실수(대문자, 밑줄)도 잡지 못합니다.

## 결과

- 백틱 없이 import할 수 있고 ktlint 규칙을 그대로 씁니다.
- 관련 문서: [architecture.md §5](../architecture.md#5-auth-server-구조)
