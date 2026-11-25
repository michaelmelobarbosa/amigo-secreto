# Deploy e operação

Guia reproduzível para colocar o Amigo Secreto em uma VPS Linux com Docker Compose. A aplicação e o PostgreSQL rodam no Compose do projeto; um **Caddy de borda** separado publica 80/443 e faz TLS automático. Nenhum container deste repositório publica portas no host.

> Dados reais da instância de produção (IP, usuário, provedor, caminhos e histórico) ficam em um documento local não versionado, por segurança. Este guia usa placeholders.

## Topologia

```
Internet ──▶ Caddy de borda :80/:443 (TLS, HSTS, HTTP/3)
              │  stack "edge", rede externa "edge"
              ├──▶ amigo-app:8080  ──▶ amigo-secreto-db-1:5432
              │     redes: amigo-secreto_internal + edge
              └──▶ outras aplicações na mesma borda
```

| Recurso | Valor padrão |
| --- | --- |
| Projeto Compose | `amigo-secreto` |
| App | container `amigo-app`, `expose: 8080` |
| Banco | serviço `db`, rede privada, volume `pgdata` |
| Borda | stack `edge`, container `edge-caddy-1` |
| Imagem | `ghcr.io/<owner>/amigo-secreto:<sha>` |

### Um Postgres por projeto

Cada aplicação da VPS tem seu próprio container `db` e volume. Isso isola backup/restore, versão do Postgres, credenciais, locks e blast radius. O custo (~60–120 MB de RAM e disco por instância) é irrelevante em uma VPS de 8–12 GiB. Não compartilhe um cluster entre aplicações; se um dia isso for necessário, use um *database* por app, nunca um schema compartilhado.

## Decisões de arquitetura

| # | Decisão | Motivo |
| --- | --- | --- |
| D1 | Dois containers (`app` + `db`), Caddy em stack separado | isola o banco, permite deploy independente e centraliza TLS para N apps |
| D2 | Nenhum container do projeto publica porta | superfície mínima; o firewall do host não protege porta de container |
| D3 | Imagem ARM64 vem do registry, não é compilada na VPS | a VPS é pequena; build local gasta disco e diverge do CI |
| D4 | `mem_limit` explícito na JVM e no banco | sem limite, o container enxerga a RAM do host e um pico pode derrubar a VPS |
| D5 | Rotação de log do Docker (`10m` × `3`) | `json-file` cresce sem teto por padrão |
| D6 | Firewall de nuvem é o portão real | Docker insere DNAT/FORWARD e ignora o `INPUT` do host |
| D7 | Compose v2 | os scripts usam `docker compose`, não `docker-compose` |
| D8 | Tag imutável por SHA | deploy e rollback determinísticos; nunca `latest` |

## Pré-requisitos

- VPS Linux **ARM64** (ou ajuste a plataforma no CI) com Docker Engine e Compose v2.
- Recomendado: 2 vCPU, 4 GB de RAM e 20 GB de disco livres.
- Domínio com registro **A** apontando para a VPS. Se a VPS não tiver IPv6 global, não crie AAAA.
- Firewall de nuvem liberando **TCP 80**, **TCP 443** e **UDP 443**; SSH restrito por IP quando possível.
- Rede externa `edge` e stack de borda com o site desta aplicação.
- Acesso ao GHCR; o pacote é público por padrão, então não é necessário `docker login`.
- Git na VPS. Um clone raso funciona; para rollback por commit, use histórico completo.

## Variáveis de ambiente

Copie o exemplo e edite:

```bash
cp .env.prod.example .env
chmod 600 .env
```

| Variável | Obrigatória | Descrição |
| --- | --- | --- |
| `POSTGRES_DB` | sim | nome do banco |
| `POSTGRES_USER` | sim | usuário do banco |
| `POSTGRES_PASSWORD` | sim | senha longa; gere com `openssl rand -hex 32` |
| `DOMAIN` | sim | host público, sem esquema e sem barra final |
| `APP_IMAGE` | sim | repositório da imagem no GHCR |
| `APP_TAG` | sim | SHA do commit publicado pelo CI |
| `EDGE_NETWORK` | sim | rede externa compartilhada (`edge`) |
| `RATE_LIMIT_*` | não | limites por IP; padrões em [SECURITY.md](SECURITY.md) |
| `EMAIL_ENABLED` e `BREVO_*` | não | envio transacional; veja [EMAIL.md](EMAIL.md) |

