plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":commerce-common"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
