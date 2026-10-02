# 설정

auth-server가 읽는 설정과 프로필별 동작입니다. 비밀값(DB 비밀번호, 서명 키, SMTP 비밀번호)은 저장소에 두지 않고 환경 변수나 비밀 관리 도구로 주입합니다.

## 1. 환경 변수

| 변수 | 필수 | 예시 | 설명 |
|---|---|---|---|
| `AUTH_ISSUER_BASE_URL` | ✅ | `https://auth.dozycoffee.com` | issuer 기준 주소. 뒤에 `/realms/{realm}`이 붙음. 속성 `dozy.auth.issuer-base-uri`(발급과 검증이 같은 속성, [§7](#7-토큰-검증)), local·test 기본값 `http://localhost:8080` |
| `AUTH_DB_URL` | ✅ | `jdbc:postgresql://db:5432/auth` | local은 Docker Compose 지원이 대신 설정 |
| `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD` | ✅ | | |
| `AUTH_SIGNING_KEYS_DIR` | ✅ | `/secrets/signing-keys` | 서명 키 폴더 ([§3](#3-서명-키)) |
| `AUTH_SIGNING_ACTIVE_KID` | ✅ | `dozy-2026-09` | 서명에 쓸 키 |
| `AUTH_CORS_ALLOWED_ORIGINS` | ✅ | `https://admin.dozycoffee.com,https://partner.dozycoffee.com` | CORS 허용 origin ([api/conventions.md §7](api/conventions.md#7-cors와-csrf)). 쉼표 구분, `scheme://host[:port]` 형식, 와일드카드 금지. 속성 `dozy.auth.cors.allowed-origins`, local 기본값 `http://localhost:3000`, test는 `https://admin.dozycoffee.test` |
| `AUTH_APP_URL_INTERNAL` | ✅ | `https://admin.dozycoffee.com` | internal realm 메일 링크 기준 주소 |
| `AUTH_APP_URL_PARTNER` | ✅ | `https://partner.dozycoffee.com` | partner realm 메일 링크 기준 주소 |
| `AUTH_MAIL_SENDER` | ✅ | `smtp` / `console` | `console`은 메일 내용을 로그로 출력 (local·dev 전용) |
| `AUTH_MAIL_SMTP_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD` | smtp일 때 | | |
| `AUTH_MAIL_FROM` | ✅ | `no-reply@dozycoffee.com` | |
| `BOOTSTRAP_OWNER_EMAIL` | owner가 없을 때 | `owner@dozycoffee.com` | [GOV-11](domain.md#8-관리-권한-규칙-gov). 비밀번호는 설정에 두지 않음 |

- 정책 수치([domain.md §2](domain.md#2-정책-값))는 코드 기본값(`domain.AuthPolicy`)으로 두고, 바꿀 필요가 생기면 `dozy.auth.policy.*` 속성으로 노출합니다. 속성 이름은 정책 이름에서 `policy.`를 뗀 것입니다 (예: `dozy.auth.policy.access-token-ttl`).
- 환경 변수와 Spring 속성의 연결은 `application.yaml`에서 `${AUTH_...}`로 합니다.

## 2. 기동 시 검사

`prod` 프로필에서 아래 중 하나라도 해당하면 기동에 실패합니다. 잘못된 설정으로 운영이 뜨는 것을 막기 위해서입니다.

- `AUTH_MAIL_SENDER=console` (초대 링크가 로그로 새는 것을 막음)
- 서명 키 폴더가 없거나, `AUTH_SIGNING_ACTIVE_KID`에 해당하는 키 파일이 없음
- 키가 `policy.signing-key-size`보다 작음
- `AUTH_CORS_ALLOWED_ORIGINS`에 `*`가 있음
- 개발용 토큰 API(`local`·`dev` 전용)나 서명 키 자동 생성(`local`·`test` 전용)이 활성화됨

CORS 허용 origin이 비어 있거나 와일드카드·origin 형식이 아닌 값이 있으면 **모든 프로필에서** 기동에 실패합니다. 쿠키를 허용하는 CORS에는 와일드카드를 쓸 수 없기 때문입니다.

## 3. 서명 키

```text
{AUTH_SIGNING_KEYS_DIR}/
├─ dozy-2026-09.pem      AUTH_SIGNING_ACTIVE_KID로 지정된 키로 서명
└─ dozy-2027-09.pem      교체 준비 중인 키 (JWKS에만 게시)
```

- 파일 이름(확장자 제외)이 `kid`입니다. 형식은 [token.md §2](token.md#2-header)를 따릅니다.
- 폴더 안 모든 키의 공개키를 JWKS에 게시합니다.
- 서명은 `AUTH_SIGNING_ACTIVE_KID` 키로만 합니다.
- PEM은 PKCS#8 RSA 개인키(`BEGIN PRIVATE KEY`)입니다. PKCS#1(`BEGIN RSA PRIVATE KEY`)은 거부합니다.
- 키 크기가 `policy.signing-key-size`보다 작거나, 파일 이름이 `kid` 형식이 아니면 **모든 프로필에서** 기동에 실패합니다.
- 교체 순서는 [token.md §7](token.md#7-jwks와-서명-키)을 따릅니다.

**설정 속성**

| 속성 | 환경 변수 | 설명 |
|---|---|---|
| `dozy.auth.signing.keys-dir` | `AUTH_SIGNING_KEYS_DIR` | 서명 키 폴더 |
| `dozy.auth.signing.active-kid` | `AUTH_SIGNING_ACTIVE_KID` | 서명에 쓸 키. 자동 생성이 켜져 있으면 비워도 됨 |
| `dozy.auth.signing.auto-generate` | - | 키가 없으면 만들어 저장. `local`·`test` 전용이며 `prod`에서 켜면 기동 실패 |

**자동 생성 (`local`, `test`)**

- `active-kid`를 지정했으면 그 키 파일이 없을 때만 만듭니다.
- 지정하지 않았으면 폴더가 비어 있을 때 `dozy-{연도}-{월}`(UTC 현재 시각)로 만들고, 이후에는 폴더에서 이름순으로 마지막 키로 서명합니다.
- 만든 파일은 소유자만 읽고 쓸 수 있게(`rw-------`) 저장하고, 다음 기동부터 재사용합니다.

## 4. 프로필

| 프로필 | 용도 | 동작 |
|---|---|---|
| `local` | 개발자 PC | Docker Compose 지원으로 PostgreSQL·Mailpit 자동 기동. 서명 키가 없으면 `.local/signing-keys/`에 생성해 재사용. `/dev/**` 활성. 부트스트랩 이메일 기본값 `owner@dozycoffee.local` |
| `dev` | 공용 개발 서버 | `/dev/**` 활성. 서명 키는 설정으로 주입 (자동 생성 없음) |
| `prod` | 운영 | [§2](#2-기동-시-검사) 검사. `/dev/**` 비활성. JSON 로그 |
| `test` | 자동 테스트 | Testcontainers PostgreSQL. 서명 키는 `auth-server/build/test-signing-keys/`에 자동 생성. 메일은 테스트 대역. 비밀번호 해시는 가벼운 파라미터 ([§6](#6-비밀번호-해시)) |

- 개발용 API는 `@Profile("local", "dev")`로만 등록합니다.
- `.local/`은 git에 올리지 않습니다.

## 5. 로컬 실행 환경

| 항목 | 값 |
|---|---|
| Compose 파일 | 저장소 루트 `compose.yaml` (`bootRun`의 작업 폴더가 루트) |
| PostgreSQL | 이미지 `postgres:18`, DB·계정·비밀번호 `auth`. 호스트 포트는 `AUTH_LOCAL_DB_PORT`(기본 `5432`) |
| Mailpit | SMTP `1025`, 메일함 `http://localhost:8025` |
| 앱 | `http://localhost:8080` |
| Compose 수명 | 앱을 꺼도 컨테이너를 유지 (`lifecycle-management: start-only`) |

- `local` 프로필에서만 Docker Compose 지원을 켭니다. 다른 프로필과 테스트에서는 꺼져 있습니다.
- DB 접속 정보는 Docker Compose 지원이 `compose.yaml`에서 가져오므로 `AUTH_DB_*`가 필요 없습니다. `dev`·`prod`는 `AUTH_DB_*`가 없으면 기동에 실패합니다.

- 브라우저는 `localhost`를 예외로 취급해 `Secure` 쿠키를 HTTP에서도 보냅니다. 포트가 달라도 같은 사이트라 쿠키가 전달됩니다.

## 6. 비밀번호 해시

새 비밀번호를 해시할 때 쓰는 argon2id 파라미터입니다 ([PWD-04](domain.md#4-비밀번호-규칙-pwd), [ADR-0027](adr/0027-argon2id-password-hash.md)). 파라미터는 해시 인코딩 문자열에 들어가므로, 값을 바꿔도 기존 해시는 만들 때의 파라미터로 검증됩니다. 바꾼 값은 새로 해시하는 비밀번호(가입, 초대 수락, 변경, 재설정)부터 적용됩니다.

| 속성 | 기본값 | 설명 |
|---|---|---|
| `dozy.auth.password-hash.memory-kib` | `19456` (19 MiB) | 메모리 비용. `parallelism`의 8배 이상 |
| `dozy.auth.password-hash.iterations` | `5` | 반복 횟수. 1 이상 |
| `dozy.auth.password-hash.parallelism` | `1` | 병렬도 (lane 수) |
| `dozy.auth.password-hash.salt-length` | `16` | salt 바이트 수. 16 이상 |
| `dozy.auth.password-hash.hash-length` | `32` | 해시 출력 바이트 수. 16 이상 |

- 기본값은 해시·검증 한 번이 100~300ms가 되도록 측정해 정했습니다. 운영 서버에서 이 범위를 벗어나면 `iterations`로 조정합니다.
- 해시 한 번이 `memory-kib`만큼 JVM 힙을 씁니다. 동시 로그인 수만큼 곱해지므로 `memory-kib`를 키울 때는 힙 크기를 함께 봅니다.
- 범위를 벗어난 값이면 기동에 실패합니다.
- `test` 프로필은 테스트 속도를 위해 `memory-kib: 1024`, `iterations: 1`을 씁니다. 운영 프로필에서는 이 값을 쓰지 않습니다.

## 7. 토큰 검증

Auth 서버가 받는 토큰(`/realms/{realm}` 아래 본인 API, `/admin/**`, `/internal/**`)은 서비스와 같은 스타터 검증기로 검증합니다 ([ADR-0031](adr/0031-server-uses-starter-verification.md)). 설정은 스타터 속성([starter.md §2](starter.md#2-설정))을 그대로 쓰며, 서버에서는 아래 값으로 고정합니다 (`application.yaml`).

| 속성 | 값 | 설명 |
|---|---|---|
| `dozy.auth.audience` | `auth` | `/admin/**`, `/internal/**`는 `aud`에 이 값이 있어야 함. 권한 변환기는 `auth` audience의 role만 권한으로 바꿈 |
| `dozy.auth.accepted-realms` | `[internal]` | 받는 토큰의 realm. 파트너 본인 API를 만들 때 `partner`를 더함 |
| `dozy.auth.issuer-base-uri` | `AUTH_ISSUER_BASE_URL` | 발급하는 `iss`의 기준 주소와 같은 속성 하나 |

- 공개키는 HTTP로 받지 않고 서버가 JWKS에 게시하는 메모리 키를 씁니다. `dozy.auth.jwk-set-uri`는 쓰지 않습니다.
- 경로별 `aud` 검사와 필터 체인은 [api/conventions.md §2](api/conventions.md#2-인증-방식)를 따릅니다. 스타터의 `public-paths`와 기본 필터 체인은 쓰지 않습니다.
