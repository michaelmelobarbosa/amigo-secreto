#!/usr/bin/env bash
#
# Valida a API key de produção do Brevo e envia um e-mail de teste direto pela
# API, sem passar pela aplicação. Serve para confirmar chave, remetente, domínio
# e IP de saída antes do teste de fumaça da aplicação.
#
# A chave pode ser restrita a envio: GET /v3/account retorna 403 permission_denied
# e o script segue para o teste de envio. Com BREVO_SANDBOX=true, envia com
# X-Sib-Sandbox: drop e não entrega nada.
#
# Uso (na VPS, na raiz do projeto, com o .env de produção):
#   TEST_EMAIL=voce@exemplo.com bash scripts/check-brevo.sh
#   bash scripts/check-brevo.sh voce@exemplo.com
#
# Requer: curl, sed, mktemp. Não imprime a API key.
# Se o Brevo usar allowlist, rode na VPS (IP de saída <VPS_PUBLIC_IP>).

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
cd "$project_dir"

if [[ "${SKIP_ENV_FILE:-0}" != "1" && -f .env ]]; then
    env_tmp="$(mktemp)"
    trap 'rm -f "$env_tmp"' EXIT
    sed '1s/^\xEF\xBB\xBF//' .env | tr -d '\r' > "$env_tmp"
    set -a
    # shellcheck disable=SC1091
    source "$env_tmp"
    set +a
    rm -f "$env_tmp"
    trap - EXIT
fi

if [[ -n "${BREVO_API_KEY_FILE:-}" ]]; then
    BREVO_API_KEY="$(cat "$BREVO_API_KEY_FILE")"
    export BREVO_API_KEY
fi

: "${BREVO_API_KEY:?defina BREVO_API_KEY no .env ou no ambiente}"
: "${BREVO_FROM_EMAIL:?defina BREVO_FROM_EMAIL no .env ou no ambiente}"
: "${BREVO_FROM_NAME:=Amigo Secreto}"

test_email="${1:-${TEST_EMAIL:-}}"
if [[ -z "$test_email" || "$test_email" != *"@"* ]]; then
    echo "ERRO: informe um e-mail de destino: TEST_EMAIL=voce@exemplo.com bash scripts/check-brevo.sh" >&2
    exit 2
fi

if [[ "${BREVO_SANDBOX:-false}" == "true" ]]; then
    echo "Modo sandbox: o Brevo aceita o payload, mas não entrega e-mail."
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
config="$work_dir/curl.conf"
payload="$work_dir/payload.json"

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# Config do curl com a API key em arquivo 600, fora do argv/ps.
curl_config() {
    local url="$1"
    printf 'silent\nshow-error\nheader = "api-key: %s"\nheader = "accept: application/json"\nurl = "%s"\n' \
        "$BREVO_API_KEY" "$url" > "$config"
    chmod 600 "$config"
}

echo "Testando a API key do Brevo..."
egress_ip="$(curl -sS --max-time 10 https://ifconfig.me 2>/dev/null || true)"
[[ -n "$egress_ip" ]] && echo "IP de saída: $egress_ip"

curl_config "https://api.brevo.com/v3/account"
account_response="$(curl --config "$config" --write-out '\n%{http_code}' || true)"
account_status="${account_response##*$'\n'}"
account_body="${account_response%$'\n'*}"

case "$account_status" in
    200)
        account_email="$(printf '%s' "$account_body" | sed -n 's/.*"email":"\([^"]*\)".*/\1/p' | head -n 1)"
        echo "API key válida${account_email:+ para a conta $account_email}."
        ;;
    403)
        if printf '%s' "$account_body" | grep -q "permission_denied"; then
            echo "API key válida, mas restrita a envio (GET /account negado); seguindo para o teste de envio."
        else
            echo "FALHOU: o Brevo negou a consulta à conta (HTTP 403)." >&2
            exit 1
        fi
        ;;
    401)
        echo "FALHOU: a API key foi recusada (HTTP 401). Confira a chave e a allowlist de IP." >&2
        exit 1
        ;;
    *)
        echo "AVISO: GET /account retornou HTTP ${account_status:-sem status}; seguindo para o teste de envio." >&2
        ;;
esac

if [[ "${BREVO_SANDBOX:-false}" == "true" ]]; then
    headers_json=', "headers": {"X-Sib-Sandbox": "drop"}'
else
    headers_json=""
fi

cat > "$payload" <<JSON
{
  "sender": {
    "name": "$(json_escape "$BREVO_FROM_NAME")",
    "email": "$(json_escape "$BREVO_FROM_EMAIL")"
  },
  "to": [
    {
      "email": "$(json_escape "$test_email")",
      "removeTrackingPixel": true
    }
  ],
  "subject": "Teste de e-mail — Amigo Secreto",
  "htmlContent": "<p>Este é um teste de fumaça do Brevo para o Amigo Secreto.</p><p>Se você recebeu, a API key e o domínio de envio estão corretos.</p>",
  "textContent": "Este é um teste de fumaça do Brevo para o Amigo Secreto. Se você recebeu, a API key e o domínio de envio estão corretos.",
  "tags": ["amigo-secreto", "smoke-test"]$headers_json
}
JSON
chmod 600 "$payload"

curl_config "https://api.brevo.com/v3/smtp/email"
response="$(curl --config "$config" \
    --header "content-type: application/json" \
    --data-binary "@$payload" \
    --write-out '\n%{http_code}' || true)"
status="${response##*$'\n'}"
body="${response%$'\n'*}"

case "$status" in
    201|202)
        message_id="$(printf '%s' "$body" | sed -n 's/.*"messageId":"\([^"]*\)".*/\1/p' | head -n 1)"
        if [[ "${BREVO_SANDBOX:-false}" == "true" ]]; then
            echo "OK (sandbox): Brevo aceitou o payload${message_id:+ (messageId $message_id)}; nada foi entregue."
        else
            echo "OK: Brevo aceitou o e-mail de teste${message_id:+ (messageId $message_id)}."
            echo "Confira a caixa de $test_email e o Transactional Logs do Brevo."
        fi
        ;;
    400)
        if printf '%s' "$body" | grep -q "duplicate_parameter"; then
            echo "OK: requisição idempotente já aceita pelo Brevo."
        else
            echo "FALHOU: Brevo recusou o payload (HTTP 400)." >&2
            exit 1
        fi
        ;;
    401|403)
        echo "FALHOU: Brevo recusou a autenticação (HTTP $status)." >&2
        echo "        Confira a API key e a allowlist de IP (<VPS_PUBLIC_IP>)." >&2
        exit 1
        ;;
    429)
        echo "FALHOU: limite de requisições do Brevo (HTTP 429). Tente mais tarde." >&2
        exit 1
        ;;
    *)
        echo "FALHOU: resposta inesperada do Brevo (HTTP ${status:-sem status})." >&2
        exit 1
        ;;
esac
