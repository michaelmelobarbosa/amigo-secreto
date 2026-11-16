# DEPLOY_CONCLUIDO — Amigo Secreto na VPS `hermes-vps`

> **Documento de execução real.** Registra o que foi efetivamente executado em **2026-11-15/16** (fuso
> `-03`) para colocar `https://amigo-secreto.barbosalabs.dev` no ar, com as evidências de verificação
> e a lista do que ficou de fora. Complementa [`DEPLOY.md`](./DEPLOY.md) e
> [`INFRA-VPS.md`](./INFRA-VPS.md) — onde este documento divergir deles, **este é o que
> aconteceu**: os dois `.md` do repositório foram escritos antes da primeira execução.
>
> **Quem executou:** Hermes (agente na própria VPS), com autorização do dono do projeto.
> **Ações humanas no console** (DNS e security list) estão marcadas como tal — não foram feitas pelo agente.

---

## 1. Resumo executivo

- **Estado:** aplicação em produção, `https://amigo-secreto.barbosalabs.dev` respondendo `200` com TLS
  Let's Encrypt, banco Postgres 17 aplicado por Flyway (V1–V3), backup diário automatizado por
  systemd timer.
- **Topologia adotada:** a do `INFRA-VPS.md` (opção A) — Caddy de borda em stack separado (`/opt/edge`),
  rede externa `edge`, app e banco no Compose do projeto **sem nenhuma porta publicada**.
- **Imagem:** `ghcr.io/michaelmelobarbosa/amigo-secreto:2b6e2d4fc13d98eb67cfcd4bd91899957a028206`
  (arm64, pacote **público** no GHCR — não houve `docker login`).
- **Primeira tentativa de deploy falhou** por um bug de configuração do próprio repo: `BREVO_FROM_NAME`
  sem aspas quebra `source .env` (ver **P10** em §8). Corrigido no `.env` do servidor.
- **Zero eventos/dados de teste no banco** ao final (`select count(*) from event` → `0`).

---

## 2. Topologia final

```
Internet ──▶ edge-caddy-1 :80/:443 (443/udp HTTP3)      stack "edge" (rede bridge 172.18.0.0/16)
              │  TLS Let's Encrypt, HSTS, /actuator 404, SEM access log
              │
              ├──▶ amigo-app:8080  (expose, sem publicar)  ──▶ amigo-secreto-db-1:5432
              │     redes: amigo-secreto_internal + edge         rede: amigo-secreto_internal
              │
              └── (livre para próximas apps na rede edge)

Host (fora do Docker): Hermes/systemd em 127.0.0.1:8080 (python3), 9119, 9120 — loopback, sem conflito.
```

| Recurso | Nome / valor |
| --- | --- |
| Compose project (app) | `amigo-secreto` |
| Rede privada | `amigo-secreto_internal` |
| Rede de borda | `edge` (`d57962475cdb…`, `172.18.0.0/16`, criada pelo agente) |
| Volume do banco | `amigo-secreto_pgdata` |
| Volumes do Caddy | `edge_caddy_data` (certificados), `edge_caddy_config` |
| Container da app | `amigo-app` |
| Container do banco | `amigo-secreto-db-1` (nome de serviço + prefixo do projeto, **sem** `container_name` fixo) |
| Container de borda | `edge-caddy-1` |

---

## 3. Ambiente do host (medido, não herdado dos docs)

| Item | Valor medido |
| --- | --- |
| Instância OCI | `hermes-vps` · shape `VM.Standard.A1.Flex` · região `sa-saopaulo-1` · AD-1 · compartment = root/tenancy |
| IP público / privado | `147.15.121.26` / `10.0.0.71` · subnet `10.0.0.0/24` · **sem IPv6 global** |
| SO / kernel / arch | Ubuntu 24.04.5 LTS · `6.17.0-1020-oracle` · `aarch64` |
| CPU / RAM / disco | 2 vCPU · 11 GiB (2 GiB swap) · 48 GB com **28 GB livres (43% usado)** |
| Docker | **29.9.3** (instalado nesta execução) · Compose **v5.7.1** · storage `overlayfs` |
| Usuário de deploy | `ubuntu` (uid 1001), adicionado ao grupo `docker`; `sudo` sem senha |
| `cron` | **não existe** na máquina (`crontab: command not found`) |
| Firewall do host | iptables-nft: `INPUT` aceita só 22 + ICMP + lo, resto `REJECT`; `FORWARD` com `REJECT` persistido em `/etc/iptables/rules.v4` |
| Hermes | `hermes` em `127.0.0.1:9119` e `:9120`; um `python3` em `127.0.0.1:8080` — nada em `0.0.0.0` |
| Serviços intocados | `unified-monitoring-agent` (Oracle), `snap.oracle-cloud-agent`, `unattended-upgrades` |

