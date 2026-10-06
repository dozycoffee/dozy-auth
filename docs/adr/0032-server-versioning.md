# 0032. 서버는 server-v* 태그로 시맨틱 버전을 붙이고, 릴리스는 다시 빌드하지 않고 이미지 태그만 덧붙인다

- 상태: 채택
- 날짜: 2026-10-06

## 맥락

서버 이미지(`ghcr.io/dozycoffee/dozy-auth-api`)는 `main`에 병합될 때마다 CI가 테스트를 통과한 jar로 빌드해 `sha-{7자리}`와 `main` 태그로 올립니다. 지금까지 서버 버전은 커밋 SHA뿐이라, 다른 팀에 변경을 알리거나 운영 기록·롤백 기준으로 삼기 어렵습니다.

라이브러리(`auth-core`, `auth-spring-boot-starter`, `auth-test`)는 이미 `v{major}.{minor}.{patch}` 태그로 배포합니다([README 라이브러리 배포](../../README.md#라이브러리-배포)). 서버 버전은 라이브러리 버전과 따로 움직입니다.

릴리스할 때 다시 빌드하면 테스트를 통과한 이미지와 배포하는 이미지가 달라질 수 있습니다(기반 이미지 patch 업데이트, 의존성 캐시 등). 그런데 다시 빌드하지 않으면 이미지 안(jar, 라벨, 환경 변수)에는 빌드할 때 알던 값만 있고, 나중에 정한 semver는 들어갈 수 없습니다.

## 결정

- **git 태그**: `server-v{major}.{minor}.{patch}`. 라이브러리 태그 `v*`와 겹치지 않습니다 (배포 워크플로 패턴이 `v[0-9]+...`로 시작).
- **이미지 태그**: git 태그에서 버전만 꺼낸 `X.Y.Z` 하나. `X.Y` 같은 이동 태그와 `latest`는 붙이지 않습니다. `sha-{7자리}`, `main`은 그대로 둡니다.
- **릴리스 = 태그 덧붙이기**: 서버 릴리스 워크플로(`server-release.yml`)는 태그 커밋이 `main`에 있는지 확인하고, 그 커밋에서 CI가 올린 `sha-` 이미지의 manifest list에 `X.Y.Z` 태그만 추가합니다. 다시 빌드하지 않으므로 `X.Y.Z`와 `sha-`의 digest가 같습니다. 같은 버전 태그를 다른 이미지로 옮기지 않습니다.
- **버전 시작과 올리는 기준**: 0.x로 시작합니다 (첫 릴리스 `server-v0.1.0`). 1.0.0은 운영 배포 시점에 정합니다.
  - API·토큰 계약을 깨면 major (0.x 동안은 minor)
  - API·기능 추가는 minor
  - 버그 수정·내부 개선은 patch
- **실행 중인 서버의 버전**: 이미지 안에는 빌드할 때 아는 값만 넣고, semver는 배포가 알려 줍니다.
  - `revision`(커밋 SHA 전체)이 기준입니다. jar의 build-info, 이미지 라벨 `org.opencontainers.image.revision`, 기동 로그, `dozy.auth.build.info` 지표에 같은 값이 있습니다.
  - 이미지 라벨 `org.opencontainers.image.version`과 jar의 빌드 버전은 그 이미지를 가리키는 `sha-{7자리}`입니다.
  - 배포가 `AUTH_RELEASE_VERSION`에 배포한 이미지 태그(`X.Y.Z`)를 주면 기동 로그와 지표의 `version`이 그 값이 됩니다. 주지 않으면 빌드 버전(`sha-{7자리}`)입니다.
  - `/actuator/info` 같은 HTTP 엔드포인트로는 노출하지 않습니다. 지표는 내부망에서만 수집합니다 ([configuration.md §10.1](../configuration.md#101-actuator)).
- **릴리스 노트**: 서버 릴리스 노트는 직전 `server-v*` 태그 이후 병합된 PR 중 `module: server` 라벨이 있는 것만 모읍니다. GitHub 자동 노트는 라벨로 PR을 고를 수 없고 설정 파일(`.github/release.yml`)이 저장소에 하나라, 노트는 워크플로의 스크립트(`.github/scripts/server-release-notes.sh`)가 같은 분류로 만듭니다. 라이브러리 릴리스 노트는 시작점을 직전 `v*` 태그로 지정해 서버 릴리스가 끼어들지 않게 합니다. 서버 릴리스는 GitHub의 Latest로 표시하지 않습니다.
- 이미지는 `linux/amd64`, `linux/arm64` 두 플랫폼을 한 태그로 만듭니다 (multi-arch). 배포 환경의 CPU를 아직 정하지 않았고, 로컬 개발 머신(Apple Silicon)에서 같은 이미지를 그대로 실행하기 위해서입니다.

## 검토한 대안

- **릴리스 때 태그 커밋에서 다시 빌드**: 이미지 라벨과 jar에 semver를 넣을 수 있지만, 테스트를 통과한 이미지와 배포 이미지가 같다는 보장이 없어지고 릴리스마다 빌드 시간이 듭니다.
- **기존 이미지 위에 라벨·환경 변수만 더한 이미지를 만들기**(`FROM sha-...` + `LABEL`): 계층은 같지만 config가 달라 digest가 바뀝니다. `X.Y.Z`와 `sha-`가 같은 이미지라는 것을 digest로 확인할 수 없게 됩니다.
- **manifest list 주석(annotation)으로 버전 표시**: `imagetools create --annotation`으로 붙일 수 있지만 digest가 바뀌고, 실행 중인 컨테이너에서는 보이지 않습니다.
- **`/actuator/info`로 버전 공개**: 확인은 쉽지만 외부에서 서버 버전을 알 수 있게 됩니다. 지표로 충분합니다.
- **라이브러리와 서버가 한 버전**: 서버만 바뀌어도 라이브러리를 다시 배포해야 하고, 그 반대도 마찬가지입니다.

## 결과

- 운영자는 이미지 태그나 기동 로그·지표의 `version`으로 semver를, `revision`으로 정확한 커밋을 봅니다. `AUTH_RELEASE_VERSION`은 배포가 알려 주는 값이라 틀릴 수 있으므로, 둘이 다르면 `revision`이 기준입니다 (`git tag --points-at {revision}`).
- 이미지 라벨의 `version`은 릴리스 뒤에도 `sha-{7자리}`입니다. 이미지만 보고 semver를 알려면 레지스트리의 태그를 봅니다.
- `main` CI는 한 번에 하나씩 돌고 대기 중인 실행은 마지막만 남으므로, 연달아 병합된 중간 커밋에는 `sha-` 이미지가 없을 수 있습니다. 그런 커밋에 붙인 서버 태그는 워크플로가 실패시키고, 이미지가 있는 커밋에 다시 태그해야 합니다.
- 관련 문서: [configuration.md §10.2](../configuration.md#102-지표), [§12](../configuration.md#12-컨테이너-이미지), [README 서버 릴리스](../../README.md#서버-릴리스)
