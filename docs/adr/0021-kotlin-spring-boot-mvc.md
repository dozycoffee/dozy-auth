# 0021. Kotlin, JDK 21, Spring Boot 4.1(MVC)로 만든다

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

다른 Dozy 서비스가 Kotlin + Spring Boot를 씁니다. 스타터는 서비스의 JVM 버전과 호환돼야 합니다.

## 결정

- 서버: Kotlin, JDK 21, Spring Boot 4.1, Spring MVC, Spring Security 7.
- 라이브러리(core, starter, test): JVM 17 타깃.
- 버전은 `gradle/libs.versions.toml`이 기준입니다.

## 검토한 대안

- WebFlux: 블로킹 JDBC(Exposed)와 맞지 않고 이득이 작습니다.

## 결과

- 기술별 세부 선택은 해당 ADR(0007, 0019, 0022, 0024, 0025, 0027)을 따릅니다.
