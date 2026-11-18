#!/usr/bin/env bash
# Cria um backup do Postgres e publica uma cópia criptografada em um remote rclone.
#
# Uso:
#   bash scripts/backup-offsite.sh
#   OFFSITE_REMOTE=offsite-crypt:backups OFFSITE_RETENTION_DAYS=30 bash scripts/backup-offsite.sh
#   OFFSITE_SKIP_BACKUP=1 bash scripts/backup-offsite.sh   # usa o dump mais recente
#
# Requisitos:
#   - scripts/backup-postgres.sh funcionando;
#   - rclone no PATH ou em ~/.local/bin/rclone;
#   - remote criptografado configurado (ex.: `rclone config` com crypt sobre local/S3/B2).
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

REMOTE="${OFFSITE_REMOTE:-offsite-crypt:backups}"
RETENTION_DAYS="${OFFSITE_RETENTION_DAYS:-30}"

if [ "${OFFSITE_SKIP_BACKUP:-0}" != "1" ]; then
    bash scripts/backup-postgres.sh
fi
DUMP="$(ls -t backups/*.dump | head -n1)"
NAME="$(basename "$DUMP")"

"$RCLONE" copy "$DUMP" "$REMOTE/" --immutable

LOCAL_SHA="$(sha256sum "$DUMP" | awk '{print $1}')"
REMOTE_SHA="$("$RCLONE" cat "$REMOTE/$NAME" | sha256sum | awk '{print $1}')"
if [ "$LOCAL_SHA" != "$REMOTE_SHA" ]; then
    echo "ERRO: checksum não confere para $NAME" >&2
    exit 2
fi

"$RCLONE" delete "$REMOTE/" --min-age "${RETENTION_DAYS}d" 2>/dev/null || true

echo "Off-site OK: $REMOTE/$NAME"
echo "sha256: $LOCAL_SHA"
"$RCLONE" ls "$REMOTE/" | tail -n 5
