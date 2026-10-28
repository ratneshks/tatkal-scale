# ---- build stage: compile the jar with Maven + JDK 21 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Download dependencies first so they are cached between builds when only code changes
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests need an embedded Postgres; they run locally / in CI, not in the image build
RUN mvn -q -B -DskipTests package

# ---- run stage: small JRE image with just the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 tatkal
COPY --from=build /app/target/tatkal-scale-1.0.0.jar app.jar
USER tatkal
# Render free instances have 512 MB: let the heap use 70% of it, serial GC for low overhead
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
