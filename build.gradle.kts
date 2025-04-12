plugins {
    id("java")
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
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

    // For Java 21 preview features (if needed)
    // compileOptions {
    //     compilerArgs.add("--enable-preview")
    // }
}