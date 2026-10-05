# 설정

auth-server가 읽는 설정과 프로필별 동작입니다. 비밀값(DB 비밀번호, 서명 키, SMTP 비밀번호)은 저장소에 두지 않고 환경 변수나 비밀 관리 도구로 주입합니다.

## 1. 환경 변수

| 변수 | 필수 | 예시 | 설명 |
|---|---|---|---|
| `AUTH_ISSUER_BASE_URL` | ✅ | `https://auth.dozycoffee.com` | issuer 기준 주소. 뒤에 `/realms/{realm}`이 붙음. 속성 `dozy.auth.issuer-base-uri`(발급과 검증이 같은 속성, [§8](#8-토큰-검증)), local·test 기본값 `http://localhost:8080` |
| `AUTH_DB_URL` | ✅ | `jdbc:postgresql://db:5432/auth` | local은 Docker Compose 지원이 대신 설정 |
| `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD` | ✅ | | |
| `AUTH_SIGNING_KEYS_DIR` | ✅ | `/secrets/signing-keys` | 서명 키 폴더 ([§3](#3-서명-키)) |
| `AUTH_SIGNING_ACTIVE_KID` | ✅ | `dozy-2026-09` | 서명에 쓸 키 |
| `AUTH_CORS_ALLOWED_ORIGINS` | ✅ | `https://admin.dozycoffee.com,https://partner.dozycoffee.com` | CORS 허용 origin ([api/conventions.md §7](api/conventions.md#7-cors와-csrf)). 쉼표 구분, `scheme://host[:port]` 형식, 와일드카드 금지. 속성 `dozy.auth.cors.allowed-origins`, local 기본값 `http://localhost:3000`, test는 `https://admin.dozycoffee.test` |
| `AUTH_APP_URL_INTERNAL` | ✅ | `https://admin.dozycoffee.com` | internal realm 메일 링크 기준 주소 ([§7](#7-메일)) |
| `AUTH_APP_URL_PARTNER` | ✅ | `https://partner.dozycoffee.com` | partner realm 메일 링크 기준 주소 ([§7](#7-메일)) |
| `AUTH_MAIL_SENDER` | ✅ | `smtp` / `console` | `console`은 메일 내용을 로그로 출력 (local·dev 전용, [§7](#7-메일)) |
| `AUTH_MAIL_SMTP_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD` | smtp일 때 | `smtp.example.com`, `587` | `HOST`가 없으면 기동 실패. `PORT` 기본값 `587`. dev·prod는 SMTP 인증과 STARTTLS를 요구 |
| `AUTH_MAIL_FROM` | ✅ | `no-reply@dozycoffee.com` | 보내는 주소. 이메일 형식이 아니면 기동 실패 |
| `BOOTSTRAP_OWNER_EMAIL` | owner가 없을 때 | `owner@dozycoffee.com` | [GOV-11](domain.md#8-관리-권한-규칙-gov). 비밀번호는 설정에 두지 않음. 속성 `dozy.auth.bootstrap.owner-email`, local 기본값 `owner@dozycoffee.local`. 이메일 형식이 아니면 기동 실패 |

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

owner가 없는데 `BOOTSTRAP_OWNER_EMAIL`이 없으면 **모든 프로필에서** 기동에 실패합니다 ([GOV-11](domain.md#8-관리-권한-규칙-gov)). 관리할 사람이 없는 상태로 서버가 뜨는 것을 막기 위해서입니다. owner가 있으면 이 값이 없어도 됩니다.

**owner 부트스트랩 속성**

| 속성 | 환경 변수 | 설명 |
|---|---|---|
| `dozy.auth.bootstrap.owner-email` | `BOOTSTRAP_OWNER_EMAIL` | [§1](#1-환경-변수) |
| `dozy.auth.bootstrap.enabled` | - | 기동할 때 부트스트랩을 실행할지. 기본값 `true`이고 `test` 프로필만 `false`([§4](#4-프로필)) |

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
| `dozy.auth.signing.active-kid` | `AUTH_SIGNING_ACTIVE_KID` | 서명에 쓸 키. 자동 생성이 켜져 있으면 비워도 됨. 빈 문자열은 지정하지 않은 것으로 보며, 자동 생성이 꺼져 있으면 기동 실패 |
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
| `prod` | 운영 | [§2](#2-기동-시-검사) 검사. `/dev/**` 비활성. JSON 로그 ([§10](#10-지표추적로그)) |
| `test` | 자동 테스트 | Testcontainers PostgreSQL. 서명 키는 `auth-server/build/test-signing-keys/`에 자동 생성. 메일은 `console`(링크의 토큰은 가림)이고, 보낸 메일을 확인하는 테스트는 기록용 테스트 대역을 씀. 비밀번호 해시는 가벼운 파라미터 ([§6](#6-비밀번호-해시)). 테스트끼리 DB를 함께 쓰므로 owner 부트스트랩을 끄고, 부트스트랩 테스트에서만 켬. 같은 이유로 정리 배치 스케줄러도 끔 ([§11](#11-정리-배치)) |

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

## 7. 메일

발송 시점과 실패 처리는 [architecture.md §9.3](architecture.md#93-메일-발송)을 따릅니다.

| 속성 | 환경 변수 | 설명 |
|---|---|---|
| `dozy.auth.mail.sender` | `AUTH_MAIL_SENDER` | `smtp` 또는 `console` |
| `dozy.auth.mail.from` | `AUTH_MAIL_FROM` | 보내는 주소. 보내는 사람 이름은 `Dozy Coffee` |
| `dozy.auth.mail.app-url.internal` | `AUTH_APP_URL_INTERNAL` | 관리 콘솔 주소. http(s)이고 쿼리가 없어야 함 |
| `dozy.auth.mail.app-url.partner` | `AUTH_APP_URL_PARTNER` | 파트너 웹 주소. 형식은 위와 같음 |
| `spring.mail.host`, `port`, `username`, `password` | `AUTH_MAIL_SMTP_*` | SMTP 접속 정보 (Spring Boot 속성) |

- 링크는 앱 주소 뒤에 화면 경로와 토큰을 붙여 만듭니다. 경로는 [api/account.md](api/account.md)의 링크 표를 따릅니다.
- SMTP 연결·응답 시간 제한은 5초·10초입니다. Spring Boot의 메일 상태 확인(`management.health.mail`)은 끕니다. SMTP 장애로 서버 상태가 DOWN이 되지 않게 하기 위해서입니다.

**프로필별 기본값**

| 프로필 | 발송 방식 | 그 밖의 기본값 |
|---|---|---|
| `local` | `smtp` → Mailpit(`localhost:1025`, 인증·TLS 없음) | 보내는 주소 `no-reply@dozycoffee.local`, 앱 주소 `http://localhost:3000`(internal), `http://localhost:3001`(partner). 환경 변수로 바꿀 수 있음 |
| `dev`, `prod` | 환경 변수 (기본값 없음) | |
| `test` | `console` | 앱 주소 `https://admin.dozycoffee.test`, `https://partner.dozycoffee.test` |

**콘솔 발송(`console`)**

- 메일을 보내지 않고 받는 주소, 제목, 텍스트 본문을 로그로 출력합니다. `prod`에서는 기동에 실패합니다 ([§2](#2-기동-시-검사)).
- 링크의 토큰은 `local` 프로필에서만 그대로 출력하고, 그 밖의 프로필(`dev`, `test`)에서는 `token=***`로 가립니다. `local`은 개발자 PC 안에서만 보는 로그이고, `dev`는 여러 사람이 보는 공용 서버 로그이기 때문입니다 ([SEC-03](domain.md#12-민감정보-sec)의 예외). `dev`에서 링크가 필요하면 `smtp`를 씁니다.

## 8. 토큰 검증

Auth 서버가 받는 토큰(`/realms/{realm}` 아래 본인 API, `/admin/**`, `/internal/**`)은 서비스와 같은 스타터 검증기로 검증합니다 ([ADR-0031](adr/0031-server-uses-starter-verification.md)). 설정은 스타터 속성([starter.md §2](starter.md#2-설정))을 그대로 쓰며, 서버에서는 아래 값으로 고정합니다 (`application.yaml`).

| 속성 | 값 | 설명 |
|---|---|---|
| `dozy.auth.audience` | `auth` | `/admin/**`, `/internal/**`는 `aud`에 이 값이 있어야 함. 권한 변환기는 `auth` audience의 role만 권한으로 바꿈 |
| `dozy.auth.accepted-realms` | `[internal]` | 받는 토큰의 realm. 파트너 본인 API를 만들 때 `partner`를 더함 |
| `dozy.auth.issuer-base-uri` | `AUTH_ISSUER_BASE_URL` | 발급하는 `iss`의 기준 주소와 같은 속성 하나 |

- 공개키는 HTTP로 받지 않고 서버가 JWKS에 게시하는 메모리 키를 씁니다. `dozy.auth.jwk-set-uri`는 쓰지 않습니다.
- 경로별 `aud` 검사와 필터 체인은 [api/conventions.md §2](api/conventions.md#2-인증-방식)를 따릅니다. 스타터의 `public-paths`와 기본 필터 체인은 쓰지 않습니다.

## 9. 클라이언트 주소와 프록시

요청 제한([api/conventions.md §8](api/conventions.md#8-요청-제한))과 세션·감사 기록의 IP는 서블릿의 `remoteAddr`입니다. 프록시 뒤에서는 Tomcat `RemoteIpValve`(`server.forward-headers-strategy: native`)가 **신뢰할 프록시에서 온 요청일 때만** 헤더로 `remoteAddr`를 바꿉니다. 애플리케이션 코드는 `X-Forwarded-For`를 직접 읽지 않습니다.

| 헤더 | 반영 |
|---|---|
| `X-Forwarded-For` | 오른쪽부터 신뢰할 프록시 주소를 건너뛰고, 처음 만나는 신뢰하지 않는 주소를 클라이언트 주소로 씀 |
| `X-Forwarded-Proto` | 요청 scheme (`https`) |
| `Forwarded`, `X-Real-IP` 등 | 보지 않음 |

| 속성 | 환경 변수 | 설명 |
|---|---|---|
| `server.forward-headers-strategy` | - | 모든 프로필 `native` |
| `server.tomcat.remoteip.internal-proxies` | - | 신뢰할 프록시 주소. 지금은 모든 프로필이 Spring Boot 기본값(사설·루프백 대역)을 씀 |

- 신뢰할 프록시가 아닌 곳에서 온 요청은 헤더를 무시하고 연결한 주소를 씁니다. 아무나 보낸 `X-Forwarded-For`로 요청 제한을 피하거나 기록을 속일 수 없게 하기 위해서입니다.
- `prod`는 로드 밸런서 뒤에 두고, 서버 포트는 로드 밸런서에서만 접근할 수 있게 합니다. 로드 밸런서는 받은 `X-Forwarded-For` 끝에 연결한 클라이언트 주소를 붙이거나 덮어써야 합니다. 클라이언트가 보낸 값 앞부분은 위 규칙대로 무시됩니다.
- 운영 배포 환경을 정할 때 신뢰할 프록시를 로드 밸런서 주소 대역으로 좁힙니다 (예: 환경 변수 `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`). 기본값처럼 넓은 사설 대역을 믿으면 같은 대역에서 직접 접속한 클라이언트가 주소를 속일 수 있습니다.

## 10. 지표·추적·로그

### 10.1 Actuator

| 경로 | 인증 | 용도 |
|---|---|---|
| `/actuator/health` | 없음 | 로드 밸런서·오케스트레이터의 상태 확인. 상세(구성요소별 상태)는 보이지 않음 |
| `/actuator/prometheus` | 없음 | Prometheus 수집 ([§10.2](#102-지표)) |

- 그 밖의 Actuator 엔드포인트는 HTTP로 열지 않습니다 (`management.endpoints.web.exposure.include: health, prometheus`). 열지 않은 경로는 보안 설정의 마지막 체인이 거부합니다.
- 둘 다 요청 제한 대상이 아닙니다 ([api/conventions.md §8](api/conventions.md#8-요청-제한)). 주기적으로 호출되기 때문입니다.
- `/actuator/prometheus`는 서버에서 인증 없이 열고, **외부에서 닿지 않게 하는 것은 배포 환경이 맡습니다.** 로드 밸런서는 `/actuator/prometheus`를 외부로 라우팅하지 않고, Prometheus는 내부망에서 인스턴스 주소로 직접 수집합니다. 수집기가 짧은 수명의 토큰을 주기적으로 갱신하기 어렵고, 지표에는 비밀값·개인정보가 없기 때문입니다([§10.2](#102-지표)의 태그 규칙). 요청 수, JVM 상태 같은 운영 정보는 보이므로 외부에 공개하지 않습니다.

### 10.2 지표

Micrometer counter입니다. Prometheus에서는 이름의 `.`이 `_`로 바뀌고 `_total`이 붙습니다 (예: `dozy_auth_login_failed_total`).

| 지표 | 태그 | 세는 때 |
|---|---|---|
| `dozy.auth.login.succeeded` | `realm` | 로그인 성공 |
| `dozy.auth.login.failed` | `realm`, `reason` | 로그인 실패. `reason`은 응답의 에러 code (`INVALID_CREDENTIALS`, `EMAIL_NOT_VERIFIED`, `ACCOUNT_SUSPENDED`, 계정 잠금의 `TOO_MANY_ATTEMPTS`). IP 요청 제한으로 컨트롤러 전에 거부된 요청은 여기 세지 않고 `dozy.auth.ratelimit.rejected`로 셈 |
| `dozy.auth.token.issued` | `kind`, `realm` | access token 발급. `kind`는 `login`, `refresh`, `client_credentials`(서비스 토큰), `dev`(개발용 API, `local`·`dev`만) |
| `dozy.auth.refresh.reuse.detected` | `realm` | 재사용 탐지로 세션을 폐기함 ([SES-03](domain.md#6-세션-규칙-ses)) |
| `dozy.auth.ratelimit.rejected` | `limit` | 요청 제한 초과 ([api/conventions.md §8](api/conventions.md#8-요청-제한)). `limit`은 `ip`, `email`(같은 `202`로 응답하고 메일만 보내지 않음), `password_confirm` |
| `dozy.auth.cleanup.deleted` | `table` | 정리 배치([§11](#11-정리-배치))가 지우고 커밋한 행 수. `table`은 `refresh_session`, `verification`, `audit_log`. 묶음마다 더하므로 실패한 실행에서 앞서 커밋한 묶음도 셈 |
| `dozy.auth.cleanup.runs` | `outcome` | 정리 배치 실행이 끝남. `outcome`은 `success`, `failure` |
| `dozy.auth.mail.failed` | `kind` | 메일을 보내지 못함 (발송 실패, 발송 대기열 가득 참. [architecture.md §9.3](architecture.md#93-메일-발송)). `kind`는 `employee_invitation`, `password_reset`, `owner_transfer_request`, `owner_transfer_completed`, `owner_notification`. 커밋 뒤 메일 발송기가 셈 |

- 이름은 `dozy.auth.`로 시작하는 점 구분 소문자입니다. 지표를 추가하면 이 표에 먼저 넣습니다.
- 태그 값은 정해진 몇 가지만 씁니다 (realm, 에러 code, 위 표의 값). 이메일, principal id, 세션 id, IP, client_id처럼 값이 계속 늘어나는 것은 태그에 넣지 않습니다. 지표 저장소가 커지지 않게 하고, 개인정보가 지표로 나가지 않게 하기 위해서입니다 ([SEC-03](domain.md#12-민감정보-sec)).
- 결과가 정해진 시점에 세며 트랜잭션 커밋을 기다리지 않습니다. counter는 처음 일어날 때 생기므로, 한 번도 일어나지 않은 조합은 수집 결과에 없습니다.
- 코드에서는 UseCase가 `RecordMetricsPort`로 남깁니다 ([architecture.md §9](architecture.md#9-코드-규칙)). `dozy.auth.mail.failed`만 커밋 뒤 발송을 맡는 메일 어댑터(`AfterCommitMailSender`)가 같은 포트로 남깁니다.
- Spring Boot 기본 지표(HTTP 요청 `http.server.requests`, JVM, DB 연결 풀 등)도 함께 나옵니다.

### 10.3 추적

- Micrometer Tracing(Brave)으로 요청마다 trace를 만듭니다. 요청에 W3C `traceparent` 헤더가 있으면 그 trace를 이어 갑니다.
- trace id(소문자 hex 32자)가 응답의 `X-Trace-Id`, 에러 응답의 `traceId`, 그 요청 중에 남은 로그의 `traceId`에 같은 값으로 들어갑니다 ([api/conventions.md §9](api/conventions.md#9-추적과-로그)).
- 스팬을 내보내는 곳(Zipkin, OTLP 등)은 아직 두지 않습니다. 지금은 trace id를 응답과 로그를 잇는 데만 씁니다. 수집기를 정하면 exporter 의존성과 `management.tracing.*` 설정(표본 비율 등)을 추가합니다.

### 10.4 로그

| 프로필 | 형식 |
|---|---|
| `prod` | 한 줄 JSON. Spring Boot 구조화 로그의 ECS 형식(`logging.structured.format.console: ecs`)이고, `traceId`·`spanId`가 최상위 필드 |
| 그 밖 | Spring Boot 기본 텍스트 형식. 줄마다 `[{traceId}-{spanId}]`가 들어감 |

- 로그에 남기지 않는 값은 [SEC-03](domain.md#12-민감정보-sec)을 따릅니다. 서버 코드와 Spring Web·Security 로그를 디버그로 올려도 비밀번호, 토큰, 쿠키 값, `Authorization` 헤더가 남지 않는지 테스트(`ObservabilityApiTest`)로 확인합니다.

## 11. 정리 배치

[AUD-05](domain.md#11-감사와-알림-aud) 정리 배치는 `policy.cleanup-schedule`마다 실행합니다 (`@Scheduled`, [ADR-0024](adr/0024-in-memory-rate-limit-and-scheduler.md)). 실행 시각은 정책 상수(`domain.AuthPolicy`)이며 속성으로 노출하지 않습니다. 삭제 방식은 [data-model.md §5](data-model.md#5-정리)를 따릅니다.

| 속성 | 설명 |
|---|---|
| `dozy.auth.cleanup.enabled` | 스케줄러를 등록할지. 기본값 `true`이고 `test` 프로필만 `false`([§4](#4-프로필)) |

- 실행이 끝나면 테이블별 삭제 건수만 `INFO` 로그로 남깁니다. 지운 행의 값은 남기지 않습니다 ([SEC-03](domain.md#12-민감정보-sec)).
- 실패하면 `ERROR` 로그를 남기고 다음 실행을 기다립니다. 다시 시도하지 않습니다.
- 지표는 [§10.2](#102-지표)의 `dozy.auth.cleanup.*`입니다.
