plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    `java-library`
    `maven-publish`
}

group = "st.flrn.hermes"
version = "0.21.4"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
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

// Live scenarios shared with the Android instrumented test in ../android.
sourceSets.test {
    kotlin.srcDir("src/live/kotlin")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Exercise the Kotlin gateway against a live tagged Hermes server"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "st.flrn.hermes.api.LiveSmokeKt"
}

publishing {
    publications {
        create<MavenPublication>("hermesAPI") {
            from(components["java"])
            pom {
                name = "HermesAPI"
                description = "Generated Kotlin client for stable Hermes Agent releases"
                url = "https://github.com/flrn-st/hermes-api"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                scm { url = "https://github.com/flrn-st/hermes-api" }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/flrn-st/hermes-api")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
