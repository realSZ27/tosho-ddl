import java.time.Instant

group = "dev.ddlproxy"
version = "2.1.1"

val ktor_version = "3.2.1"
val kotlin_coroutines_version = "1.10.2"
val jsoup_version = "1.22.1"
//val graaljs_version = "25.0.2"

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.spring") version "2.3.20"

    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.cloud.tools.jib") version "3.5.3"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation("org.jsoup:jsoup:$jsoup_version")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$kotlin_coroutines_version")

    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")

    // for AnimePahe
    //implementation("org.graalvm.js:js:$graaljs_version")
    //implementation("org.graalvm.polyglot:polyglot:$graaljs_version")
}

jib {
    from {
        image = "eclipse-temurin:21-jre-alpine"
    }

    container {
        labels = mapOf(
            "maintainer" to "SZ27 (https://github.com/realSZ27)",
            "org.opencontainers.image.title" to "ToshoDDL",
            "org.opencontainers.image.version" to project.version.toString(),
            "org.opencontainers.image.created" to Instant.now().toString(),
            "org.opencontainers.image.url" to "https://github.com/realSZ27/tosho-ddl",
            "org.opencontainers.image.source" to "https://github.com/realSZ27/tosho-ddl",
            "org.opencontainers.image.licenses" to "GPLv3"
        )
    }
}
