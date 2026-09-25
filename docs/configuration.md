# 설정

auth-server가 읽는 설정과 프로필별 동작입니다. 비밀값(DB 비밀번호, 서명 키, SMTP 비밀번호)은 저장소에 두지 않고 환경 변수나 비밀 관리 도구로 주입합니다.

## 1. 환경 변수

| 변수 | 필수 | 예시 | 설명 |
|---|---|---|---|
| `AUTH_ISSUER_BASE_URL` | ✅ | `https://auth.dozycoffee.com` | issuer 기준 주소. 뒤에 `/realms/{realm}`이 붙음 |
| `AUTH_DB_URL` | ✅ | `jdbc:postgresql://db:5432/auth` | local은 Docker Compose 지원이 대신 설정 |
| `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD` | ✅ | | |
| `AUTH_SIGNING_KEYS_DIR` | ✅ | `/secrets/signing-keys` | 서명 키 폴더 ([§3](#3-서명-키)) |
| `AUTH_SIGNING_ACTIVE_KID` | ✅ | `dozy-2026-09` | 서명에 쓸 키 |
| `AUTH_CORS_ALLOWED_ORIGINS` | ✅ | `https://admin.dozycoffee.com,https://partner.dozycoffee.com` | 쉼표 구분, 와일드카드 금지 |
| `AUTH_APP_URL_INTERNAL` | ✅ | `https://admin.dozycoffee.com` | internal realm 메일 링크 기준 주소 |
| `AUTH_APP_URL_PARTNER` | ✅ | `https://partner.dozycoffee.com` | partner realm 메일 링크 기준 주소 |
| `AUTH_MAIL_SENDER` | ✅ | `smtp` / `console` | `console`은 메일 내용을 로그로 출력 (local·dev 전용) |
| `AUTH_MAIL_SMTP_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD` | smtp일 때 | | |
| `AUTH_MAIL_FROM` | ✅ | `no-reply@dozycoffee.com` | |
| `BOOTSTRAP_OWNER_EMAIL` | owner가 없을 때 | `owner@dozycoffee.com` | [GOV-11](domain.md#8-관리-권한-규칙-gov). 비밀번호는 설정에 두지 않음 |

- 정책 수치([domain.md §2](domain.md#2-정책-값))는 코드 기본값으로 두고, 바꿀 필요가 생기면 `dozy.auth.policy.*` 속성으로 노출합니다. 속성 이름은 정책 이름에서 `policy.`를 뗀 것입니다 (예: `dozy.auth.policy.access-token-ttl`).
- 환경 변수와 Spring 속성의 연결은 `application.yaml`에서 `${AUTH_...}`로 합니다.

## 2. 기동 시 검사

`prod` 프로필에서 아래 중 하나라도 해당하면 기동에 실패합니다. 잘못된 설정으로 운영이 뜨는 것을 막기 위해서입니다.

- `AUTH_MAIL_SENDER=console` (초대 링크가 로그로 새는 것을 막음)
- 서명 키 폴더가 없거나, `AUTH_SIGNING_ACTIVE_KID`에 해당하는 키 파일이 없음
- 키가 `policy.signing-key-size`보다 작음
- `AUTH_CORS_ALLOWED_ORIGINS`에 `*`가 있음
- 개발용 토큰 API(`local`·`dev` 전용)나 서명 키 자동 생성(`local` 전용)이 활성화됨

## 3. 서명 키

```text
{AUTH_SIGNING_KEYS_DIR}/
├─ dozy-2026-09.pem      AUTH_SIGNING_ACTIVE_KID로 지정된 키로 서명
└─ dozy-2027-09.pem      교체 준비 중인 키 (JWKS에만 게시)
```

- 파일 이름(확장자 제외)이 `kid`입니다. 형식은 [token.md §2](token.md#2-header)를 따릅니다.
- 폴더 안 모든 키의 공개키를 JWKS에 게시합니다.
- 서명은 `AUTH_SIGNING_ACTIVE_KID` 키로만 합니다.
- PEM은 PKCS#8 RSA 개인키입니다.
- 교체 순서는 [token.md §7](token.md#7-jwks와-서명-키)을 따릅니다.

## 4. 프로필

| 프로필 | 용도 | 동작 |
|---|---|---|
| `local` | 개발자 PC | Docker Compose 지원으로 PostgreSQL·Mailpit 자동 기동. 서명 키가 없으면 `.local/signing-keys/`에 생성해 재사용. `/dev/**` 활성. 부트스트랩 이메일 기본값 `owner@dozycoffee.local` |
| `dev` | 공용 개발 서버 | `/dev/**` 활성. 서명 키는 설정으로 주입 (자동 생성 없음) |
| `prod` | 운영 | [§2](#2-기동-시-검사) 검사. `/dev/**` 비활성. JSON 로그 |
| `test` | 자동 테스트 | Testcontainers PostgreSQL, 테스트용 서명 키, 메일은 테스트 대역 |

- 개발용 API는 `@Profile("local", "dev")`로만 등록합니다.
- `.local/`은 git에 올리지 않습니다.

## 5. 로컬 실행 환경

| 항목 | 값 |
|---|---|
| Compose 파일 | 저장소 루트 `compose.yaml` (`bootRun`의 작업 폴더가 루트) |
| PostgreSQL | 이미지 `postgres:18`, DB `auth` |
| Mailpit | SMTP `1025`, 메일함 `http://localhost:8025` |
| 앱 | `http://localhost:8080` |

- 브라우저는 `localhost`를 예외로 취급해 `Secure` 쿠키를 HTTP에서도 보냅니다. 포트가 달라도 같은 사이트라 쿠키가 전달됩니다.
