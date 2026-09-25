# 0015. JWKS는 모든 realm이 공유하고 realm은 iss로 구분한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

realm마다 키를 따로 두면 키 관리가 realm 수만큼 늘어납니다.

## 결정

- 서명 키셋은 하나이고 JWKS 주소도 하나입니다.
- realm은 issuer(`{base}/realms/{realm}`)로 구분하고, 서비스는 허용 realm의 issuer만 받습니다.

## 검토한 대안

- realm별 키셋: 키 교체 작업이 realm 수만큼 늘어납니다.

## 결과

- realm 격리는 서명 키가 아니라 `iss` 검증과 principal type 검증으로 보장합니다.
- 관련 문서: [token.md §7](../token.md#7-jwks와-서명-키)
