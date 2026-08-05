**Setup**
Used Spring Initializer - start.spring.io
Gradle - Kotlin
Java 17
Jar
Spring Boot 3.5.5
Group  com.david
Artifact notification-hub
Package name com.david.notification_hub

**Dependencies**
spring-boot-starter-web: HTTP API. Built in web server (Tomcat), auto converts between JSON and Java
Spring Data JPA + hibernate(JPA provider): Talks to database, so I don't need to write SQL
Validation - checks incoming data
Spring Boot Actuator - Adds a health check url. Hit /actuator/health and get up or down
flyware-core + flyway-database-postgresql - manages database structure
postgresql - translator between java and postgres
springdoc-openapi - webpage documentation at /swagger-ui.html
starter-webflux - could be replaced, just using WebClient
starter-test - test framework, mock libary, fake HTTP requests
h2 - a fake database
junit-platform-launcher - lets gradle run

**Run**
Start docker desktop, then run commands
```powershell
docker start notifhub-postgres
docker ps
```

Set webhooks in powershell (should find a way to replace URL)
$env:DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/..."
$env:SLACK_WEBHOOK_URL="https://hooks.slack.com/services/..."

cd notification-hub
.\gradlew.bat bootRun
