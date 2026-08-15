
#  build the jar
#  Full JDK + Gradle live here

FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy ONLY the build scripts first.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Now the source
COPY src ./src

# CI already runs the suite with the coverage gate; no need to
# duplicate it during image builds
RUN ./gradlew bootJar --no-daemon -x test


#  runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as an unprivileged user
RUN useradd -r -u 1001 spring

COPY --from=build /app/build/libs/app.jar app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
