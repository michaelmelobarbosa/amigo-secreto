#!/usr/bin/env bash
#
# Backup do PostgreSQL de produção do Amigo Secreto.
#
# Uso (na VPS, na raiz do projeto):
#   ./scripts/backup-postgres.sh
#
# Gera backups/amigo-secreto-AAAAMMDD-HHMMSS.dump (formato custom, restore com pg_restore)
# e remove dumps com mais de RETENTION_DAYS dias (padrão: 14).
#
# Não imprime senhas: a conexão usa o socket local do container, autenticado pelo
# usuário dono do banco.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
cd "$project_dir"

# Carrega POSTGRES_DB/POSTGRES_USER/POSTGRES_PASSWORD do .env, se existir.
if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
fi

: "${POSTGRES_DB:?defina POSTGRES_DB no .env}"
: "${POSTGRES_USER:?defina POSTGRES_USER no .env}"

retention_days="${RETENTION_DAYS:-14}"
backup_dir="$project_dir/backups"
timestamp="$(date +%Y%m%d-%H%M%S)"
dump_file="$backup_dir/amigo-secreto-$timestamp.dump"

compose=(docker compose -f docker-compose.prod.yml)

mkdir -p "$backup_dir"
chmod 700 "$backup_dir"

# Dump contém dados pessoais dos participantes: nasce restrito ao dono.
umask 077

if ! "${compose[@]}" exec -T db \
        pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB" > "$dump_file"; then
    rm -f -- "$dump_file"
    echo "ERRO: pg_dump falhou. O banco está de pé? (docker compose -f docker-compose.prod.yml ps)" >&2
    exit 1
fi

if [[ ! -s "$dump_file" ]]; then
    rm -f -- "$dump_file"
    echo "ERRO: dump vazio, backup descartado." >&2
    exit 1
fi

chmod 600 "$dump_file"
echo "Backup criado: $dump_file ($(du -h "$dump_file" | cut -f1))"

# Retenção: apaga apenas dumps do próprio script.
find "$backup_dir" -maxdepth 1 -type f -name 'amigo-secreto-*.dump' \
    -mtime +"$retention_days" -print -delete

echo "Retenção de $retention_days dias aplicada."