> **Aspas em valores com espaço.** Os scripts carregam o `.env` com `source`. Use
> `BREVO_FROM_NAME="Amigo Secreto"`; sem aspas, o deploy aborta com `Secreto: command not found`.

O `.env` real nunca vai para o Git. `.env.prod.example` é o modelo versionado.

## Imagem e CI

O workflow do GitHub Actions tem dois jobs:

- `test`: roda a suíte em todo push e PR;
- `image`: em push para a branch principal, publica `linux/arm64` no GHCR com a tag do SHA.

PRs não publicam imagem. O deploy só funciona depois que o job `image` do SHA terminar. O Dockerfile usa `$BUILDPLATFORM` no build e `$TARGETPLATFORM` no runtime, sem QEMU; o HEALTHCHECK usa um BusyBox estático copiado de uma stage ARM64.

```yaml
app:
  image: ${APP_IMAGE}:${APP_TAG}
  pull_policy: always
```

Se o pacote do GHCR virar privado, a VPS precisa de `docker login ghcr.io` com um token de leitura (`read:packages`). Se o push falhar com 403, confira **Settings → Actions → General → Workflow permissions**.

## Primeiro deploy

1. **Clonar o projeto**

   ```bash
   sudo mkdir -p /opt/amigo-secreto
   sudo chown "$USER":"$USER" /opt/amigo-secreto
   git clone https://github.com/<owner>/amigo-secreto.git /opt/amigo-secreto
   cd /opt/amigo-secreto
   ```

2. **Criar o `.env`** conforme a seção anterior.

3. **Conferir a rede de borda**

   ```bash
   set -a; source .env; set +a
   docker network ls | grep -w "${EDGE_NETWORK:-edge}" \
     || docker network create "${EDGE_NETWORK:-edge}"
   ```

   O Caddy de borda precisa estar de pé e publicando 80/443 **antes** deste deploy.

4. **Validar a configuração resolvida**

   ```bash
   docker compose -f docker-compose.prod.yml config
   ```

   Confirme `image`, `APP_TAG`, `mem_limit`, `cpus`, `logging`, a rede `edge` externa e a ausência de `ports` em `app`/`db`. Não copie essa saída para lugar público: ela contém o `.env` resolvido.

5. **Subir**

   ```bash
   APP_TAG=<sha-do-commit> bash scripts/deploy.sh
   ```

   O script valida o `.env` e a rede, faz `pull`, `up -d --remove-orphans` e espera o healthcheck de `amigo-app`. Equivalente manual:

   ```bash
   docker compose -f docker-compose.prod.yml pull
   docker compose -f docker-compose.prod.yml up -d --remove-orphans
   ```

6. **Conferir saúde e logs**

   ```bash
   docker compose -f docker-compose.prod.yml ps
   docker compose -f docker-compose.prod.yml logs --tail=200 app
   ```

   Esperado: `db` e `app` `healthy`, Flyway aplicado e nenhum `ERROR`/`WARN` no arranque.

7. **Testar HTTPS e headers**

   ```bash
   curl -sS -o /dev/null -w '%{http_code}\n' "https://$DOMAIN/"
   curl -sSI "https://$DOMAIN/" | grep -i strict-transport-security
   curl -sS "https://$DOMAIN/robots.txt"
   for p in /actuator /actuator/health '/actuator;x' '/actuator;x/health' '/admin;x/adm_inexistente'; do
     printf '%-28s -> ' "$p"
     curl -sS --path-as-is -o /dev/null -w '%{http_code}\n' "https://$DOMAIN$p"
   done
   ```

   Esperado: `200`, HSTS presente, `Disallow: /` e **404** em todas as variações de `/actuator` e caminhos com `;`.

8. **Confirmar o Flyway**

   ```bash
   docker compose -f docker-compose.prod.yml exec db \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
     -c "select version, description, success from flyway_schema_history order by installed_rank;"'
   ```

9. **Auditar portas e disco**

   ```bash
   docker ps --format 'table {{.Names}}\t{{.Ports}}'
   df -h /
   docker system df
   ```

   Só o Caddy da borda publica 80/443. `app` e `db` aparecem apenas com portas internas.

## Atualização

```bash
cd /opt/amigo-secreto
git pull --ff-only

# a imagem do SHA precisa existir no GHCR (CI concluído)
APP_TAG=<sha-do-commit> bash scripts/deploy.sh
```

