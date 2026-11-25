#!/usr/bin/env bash
# Cria um backup do Postgres e publica cópias criptografadas em dois remotes:
#   1) staging local na VPS (`offsite-crypt`) — origem do pull do PC;
#   2) bucket R2 da Cloudflare (`offsite-r2`) — cópia off-site 24/7.
#
# Uso:
#   bash scripts/backup-offsite.sh
#   OFFSITE_SKIP_BACKUP=1 bash scripts/backup-offsite.sh   # reaproveita o dump mais recente
#
# Variáveis:
#   OFFSITE_RETENTION_DAYS    (padrão 30)    retenção aplicada nos dois remotes
#   OFFSITE_SIZE_LIMIT_BYTES  (padrão 2 GB)  teto do bucket R2; acima disso o job falha
#   OFFSITE_HEARTBEAT_FILE    (padrão ~/.config/amigo-secreto/heartbeat-url)
#                              URL de ping (healthchecks.io) acionada só no sucesso
#
# Saídas: 0 = ok; 1 = ambiente; 2 = checksum; 3 = bucket R2 acima do teto.
# Falhar sem ping é intencional: o healthchecks.io alerta quando o pipeline para.
set -euo pipefail
cd "$(dirname "$0")/.."

RCLONE="${RCLONE:-$(command -v rclone || true)}"
if [ -z "$RCLONE" ] && [ -x "$HOME/.local/bin/rclone" ]; then
    RCLONE="$HOME/.local/bin/rclone"
fi
if [ -z "$RCLONE" ]; then
    echo "rclone não encontrado; instale ou defina RCLONE=/caminho/para/rclone" >&2
    exit 1
fi

RETENTION_DAYS="${OFFSITE_RETENTION_DAYS:-30}"
SIZE_LIMIT_BYTES="${OFFSITE_SIZE_LIMIT_BYTES:-2147483648}"
HEARTBEAT_FILE="${OFFSITE_HEARTBEAT_FILE:-$HOME/.config/amigo-secreto/heartbeat-url}"

if [ "${OFFSITE_SKIP_BACKUP:-0}" != "1" ]; then
    bash scripts/backup-postgres.sh
fi
DUMP="$(ls -t backups/*.dump | head -n1)"
NAME="$(basename "$DUMP")"
LOCAL_SHA="$(sha256sum "$DUMP" | awk '{print $1}')"

publish() {
    local remote="$1"
    "$RCLONE" copy "$DUMP" "$remote/" --immutable
    local remote_sha
    remote_sha="$("$RCLONE" cat "$remote/$NAME" | sha256sum | awk '{print $1}')"
    if [ "$LOCAL_SHA" != "$remote_sha" ]; then
        echo "ERRO: checksum não confere em $remote/$NAME" >&2
        exit 2
    fi
    "$RCLONE" delete "$remote/" --min-age "${RETENTION_DAYS}d" 2>/dev/null || true
}

publish "offsite-crypt:backups"   # staging local (consumido pelo pull do PC)
publish "offsite-r2:backups"      # Cloudflare R2

# Teto de uso do bucket R2: passar do limite falha o job (sem heartbeat) e o
# healthchecks alerta. Protege contra runaway antes dos 10 GB grátis do R2.
BUCKET_BYTES="$("$RCLONE" size offsite-r2: --json 2>/dev/null | sed -n 's/.*"bytes":\([0-9]*\).*/\1/p')"
if [ -n "$BUCKET_BYTES" ] && [ "$BUCKET_BYTES" -gt "$SIZE_LIMIT_BYTES" ]; then
    echo "ERRO: bucket R2 em ${BUCKET_BYTES} bytes (teto ${SIZE_LIMIT_BYTES})" >&2
    exit 3
fi

# Heartbeat: só depois de dump, uploads duplos e checksums validados.
# Passa a URL via --config - para não expor o token do healthcheck no argv/ps.
if [ -s "$HEARTBEAT_FILE" ]; then
    HEARTBEAT_URL="$(tr -d ' "\r\n\\' < "$HEARTBEAT_FILE")"
    if [ -n "$HEARTBEAT_URL" ]; then
        printf 'url = "%s"\n' "$HEARTBEAT_URL" | \
            curl -fsS -m 20 --retry 3 --config - >/dev/null 2>&1 || \
            echo "AVISO: heartbeat não respondeu; o job seguiu com sucesso" >&2
    fi
fi

echo "Off-site OK: $NAME"
echo "sha256: $LOCAL_SHA"
echo "bucket R2: ${BUCKET_BYTES:-?} bytes (teto $SIZE_LIMIT_BYTES)"
"$RCLONE" ls offsite-r2:backups/ | tail -n 5
