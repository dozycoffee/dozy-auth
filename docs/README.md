# dozy-auth 명세

이 폴더는 dozy-auth 구현의 **기준 명세**입니다. 코드와 명세가 다르면 명세가 기준입니다.

## 담당 범위

같은 내용은 한 문서에만 씁니다. 다른 문서는 값을 다시 쓰지 않고 링크(정책 이름, 규칙 ID, 절)로 참조합니다.

| 내용 | 담당 문서 |
|---|---|
| 용어, 정책 수치, 도메인 규칙, 감사 action, owner 알림, 민감정보 취급 | [domain.md](domain.md) |
| 토큰 형식, `aud` 규칙, 검증 순서, JWKS, 호환성, auth-core 공유 타입 | [token.md](token.md) |
| API 공통 형식, 인증 방식, refresh 쿠키, CORS·CSRF, 요청 제한, 에러 응답 구조, 에러 코드 | [api/conventions.md](api/conventions.md) |
| 엔드포인트 요청·응답과 호출 흐름 | [api/auth.md](api/auth.md), [api/account.md](api/account.md), [api/admin.md](api/admin.md), [api/internal.md](api/internal.md), [api/dev.md](api/dev.md) |
| 테이블, 컬럼, 인덱스, 제약, seed | [data-model.md](data-model.md) |
| 스타터·auth-test가 서비스에 약속하는 것 | [starter.md](starter.md) |
| 서버 환경 변수, 서명 키 파일, 프로필 | [configuration.md](configuration.md) |
| 구성요소, 책임 경계, 모듈, 계층, 의존·이름·코드 규칙 | [architecture.md](architecture.md) |
| 결정과 이유 | [adr/](adr/README.md) |
| 빌드·실행 방법, 모듈 사용 방법(의존성 추가), 모듈 안의 폴더 구조 | 루트 [README.md](../README.md)와 각 모듈 README |

**API 파일 구분**

| 파일 | 경로 |
|---|---|
| `auth.md` | 로그인 세션: `/realms/{realm}/login`, `token/refresh`, `logout`, `me`(조회), `password/change` |
| `account.md` | 계정 생성·복구·본인 관리: `signup/*`, `invitations/*`, `password/forgot`, `password/reset`, `me`(수정), `me/deactivate` |
| `admin.md` | `/admin/**` |
| `internal.md` | 서비스가 호출: `/realms/internal/token`, `/.well-known/jwks.json`, `/internal/**` |
| `dev.md` | `/dev/**` |

## 참조 방식

- **정책 수치:** `policy.access-token-ttl`처럼 이름으로 참조합니다. 값은 [domain.md §2](domain.md#2-정책-값)에만 있습니다.
- **규칙:** `SES-03`처럼 ID로 참조합니다. 코드 주석과 테스트 이름에도 이 ID를 씁니다 (예: `` `SES-03 교체 직후 직전 토큰이면 TOKEN_ROTATED` ``).
- **ADR:** `ADR-0013`처럼 번호로 참조합니다.

## 수정 규칙

1. 동작이 바뀌면 **같은 PR에서** 담당 문서를 고칩니다. 명세와 코드가 다른 상태로 `main`에 들어가지 않게 합니다.
2. 담당 문서가 아닌 곳에 값이나 규칙을 다시 쓰지 않습니다. 필요하면 링크합니다.
3. 규칙 ID는 재사용하지 않습니다. 규칙을 없애면 "(삭제)"로 남기고 번호를 비웁니다.
4. 이미 정한 결정을 바꾸려면 새 ADR을 쓰고 기존 ADR을 "대체됨"으로 표시합니다. 기존 ADR 본문은 고치지 않습니다.
5. 에러 코드를 추가하면 [api/conventions.md §11](api/conventions.md#11-에러-코드)에 먼저 넣습니다.
6. [token.md](token.md)나 [starter.md](starter.md)를 바꾸면 Notion "서비스 연동 가이드"도 갱신합니다. 다른 팀은 그 문서를 봅니다.

## 문서 밖의 것

- 로컬 메모(`.context/`, `CLAUDE.local.md`)는 명세가 아닙니다. 결정이 되면 여기로 옮깁니다.
- 운영 절차(배포, 키 교체 작업, 사고 대응)와 작업 계획은 Notion에서 관리합니다.
