FROM docker.io/amazoncorretto:17 AS builder
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar

RUN java -Djarmode=layertools -jar application.jar extract

FROM docker.io/amazoncorretto:17

# WO-SEC-50: unprivileged user for the JVM process (RCE blast-radius reduction). This image is
# not currently referenced by docker-compose.yml/CI (the root Dockerfile is what's deployed) —
# hardened for consistency/scope, not because it's in the live deploy path.
RUN dnf install -y shadow-utils && dnf clean all \
 && groupadd --system --gid 10001 zorrobpm \
 && useradd --system --uid 10001 --gid zorrobpm --no-create-home --shell /sbin/nologin zorrobpm

COPY --from=builder dependencies/ ./
COPY --from=builder snapshot-dependencies/ ./
COPY --from=builder spring-boot-loader/ ./
COPY --from=builder application/ ./
RUN chown -R zorrobpm:zorrobpm /application /snapshot-dependencies /dependencies /spring-boot-loader 2>/dev/null || true

ENV SERVER_PORT=80
ENV TZ=Asia/Almaty
EXPOSE 80

HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1

USER zorrobpm
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
