# auth-spring-boot-starter

서비스(WMS, Catalog, Store)가 Auth 토큰을 검증하고 role로 인가할 수 있게 하는 Spring Boot 자동 설정입니다.

> 서비스에서 쓰는 방법은 Notion "서비스 연동 가이드"에 자세히 있습니다. 이 README는 설치와 최소 설정만 다룹니다.

## 명세

- [docs/starter.md](../docs/starter.md): 설정 키, 제공 빈, 인가 도구, 에러 응답, 서비스 간 호출. **이 모듈의 기준**
- [docs/token.md §6](../docs/token.md#6-검증-규칙): 토큰 검증 규칙
- [docs/api/conventions.md §4](../docs/api/conventions.md#4-에러-응답): 401·403 응답 형식

## 사용 방법

(준비 중: GitHub Packages 배포 설정)

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/dozycoffee/dozy-auth")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GPR_USER")
            password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GPR_TOKEN")
        }
        content { includeGroup("com.dozycoffee.auth") }
    }
}

dependencies {
    implementation("com.dozycoffee.auth:auth-spring-boot-starter:{version}")
    testImplementation("com.dozycoffee.auth:auth-test:{version}")
}
```

최소 설정 (나머지 설정 키는 [starter.md §2](../docs/starter.md#2-설정)):

```yaml
dozy:
  auth:
    audience: wms
    accepted-realms: [internal]
    issuer-base-uri: https://auth.dozycoffee.com
```

## 구조 (준비 중)

```text
src/main/kotlin/com/dozycoffee/auth/starter/
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

(자동 설정 클래스가 추가되면 패키지 구성을 적습니다.)

## 제약

- `auth-core`만 의존합니다. `auth-server` 코드를 참조하지 않습니다.
- JVM 17 타깃, `explicitApi()`입니다.
- Spring Boot BOM을 배포 메타데이터에 싣지 않습니다. 서비스의 Spring 버전을 바꾸지 않기 위해서입니다 (`build-logic`의 `dozy.spring-library`).
- 모든 빈은 서비스가 교체할 수 있게 `@ConditionalOnMissingBean`으로 등록합니다.
- **공개 API나 동작을 바꾸면 라이브러리 버전에 반영하고, Notion 연동 가이드도 갱신합니다.**

## 테스트

```bash
./gradlew :auth-spring-boot-starter:test
```

- 검증 실패 경우(잘못된 `iss`, `aud`, `typ`, 만료, realm과 principal type 불일치)마다 401을 확인합니다.
- 샘플 컨트롤러로 `@PreAuthorize`와 `@CurrentPrincipal`이 동작하는지 확인합니다.
