# syntax=docker/dockerfile:1

# ---------- healthcheck tool ----------
# BusyBox estático: o HEALTHCHECK não depende de curl nem de pacotes instalados
# na imagem de runtime. Como esta stage não executa RUN, ela também não precisa
# de emulação ao compilar para ARM64.
FROM --platform=$TARGETPLATFORM busybox:1.37.0 AS busybox

# ---------- build ----------
# Compila o jar na arquitetura do runner (amd64 no CI), sem emular ARM.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# O wrapper é "only-script" (não versiona o jar) e baixa o Maven no build.
# Ele precisa de curl ou wget; a imagem temurin do JDK pode não trazer nenhum.
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

# ---------- runtime user ----------
# Cria o usuário na arquitetura do runner. A stage de runtime só copia texto
# (/etc/passwd e /etc/group), então não roda comandos sob emulação.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre AS user

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --home-dir /app --shell /usr/sbin/nologin app

# ---------- runtime ----------
FROM --platform=$TARGETPLATFORM eclipse-temurin:25-jre

# Usuário sem privilégios; o processo nunca roda como root.
COPY --from=user /etc/passwd /etc/passwd
COPY --from=user /etc/group /etc/group
COPY --from=busybox /bin/busybox /bin/busybox

WORKDIR /app

# O pacote do Spring Boot gera um único .jar executável; o nome foi fixado no stage de build.
COPY --from=build --chown=1001:1001 /workspace/app.jar /app/app.jar

# hadolint ignore=DL3066
USER app

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

# start-period cobre Flyway + JPA; /actuator/health responde apenas na porta de management em loopback (8081).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["/bin/busybox", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8081/actuator/health"]