---

## 4. O que foi executado (fase por fase, com evidência)

### F1 — Docker Engine + Compose v2

```bash
curl -fsSL https://get.docker.com -o /tmp/get-docker.sh && sudo sh /tmp/get-docker.sh
sudo usermod -aG docker ubuntu
```

**Ordenação com o `iptables-persistent`** (defesa em profundidade, ver §7 D4):

| Arquivo criado | Conteúdo |
| --- | --- |
| `/etc/systemd/system/netfilter-persistent.service.d/10-before-docker.conf` | `[Unit]` / `Before=docker.service` |
| `/etc/systemd/system/docker.service.d/10-after-netfilter.conf` | `[Unit]` / `After=netfilter-persistent.service` |

`sudo systemctl daemon-reload` executado.

**Evidências:**

- `docker --version` → `Docker version 29.9.3, build 5457199`; `docker compose version` → `v5.7.1`.
- `docker run --rm hello-world` → executou; `docker run --rm alpine:3.20 wget -qO- https://ifconfig.me`
  → `147.15.121.26` (**IP de saída dos containers = IP público da VPS**, relevante para allowlist de
  provedor de e-mail).
- `iptables -S FORWARD` mostra `DOCKER-USER` e `DOCKER-FORWARD` **antes** do `REJECT` herdado da Oracle.
- Hermes continuou respondendo em `127.0.0.1:9119/9120/8080` (nada foi reiniciado).

### F2 — Stack de borda `/opt/edge`

```bash
docker network create edge
sudo install -d -m 755 /opt/edge/sites
```

Arquivos criados (conteúdo integral em §5), todos `root:root`, `.env` em `600`, `sites/` montado `:ro`.

Validação **antes** de subir (sem nenhuma tentativa de ACME):

```bash
sudo docker run --rm -e ACME_EMAIL=x@y.z \
  -v /opt/edge/Caddyfile:/etc/caddy/Caddyfile:ro -v /opt/edge/sites:/etc/caddy/sites:ro \
  caddy:2-alpine caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
# → "Valid configuration"
```

Subida: `cd /opt/edge && sudo docker compose -f compose.yml up -d`.

**Evidências:** `edge-caddy-1` publicando `0.0.0.0:80`, `443/tcp`, `443/udp` (e `[::]`); `2019/tcp`
apenas interno; `curl -H 'Host: amigo-secreto.barbosalabs.dev' http://127.0.0.1/` → **308** para HTTPS;
`ls /var/log/caddy` → *No such file or directory* (prova de que **não há access log**).

### F3 — App + banco

```bash
sudo mkdir -p /opt/amigo-secreto && sudo chown ubuntu:ubuntu /opt/amigo-secreto
git clone --depth 1 https://github.com/michaelmelobarbosa/amigo-secreto.git /opt/amigo-secreto
# commit clonado: 2b6e2d4fc13d98eb67cfcd4bd91899957a028206
cp .env.prod.example .env && chmod 600 .env
# POSTGRES_PASSWORD=$(openssl rand -hex 32)  → 64 chars hex, gravado só no .env do servidor
# APP_TAG=2b6e2d4fc13d98eb67cfcd4bd91899957a028206
cd /opt/amigo-secreto && sg docker -c 'APP_TAG=2b6e2d4fc13d98eb67cfcd4bd91899957a028206 bash scripts/deploy.sh'
```

**Primeira execução abortou** com `.env: line 48: Secreto: command not found` (bug P10, §8). Correção
aplicada no `.env` do servidor (`BREVO_FROM_NAME="Amigo Secreto"`, com aspas) e o deploy rodou com
`EXIT=0`.

**Evidências:**

- `amigo-secreto-db-1 Healthy` → `amigo-app Started` → `App saudável.`
- `docker ps` → `amigo-app` **`8080/tcp`** e `amigo-secreto-db-1` **`5432/tcp`** (nenhuma porta
  publicada, `PORTS` sem `0.0.0.0`).
