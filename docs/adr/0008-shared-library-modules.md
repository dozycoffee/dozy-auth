# 0008. 서버와 서비스용 라이브러리를 한 저장소의 멀티모듈로 둔다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

서비스들이 토큰 검증, role 변환, 테스트 설정을 각자 구현하면 규칙이 어긋납니다. 발급 측과 검증 측의 claim 정의도 한 곳에 있어야 합니다.

## 결정

- 저장소 `dozy-auth` 하나에 네 모듈을 둡니다.
- `auth-core`: 발급·검증이 공유하는 타입과 상수. Spring 의존 없음.
- `auth-spring-boot-starter`: 서비스용 자동 설정. 검증은 Spring Security `oauth2-resource-server`에 맡기고 얇게 유지합니다.
- `auth-test`: `@WithDozyPrincipal`, 테스트 토큰.
- 라이브러리는 JVM 17 타깃, `explicitApi()`, GitHub Packages 배포, 세 모듈 한 버전(SemVer).

## 검토한 대안

- 서버와 라이브러리 저장소 분리: claim 변경 시 두 저장소를 맞춰야 합니다.

## 결과

- 서버 변경과 라이브러리 배포가 한 저장소에서 일어나므로 버전 관리 규칙이 필요합니다([starter.md §1](../starter.md#1-배포와-호환)).
