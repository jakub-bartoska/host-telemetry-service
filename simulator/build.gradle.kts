plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // No Spring Boot plugin or BOM here (plain CLI tool), so versions are explicit.
    // Keep Jackson in step with the service's Boot BOM.
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.7")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Only to check generated messages against the real ingest validator; never on the runtime classpath.
    testImplementation(project(":service"))
}

application {
    mainClass.set("com.hosttelemetry.simulator.SimulatorKt")
}

tasks.test {
    useJUnitPlatform()
}
