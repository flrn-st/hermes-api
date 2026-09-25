// The Android artifact, hermes-api-android: the Kotlin library sources compiled for Android (so lint checks
// every API call against minSdk) plus Android adapters for networking, lifecycle and logging. It runs the
// shared live scenarios on an emulator. Android apps depend on this instead of the JVM artifact.
plugins {
    id("com.android.library") version "9.4.1"
    kotlin("plugin.serialization") version "2.4.20"
    `maven-publish`
}

group = "st.flrn.hermes"
// The release tracker bumps one version, in the JVM build.
version = Regex("""^version = "([^"]+)"$""", RegexOption.MULTILINE)
    .find(file("../kotlin/build.gradle.kts").readText())?.groupValues?.get(1)
    ?: error("No version in ../kotlin/build.gradle.kts")

android {
    namespace = "st.flrn.hermes.api.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main").kotlin.directories.add("../kotlin/src/main/kotlin")
        getByName("androidTest").kotlin.directories.add("../kotlin/src/live/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    explicitApi()
}

// Keep in step with ../kotlin/build.gradle.kts; a missing dependency fails compilation here.
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-websockets:3.6.0")
    api("androidx.lifecycle:lifecycle-process:2.11.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

publishing {
    publications {
        create<MavenPublication>("hermesAPIAndroid") {
            artifactId = "hermes-api-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name = "HermesAPI for Android"
                description = "Generated Kotlin client for stable Hermes Agent releases, with Android lifecycle and network adapters"
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
