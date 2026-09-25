# dozy-auth

Dozy Coffee의 인증 서비스입니다. 직원·점주·서비스 계정의 로그인과 토큰 발급을 맡고, WMS·Catalog·Store 서비스가 토큰을 검증할 때 쓰는 라이브러리를 함께 제공합니다.

Auth는 **"누구인가"와 "어떤 role을 가졌는가"만 보증**합니다. 그 정보로 무엇을 허용할지는 각 서비스가 정합니다.

> 현재 구현 초기 단계입니다. "준비 중"으로 표시한 절은 해당 기능이 만들어지면 갱신합니다.

## 모듈

| 모듈 | 설명 |
|---|---|
| [`auth-core`](auth-core/README.md) | Auth 서버와 라이브러리가 함께 쓰는 타입과 상수 |
| [`auth-server`](auth-server/README.md) | Auth 서버 |
| [`auth-spring-boot-starter`](auth-spring-boot-starter/README.md) | 서비스용 토큰 검증·인가 자동 설정 |
| [`auth-test`](auth-test/README.md) | 서비스 테스트 도구 |

## 준비물

- JDK 21 (Gradle 실행용. 라이브러리 빌드에 쓰는 JDK 17은 없으면 Gradle이 자동으로 내려받습니다)
- Docker (로컬 실행과 통합 테스트용)

PR과 `main` 푸시마다 GitHub Actions가 `./gradlew build`(ktlint, 테스트)를 실행합니다 ([.github/workflows/ci.yml](.github/workflows/ci.yml)).

## 빠른 시작

```bash
./gradlew build                      # 빌드, 테스트, ktlint 검사
```

### 로컬 실행 (준비 중: 로컬 Compose·프로필 설정)

```bash
./gradlew :auth-server:bootRun --args='--spring.profiles.active=local'
```

- Docker Compose 지원이 `compose.yaml`의 PostgreSQL과 Mailpit을 자동으로 띄웁니다.
- 서명 키가 없으면 `.local/signing-keys/`에 자동으로 만듭니다.

| 확인할 곳 | 주소 |
|---|---|
| 앱 | http://localhost:8080 |
| JWKS | http://localhost:8080/.well-known/jwks.json |
| Mailpit 메일함 | http://localhost:8025 |

### 첫 owner 계정 만들기 (준비 중: 초대·로그인 API)

처음 기동하면 owner가 없으므로 부트스트랩 이메일(`owner@dozycoffee.local`)로 초대 메일이 갑니다. 관리 콘솔 없이 API로 수락하고 로그인할 수 있습니다.

1. Mailpit에서 초대 메일을 열고 링크의 `token` 값을 복사합니다.
2. 초대를 수락하고 로그인합니다.

```bash
curl -X POST http://localhost:8080/realms/internal/invitations/accept \
  -H 'Content-Type: application/json' \
  -d '{"token": "메일의 토큰", "password": "local-owner-password"}'

curl -i -X POST http://localhost:8080/realms/internal/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "owner@dozycoffee.local", "password": "local-owner-password"}'
```

### 개발용 토큰 (준비 중: 개발용 토큰 API)

로그인 없이 원하는 role의 토큰이 필요하면 `local` 프로필의 개발용 API를 씁니다. 형식은 [docs/api/dev.md](docs/api/dev.md)에 있습니다.

```bash
curl -X POST http://localhost:8080/dev/tokens \
  -H 'Content-Type: application/json' \
  -d '{"realm": "internal", "principalType": "employee", "principalId": 1, "roles": ["wms:inbound_manager"]}'
```

## 자주 쓰는 명령

```bash
./gradlew :auth-server:test          # 모듈 하나만 테스트
./gradlew ktlintFormat               # 포맷 자동 수정
./gradlew ktlintCheck                # 포맷 검사만
./gradlew publishToMavenLocal        # 라이브러리를 로컬 Maven에 배포 (준비 중: 배포 설정)
```

- 통합 테스트는 Testcontainers를 쓰므로 Docker가 켜져 있어야 합니다.
- 의존성 버전은 `gradle/libs.versions.toml`, 공통 빌드 설정은 `build-logic/`에서만 바꿉니다.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/README.md](docs/README.md) | 명세 목록과 각 문서가 맡는 범위. **구현의 기준** |
| [docs/adr/README.md](docs/adr/README.md) | 결정과 이유 |
| [AGENTS.md](AGENTS.md) | 코딩 에이전트와 사람이 함께 지키는 작업 규칙 |

- 이 저장소의 라이브러리를 쓰는 **서비스 개발자**는 Notion의 "서비스 연동 가이드"를 보면 됩니다.

## 기여

- 동작을 바꾸면 **같은 PR에서** `docs/`의 해당 문서를 고칩니다. 명세와 코드가 다르면 명세가 기준입니다.
- 브랜치 이름, PR 제목, 버전 규칙은 [AGENTS.md의 git 절](AGENTS.md#git)을 따릅니다.
- PR을 열면 템플릿이 채워집니다. 버그, 다른 팀 요청, 개선 제안은 이슈 템플릿으로 올립니다.

## 저장소 구조

```text
dozy-auth/
├─ auth-core/                    공유 타입
├─ auth-server/                  Auth 서버
├─ auth-spring-boot-starter/     서비스용 자동 설정
├─ auth-test/                    서비스 테스트 도구
├─ build-logic/                  공통 Gradle 설정 (convention 플러그인)
├─ gradle/libs.versions.toml     의존성 버전
├─ docs/                         명세와 ADR
├─ compose.yaml                  로컬 PostgreSQL, Mailpit
├─ AGENTS.md, CLAUDE.md          에이전트 작업 규칙
├─ CLAUDE.local.md.example       개인 로컬 지침 양식 (복사해서 CLAUDE.local.md로)
└─ .github/                      PR·이슈 템플릿, 릴리스 노트 설정
```

`CLAUDE.local.md`, `.context/`(개인 메모), `.local/`(로컬 서명 키)는 git에 올라가지 않습니다.
