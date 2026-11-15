#!/usr/bin/env bash
#
# Deploy/atualização do Amigo Secreto na VPS.
#
# Uso (na VPS, na raiz do projeto):
#   APP_TAG=<sha-do-commit> bash scripts/deploy.sh
#
# Se APP_TAG já estiver no .env, basta:
#   bash scripts/deploy.sh
#
# Pré-requisitos:
#   - .env preenchido (APP_IMAGE, APP_TAG, POSTGRES_*, DOMAIN)
#   - stack de borda (Caddy) criado e rede externa "edge" existente
#   - docker login ghcr.io, se o pacote do registry for privado
#
# O script não compila nada: a imagem vem do registry com tag imutável (SHA).
# Para rollback, rode de novo com o APP_TAG anterior.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
cd "$project_dir"

if ! docker compose version >/dev/null 2>&1; then
    echo "ERRO: Docker Compose v2 não encontrado. Instale com https://get.docker.com." >&2
    exit 1
fi

if [[ ! -f .env ]]; then
    echo "ERRO: .env não encontrado. Copie de .env.prod.example e preencha." >&2
    exit 1
fi

# APP_TAG passado na linha de comando vence o valor do .env.
env_app_tag="${APP_TAG:-}"

set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ -n "$env_app_tag" ]]; then
    APP_TAG="$env_app_tag"
fi
export APP_TAG

: "${APP_IMAGE:?defina APP_IMAGE no .env (ex.: ghcr.io/usuario/amigo-secreto)}"
: "${APP_TAG:?defina APP_TAG no ambiente ou no .env}"
: "${POSTGRES_DB:?defina POSTGRES_DB no .env}"
: "${POSTGRES_USER:?defina POSTGRES_USER no .env}"
: "${POSTGRES_PASSWORD:?defina POSTGRES_PASSWORD no .env}"
: "${DOMAIN:?defina DOMAIN no .env}"

if [[ "$APP_TAG" == *"troque"* ]]; then
    echo "ERRO: APP_TAG ainda é o placeholder. Use o SHA da imagem publicada pelo CI." >&2
    exit 1
fi

edge_network="${EDGE_NETWORK:-edge}"
if ! docker network inspect "$edge_network" >/dev/null 2>&1; then
    echo "ERRO: rede externa '$edge_network' não existe." >&2
    echo "      Crie com: docker network create $edge_network" >&2
    echo "      E suba o stack de borda (Caddy) antes deste deploy." >&2
    exit 1
fi

compose=(docker compose -f docker-compose.prod.yml)

echo "Validando a configuração do Compose..."
"${compose[@]}" config >/dev/null

echo "Baixando ${APP_IMAGE}:${APP_TAG}..."
"${compose[@]}" pull app

echo "Subindo app e banco..."
"${compose[@]}" up -d --remove-orphans

echo "Aguardando o healthcheck de amigo-app..."
for _ in $(seq 1 60); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}starting{{end}}' amigo-app 2>/dev/null || true)"
    if [[ "$status" == "healthy" ]]; then
        echo "App saudável."
        "${compose[@]}" ps
        echo
        echo "Pronto: https://${DOMAIN}"
        echo "Logs:   ${compose[*]} logs -f app"
        exit 0
    fi
    sleep 2
done

echo "ERRO: amigo-app não ficou healthy em 120s. Últimas linhas do log:" >&2
"${compose[@]}" logs --tail=100 app >&2 || true
exit 1
