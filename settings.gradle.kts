plugins {
    // Lets Gradle download the JDK 25 toolchain when the machine has only an older JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "host-telemetry-aggregator"

include("service", "simulator")
