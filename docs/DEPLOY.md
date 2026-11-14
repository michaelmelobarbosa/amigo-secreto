# Deploy — Amigo Secreto

Guia de operação em VPS Linux: Docker Compose (app + PostgreSQL) atrás do Caddy com TLS automático.
O Postgres e a aplicação **não** publicam portas no host; só o Caddy fica exposto.

## Arquivos de operação

| Arquivo | Papel |
| --- | --- |
| `Dockerfile` | Build multi-stage (JDK 25 → JRE 25), usuário não-root, `HEALTHCHECK` no actuator |
| `.dockerignore` | Mantém fora da imagem `target/`, `.git/`, `.env*`, docs, scripts e Compose |
| `docker-compose.prod.yml` | `db` + `app` + `caddy`, rede interna, volumes nomeados |
| `Caddyfile` | TLS, compressão, HSTS, sem access log |
| `.env.prod.example` | Modelo do `.env` de produção (nunca commitar o `.env` real) |
| `scripts/backup-postgres.sh` | `pg_dump -Fc` com retenção de 14 dias |
| `scripts/restore-postgres.sh` | `pg_restore --clean --if-exists` com confirmação |

## Pré-requisitos

- VPS Linux (Ubuntu 24.04 LTS ou similar), 2 vCPU / 2 GB de RAM no mínimo.
- Docker Engine 27+ com o plugin Compose v2 (`docker compose version`).
- Domínio com registro **A** apontando para o IP da VPS (e **AAAA** se houver IPv6).
- Portas **80/tcp**, **443/tcp** e **443/udp** liberadas no firewall (80 é usada pelo desafio ACME; 443/udp habilita HTTP/3).
- Git instalado na VPS.

Instalação do Docker (Ubuntu):

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # reabra a sessão depois
```

## Passo a passo do primeiro deploy

1. **Clonar o projeto**

   ```bash
   sudo mkdir -p /opt/amigo-secreto
   sudo chown "$USER":"$USER" /opt/amigo-secreto
   git clone <url-do-repositorio> /opt/amigo-secreto
   cd /opt/amigo-secreto
   ```

2. **Criar o `.env`**

   ```bash
   cp .env.prod.example .env
   chmod 600 .env
   # edite POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD, DOMAIN e ACME_EMAIL
   ```

   Gere a senha do banco com `openssl rand -hex 32` (evite `$`, espaços, `#` e `=` nos valores).
   `DOMAIN` é só o host, sem esquema e sem barra final (`amigo.exemplo.com`).

   > `.env` e `.env.*` estão no `.gitignore`; `.env.prod.example` é versionado e serve de modelo.

3. **Conferir a configuração resolvida**

   ```bash
   docker compose -f docker-compose.prod.yml config
   ```

   O comando imprime a configuração final (com as variáveis do `.env`). Não copie essa saída para lugar público.

4. **Subir**

   ```bash
   docker compose -f docker-compose.prod.yml up -d --build
   ```

   Ordem esperada: `db` sobe e fica `healthy`, `app` compila/baixa a imagem, o Flyway aplica as migrações e o healthcheck fica `healthy`, depois o Caddy pede o certificado.

5. **Conferir saúde e logs**

   ```bash
   docker compose -f docker-compose.prod.yml ps
   docker compose -f docker-compose.prod.yml logs --tail=200 app
   docker compose -f docker-compose.prod.yml logs --tail=100 caddy
   ```

   `ps` deve mostrar `db (healthy)`, `app (healthy)` e `caddy (running)`.

6. **Testar HTTPS**

   ```bash
   curl -sS -o /dev/null -w '%{http_code}\n' https://amigo.exemplo.com/
   curl -sSI https://amigo.exemplo.com/ | grep -i strict-transport-security
   curl -sS https://amigo.exemplo.com/robots.txt
   ```

   Esperado: `200`, o header HSTS presente e `robots.txt` com `Disallow: /`.
   `/actuator/*` responde **404** pela internet de propósito; o healthcheck roda dentro do container.

7. **Confirmar que o Flyway aplicou do zero**

   ```bash
   docker compose -f docker-compose.prod.yml exec db \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank;"'
   ```

   O schema é do Flyway (`ddl-auto=validate`). Migração aplicada não se edita: crie a próxima versão.

## Backup

```bash
cd /opt/amigo-secreto
./scripts/backup-postgres.sh
```

Gera `backups/amigo-secreto-AAAAMMDD-HHMMSS.dump` (`pg_dump -Fc`), com permissão `600`, e apaga dumps com mais de 14 dias (`RETENTION_DAYS` sobrescreve).

Cron sugerido (backup diário às 03:15), rodando como um usuário do grupo `docker`:

```cron
15 3 * * * cd /opt/amigo-secreto && ./scripts/backup-postgres.sh >> /var/log/amigo-secreto-backup.log 2>&1
```

- Mantenha cópia fora da VPS (ex.: `rclone`/`rsync` para storage externo); backup no mesmo disco não protege contra perda da VPS.
- Teste o restore periodicamente — backup nunca testado não é backup.
- Monitore espaço em disco: a retenção é de 14 dias, mas o crescimento depende do uso.

## Restore

```bash
cd /opt/amigo-secreto
./scripts/restore-postgres.sh backups/amigo-secreto-20260101-030000.dump
```

O script:

