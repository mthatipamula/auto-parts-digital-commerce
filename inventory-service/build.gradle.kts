plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":commerce-common"))
    implementation("com.google.cloud:spring-cloud-gcp-starter-data-spanner")
    implementation("com.google.cloud:spring-cloud-gcp-starter-pubsub")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
