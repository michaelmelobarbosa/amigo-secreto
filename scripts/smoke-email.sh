#!/usr/bin/env bash
#
# Teste de fumaça do e-mail real (Brevo) pela aplicação em produção.
#
# Cria um evento temporário, envia os três links do organizador para o endereço
# informado usando o mesmo EmailSender/Brevo do painel, confere o flash de
# sucesso e apaga o evento no final.
#
# Uso (na VPS, na raiz do projeto):
#   TEST_EMAIL=voce@exemplo.com bash scripts/smoke-email.sh
#   bash scripts/smoke-email.sh voce@exemplo.com
#
# Variáveis opcionais:
#   BASE_URL   padrão https://amigo-secreto.barbosalabs.dev
#
# Requisitos: curl, sed, grep, mktemp. Não imprime o token do painel nem a API key.
# Se o e-mail estiver desabilitado, o próprio painel informa no flash.

set -euo pipefail

base_url="${BASE_URL:-https://amigo-secreto.barbosalabs.dev}"
test_email="${1:-${TEST_EMAIL:-}}"

if [[ -z "$test_email" || "$test_email" != *"@"* ]]; then
    echo "ERRO: informe um e-mail de destino: TEST_EMAIL=voce@exemplo.com bash scripts/smoke-email.sh" >&2
    exit 2
fi

for cmd in curl sed grep mktemp; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERRO: comando '$cmd' não encontrado." >&2
        exit 2
    fi
done

work_dir="$(mktemp -d)"
chmod 700 "$work_dir"
jar="$work_dir/cookies.txt"
headers="$work_dir/headers.txt"
page="$work_dir/page.html"
curl_conf="$work_dir/curl.conf"
event_name="Smoke e-mail $(date +%Y%m%d-%H%M%S)"
csrf=""
admin_token=""
cleaned=0

csrf_from() {
    sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' "$1" | head -n 1
}

curl_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

write_curl_conf() {
    local url="$1"
    shift
    printf 'url = "%s"\n' "$(curl_escape "$url")" > "$curl_conf"
    for field in "$@"; do
        printf 'data-urlencode = "%s"\n' "$(curl_escape "$field")" >> "$curl_conf"
    done
    chmod 600 "$curl_conf"
}

cleanup() {
    if [[ -n "$admin_token" && "$cleaned" -eq 0 ]]; then
        write_curl_conf "$base_url/admin/$admin_token/delete" "_csrf=$csrf"
        curl -sS -o /dev/null -c "$jar" -b "$jar" --config "$curl_conf" >/dev/null 2>&1 || true
    fi
    rm -rf "$work_dir"
}
trap cleanup EXIT

echo "Base:  $base_url"
echo "E-mail de teste: $test_email"
echo "Evento temporário: $event_name"
echo

# 1. Sessão + CSRF da tela inicial.
status="$(curl -sS -c "$jar" -b "$jar" -o "$page" -w '%{http_code}' "$base_url/")"
if [[ "$status" != "200" ]]; then
    echo "ERRO: GET / retornou HTTP $status." >&2
    exit 1
fi
csrf="$(csrf_from "$page")"
if [[ -z "$csrf" ]]; then
    echo "ERRO: não encontrei o token CSRF na tela inicial." >&2
    exit 1
fi

# 2. Cria o evento e captura o token do painel no Location.
write_curl_conf "$base_url/events" \
    "_csrf=$csrf" \
    "name=$event_name" \
    "organizerName=Smoke Test"
status="$(curl -sS -c "$jar" -b "$jar" -D "$headers" -o /dev/null -w '%{http_code}' --config "$curl_conf")"
if [[ "$status" != "302" ]]; then
    echo "ERRO: POST /events retornou HTTP $status (esperado 302)." >&2
    if [[ "$status" == "429" ]]; then
        echo "      Rate limit de criação de eventos atingido; aguarde alguns minutos." >&2
    fi
    exit 1
fi

location="$(sed -n 's/^[Ll]ocation: //p' "$headers" | tr -d '\r' | tail -n 1)"
admin_token="$(printf '%s' "$location" | sed -n 's#.*/admin/\([^/?#]*\).*#\1#p')"
if [[ -z "$admin_token" ]]; then
    echo "ERRO: não consegui extrair o token do painel do redirect." >&2
    exit 1
fi

# 3. Abre o painel e confirma que a sessão/token continuam válidos.
write_curl_conf "$base_url/admin/$admin_token"
status="$(curl -sS -c "$jar" -b "$jar" -o "$page" -w '%{http_code}' --config "$curl_conf")"
if [[ "$status" != "200" ]]; then
    echo "ERRO: GET do painel retornou HTTP $status." >&2
    exit 1
fi
csrf="$(csrf_from "$page")"
if [[ -z "$csrf" ]]; then
    echo "ERRO: não encontrei o CSRF no painel." >&2
    exit 1
fi

# 4. Envia os links do organizador pelo mesmo caminho do painel.
write_curl_conf "$base_url/admin/$admin_token/links/email" \
    "_csrf=$csrf" \
    "email=$test_email"
body="$(curl -sS -L -c "$jar" -b "$jar" --config "$curl_conf")"

if grep -q "Links enviados para o e-mail informado" <<<"$body"; then
    echo "OK: o Brevo aceitou o envio. Confira a caixa de entrada e os logs do Brevo."
elif grep -q "Links aceitos em modo sandbox" <<<"$body"; then
    echo "FALHOU: a aplicação está em sandbox (BREVO_SANDBOX=true); nada foi entregue." >&2
    exit 1
elif grep -q "O envio de e-mail está desativado" <<<"$body"; then
    echo "FALHOU: EMAIL_ENABLED=false no ambiente de produção." >&2
    exit 1
elif grep -q "Não foi possível enviar os links" <<<"$body"; then
    echo "FALHOU: o Brevo recusou o envio. Veja os logs da app e o Transactional Logs do Brevo." >&2
    exit 1
else
    echo "FALHOU: resposta inesperada do painel; não encontrei o flash de sucesso." >&2
    exit 1
fi

# 5. Apaga o evento temporário (o trap repete se algo falhar antes).
write_curl_conf "$base_url/admin/$admin_token/delete" "_csrf=$csrf"
status="$(curl -sS -o /dev/null -c "$jar" -b "$jar" -w '%{http_code}' --config "$curl_conf")"
if [[ "$status" == "302" || "$status" == "200" ]]; then
    cleaned=1
    echo "Evento temporário apagado."
else
    echo "AVISO: não consegui apagar o evento temporário (HTTP $status)." >&2
fi

echo "Smoke test concluído."