Regras:

- Só faça deploy com a suíte verde.
- Use o SHA do merge, não `latest`.
- `up -d` recria apenas o que mudou; o volume do banco permanece.
- Se o grupo `docker` não valer na sessão, use `sg docker -c '...'` ou reabra a sessão.
- O Caddy de borda não reinicia neste deploy.

## Rollback

**Aplicação:** rode o script com o SHA anterior.

```bash
APP_TAG=<sha-anterior> bash scripts/deploy.sh
```

**Banco:** o Flyway não reverte migração. Rollback de schema é restore do dump anterior. Faça backup antes de todo deploy que traga migração nova.

**Borda:** o stack é separado; reverta o `compose.yml`/`sites/*.caddy` e rode o reload do Caddy.

## Backup

```bash
cd /opt/amigo-secreto
bash scripts/backup-postgres.sh
```

O script gera `backups/amigo-secreto-AAAAMMDD-HHMMSS.dump` com `pg_dump -Fc`, permissão `600`, e remove dumps com mais de 14 dias (`RETENTION_DAYS` sobrescreve).

Se a VPS não tiver `cron`, use um systemd timer. Substitua `<deploy-user>` pelo usuário dono do clone e do `.env`:

```ini
# /etc/systemd/system/amigo-secreto-backup.service
[Unit]
Description=Backup do PostgreSQL do Amigo Secreto
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
User=<deploy-user>
Group=<deploy-user>
WorkingDirectory=/opt/amigo-secreto
Environment=RETENTION_DAYS=14
ExecStart=/usr/bin/bash /opt/amigo-secreto/scripts/backup-postgres.sh
Nice=10
IOSchedulingClass=best-effort
IOSchedulingPriority=7
```

```ini
# /etc/systemd/system/amigo-secreto-backup.timer
[Unit]
Description=Backup diário do PostgreSQL do Amigo Secreto

[Timer]
OnCalendar=*-*-* 03:15:00
Persistent=true
RandomizedDelaySec=5m

[Install]
WantedBy=timers.target
```

```bash
sudo systemctl enable --now amigo-secreto-backup.timer
systemctl list-timers amigo-secreto-backup.timer
```

### Cópia off-site

