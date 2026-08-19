plugins {
    java
    id("org.springframework.boot") version "3.5.5"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

group = "com.david"
version = "0.0.1-SNAPSHOT"
description = "iOS notification hub with Spring Boot, PostgreSQL, Flyway"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.mockito:mockito-core:5.13.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.13.0")
    // Real PostgreSQL in tests instead of H2, so Flyway migrations and
    // Postgres-only features (partial unique indexes) are actually exercised
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "test")
    finalizedBy(tasks.jacocoTestReport) // always generate report
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Configure the existing task (do NOT register a new one)
//
// This is a ratchet, not an aspiration. It sits just under actual line coverage
// (53.5% at the time of writing) so the build fails on a regression rather than
// passing a number nobody meets. It read "0.05" with "// 90%" next to it for a
// while, which meant the gate was decorative.
//
// Raise it as coverage rises. The gap is concentrated in the HTTP senders -
// DiscordSender is at 9% and SlackSender at 20%, both of which need a stubbed
// HTTP server rather than more unit tests.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

tasks.check { dependsOn("jacocoTestCoverageVerification") }
