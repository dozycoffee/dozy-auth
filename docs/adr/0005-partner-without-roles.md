# 0005. 파트너에게는 Auth role을 주지 않고 매장 관계는 Store가 관리한다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

점주는 매장 단위로 권한이 갈립니다. 이것을 role로 표현하면 매장마다 role이 필요합니다.

## 결정

- 파트너는 셀프 가입하고 role 없이 `aud=["store"]` 토큰을 받습니다.
- 매장관리 직원이 Store 서비스에서 이메일로 점주를 조회해 매장에 할당합니다. 관계는 Store의 `store_member`에 있습니다.
- Auth는 이메일로 파트너를 찾는 내부 API만 제공합니다.

## 검토한 대안

- 매장별 role(`store:owner_123`): role이 매장 수만큼 늘고 Auth가 매장 데이터를 알아야 합니다.

## 결과

- Store는 모든 API에서 리소스 인가(소유 여부)를 해야 합니다.
- 관련 규칙: [DOM-04](../domain.md#11-realm과-principal-type), [INT-01](../domain.md#10-서비스-연계-규칙-int)
