# 0002. principal id는 bigint 순번이고 토큰에는 type과 id를 함께 싣는다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

다른 서비스들이 사용자 id를 bigint로 저장하고 있어 UUID로 바꾸면 모든 서비스 스키마가 바뀝니다. 한편 id만으로는 어떤 종류의 계정인지 알 수 없습니다.

## 결정

- `principal.id`는 `bigint GENERATED ALWAYS AS IDENTITY`이며 타입과 관계없이 전역에서 유일합니다.
- 토큰에는 `sub="{type}:{id}"`와 `principalType`, `principalId` claim을 함께 넣습니다.
- 서비스는 항상 `(principalType, principalId)` 쌍으로 주체를 저장합니다.
- 휘발성 테이블 중 `refresh_session`만 UUID를 씁니다(토큰의 `sid`로 노출되기 때문).

## 검토한 대안

- UUID: 추측이 어렵지만 서비스 스키마 변경 비용이 큽니다.
- 타입별 id 공간: 같은 id가 여러 타입에 생겨 혼동이 생깁니다.

## 결과

- id가 순번이라 추측하기 쉬우므로 **모든 서비스에서 리소스 인가가 필수**입니다.
- `principalId`는 JavaScript 안전 정수 범위 안에 있어야 합니다.
