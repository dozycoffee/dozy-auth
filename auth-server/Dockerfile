# Auth 서버 이미지. 실행에 필요한 설정은 docs/configuration.md §12
#
# jar는 Gradle이 테스트를 통과한 뒤 만든 것을 그대로 담는다. 이미지 안에서 빌드하지 않는다.
#   ./gradlew :auth-server:bootJar
#   docker build -t dozy-auth-api auth-server
#
# 서명 키, DB·SMTP 비밀번호 같은 비밀값은 이미지에 넣지 않고 실행할 때 환경 변수와 볼륨으로 준다.
# CI는 linux/amd64, linux/arm64 두 플랫폼으로 빌드하고 버전·커밋 라벨(org.opencontainers.image.version·revision)을 붙인다.

# JDK major와 OS(Ubuntu noble)를 고정하고 21의 patch 업데이트는 빌드할 때마다 받는다
ARG JRE_IMAGE=eclipse-temurin:21-jre-noble

# 1단계: jar를 Spring Boot 계층별 폴더로 푼다.
# 결과가 플랫폼과 관계없는 파일이라 빌드하는 머신의 플랫폼에서 한 번만 실행한다 (multi-arch 빌드에서 에뮬레이션을 피함)
FROM --platform=$BUILDPLATFORM ${JRE_IMAGE} AS extract
WORKDIR /build
COPY build/libs/auth-server.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# 2단계: 실행 이미지. 자주 바뀌지 않는 계층부터 담아 이미지 계층 캐시를 살린다
FROM ${JRE_IMAGE}

LABEL org.opencontainers.image.title="dozy-auth-api" \
      org.opencontainers.image.description="Dozy Auth 서버" \
      org.opencontainers.image.source="https://github.com/dozycoffee/dozy-auth"

# root가 아닌 고정 UID·GID(10001)로 실행한다. 서명 키 볼륨의 파일은 이 UID가 읽을 수 있어야 한다
RUN groupadd --system --gid 10001 auth \
    && useradd --system --uid 10001 --gid auth --no-create-home --shell /usr/sbin/nologin auth

WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./

USER 10001:10001
EXPOSE 8080

# 힙 최대치를 컨테이너 메모리 제한의 75%로 잡는다. JVM 기본값(25%)은 JVM만 도는 컨테이너에서 메모리를 대부분 놀린다.
# 실행할 때 JAVA_TOOL_OPTIONS를 다시 주면 덮어쓴다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