- Flyway: `V1 init`, `V2 add participant email`, `V3 create notification`, todas `success = t`.
- Logs do app sem `ERROR`/`WARN` no arranque.

### F4 — Validação (todas as checagens feitas com TLS real, via Caddy)

| # | Checagem | Esperado | Obtido |
| --- | --- | --- | --- |
| 1 | `GET /` de **fora da VPS** | 200 + página | 200 · `Criar evento · Amigo Secreto` |
| 2 | Redirect HTTP | 308 → HTTPS | 308, `location: https://amigo-secreto.barbosalabs.dev/` |
| 3 | `Strict-Transport-Security` | presente | `max-age=31536000; includeSubDomains` |
| 4 | CSP / XFO / nosniff / referrer | presentes | `frame-ancestors 'none'`, `DENY`, `nosniff`, `no-referrer` |
| 5 | `/actuator` e `/actuator/health` | 404 | 404 / 404 |
| 6 | `/robots.txt` | `Disallow: /` | ok |
| 7 | Token inválido (`/admin/adm_naoexiste`) | 404, sem revelar existência | 404 |
| 8 | Certificado | Let's Encrypt válido | `issuer CN=YE1`, `notAfter Feb 13 2027` |
| 9 | `POST /events` × 6 (limite 5/10 min) | 429 no 6º | `403 403 403 403 403 429` |
| 10 | 429 traz `Retry-After` | sim | `retry-after: 600` |
| 11 | `X-Forwarded-For` forjado não cria bucket novo | 429 | 429 com `9.9.9.9` e `8.8.4.4` |
| 12 | Eventos criados durante o teste | 0 | 0 (`select count(*) from event` → `0`) |
| 13 | Auditoria de portas | só o Caddy publica | ok (ver §4 F2) |
| 14 | Consumo | app ≤ 1 GiB | app `288 MiB / 1 GiB`, db `45 MiB`, caddy `13 MiB` |

> Observação sobre a checagem 9: os `403` são o CSRF rejeitando `POST` sem token de sessão — o rate
> limiter **conta antes** da validação CSRF, o que é o comportamento correto. O bucket usado foi o do
> IP `127.0.0.1` (origem do teste), sem qualquer efeito para usuários reais.
>
> Não testado em F4: navegação de ponta a ponta criando evento/sorteio pelo navegador (fluxo funcional
> coberto pelos 159 testes do projeto + smoke HTTP). Ver §9.

### F5 — Backup automatizado (systemd timer, porque não há `cron`)

Arquivos em §5.3/§5.4. `sudo systemctl enable --now amigo-secreto-backup.timer`.

**Evidências:**

- `systemctl list-timers` → próxima execução `Mon 2026-11-16 03:18 -03`.
- Execução manual de prova: `Backup criado: /opt/amigo-secreto/backups/amigo-secreto-20261115-195346.dump (20K)`
  · `Retenção de 14 dias aplicada.` · serviço terminou `status=0/SUCCESS`.
- Diretório `backups/` em `700`, dump `600`, `17600` bytes.
- Integridade: `pg_restore --list` num container descartável → `TOC Entries: 48`, `Compression: gzip`,
  `dbname: amigo_secreto`.

---

## 5. Arquivos criados **fora** do repositório (conteúdo integral)

### 5.1 `/opt/edge/compose.yml` (root:root 644)

```yaml
name: edge

services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    environment:
      ACME_EMAIL: ${ACME_EMAIL:?defina ACME_EMAIL no .env do stack de borda}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ./sites:/etc/caddy/sites:ro
      - caddy_data:/data
      - caddy_config:/config
    networks: [edge]
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"

networks:
  edge:
    external: true

volumes:
  caddy_data:
  caddy_config:
```

### 5.2 `/opt/edge/Caddyfile` (644)

```caddyfile
{
    email {$ACME_EMAIL}
    # Sem "log" aqui: log global configura loggers, NÃO liga access log.
}

import sites/*.caddy
```

### 5.3 `/opt/edge/sites/amigo-secreto.caddy` (644, montado `:ro`)

