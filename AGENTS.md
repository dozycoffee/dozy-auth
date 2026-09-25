# AGENTS.md

Dozy Coffee의 인증 서비스 저장소입니다. WMS, Catalog, Store 서비스에 토큰을 발급하고, 서비스가 쓰는 검증 라이브러리를 함께 제공합니다.

## 명세

- 명세는 [`docs/`](docs/README.md)에 있고, **코드와 명세가 다르면 명세가 기준입니다.** 명세가 틀렸다고 판단되면 코드를 바꾸기 전에 사람에게 알립니다.
- 작업 전에 [`docs/README.md`](docs/README.md)의 담당 범위 표를 보고 필요한 문서만 읽습니다.
- 이미 정한 결정은 [`docs/adr/README.md`](docs/adr/README.md)를 먼저 확인합니다. ADR과 다른 방향을 제안할 때는 그 ADR을 근거와 함께 언급합니다.
- 동작을 바꾸면 **같은 변경에서** 담당 문서를 고칩니다. 값이나 규칙을 담당 문서가 아닌 곳에 다시 쓰지 않습니다.
- 규칙을 구현하거나 테스트할 때 `docs/domain.md`의 규칙 ID(예: `SES-03`)를 주석이나 테스트 이름에 씁니다.
- `docs/token.md`나 `docs/starter.md`를 바꾸면 "Notion 서비스 연동 가이드 갱신 필요"를 PR 본문에 남깁니다.
- README(루트, 모듈)는 명세를 요약하지 않고 링크합니다. 실행 방법, 모듈 사용 방법, 모듈 구조가 바뀌거나 "준비 중"으로 표시한 기능을 구현하면 해당 README를 같은 변경에서 갱신합니다.

## 모듈

각 모듈의 README에 사용 방법과 구조가 있습니다.

| 모듈 | 역할 | 읽을 문서 | 주의 |
|---|---|---|---|
| `auth-core` | 발급·검증이 공유하는 타입과 상수 | `token.md` §10, `domain.md` §1 | Spring 의존 금지. 바꾸면 모든 서비스에 영향 |
| `auth-server` | Auth 서버 | `architecture.md`, 작업에 해당하는 `domain.md` 절, `api/*.md`, `data-model.md`, `configuration.md` | 계층·도메인 규칙은 Konsist가 검사 |
| `auth-spring-boot-starter` | 서비스용 토큰 검증·인가 자동 설정 | `starter.md`, `token.md` §6, `api/conventions.md` §4 | JVM 17, `explicitApi()`. `auth-server` 참조 금지 |
| `auth-test` | 서비스 테스트 도구 | `starter.md` §7, `token.md` | 테스트 키와 운영 키를 섞지 않음 |

## 명령

빌드, 테스트, 로컬 실행 명령은 [README.md](README.md#빠른-시작)에 있습니다. 모듈별 테스트 방법은 각 모듈 README에 있습니다.

## 코드

- 구조, 의존 규칙, 이름 규칙, 코드 규칙은 [`docs/architecture.md`](docs/architecture.md)를 따릅니다.
- 현재 시각은 `Clock` 주입, 난수는 `SecureRandom`, 비밀값 비교는 상수 시간입니다.
- 로그·예외 메시지·테스트 출력에 남기면 안 되는 값은 [`docs/domain.md` SEC-03](docs/domain.md#12-민감정보-sec)을 따릅니다.
- 비밀값(서명 키, DB 비밀번호, client secret)을 저장소에 커밋하지 않습니다.

## git

| 항목 | 규칙 |
|---|---|
| 브랜치 | `main` + 작업 브랜치 `{type}/{설명}` (예: `feat/login-api`) |
| 병합 | PR, squash merge |
| PR 제목 | `{type}({scope}): {설명}` (예: `feat(server): 로그인과 내 정보 API 추가`) |
| type | `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`. 호환성이 깨지면 `feat(starter)!: ...`처럼 `!` |
| scope | `core`, `server`, `starter`, `test`, `docs`, `build`. 여러 모듈이면 생략 |
| 언어 | type·scope는 영어, 설명은 한국어 |
| PR 본문 | [`.github/pull_request_template.md`](.github/pull_request_template.md) |
| 버전 | 라이브러리 세 모듈이 한 버전, `v*` 태그로 배포. 서버는 커밋 SHA |

### 라벨

PR과 이슈에 붙입니다. 릴리스 노트는 `.github/release.yml`이 이 라벨로 분류하므로, 이름을 바꾸거나 추가하면 이 표와 `release.yml`을 함께 고칩니다.

| 분류 | 라벨 | 용도 |
|---|---|---|
| 종류 (하나만) | `bug`, `feature`, `request`, `refactor`, `documentation` | 잘못된 동작, 기능 추가, 다른 팀 요청, 동작 변화 없는 구조 개선, `docs/` 명세 |
| 모듈 | `module: core`, `module: server`, `module: starter`, `module: test` | PR 제목의 scope와 같은 모듈 |
| 상태 | `needs-triage`, `blocked` | 확인 전, 다른 일에 막힘 |
| 우선순위 | `high-priority` | 먼저 처리할 것만 표시 |
| 특수 | `breaking-change`, `security`, `dependencies` | 라이브러리 호환성 변경(major), 보안, 의존성 업데이트 |
| 닫을 때 | `duplicate`, `wontfix`, `question` | 중복, 하지 않음, 질문 |

- 이슈 템플릿이 종류 라벨과 `needs-triage`를 자동으로 붙입니다.
- 라벨은 GitHub 저장소 설정에 있으며, 위 표가 기준입니다.

- 브랜치 안의 커밋 메시지는 자유입니다. `main`에는 PR 제목이 커밋 메시지로 남습니다.
- 사람이 요청하지 않으면 커밋, 푸시, PR 생성을 하지 않습니다.

## 로컬 메모

- `.context/`와 `CLAUDE.local.md`는 개인 로컬 파일이며 git에 올리지 않습니다.
- 로컬 메모는 명세가 아닙니다. 결정이 되면 `docs/adr/`, 동작 규칙이 되면 `docs/`로 옮깁니다. 로컬 메모와 `docs/`가 다르면 `docs/`가 기준입니다.
