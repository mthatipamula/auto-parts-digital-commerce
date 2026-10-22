plugins { `java-library` }

// Exported: what every service needs. GCP starters are compile-only here; each service declares the ones it uses,
// so e.g. the payment service never tries to connect to Spanner.
dependencies {
    compileOnly("com.google.cloud:spring-cloud-gcp-starter-storage")
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-opentelemetry")
    api("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    compileOnly("com.google.cloud:spring-cloud-gcp-starter-data-spanner")
    compileOnly("com.google.code.gson:gson")
    compileOnly("com.google.cloud:spring-cloud-gcp-starter-pubsub")
}
