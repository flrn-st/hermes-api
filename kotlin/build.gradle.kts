plugins {
    kotlin("jvm") version "2.4.20"
    `java-library`
}

group = "st.flrn.hermes"
version = "2026.921.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}
