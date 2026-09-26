# --- Build stage: package the runnable jar (tests skipped here; see the `test` stage / CI) ---
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS builder

WORKDIR /build

# WO-QW-2: layer-cache — dependency download is its own layer. pom.xml changes
# invalidate only this layer; source edits invalidate only the layer below.
# (Module list mirrors <modules> in pom.xml — keep in sync when adding modules.)
COPY pom.xml .
COPY zorrobpm-contract/pom.xml zorrobpm-contract/pom.xml
COPY zorrobpm-client/pom.xml zorrobpm-client/pom.xml
COPY zorrobpm-engine/pom.xml zorrobpm-engine/pom.xml
COPY zorrobpm-rest/pom.xml zorrobpm-rest/pom.xml
COPY zorrobpm-job-handler-spring-boot-starter/pom.xml zorrobpm-job-handler-spring-boot-starter/pom.xml
COPY zorrobpm-test/pom.xml zorrobpm-test/pom.xml
COPY zorrobpm-exchange/pom.xml zorrobpm-exchange/pom.xml
COPY zorrobpm-rabbitmq/pom.xml zorrobpm-rabbitmq/pom.xml
COPY zorrobpm-app/pom.xml zorrobpm-app/pom.xml
RUN mvn -B -ntp dependency:go-offline

COPY . .

RUN mvn -B -ntp clean package -DskipTests


# --- Test stage: full reactor verify (unit + integration). Built in CI via `docker build --target test`.
#     Not in the runtime dependency graph, so a plain `docker build` / `docker compose build` skips it. ---
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS test

WORKDIR /build

# WO-QW-2: same layer-cache split as the builder stage above.
# (Module list mirrors <modules> in pom.xml — keep in sync when adding modules.)
COPY pom.xml .
COPY zorrobpm-contract/pom.xml zorrobpm-contract/pom.xml
COPY zorrobpm-client/pom.xml zorrobpm-client/pom.xml
COPY zorrobpm-engine/pom.xml zorrobpm-engine/pom.xml
COPY zorrobpm-rest/pom.xml zorrobpm-rest/pom.xml
COPY zorrobpm-job-handler-spring-boot-starter/pom.xml zorrobpm-job-handler-spring-boot-starter/pom.xml
COPY zorrobpm-test/pom.xml zorrobpm-test/pom.xml
COPY zorrobpm-exchange/pom.xml zorrobpm-exchange/pom.xml
COPY zorrobpm-rabbitmq/pom.xml zorrobpm-rabbitmq/pom.xml
COPY zorrobpm-app/pom.xml zorrobpm-app/pom.xml
RUN mvn -B -ntp dependency:go-offline

COPY . .

# WO-PERF-4: the jacoco plugin lives behind the Maven `coverage` profile (see
# zorrobpm-engine/pom.xml). CI passes --build-arg MAVEN_PROFILES=-Pcoverage so the coverage
# report keeps being produced in CI; local builds default to no instrumentation.
ARG MAVEN_PROFILES
RUN mvn -B -ntp clean verify $MAVEN_PROFILES
# WO-QW-4 (NEW-13): GitLab `coverage:` парсит ЛОГ job'а, а JaCoCo пишет только
# файлы (target/site/jacoco) — поле coverage вечно None. Печатаем итог из
# jacoco.csv в лог build'а (он — часть лога job'а) строкой вида
# "Total instruction: 72%" — её матчит coverage-regex из .gitlab-ci.yml.
# awk вместо python: в maven-образе python нет. Без jacoco.csv (профиль
# coverage выключен) — молча пропускаем, не роняем build.
RUN for csv in zorrobpm-*/target/site/jacoco/jacoco.csv; do \
      [ -f "$csv" ] || continue; \
      awk -F, 'NR>1 {im+=$4; ic+=$5; bm+=$6; bc+=$7} END {if (ic+im>0) printf "Total instruction: %d%% (branch %d%%) [%s]\n", 100*ic/(ic+im), (bc+bm>0?100*bc/(bc+bm):100), FILENAME}' "$csv"; \
    done


# --- Runtime stage ---
FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55

# WO-SEC-50: unprivileged user for the JVM process (RCE blast-radius reduction). Fixed uid/gid
# (not dynamically allocated) so it's stable across image rebuilds and matches what entrypoint.sh
# chowns the files volume to.
RUN groupadd --system --gid 10001 zorrobpm \
 && useradd --system --uid 10001 --gid zorrobpm --no-create-home --shell /usr/sbin/nologin zorrobpm

WORKDIR /app
ENV TZ=Asia/Almaty

COPY --from=builder /build/zorrobpm-app/target/*.jar app.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && chown zorrobpm:zorrobpm /app/app.jar

EXPOSE 8080

# start-period accounts for Liquibase migrations + Spring context startup before the first
# probe counts against the container.
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Runs as root (image default) so entrypoint.sh can fix the files-volume ownership on every
# start, then drops to `zorrobpm` before exec'ing java — see entrypoint.sh for why this is safer
# than a Dockerfile-level `USER` for a service with a persistent named volume.
ENTRYPOINT ["/entrypoint.sh"]
