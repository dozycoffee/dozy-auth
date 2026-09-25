# 0001. 계정은 공통 principal 테이블과 타입별 profile로 나눈다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

직원, 점주, 서비스, 고객은 로그인 방식과 필드가 다르지만 role, 세션, 크리덴셜, 감사 로그는 모두 "계정"을 참조해야 합니다.

## 결정

- 모든 계정의 공통 정보(id, type, status, 로그인 잠금)는 `principal`에 둡니다.
- 타입별 정보는 `employee_profile`, `partner_profile`, `customer_profile`, `system_client`에 두고 PK를 `principal.id`로 합니다.
- role, 세션, 크리덴셜은 `principal.id`를 FK로 참조합니다.
- 로그인은 realm에 맞는 profile에서만 조회합니다.

## 검토한 대안

- realm별 계정 테이블 완전 분리: 공통 테이블(role, 세션)이 여러 테이블을 참조해야 해서 FK를 걸 수 없습니다.
- 단일 테이블 + nullable 컬럼: 타입별 필드가 섞이고 이메일 유일성을 realm 단위로 걸기 어렵습니다.

## 결과

- FK로 정합성을 보장하고, 이메일 유일성은 profile 테이블 단위(=realm 단위)가 됩니다.
- 계정 조회 시 principal과 profile 조인이 필요합니다.
- 관련 문서: [data-model.md](../data-model.md), [DOM-02](../domain.md#11-realm과-principal-type)
