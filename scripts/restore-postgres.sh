#!/usr/bin/env bash
#
# Restore do PostgreSQL de produção do Amigo Secreto, a partir de um dump gerado
# por scripts/backup-postgres.sh.
#
# Uso (na VPS, na raiz do projeto):
#   ./scripts/restore-postgres.sh backups/amigo-secreto-20260101-030000.dump
#   ./scripts/restore-postgres.sh --yes backups/amigo-secreto-20260101-030000.dump
#
# O script para o container da aplicação antes de restaurar (para não servir dados
# inconsistentes) e o reinicia ao final. Em caso de falha, a aplicação fica parada:
# verifique o erro, corrija e suba manualmente com
#   docker compose -f docker-compose.prod.yml start app
#
# Não imprime senhas.

set -euo pipefail

usage() {
    echo "uso: $0 [--yes] <arquivo.dump>" >&2
    echo "  --yes  não pede confirmação (necessário em execução não interativa)" >&2
}

assume_yes=0
dump_file=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -y|--yes)
            assume_yes=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            echo "opção desconhecida: $1" >&2
            usage
            exit 2
            ;;
        *)
            if [[ -n "$dump_file" ]]; then
                echo "informe apenas um arquivo" >&2
                usage
                exit 2
            fi
            dump_file="$1"
            shift
            ;;
    esac
done

if [[ -z "$dump_file" ]]; then
    usage
    exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
cd "$project_dir"

if [[ ! -f "$dump_file" ]]; then
    echo "ERRO: arquivo não encontrado: $dump_file" >&2
    exit 1
fi

if [[ ! -r "$dump_file" ]]; then
    echo "ERRO: arquivo sem permissão de leitura: $dump_file" >&2
    exit 1
fi

if [[ ! -s "$dump_file" ]]; then
    echo "ERRO: arquivo vazio: $dump_file" >&2
    exit 1
fi

load_env_var() {
    local key="$1"
    local line value
    line="$(sed '1s/^\xEF\xBB\xBF//' .env | tr -d '\r' | grep -E "^[[:space:]]*${key}=" | tail -n 1 || true)"
    [[ -z "$line" ]] && return 0
    value="${line#*=}"
    if [[ "$value" == \"*\" && "$value" == *\" ]]; then
        value="${value:1:${#value}-2}"
    elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
        value="${value:1:${#value}-2}"
    fi
    printf '%s' "$value"
}

if [[ -f .env ]]; then
    : "${POSTGRES_DB:=$(load_env_var POSTGRES_DB)}"
    : "${POSTGRES_USER:=$(load_env_var POSTGRES_USER)}"
fi

: "${POSTGRES_DB:?defina POSTGRES_DB no .env}"
: "${POSTGRES_USER:?defina POSTGRES_USER no .env}"

compose=(docker compose -f docker-compose.prod.yml)

if [[ -z "$("${compose[@]}" ps --status running --quiet db)" ]]; then
    echo "ERRO: o serviço db não está rodando. Suba com:" >&2
    echo "  docker compose -f docker-compose.prod.yml up -d db" >&2
    exit 1
fi

echo "Atenção: o restore APAGA e recria os objetos de $POSTGRES_DB com o conteúdo de $dump_file."
if [[ "$assume_yes" -ne 1 ]]; then
    if [[ ! -t 0 ]]; then
        echo "ERRO: execução não interativa; repita com --yes após revisar o arquivo." >&2
        exit 1
    fi
    read -r -p "Confirmar restore? [digite RESTORE] " answer || answer=""
    if [[ "$answer" != "RESTORE" ]]; then
        echo "Restore cancelado."
        exit 1
    fi
fi

# Confere que o arquivo é um dump válido antes de tocar no banco.
if ! "${compose[@]}" exec -T db pg_restore --list < "$dump_file" > /dev/null; then
    echo "ERRO: $dump_file não parece um dump de pg_dump válido." >&2
    exit 1
fi

echo "Parando a aplicação..."
"${compose[@]}" stop app > /dev/null

restore_done=0
trap 'if [[ "$restore_done" -ne 0 ]]; then :; else echo "Restore interrompido: a aplicação continua parada." >&2; fi' EXIT

echo "Restaurando..."
"${compose[@]}" exec -T db \
    pg_restore --clean --if-exists --no-owner --no-privileges \
    -U "$POSTGRES_USER" -d "$POSTGRES_DB" < "$dump_file"

restore_done=1
trap - EXIT

echo "Subindo a aplicação..."
"${compose[@]}" start app > /dev/null

echo "Restore concluído. Confira o health:"
echo "  docker compose -f docker-compose.prod.yml ps"
echo "  docker compose -f docker-compose.prod.yml logs --tail=100 app"