```caddyfile
amigo-secreto.barbosalabs.dev {
    encode zstd gzip

    @actuator path /actuator /actuator/*
    respond @actuator 404

    reverse_proxy amigo-app:8080

    header {
        -Server
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
    }

    # SEM "log": as URLs carregam tokens de acesso no path (/admin/, /p/, /join/).
}
```

> **Desvio consciente do `INFRA-VPS.md`:** o bloco usa **hostname literal** em vez de `{$DOMAIN}`.
> Motivo: `sites/` vai hospedar N apps e um único `.env` do stack não comporta N domínios. Nova app =
> novo arquivo + `caddy reload`.

### 5.4 `/opt/edge/.env` (600, root)

```dotenv
ACME_EMAIL=michaelmelobarbosa@gmail.com
```

### 5.5 `/opt/edge/README.md`

Documenta dono/`chmod`, regra "`sites/` pertence ao deploy e não à aplicação", proibição de `log` em
site com token no path, proibição de publicar `2019`, comando de `reload`/`validate`, como adicionar
site novo e a pendência de emissão enquanto o DNS não existir.

### 5.6 `/etc/systemd/system/amigo-secreto-backup.service`

```ini
[Unit]
Description=Backup do PostgreSQL do Amigo Secreto
Documentation=file:/opt/amigo-secreto/docs/DEPLOY.md
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
User=ubuntu
Group=ubuntu
WorkingDirectory=/opt/amigo-secreto
Environment=RETENTION_DAYS=14
ExecStart=/usr/bin/bash /opt/amigo-secreto/scripts/backup-postgres.sh
Nice=10
IOSchedulingClass=best-effort
IOSchedulingPriority=7
```

### 5.7 `/etc/systemd/system/amigo-secreto-backup.timer`

```ini
[Unit]
Description=Backup diário do PostgreSQL do Amigo Secreto

[Timer]
OnCalendar=*-*-* 03:15:00
Persistent=true
RandomizedDelaySec=5m

[Install]
WantedBy=timers.target
```

### 5.8 `/opt/amigo-secreto/.env` (600, `ubuntu`, fora do Git)

Valores não sensíveis (o `.env` real é `gitignore`d; `git status` mostra apenas `?? backups/`):

```
POSTGRES_DB=amigo_secreto
POSTGRES_USER=amigo
POSTGRES_PASSWORD=<openssl rand -hex 32 — 64 chars, só no servidor>
DOMAIN=amigo-secreto.barbosalabs.dev
APP_IMAGE=ghcr.io/michaelmelobarbosa/amigo-secreto
APP_TAG=2b6e2d4fc13d98eb67cfcd4bd91899957a028206
EDGE_NETWORK=edge
RATE_LIMIT_ENABLED=true
RATE_LIMIT_EVENT_CREATE=5  RATE_LIMIT_JOIN=30  RATE_LIMIT_DRAW=10  RATE_LIMIT_WISHLIST=30
RATE_LIMIT_WINDOW=PT10M  RATE_LIMIT_DRAW_WINDOW=PT1H  RATE_LIMIT_MAX_KEYS=10000
EMAIL_ENABLED=false
EMAIL_WORKER_ENABLED=true
BREVO_API_KEY=            # vazio — e-mail desligado
BREVO_FROM_EMAIL=no-reply@mail.barbosalabs.dev
BREVO_FROM_NAME="Amigo Secreto"   # ← aspas obrigatórias (P10)
BREVO_REPLY_TO=
BREVO_SANDBOX=false
```

---

## 6. Ações externas executadas **pelo dono do projeto** (console)

| Onde | O que foi feito |
| --- | --- |
| Name.com (`barbosalabs.dev` — nameservers `ns1bqx/ns2nsw/ns3qtx/ns4ghm.name.com`) | Registro **A**: host `amigo-secreto` → `147.15.121.26`, TTL `300`. **Nenhum AAAA** criado (VPS não tem IPv6 global). MX/TXT/DKIM/DMARC do Brevo intocados. |
| OCI Console → VCN → Security Lists → `Default Security List` | 3 **ingress rules** stateful, source `0.0.0.0/0`, source port `All`: **TCP 80**, **TCP 443**, **UDP 443**. |

Verificação depois disso: `A` resolveu em Google DNS e Cloudflare (`147.15.121.26`); Caddy emitiu o
certificado em ~6 s após `docker compose restart caddy`
(`certificate obtained successfully`, issuer `acme-v02.api.letsencrypt.org-directory`).
Não foram abertas portas `5432`, `8080` ou `2019`; egress rules não foram alteradas.

