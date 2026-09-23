plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    `java-library`
}

group = "st.flrn.hermes"
version = "2026.921.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("io.ktor:ktor-client-websockets:3.6.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
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
