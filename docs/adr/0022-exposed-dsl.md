# 0022. DB 접근은 Exposed DSL로 한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

갱신 판정처럼 "조건부 UPDATE 한 번"이 중요한 쿼리가 많고, 실행되는 SQL을 코드에서 바로 보고 싶습니다.

## 결정

- Exposed DSL만 씁니다. DAO 방식은 쓰지 않습니다.
- 시간은 `exposed-java-time`, jsonb는 `exposed-json`을 씁니다.
- DB 접근은 `adapter/out/persistence` 안에서만 합니다.

## 검토한 대안

- JPA: 지연 로딩과 변경 감지가 조건부 갱신 같은 쿼리를 숨깁니다.
- jOOQ: 코드 생성 단계가 추가됩니다.

## 결과

- 매핑 코드를 직접 씁니다.
- 관련 문서: [architecture.md §9](../architecture.md#9-코드-규칙)
