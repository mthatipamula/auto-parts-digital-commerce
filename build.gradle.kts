plugins {
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.autoparts"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    extensions.configure<JavaPluginExtension> { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.1")
            mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:8.2.4")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.4")
        }
    }
    tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }
    tasks.withType<Test> { useJUnitPlatform() }
}
