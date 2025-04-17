import java.time.Instant

plugins {
    id("java")
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.cloud.tools.jib") version "3.4.5"
    id("net.nemerosa.versioning") version "3.1.0"
}

group = "dev.ddlproxy"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jsoup:jsoup:1.19.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.18.3")
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