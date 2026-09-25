# 0019. 저장소는 PostgreSQL, 마이그레이션은 Flyway SQL로 한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

Auth 데이터는 관계가 강하고(계정-role-세션) 트랜잭션이 중요합니다. 트래픽은 로그인·갱신 위주로 크지 않습니다.

## 결정

- 모든 데이터를 PostgreSQL 18에 둡니다. refresh 세션도 포함합니다.
- 스키마는 Flyway SQL 파일로 관리합니다.
- enum은 `varchar` + `CHECK`로 둡니다.

## 검토한 대안

- 세션만 Redis: 인스턴스 한 대 규모에서는 운영 부담만 늡니다. 트래픽이 커지면 세션·요청 제한부터 분리합니다.

## 결과

- 관련 문서: [data-model.md](../data-model.md)