---

## 7. Divergências entre os documentos do repositório e a realidade

| # | O que o doc dizia | O que foi encontrado / feito |
| --- | --- | --- |
| **D1** | "Hermes usa as portas 8080, 9119 e 9120" | Na prática **tudo em loopback**. Não há conflito com a app (que nem publica porta). |
| **D2** | `DEPLOY.md` sugere cron diário para o backup | **Não existe `cron`** na máquina. Feito com **systemd timer** (`Persistent=true`), equivalente e sem instalar pacote. |
| **D3** | "Se o pacote do GHCR for privado, faça `docker login` com `read:packages`" | O pacote é **público** (tags listadas e manifesto `arm64` lidos anonimamente). Nenhum login foi necessário. |
| **D4** | Docker "fura" o `iptables` do host | Confirmado, **mas** existe `FORWARD -j REJECT` persistido em `/etc/iptables/rules.v4`. Testado: o restore do `netfilter-persistent` **não** apaga as cadeias `DOCKER-USER`/`DOCKER-FORWARD` (apenas duplicou uma regra `REJECT`, removida). Os drop-ins de ordenação ficaram como cinto de segurança. |
| **D5** | `INFRA-VPS.md` manda limitar o banco (`mem_limit: 512m`) | O `docker-compose.prod.yml` **não** aplica limite no `db` (hoje vê os 11,65 GiB do host). Ver **P9**. |
| **D6** | `.env.prod.example` serve de modelo pronto | Ele **quebra** o `scripts/deploy.sh`, porque `BREVO_FROM_NAME` tem espaço sem aspas e o script faz `source .env`. Ver **P10**. |
| **D7** | `sites/*.caddy` documentado com `{$DOMAIN}` | Usado hostname **literal** — `{$DOMAIN}` não escala para a segunda app (um `.env` por stack). |
| **D8** | "⚠️ confirmar distro" / "2 vCPU / 2 GB mínimo" | Ubuntu 24.04.5, 2 vCPU, **11 GiB**. Folga confortável: hoje app 288 MiB + db 45 MiB + caddy 13 MiB. |

---

## 8. Pendências de código no repositório (para o agente de código)