O dump local não protege contra perda da VPS. `scripts/backup-offsite.sh` publica o backup
criptografado em **dois** remotes [rclone](https://rclone.org/):

```bash
bash scripts/backup-offsite.sh              # gera dump novo e publica
OFFSITE_SKIP_BACKUP=1 bash scripts/backup-offsite.sh   # publica o dump mais recente
```

1. **Staging local** (`offsite-crypt`): diretório na própria VPS; origem do pull de outra máquina.
2. **Remote 24/7** (`offsite-r2`): object storage S3-compatible (ex.: Cloudflare R2, B2, S3); a cópia
   sai da VPS minutos após o backup, sem depender de outro computador ligado.

Regras do script:

- Confere o `sha256` lendo cada arquivo de volta antes de considerar sucesso.
- `OFFSITE_RETENTION_DAYS` (padrão 30) remove cópias antigas nos dois remotes.
- `OFFSITE_SIZE_LIMIT_BYTES` (padrão 2 GB) falha o job se o remote 24/7 passar do teto — protege
  contra runaway antes de estourar o plano grátis.
- `OFFSITE_HEARTBEAT_FILE` (padrão `~/.config/amigo-secreto/heartbeat-url`) faz um ping de sucesso
  para um dead-man's switch (ex.: healthchecks.io); sem ping, o serviço externo alerta.

Guarde a senha do remote `crypt` fora da VPS; sem ela o backup é irrecuperável. Um timer diário pode
rodar o script logo após o backup local.

Se o destino for manual, copie o diretório de staging criptografado para outra máquina e valide a
decriptação com a mesma chave. Uma cópia baixada manualmente só é off-site depois de sair da VPS.

Boas práticas:

- Mantenha uma cópia **fora da VPS** (`rclone`/`rsync`). Backup no mesmo disco não protege contra perda da máquina.
- Teste o restore periodicamente; backup nunca testado não é backup.
- Monitore o espaço em disco: imagens, logs e dumps competem.

## Restore

```bash
cd /opt/amigo-secreto
bash scripts/restore-postgres.sh backups/<arquivo>.dump
```

O script valida o arquivo e o dump, pede confirmação (`--yes` para não interativo), para o container `app`, roda `pg_restore --clean --if-exists` e sobe a app novamente. Em falha, a app fica parada; corrija e rode `docker compose -f docker-compose.prod.yml start app`.

Teste sem risco em um banco descartável:

```bash
docker run --rm -d --name restore-test \
  -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=amigo_secreto -e POSTGRES_USER=amigo \
  postgres:17-alpine
until docker exec restore-test pg_isready -U amigo -d amigo_secreto; do sleep 1; done
docker exec -i restore-test pg_restore --clean --if-exists --no-owner \
  -U amigo -d amigo_secreto < backups/<arquivo>.dump
docker rm -f restore-test
```

## Caddy de borda

O Caddy vive fora deste repositório, em um stack compartilhado que roteia todas as aplicações da VPS por hostname.

### Compose da borda

```yaml
# /opt/edge/compose.yml
name: edge

services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    mem_limit: 256m
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    environment:
      ACME_EMAIL: ${ACME_EMAIL:?defina ACME_EMAIL}
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

O `.env` da borda guarda **apenas** o `ACME_EMAIL`, em `600`.

### Caddyfile

```caddyfile
{
    email {$ACME_EMAIL}
    # Sem "log" aqui: log global configura loggers, não liga access log.
}

import sites/*.caddy
```

### Site da aplicação

```caddyfile
# /opt/edge/sites/amigo-secreto.caddy
amigo-secreto.barbosalabs.dev {
    encode zstd gzip

    @actuator path_regexp actuator ^/actuator
    respond @actuator 404

    reverse_proxy amigo-app:8080 {
        header_up -Forwarded
        header_up -X-Forwarded-Prefix
    }

    header {
        -Server
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
    }

    # SEM "log": as URLs carregam tokens de acesso no path.
}
```

O `reverse_proxy` remove `Forwarded` (RFC 7239) e `X-Forwarded-Prefix` vindos do cliente para que não passem para a app, atuando em conjunto com `server.forward-headers-strategy: native` (que lê apenas `X-Forwarded-For` e `X-Forwarded-Proto` de proxies internos). O matcher `@actuator path_regexp actuator ^/actuator` atua como defesa em profundidade na borda: na aplicação, o Actuator já escuta apenas em `127.0.0.1:8081` e `MatrixParameterRejectionFilter` responde `404` para qualquer caminho contendo `;`.

Na prática, o arquivo usa o **hostname literal** em vez de `{$DOMAIN}`: um `.env` por stack não comporta vários domínios. Nova aplicação = novo arquivo em `sites/` + reload.

### Operação da borda

```bash
cd /opt/edge
sudo docker compose -f compose.yml ps
sudo docker compose -f compose.yml logs --tail=100 caddy
sudo docker compose -f compose.yml exec caddy caddy validate --config /etc/caddy/Caddyfile
sudo docker compose -f compose.yml exec caddy caddy reload --config /etc/caddy/Caddyfile
sudo docker compose -f compose.yml restart caddy
```

- `sites/` pertence ao deploy/operador, não à aplicação; monte `:ro` e restrinja a escrita.
- A admin API (`:2019`) nunca é publicada.
- `caddy_data` guarda certificados: precisa persistir. Recriar sem o volume força reemissão e pode bater no rate limit da Let's Encrypt.
- O Caddy é ponto único de falha para todos os sites; `restart: unless-stopped` mitiga, não elimina.

## Configuração externa

O que não vive no repositório:

| Item | Onde | Observação |
| --- | --- | --- |
| DNS | provedor do domínio | registro A para a VPS; sem AAAA se não houver IPv6 |
| Firewall de nuvem | console do provedor | TCP 80, TCP 443 e UDP 443; SSH restrito |
| Stack de borda | `/opt/edge` | Caddy, sites e certificados; fora deste repositório |
| GHCR | GitHub | pacote público por padrão; CI com `packages: write` |
| `.env` da app | servidor | `600`, fora do Git, senha gerada com `openssl rand -hex 32` |
| Brevo | painel do provedor | domínio autenticado, sender e API key restrita a envio |
| Backup | systemd timer | conteúdo do dump fora do Git; cópia off-site recomendada |

Não abra `5432`, `8080` nem `2019`. Não coloque segredos em `README`, `docs/` ou issues.

## Runbook

```bash
# ---- aplicação e banco ----
cd /opt/amigo-secreto
APP_TAG=<sha> bash scripts/deploy.sh
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=200 app
docker compose -f docker-compose.prod.yml logs -f app

# auditoria de exposição
sudo docker ps --format 'table {{.Names}}\t{{.Ports}}'
ss -lntup | grep -E ':(80|443|8080|5432|2019)\b'

# ---- backup ----
sudo systemctl start amigo-secreto-backup.service
journalctl -u amigo-secreto-backup.service -n 30
systemctl list-timers amigo-secreto-backup.timer
sudo ls -l /opt/amigo-secreto/backups/

# ---- restore ----
bash scripts/restore-postgres.sh backups/<arquivo>.dump

# ---- disco ----
df -h /
sudo docker system df
```

## Notas de segurança

- **HTTPS obrigatório.** A app nunca é publicada em HTTP.
- **Tokens nas URLs.** Não habilite access log no proxy nem log de requisição: o token está no path. Se precisar de telemetria, redija a URI inteira (`request>uri delete`).
- **Sem porta publicada** em `app`/`db`; o banco fica em rede privada.
- **Containers endurecidos:** `app` roda com usuário não-root, `read_only: true`, `tmpfs` em `/tmp`, `cap_drop: [ALL]` e `no-new-privileges:true`; `db` também usa `no-new-privileges:true`.
- **Rate limiting** em memória por IP; detalhes em [SECURITY.md](SECURITY.md).
- **HSTS com `includeSubDomains`** está ativo: subdomínios do host ficam presos em HTTPS.
- **`db` limitado** (`mem_limit: 512m`, `cpus: "0.5"`); a app usa `1g`/`1.0`.
- **Secrets** apenas no `.env` do servidor, com `600`. Nunca no Git, na imagem ou em log.
- **Usuário no grupo `docker` equivale a root** na máquina. Aceito em operação single-tenant.
- **Rede `edge`:** qualquer container dela alcança `amigo-app:8080` e pode forjar `X-Forwarded-*`; o banco não está na borda.
- **Atualize as imagens base** periodicamente (`docker compose pull`); avalie fixar digest.

## Problemas comuns

| Sintoma | Verificação |
| --- | --- |
| `exec format error` | imagem amd64; use a imagem ARM64 publicada pelo CI |
| `app` reinicia em loop | `logs app`: conexão com `db`, credenciais e Flyway |
| `app` unhealthy | `docker compose ... exec app /bin/busybox wget -q -O - http://127.0.0.1:8081/actuator/health` |
| Caddy não emite certificado | DNS aponta para a VPS? 80/443 liberadas no firewall de nuvem? `logs caddy` |
| `502 Bad Gateway` | `amigo-app` está healthy e na rede `edge`? |
| `network edge not found` | crie a rede externa e suba a borda |
| `up` falha por variável vazia | `.env` ausente/incompleto; rode `docker compose config` |
| `Secreto: command not found` | valor com espaço sem aspas no `.env` |
| `crontab: command not found` | use o systemd timer de backup |
| `;jsessionid=` na URL | confira `session.tracking-modes: cookie` |
| `db` usando RAM do host | confira `mem_limit: 512m` no Compose |
| `permission denied` no socket Docker | reabra a sessão ou use `sg docker -c '...'` |
| Senha do banco mudou | a senha só é aplicada na criação do volume; altere via `psql` |
| `port is already allocated` | 80/443 pertencem ao Caddy da borda |
| Disco enchendo | `docker system df`, `docker image prune`, `du -sh backups` |
| Container morre por OOM | confira `mem_limit` e `OOMKilled` no `docker inspect` |

## Checklist de aceite

- [ ] `docker compose -f docker-compose.prod.yml config` sem erro.
- [ ] `db` e `app` `healthy`.
- [ ] Flyway aplicado; `ddl-auto=validate` passou.
- [ ] `GET /` retorna `200`; HSTS presente.
- [ ] `/actuator/health` e `/actuator;x/health` retornam `404` pela borda.
- [ ] `robots.txt` com `Disallow: /`.
- [ ] HTML sem `;jsessionid`.
- [ ] Só o Caddy da borda publica 80/443.
- [ ] Backup manual gera dump válido.
- [ ] Logs de arranque sem `ERROR`/`WARN`.
