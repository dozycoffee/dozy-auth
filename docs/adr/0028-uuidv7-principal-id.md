# 0028. principal id는 UUIDv7이고 토큰에는 type과 id를 함께 싣는다

- 상태: 채택
- 날짜: 2026-09-25
- 대체: [0002](0002-bigint-principal-id.md)

## 맥락

ADR-0002는 "다른 서비스가 사용자 id를 bigint로 저장하고 있어 UUID로 바꾸면 모든 서비스 스키마가 바뀐다"는 이유로 bigint 순번을 택했습니다. 다시 확인해 보니 WMS와 Catalog 모두 사용자·인증 정보를 붙이기 전이고, Store는 아직 설계 전이라 서비스 쪽 변경 비용이 거의 없습니다.

한편 고객 realm 도입이 사실상 확정되면서 순번 id의 문제가 커집니다.

- id를 추측하기 쉬워서, 리소스 인가가 하나라도 빠지면 다른 사용자의 데이터에 바로 접근할 수 있습니다.
- 모든 타입이 순번을 함께 쓰므로, 새 계정의 id만 봐도 전체 계정 수와 증가 속도를 짐작할 수 있습니다.

## 결정

- `principal.id`는 `uuid DEFAULT uuidv7()`이며, DB(PostgreSQL 18)가 생성합니다. 타입과 관계없이 전역에서 유일합니다.
- 토큰에는 `sub="{type}:{id}"`와 `principalType`, `principalId` claim을 함께 넣습니다. `principalId`는 소문자·하이픈 포함 정규형 UUID 문자열입니다.
- 검증하는 쪽은 UUID 형식만 검사하고 버전은 검사하지 않습니다.
- Kotlin 타입은 `java.util.UUID`입니다.
- 서비스는 항상 `(principalType, principalId)` 쌍으로 주체를 저장합니다 (`uuid` 컬럼).
- `role`, `audience`, `verification`, `audit_log`의 자기 id는 관리 화면 안에서만 쓰이므로 bigint를 유지합니다.

## 검토한 대안

- bigint 순번 유지(ADR-0002): 읽기 쉽고 작지만, 위의 추측·규모 노출 문제가 남습니다.
- UUIDv4: 생성 시각도 드러나지 않지만, 무작위 값이라 인덱스 단편화가 생깁니다. 계정 생성 시각은 민감한 정보가 아니라고 판단했습니다.
- 내부 bigint + 외부 공개용 UUID: Auth 내부는 편하지만 식별자가 둘이 되어 매번 변환이 필요하고, 서비스가 저장하는 값은 결국 UUID라 서비스 쪽 비용은 같습니다.
- 앱에서 UUIDv7 생성: JDK에 v7 생성 기능이 없어 라이브러리가 필요합니다.

## 결과

- id를 추측하기 어려워지지만, **리소스 인가는 여전히 모든 서비스의 필수 규칙**입니다.
- UUIDv7은 앞 48비트가 생성 시각이라 계정 생성 시각이 드러납니다. 계정 수는 드러나지 않습니다.
- JavaScript 정밀도 제한(2^53 - 1)이 필요 없어집니다.
- 로그와 관리 API 경로에서 id가 길어져 읽기 어려워집니다.
- 관련 문서: [token.md §3](../token.md#3-claims), [data-model.md](../data-model.md)
