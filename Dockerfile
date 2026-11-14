# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# O wrapper é "only-script" (não versiona o jar) e baixa o Maven no build.
# Ele precisa de curl ou wget; a imagem temurin já traz, mas garantimos o mínimo.
# hadolint ignore=DL3008
RUN if ! command -v curl >/dev/null && ! command -v wget >/dev/null; then \
        apt-get update \
        && apt-get install -y --no-install-recommends curl \
        && rm -rf /var/lib/apt/lists/*; \
    fi

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Checkout no Windows pode gravar CRLF; o wrapper exige LF e bit de execução.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

COPY src/ src/

RUN ./mvnw -B -DskipTests package \
    && jar_file="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' -print -quit)" \
    && test -n "$jar_file" \
    && cp "$jar_file" /workspace/app.jar

# ---------- runtime ----------
FROM eclipse-temurin:25-jre

# curl é usado apenas pelo HEALTHCHECK; as listas do apt são limpas na mesma camada.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Usuário sem privilégios; o processo nunca roda como root.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# O pacote do Spring Boot gera um único .jar executável; o nome foi fixado no stage de build.
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

# hadolint ignore=DL3066
USER app

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

# start-period cobre Flyway + JPA; /actuator/health expõe só o necessário (application.yaml).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["curl", "-fsS", "http://localhost:8080/actuator/health"]
