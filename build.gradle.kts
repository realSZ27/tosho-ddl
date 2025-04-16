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
    to {
        image = "ghcr.io/realSZ27/toshoddl:${project.version}"
        auth {
            username = "realSZ27"
            password = System.getenv("GITHUB_TOKEN")
        }
    }
    from {
        image = "eclipse-temurin:21-jre-alpine"
    }
}