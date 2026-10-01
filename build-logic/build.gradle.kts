plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
}

// kotlin-dsl이 만든 코드(build/generated-sources)는 검사하지 않는다
val generatedSources =
    layout.buildDirectory
        .dir("generated-sources")
        .get()
        .asFile
        .toPath()
ktlint {
    filter {
        exclude { it.file.toPath().startsWith(generatedSources) }
    }
}
