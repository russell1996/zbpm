# --- Build stage: package the runnable jar (tests skipped here; see the `test` stage / CI) ---
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY . .

RUN mvn -B -ntp clean package -DskipTests


# --- Test stage: full reactor verify (unit + integration). Built in CI via `docker build --target test`.
#     Not in the runtime dependency graph, so a plain `docker build` / `docker compose build` skips it. ---
FROM maven:3.9.9-eclipse-temurin-21 AS test

WORKDIR /build

COPY . .

RUN mvn -B -ntp clean verify


# --- Runtime stage ---
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/zorrobpm-ce/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