> **Atualização posterior (2026-11-15):** P9, P10 e P11 foram aplicadas ao repositório; P12 foi
> incluída no template do stack de borda e precisa ser aplicada no `/opt/edge/compose.yml` da VPS.
> Ver [`DEPLOY.md#pendências-abertas-após-o-deploy`](./DEPLOY.md#pendências-abertas-após-o-deploy).
>
> **Atualização de 2026-11-16:** a API key de produção foi validada e o e-mail foi **ligado e
> validado ponta a ponta** (2 notificações `SENT`, painel **Enviado**, evento de teste apagado). Ver
> §14. O SPF foi publicado e o rate limit dos endpoints de e-mail ficou no código; resta o deploy da
> imagem do commit novo, que leva o rate limit.

### P9 — `db` sem limite de memória (divergência com o próprio `INFRA-VPS.md`)

`docker-compose.prod.yml`:

```yaml
  db:
    image: postgres:17-alpine
    restart: unless-stopped
    # ...
    mem_limit: 512m     # adicionar
    cpus: "0.5"         # opcional, coerente com o app (1.0)
```

Motivo: sem limite, um pico do Postgres compete com o app (1 GiB) e com o Hermes na mesma máquina de
11 GiB. O doc de infra já recomenda 512m; o código ficou sem.

### P10 — `.env.prod.example` quebra `scripts/deploy.sh` (reproduzido em produção)

Linha atual: `BREVO_FROM_NAME=Amigo Secreto`
Erro real: `bash: line 48: Secreto: command not found` → `set -e` aborta o deploy **antes** do `pull`.

Correções sugeridas:

1. `BREVO_FROM_NAME="Amigo Secreto"` no `.env.prod.example` (e no `.env.example`, se repetir o padrão).
2. Reforçar no cabeçalho do arquivo (a nota atual fala de "espaços… dentro dos valores", mas o próprio
   exemplo tem espaço): *"valores com espaço exigem aspas, porque `scripts/deploy.sh` carrega o arquivo
   com `source`"*.
3. Opcional: em `deploy.sh`, validar o `.env` com `env -i bash -n`/checagem simples e emitir mensagem
   clara em vez do erro cru do bash.

### P11 — `jsessionid` na URL quando o cliente não aceita cookie

Observado no fetch externo: links saem como `https://…/;jsessionid=C3836…`. Em navegador com cookie
não aparece, mas o rastreio por URL é vetor conhecido (fixation/vazamento em `Referer`).

Sugestão em `application.yaml`:

```yaml
server:
  servlet:
    session:
      tracking-modes: cookie   # remove o ;jsessionid= das URLs
```

### P12 (opcional) — `mem_limit`/healthcheck do Caddy e política de imagem

O `edge/compose.yml` (fora do repo) não tem `mem_limit` no Caddy. Se quiserem padronizar limites em
todos os serviços, vale um bloco análogo (ex.: `mem_limit: 256m`).

---

## 9. O que ficou de fora / não foi validado

| Item | Por quê | Como fechar |
| --- | --- | --- |
| **E-mail real (Brevo)** | ✅ Validado em 2026-11-16: 2 `SENT`, `attempts=1`, painel **Enviado**, evento apagado; SPF aplicado e rate limit no código (P15 leva à VPS) | Ver §14 |
| **Cópia off-site do backup** | Decisão do dono: "só na VPS por enquanto" | Backup no mesmo disco **não** protege contra perda da VPS. Restam 28 GB; `backups/` é `700` e a retenção é 14 dias |
| **Restore completo testado** | Só foi validada a integridade do dump (`pg_restore --list`) | Rodar `scripts/restore-postgres.sh` num banco descartável (procedimento do `DEPLOY.md#restore`) e depois um ciclo real |
| **Reboot da VPS** | Não foi reiniciada em produção nesta janela | Após o próximo reboot: conferir `docker ps` com os 3 containers `Up`, `iptables -S FORWARD` com `DOCKER-USER` no topo, `https://…` 200 e `systemctl list-timers` |
| **Fluxo funcional no navegador** | Validação foi HTTP/segurança + suíte do projeto (159 testes) | Criar um evento real de teste e apagar depois (o banco está em 0/0) |
| **Monitoração/uptime externo** | Fora do escopo pedido | Um monitor HTTP (ex.: Uptime Kuma/healthchecks.io) apontando para `/` com alerta |
| **`DOCKER-USER` com política restritiva** | Defesa em profundidade opcional do `DEPLOY.md#D6` | Criar regras de drop em `DOCKER-USER` liberando só 80/443 (lembrar que DNAT de porta publicada passa por `FORWARD`) |
| **`git clone` raso** | Foi usado `--depth 1` | Se precisarem de histórico/rollback por commit: `cd /opt/amigo-secreto && git fetch --unshallow` |
| **Imagens de teste no host** | `hello-world` e `alpine:3.20` ficaram para trás da validação | `docker image prune -a` com critério, ou `docker rmi hello-world alpine:3.20` |
| **Deploy automatizado (CD)** | O fluxo é manual por decisão do projeto (tag imutável + script) | Opcional: Action com `appleboy/ssh-action` rodando `scripts/deploy.sh` com `APP_TAG=$GITHUB_SHA` |
| **Atualização de imagens base** | `postgres:17-alpine`/`caddy:2-alpine` são flutuantes por tag | `docker compose pull` periódico + `docker image prune`; avaliar fixar digest |

> O e-mail real foi validado em 2026-11-16 via API/UI (§14). Os scripts `scripts/check-brevo.sh` e
> `scripts/smoke-email.sh` já foram adicionados ao repositório e podem ser usados na VPS após o
> deploy do commit que os contém; o procedimento está em [`EMAIL-BREVO.md`](./EMAIL-BREVO.md) §8.1/8.2.

---

## 10. Runbook de operação (na VPS, como `ubuntu`)

```bash
# ---- app / banco ------------------------------------------------------------
cd /opt/amigo-secreto

# deploy ou rollback (tag imutável = SHA do commit publicado pelo CI)
sg docker -c 'APP_TAG=<sha> bash scripts/deploy.sh'
# rollback: mesmo comando com o SHA anterior; banco com migração nova = restore do dump

# status / logs
sg docker -c 'docker compose -f docker-compose.prod.yml ps'
sg docker -c 'docker compose -f docker-compose.prod.yml logs --tail=200 app'
sg docker -c 'docker compose -f docker-compose.prod.yml logs -f app'

# auditoria de exposição (só o Caddy deve publicar portas)
sudo docker ps --format 'table {{.Names}}\t{{.Ports}}'
ss -lntup | grep -E ':(80|443|5432|8080|2019)\b'

# ---- borda (Caddy) ----------------------------------------------------------
cd /opt/edge
sudo docker compose -f compose.yml ps
sudo docker compose -f compose.yml logs --tail=100 caddy
sudo docker compose -f compose.yml exec caddy caddy validate --config /etc/caddy/Caddyfile
sudo docker compose -f compose.yml exec caddy caddy reload  --config /etc/caddy/Caddyfile
sudo docker compose -f compose.yml restart caddy     # força nova tentativa de ACME

# nova app na borda: criar sites/<host>.caddy com reverse_proxy <container>:<porta>
# (container na rede edge) e rodar o reload acima.

# certificado
echo | openssl s_client -connect 127.0.0.1:443 -servername amigo-secreto.barbosalabs.dev 2>/dev/null \
  | openssl x509 -noout -subject -issuer -enddate

# ---- backup ----------------------------------------------------------------
sudo systemctl start amigo-secreto-backup.service      # manual
journalctl -u amigo-secreto-backup.service -n 30       # log
systemctl list-timers amigo-secreto-backup.timer       # próxima execução
sudo ls -l /opt/amigo-secreto/backups/

# restore (para a app, restaura e sobe de novo)
cd /opt/amigo-secreto && sg docker -c 'bash scripts/restore-postgres.sh backups/<arquivo>.dump'

# ---- disco -----------------------------------------------------------------
df -h /; sudo docker system df
```

> Em sessão SSH nova o grupo `docker` já vale e o `sg docker -c '…'` é dispensável.

---

## 11. Segurança e riscos aceitos

- **`ubuntu` no grupo `docker` = root equivalente** (acesso ao socket). Aceito para operação single-tenant.
- **Docker fura o `iptables` do host**: a proteção real é a Security List da Oracle + a disciplina de
  não publicar portas. Hoje só o Caddy publica.
- **Tudo na rede `edge` alcança `amigo-app:8080`**: Docker não tem firewall por container. O banco está
  só na `amigo-secreto_internal`.
- **HSTS com `includeSubDomains`** está ativo: subdomínios *de* `amigo-secreto.barbosalabs.dev` ficam
  presos em HTTPS no navegador do usuário. Hosts irmãos (`outro.barbosalabs.dev`) não são afetados.
- **Sem access log no Caddy** (tokens no path) — decisão de privacidade mantida; qualquer telemetria
  futura precisa redigir a URI (`request>uri delete`), não só a query.
- **Rate limiting em memória** (por processo): reinício do container zera contadores.
- **Caddy é ponto único de falha** para todos os sites da borda; `restart: unless-stopped` mitiga.
- **Segredos**: `.env` da app em `600` (`ubuntu`), `.env` da borda em `600` (`root`), senha do banco
  gerada com `openssl rand -hex 32`. Nada foi escrito em documento, Git ou log.

---

## 12. Checklist de aceite (reproduzível)

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://amigo-secreto.barbosalabs.dev/          # 200
curl -sSI https://amigo-secreto.barbosalabs.dev/ | grep -i strict-transport-security      # HSTS
curl -sS -o /dev/null -w '%{http_code}\n' https://amigo-secreto.barbosalabs.dev/actuator/health  # 404
curl -sS https://amigo-secreto.barbosalabs.dev/robots.txt                                # Disallow: /
sudo docker ps --format '{{.Names}} {{.Ports}}'   # só edge-caddy-1 publica 80/443
sudo docker compose -f /opt/amigo-secreto/docker-compose.prod.yml ps                     # db e app healthy
systemctl list-timers amigo-secreto-backup.timer                                          # próxima 03:18
```

---

## 13. Referências

- `docs/DEPLOY.md`, `docs/INFRA-VPS.md`, `docs/RATE-LIMITING.md`, `docs/EMAIL-BREVO.md` (repo).
- Commit em produção no deploy de 2026-11-15: `2b6e2d4fc13d98eb67cfcd4bd91899957a028206` ("Merge pull
  request #13 from michaelmelobarbosa/dev"). No teste de e-mail de 2026-11-16, a VPS ainda estava
  nesse commit; o remoto já estava em `26bd7f4` (P9–P12), mas sem imagem publicada para esse SHA.
- CI: `.github/workflows/ci.yml` — job `image` publica `linux/arm64` com tag `:<sha>`; PR só testa.

---

## 14. Atualização 2026-11-16 — e-mail ligado e validado

Execução pelo Hermes, com autorização do dono.

| Passo | Resultado |
| --- | --- |
| Validação da chave antes do `.env` | `POST /v3/smtp/email` com `X-Sib-Sandbox: drop` → `201` + `messageId`; chave, remetente e domínio aceitos |
| Escopo da chave | `GET /v3/account` → `403 permission_denied`; chave **restrita a envio** (menor privilégio, esperado) |
| `.env` atualizado | `EMAIL_ENABLED=true`, `EMAIL_WORKER_ENABLED=true`, `BREVO_SANDBOX=false`, `BREVO_API_KEY` gravada; `600`; backup `.env.bak-20261116` também `600` |
| Container | `up -d app` → `amigo-app healthy`; dentro do container: `EMAIL_ENABLED=true`, `SANDBOX=false`, `FROM=no-reply@mail.barbosalabs.dev`, `KEY_LEN=89` |
| Teste ponta a ponta | Evento + 2 participantes com e-mail → `POST /draw` → worker do outbox |
| Outbox | `Ana Teste` (`michaelmelobarbosa+ana@gmail.com`) e `Bruno Teste` (`michaelmelobarbosa+bruno@gmail.com`): `SENT`, `attempts=1`, `provider_message_id` `<202611161300.56926194945@smtp-relay.mailin.fr>` / `<202611161300.34857283944@smtp-relay.mailin.fr>`, `enviado_em` 13:00:54/55 |
| Painel | **Enviado** nas duas linhas; zero erro/aviso do Brevo no log; nenhuma retentativa |
| Entrega | Os dois e-mails foram entregues de verdade (sandbox off) para `michaelmelobarbosa+ana@gmail.com` e `+bruno@gmail.com` |
| Limpeza | Evento apagado via `POST /admin/{token}/delete` → `302`; token agora `404`; banco em 0 eventos, 0 participantes, 0 notificações, 0 assignments |
| Segredos | `/tmp/brevo.key`, cookies e respostas da API removidos |

### 14.1 Correções e pendências

- **Scripts:** `scripts/check-brevo.sh` e `scripts/smoke-email.sh` **não estavam no remoto** no
  momento do teste; o Hermes fez o equivalente via API/UI. Os scripts foram adicionados ao
  repositório depois e podem ser usados na VPS após o deploy do commit que os contém.
- **SPF:** publicado em 2026-11-16 em `mail.barbosalabs.dev` (`v=spf1 include:spf.sendinblue.com
  -all`) e verificado no DNS público. O `brevo-code` continua no mesmo nome, como segundo TXT. DKIM
  (`brevo1`/`brevo2`) e DMARC (`p=none`, `rua@dmarc.brevo.com`) já estavam corretos.
- **Rate limit:** `POST /admin/{token}/links/email` (10/h) e
  `POST /admin/{token}/participants/{id}/email/resend` (20/h) foram incluídos no `RateLimitFilter`
  em 2026-11-16, com janela de 1h e baldes separados, para proteger a cota de 300/dia e a reputação
  do domínio. Entram em produção com o deploy da imagem nova (P15).
- **Imagem/deploy:** a VPS roda `2b6e2d4`; o remoto está em `26bd7f4` (P9–P12) e ainda não havia
  imagem publicada para esse SHA. Quando o CI publicar, o deploy é
  `APP_TAG=<novo-sha> bash scripts/deploy.sh`.
- **Cota:** 300 e-mails/dia no plano grátis. Um sorteio de 100 participantes usa 100; cada refazer
  usa mais 100 — dois refazeres no mesmo dia estouram a cota.
- **Testes seguros:** `BREVO_SANDBOX=true` volta a ser o modo de testar sem entregar; o
  `check-brevo.sh` envia com `X-Sib-Sandbox: drop` nesse modo.
