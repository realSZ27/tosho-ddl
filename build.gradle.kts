import java.time.Instant

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.spring") version "2.3.20"

    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.cloud.tools.jib") version "3.5.3"
}

group = "dev.ddlproxy"
version = "2.0.0"

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

    implementation("org.jsoup:jsoup:1.22.1")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
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
