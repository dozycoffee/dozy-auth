// auth-core, auth-spring-boot-starter, auth-test: 서비스 호환을 위해 JVM 17 타깃, GitHub Packages로 배포
// 세 모듈이 한 버전이며 v* 태그로 배포한다 (starter.md §1, AGENTS.md git)

import com.dozycoffee.build.VerifyPublicationMetadata

// 배포 저장소와 POM 주소의 기준. 저장소를 옮기면 여기만 바꾼다
val githubRepository = "dozycoffee/dozy-auth"
val projectUrl = "https://github.com/$githubRepository"

plugins {
    id("dozy.kotlin-base")
    `java-library`
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = project.name
                description = provider { project.description }
                url = projectUrl
                scm { url = projectUrl }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/$githubRepository")
            // 배포 워크플로의 GITHUB_TOKEN을 쓴다. 저장소에 자격 증명을 두지 않는다
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

// 배포는 배포 워크플로(.github/workflows/publish.yml)에서만 한다. 로컬에서 실수로 올리는 것을 막는다
// (publishToMavenLocal은 막지 않는다)
val runningInGitHubActions = providers.environmentVariable("GITHUB_ACTIONS").map { it == "true" }.orElse(false)
val releaseVersion = providers.gradleProperty("releaseVersion")
tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        check(runningInGitHubActions.get()) { "라이브러리 배포는 배포 워크플로에서만 합니다. v{major}.{minor}.{patch} 태그를 push하세요 (README 배포 절)" }
        check(releaseVersion.isPresent) { "배포 버전(-PreleaseVersion)이 없습니다" }
    }
}

// 배포 메타데이터가 계약대로인지 빌드마다 확인한다 (Boot BOM 미포함, 모듈 간 같은 버전, Kotlin 의존성 버전)
val verifyPublicationMetadata =
    tasks.register<VerifyPublicationMetadata>("verifyPublicationMetadata") {
        dependsOn("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")
        pomFile = layout.buildDirectory.file("publications/maven/pom-default.xml")
        moduleFile = layout.buildDirectory.file("publications/maven/module.json")
        expectedVersion = project.version.toString()
    }
tasks.named("check") { dependsOn(verifyPublicationMetadata) }
