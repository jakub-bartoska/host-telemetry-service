import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

val exposedVersion = "1.6.1"

// Mockito as a Java agent for the tests; without it Mockito attaches itself at run time, which newer JDKs warn about.
val mockitoAgent: Configuration = configurations.create("mockitoAgent")

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    // Spring Boot BOM as a Gradle platform: versions are aligned without the dependency-management plugin.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Typed SQL DSL for the two repositories; Flyway still owns the schema.
    // The starter connects Exposed to Boot's DataSource and to Spring transactions (@Transactional).
    implementation("org.jetbrains.exposed:exposed-spring-boot4-starter:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Bean Validation for the configuration properties: a bad value stops the application at startup.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Boot 4 auto-configures Flyway only through its starter; the PostgreSQL module is still separate.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    // Mockito itself comes with the Boot test starter; this adds its Kotlin API (null-safe matchers).
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    mockitoAgent(platform(SpringBootPlugin.BOM_COORDINATES))
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName.set("service.jar")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
