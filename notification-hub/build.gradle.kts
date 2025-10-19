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
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

jacoco{
    toolVersion = "0.8.11"
}
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.test)
    violationRules {
        rule {
            // You can also add BRANCH or INSTRUCTION counters if you prefer
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal() // 90%
            }
        }
    }
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
	implementation("com.eatthepath:pushy:0.15.4")
	implementation("com.sendgrid:sendgrid-java:4.10.3")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.mockito:mockito-core:5.13.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.13.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test{
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport) //generate report
}

tasks.check { dependsOn("jacocoTestCoverageVerification") }

tasks.withType<Test> {
	useJUnitPlatform()
}