1. valida que o arquivo existe, é legível e não está vazio;
2. confere que o dump é válido (`pg_restore --list`);
3. pede confirmação (`--yes` para execução não interativa);
4. **para o container `app`**, roda `pg_restore --clean --if-exists` e sobe a app de novo;
5. em caso de falha, deixa a app parada — corrija o erro e rode `docker compose -f docker-compose.prod.yml start app`.

Depois do restore, confira a versão do schema: um dump antigo pode deixar o Flyway atrás do código
em execução. Se a aplicação não subir, o log aponta a migração pendente/divergente.

Para um teste de restore sem risco, suba um banco descartável e restaure nele antes de tocar em produção:

```bash
docker run --rm -d --name restore-test -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=amigo_secreto -e POSTGRES_USER=amigo postgres:17-alpine
until docker exec restore-test pg_isready -U amigo -d amigo_secreto; do sleep 1; done
docker exec -i restore-test pg_restore --clean --if-exists --no-owner -U amigo -d amigo_secreto < backups/arquivo.dump
docker rm -f restore-test
```

## Atualização (deploy novo)

Antes de enviar a mudança, rode os testes na sua máquina (`.\mvnw.cmd test` no Windows, `./mvnw test`
no Linux). A imagem usa `-DskipTests`, então os testes não rodam no servidor.

```bash
cd /opt/amigo-secreto
git pull
docker tag amigo-secreto:latest amigo-secreto:pre-deploy   # opcional: ponto de rollback da imagem
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=100 app
```

Regras:

- Só faça deploy com os testes verdes (`.\mvnw.cmd test` no Windows, `./mvnw test` no Linux).
- Em banco vazio, valide o Flyway aplicando do zero antes de anunciar a versão.
- Depois do `up`, confira `ps` e o health; se a app não ficar `healthy`, leia `logs app`.
- `docker compose up -d --build` recria apenas o que mudou; o volume `pgdata` fica intacto.

## Rollback

- **Aplicação:** volte para o commit anterior e reconstrua.

  ```bash
  git checkout <commit-ou-tag-anterior>
  docker compose -f docker-compose.prod.yml up -d --build
  ```

  Como o build é local, o rollback é por código. Se você tagueou a imagem antes do deploy
  (`amigo-secreto:pre-deploy`), dá para voltar rápido com
  `docker tag amigo-secreto:pre-deploy amigo-secreto:latest` e `up -d`.

- **Banco:** o Flyway não reverte migração. Rollback de schema = restaurar o dump anterior com
  `scripts/restore-postgres.sh`. Por isso, faça backup antes de todo deploy que traga migração nova.

## Notas de segurança

- **HTTPS obrigatório.** Não publique a app em HTTP; o Caddy redireciona e emite o certificado.
- **Tokens nas URLs.** Painel/participante/convite são acessados por link com token: nunca habilite
  access log no proxy (o `Caddyfile` não tem `log`) nem log de requisição na aplicação.
- **Actuator interno.** Só `health` é exposto (ver `application.yaml`), sem detalhes; o `Caddyfile`
  responde 404 para `/actuator/*` e o container não publica porta no host.
- **Postgres sem porta pública.** Só o Caddy publica portas; `db` e `app` usam `expose`/rede interna.
- **`.env` fora do Git**, com `chmod 600`. Senha do banco com 32+ bytes aleatórios.
- **`SESSION_COOKIE_SECURE=true`** e `APP_BASE_URL=https://<domínio>` (sem barra final) já vêm do
  Compose; se mudar o domínio, atualize o `.env` e recrie a app (`up -d app`).
- **`forward-headers-strategy=framework`** já está no `application.yaml`, para os links gerados
  usarem `https://` atrás do proxy.
- **`robots.txt`** bloqueia tudo (`Disallow: /`); não remova.
- **Sem cache de proxy:** o Caddy não configura `Cache-Control` nem cache de disco; respostas
  dinâmicas seguem o que a aplicação envia. Não adicione `header Cache-Control` no proxy.
- **Firewall:** libere apenas 22 (restrinja por IP se possível), 80 e 443.
- Atualize a imagem base e as dependências com regularidade; rode `docker compose ... pull` para
  renovar `postgres` e `caddy`.

## Problemas comuns

| Sintoma | Verificação |
| --- | --- |
| `app` reinicia em loop | `logs app`: conexão com `db`, credenciais do `.env`, Flyway |
| `app` unhealthy | `docker compose -f docker-compose.prod.yml exec app curl -fsS http://localhost:8080/actuator/health` |
| Caddy não emite certificado | DNS aponta para a VPS? Portas 80/443 abertas? `logs caddy` |
| `502 Bad Gateway` | `app` está `healthy`? `docker compose -f docker-compose.prod.yml ps` |
| `up` falha por variável vazia | `.env` ausente ou incompleto; rode `docker compose -f docker-compose.prod.yml config` |
| Senha do banco mudou no `.env` | A senha só é aplicada na criação do volume; em banco existente, altere via `psql` |

## O que ainda não foi validado

O `docker` não está instalado na máquina de desenvolvimento, então o build da imagem, a subida do
Compose e o healthcheck foram escritos e revisados, mas **não executados**. No primeiro deploy,
valide com `docker compose -f docker-compose.prod.yml config`, depois `up -d --build` e confira
`ps`/`logs` antes de divulgar o link.
