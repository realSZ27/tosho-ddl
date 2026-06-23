import java.time.Instant

group = "dev.ddlproxy"
version = "2.4.0"

val ktorVersion = "3.2.1"
val kotlinCoroutinesVersion = "1.10.2"
val jsoupVersion = "1.22.1"
val jacksonKotlinVersion = "3.1.+"

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

    implementation("org.jsoup:jsoup:$jsoupVersion")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$kotlinCoroutinesVersion")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    implementation("tools.jackson.module:jackson-module-kotlin:$jacksonKotlinVersion")
}

jib {
    from {
        image = "eclipse-temurin:21-jre-alpine"
    }

    container {
        labels = mapOf(
            "maintainer" to "SZ27 (https://github.com/realSZ27)",
            "title" to "ToshoDDL",
            "version" to project.version.toString(),
            "created" to Instant.now().toString(),
            "url" to "https://github.com/realSZ27/tosho-ddl",
            "source" to "https://github.com/realSZ27/tosho-ddl",
            "licenses" to "GPLv3+"
        )
    }
}
