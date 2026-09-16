# ============================================================
# cmn-service
#
# Multi-stage: build with the JDK, run on the JRE.
#
#   docker build -t cmn-service:latest cmn-service/
#
# Or, from the repository root, let Compose do it:
#
#   cd openbao && docker compose up -d --build
# ============================================================


# ------------------------------------------------------------
# Stage 1 - build
# ------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# Dependencies first: this layer is cached and only invalidated when the POM
# changes, so ordinary source edits do not re-download the world.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Unpack the fat jar so the runtime image can hold the layers separately.
# Dependencies change far less often than application code, so splitting them
# keeps rebuild pushes small.
RUN java -Djarmode=tools -jar target/cmn-service-*.jar extract --layers --launcher --destination extracted


# ------------------------------------------------------------
# Stage 2 - runtime
# ------------------------------------------------------------

FROM eclipse-temurin:25-jre AS runtime

# curl is installed solely for HEALTHCHECK. The Temurin JRE image ships
# neither curl nor wget, so without this the probe fails on a perfectly
# healthy container:
#
#   OCI runtime exec failed: "wget": executable file not found in $PATH
#
# If your orchestrator probes HTTP itself (a Kubernetes livenessProbe
# httpGet, say), drop this layer and the HEALTHCHECK below.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Never run as root.
RUN groupadd --system --gid 1001 cmn \
 && useradd  --system --uid 1001 --gid cmn --home /app --shell /usr/sbin/nologin cmn

WORKDIR /app

COPY --from=build --chown=cmn:cmn /build/extracted/dependencies/          ./
COPY --from=build --chown=cmn:cmn /build/extracted/spring-boot-loader/    ./
COPY --from=build --chown=cmn:cmn /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=cmn:cmn /build/extracted/application/           ./

USER cmn

EXPOSE 8080

# No environment profile is baked in. The service refuses to start without
# exactly one of local/dev/staging/prod, which is deliberate - an image that
# defaulted to a profile could be deployed to the wrong environment silently.
#
#   docker run -e SPRING_PROFILES_ACTIVE=dev \
#              -e BAO_ADDR=http://openbao:8200 \
#              -e BAO_TOKEN=... \
#              cmn-service:latest

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# The layered extraction produces directories, not a jar, so the Spring Boot
# loader is invoked directly rather than with -jar.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
