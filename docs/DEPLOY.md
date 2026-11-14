# Deploy — Amigo Secreto

Guia de operação em VPS Linux: Docker Compose (`app` + PostgreSQL) atrás do **Caddy** com TLS
automático. O Postgres e a aplicação **não** publicam portas no host; só o Caddy fica exposto.

> **Origem das informações sobre a VPS.** O levantamento de hardware, sistema, portas e firewall foi
> feito pelo **Hermes**, agente instalado na própria VPS, em 2026-11-14, e cruzado com o repositório.
> Onde o dado não pôde ser confirmado por comando, está marcado com **⚠️ confirmar**.

> **Documento complementar.** A decisão de desacoplar o Caddy deste projeto, a topologia para uma
> segunda aplicação na mesma VPS e a política de logs por aplicação estão em
> [`INFRA-VPS.md`](./INFRA-VPS.md). Este arquivo cobre o deploy desta aplicação.

## Sumário

- [Resumo executivo](#resumo-executivo)
- [Ambiente da VPS](#ambiente-da-vps)
- [Decisões de arquitetura (com justificativa)](#decisões-de-arquitetura-com-justificativa)
- [Pré-requisitos](#pré-requisitos)
- [Instalação do Docker](#instalação-do-docker)
- [Firewall e exposição de portas](#firewall-e-exposição-de-portas)
- [Primeiro deploy](#primeiro-deploy)
- [Atualização](#atualização)
- [Rollback](#rollback)
- [Backup](#backup)
- [Restore](#restore)
- [Notas de segurança](#notas-de-segurança)
- [Problemas comuns](#problemas-comuns)
- [Pendências no código antes de executar](#pendências-no-código-antes-de-executar)
- [Checklist para quem for configurar a VPS](#checklist-para-quem-for-configurar-a-vps)
- [O que ainda não foi validado](#o-que-ainda-não-foi-validado)

## Resumo executivo

- **Três containers** em produção: `db` (Postgres 17), `app` (Spring Boot) e `caddy` (borda). Não é
  um container único; o porquê está em [Decisões](#decisões-de-arquitetura-com-justificativa).
- **Só o Caddy publica portas**: `80/tcp`, `443/tcp` e `443/udp`. `app` usa `expose: 8080` e `db` não
  tem `ports`.
- **A VPS é ARM64.** Todas as imagens usadas têm `linux/arm64/v8` (verificado no registry), mas a
  imagem **não deve ser compilada na VPS**: 2 vCPU e 29 GB livres.
- **A imagem ideal vem de registry** (CI com `buildx --platform linux/arm64`), e a VPS só faz
  `pull`. Compilar na VPS é o plano B.
- **O firewall do host não protege container com porta publicada.** Quem manda é a *Security List* da
  Oracle Cloud. Detalhes em [Firewall](#firewall-e-exposição-de-portas).
- **Sempre use `-f docker-compose.prod.yml`.** O `docker-compose.yml` de desenvolvimento está no
  mesmo diretório e publica o Postgres; rodá-lo na VPS expõe o banco.

## Ambiente da VPS

Levantamento do Hermes, cruzado com este repositório.

| Item | Valor levantado | Impacto no deploy |
| --- | --- | --- |
| Arquitetura | **aarch64 (ARM64)** | imagens precisam de `linux/arm64`; build fora da VPS |
| vCPU | **2** | compilar Maven na VPS é lento e caro em CPU |
| RAM | **11 GB** totais, ~9,7 GB livres | suficiente; mas a JVM precisa de teto explícito |
| Disco | `/dev/sda1` 48 GB, **19 GB usados, 29 GB livres (40%)** | recurso mais apertado: logs, cache de build e backups |
| Kernel | **6.17** com cgroup v2 | atende Docker 27+ |
| Docker | **não instalado**; `docker.io` 29.1.3 disponível no apt | instalar com `get.docker.com` e conferir Compose v2 |
| `ip_forward` | **0** | o Docker vai ligar; esperado e inofensivo |
| Firewall do host | `iptables` com `-A INPUT -j REJECT` no fim | **não** bloqueia porta publicada por container |
| Cloud | **Oracle Cloud** (Security List / NSG) | é lá que 80/443 precisam ser liberadas |
| Sistema operacional | não informado **⚠️ confirmar** | `cat /etc/os-release` antes de instalar pacotes |

### O que já roda na VPS

- **Hermes**: processo `systemd` comum, **sem Docker**, com três portas ocupadas — `8080`
  (monitor), `9119` (dashboard) e `9120` (webapp).
- Portas já em uso: **22, 53, 111, 631, 8080, 9119, 9120, 49105**.
- Portas livres sugeridas pelo Hermes: 8081, 8090, 3000, 8443.
- Hoje **só a 22 passa** pelo firewall do host; 80 e 443 estão livres.

### O que isso significa para nós

1. **Não há conflito de software.** Docker usa cgroups/namespaces e cadeias de iptables próprias; o
   Hermes é um processo systemd. Convivem.
2. **A porta 8080 do host é do Hermes, mas isso não conflita com a nossa app.** O `8080` da aplicação
   vive no namespace do container. O Caddy a alcança por `app:8080` na rede do Compose. Conflito só
   existiria com `-p 8080:8080` ou `network_mode: host` — nenhum dos dois é usado.
3. **80/443 estão livres**, então o Caddy pode ser o dono delas.
4. **Disco é o recurso crítico**, não RAM. O uso subiu de 12 GB para 19 GB em poucos dias segundo o
   Hermes: vale investigar o que cresceu antes de somar mais coisa.

## Decisões de arquitetura (com justificativa)

Cada decisão abaixo lista as opções consideradas e por que a escolhida venceu. Quem for executar a
configuração deve seguir a escolha; se quiser mudar, registre o motivo aqui.

### D1. Três containers (`db` + `app` + `caddy`), não um

| Opção | Prós | Contras |
| --- | --- | --- |
| **1 container** (app + banco + TLS) | menos peças | anti-padrão: Postgres no mesmo container da JVM, PID 1 duplo, upgrade acoplado, backup frágil |
| **2 containers** (app + db, TLS no host) | simples | exige proxy/TLS fora do Compose; perde o certificado automático |
| **3 containers** (escolhido) | isolamento, healthchecks, TLS automático, backup por `docker exec` | uma peça a mais para operar |

Por que não 1: o schema é **PostgreSQL** e o Flyway aplica migrações específicas do dialeto
(`ON DELETE CASCADE`, locks `PESSIMISTIC_WRITE`, `ddl-auto=validate`). Trocar por H2 embarcado exigiria
outro conjunto de migrações e mudaria o comportamento que a spec trata como crítico. Além disso,
[`scripts/backup-postgres.sh`](../scripts/backup-postgres.sh) e
[`scripts/restore-postgres.sh`](../scripts/restore-postgres.sh) dependem de `docker compose exec db`.

### D2. Só o Caddy publica portas

| Opção | Resultado |
| --- | --- |
| Publicar a app (`-p 8080:8080`) | app exposta, sem TLS/HSTS, firewall do host não protege |
| Bind em loopback + túnel SSH | seguro, mas exige túnel para cada acesso; bom para *staging* |
| **Reverse proxy publicando 80/443** (escolhido) | um ponto de entrada, TLS automático, HSTS, `/actuator` bloqueado |

O [Caddyfile](../Caddyfile) já faz o trabalho: `reverse_proxy app:8080`, `encode zstd gzip`, remoção
do header `Server`, HSTS e `respond 404` para `/actuator*`. O `app` não tem `ports:` no Compose de
produção, só `expose: 8080`.

**Auditoria rápida** — deve listar apenas o Caddy com portas no host:

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'
```

### D3. Não compilar na VPS; imagem vem de registry

| Opção | Custo | Risco |
| --- | --- | --- |
| (a) `up -d --build` na VPS (documentação antiga) | 5–10+ min de Maven em 2 vCPU ARM, download de dependências, cache de 5–10 GB | disco enche; deploy lento; CI e produção divergem |
| (b) build na máquina local + `docker save`/registry | rápido | depende de a máquina local ter Docker e a arquitetura certa |
| **(c) CI com `buildx --platform linux/arm64` + push (escolhido)** | minutos no CI | exige registry e credencial |

O CI atual ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) roda `docker build` no
`ubuntu-latest`, que é **amd64**, e não publica a imagem. Se essa imagem fosse enviada para a VPS, o
container morreria com `exec format error` — exatamente a armadilha do ARM64.

**Mudança mínima no Dockerfile para evitar emulação (QEMU):**

```dockerfile
# compila o jar na arquitetura do runner (amd64), sem emular ARM
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
# ...
# runtime na arquitetura de destino (arm64 na VPS)
FROM --platform=$TARGETPLATFORM eclipse-temurin:25-jre
```

Com isso, `docker buildx build --platform linux/arm64` compila o jar nativamente e só baixa a camada
ARM64 do JRE. Sem QEMU, sem Maven emulado.

**Publicação (proposta de job no CI):**

```yaml
      - uses: docker/setup-buildx-action@v3

      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - uses: docker/build-push-action@v6
        with:
          context: .
          platforms: linux/arm64
          push: true
          tags: ghcr.io/<owner>/amigo-secreto:${{ github.sha }}
```

E o Compose de produção passa a usar `image:` com tag imutável (SHA) em vez de `build:`:

```yaml
  app:
    image: ghcr.io/<owner>/amigo-secreto:${APP_TAG:-latest}
    pull_policy: always
```

Se o registry for privado, a VPS precisa de `docker login ghcr.io` com um token de leitura.

**Plano B (somente se não houver registry):** compilar na própria VPS funciona, porque ela é ARM64
nativa. Nesse caso, aceite o custo e controle o disco:

```bash
docker builder prune -f        # limpa cache de build
docker image prune -f          # remove camadas órfãs
docker system df               # confira o que está ocupando
```

### D4. Limite explícito de memória para a JVM

O `ENTRYPOINT` usa `-XX:MaxRAMPercentage=75`. **Sem `mem_limit`, o container enxerga os 11 GB do
host** e pode tentar um heap de ~8,2 GB — com ~9,7 GB livres e o Hermes também usando a máquina. Um
pico derruba a VPS inteira por OOM.

```yaml
  app:
    mem_limit: 1g        # 75% disso ≈ 768 MB de heap
    cpus: "1.0"
```

`deploy.resources.limits` também funciona no Compose v2; escolha um formato e mantenha. O importante
é **ter limite**. Não remova o `MaxRAMPercentage` do Dockerfile: com limite, ele passa a ser correto.

### D5. Rotação de log do Docker

O driver `json-file` **não tem limite por padrão**. Caddy escreve log de runtime em `stderr` e o
Spring escreve tudo em `stdout`; ambos crescem sem teto. Com 29 GB livres e crescimento de 7 GB em
poucos dias, isso é risco operacional real.

```yaml
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

Aplique em `app`, `caddy` e `db`. Isso é independente do access log do Caddy, que continua desligado
por privacidade (ver [Notas de segurança](#notas-de-segurança)).

### D6. Firewall: a Security List da Oracle é o portão real

O Hermes foi explícito: o Docker insere regras de **DNAT em `PREROUTING`** e encaminha por
**`FORWARD`**, que não passa pelo `INPUT -j REJECT` do host. Na prática, `-p 8080:8080` fica exposto
à internet mesmo com o `REJECT` ali. O Docker também liga `ip_forward`, carrega `br_netfilter` e cria
as cadeias `DOCKER`/`DOCKER-USER`.

Consequências para este projeto:

1. **A 80/443 do Caddy vai funcionar de qualquer forma** — o DNAT fura o `INPUT`. Não é bug; é o
   mecanismo normal do Docker. O que precisa liberar é a **Security List/NSG da Oracle**.
2. **O `REJECT` do host não impede um erro de configuração** que publique o Postgres. Por isso a
   regra é *só o Caddy publica*.
3. **Não use `ufw` para "conter" o Docker.** Regras de `ufw` não valem para tráfego de container.

Defesa em profundidade opcional, na cadeia `DOCKER-USER` (persistir, senão some no reboot):

```bash
iptables -I DOCKER-USER -p tcp -m multiport --dports 80,443 -j RETURN
iptables -I DOCKER-USER -p udp --dport 443 -j RETURN
iptables -A DOCKER-USER -j DROP
```

> **Ressalva:** essa regra também bloqueia portas publicadas em loopback usadas por túnel SSH
> (o DNAT de porta publicada passa por `FORWARD`/`DOCKER-USER`). Se for usar *staging* em
> `127.0.0.1:8081`, inclua essa porta na regra.

### D7. Compose v2 tem que existir

`docker.io` do apt e o plugin `docker compose` são pacotes separados. Os scripts de backup e todo
este guia usam `docker compose` (v2), não `docker-compose` (v1). Confirme antes de tudo:

```bash
docker compose version
```

Se faltar, prefira o instalador oficial (`get.docker.com`), que traz o plugin, em vez de misturar
`docker.io` com pacotes do repositório da Docker Inc.

### D8. Mapa de portas

| Porta | Onde | Uso |
| --- | --- | --- |
| **80/tcp** | host, Caddy | redirecionamento HTTP→HTTPS e desafio ACME |
| **443/tcp** | host, Caddy | HTTPS (produção) |
| **443/udp** | host, Caddy | HTTP/3 (opcional; sem ela cai para h2) |
| **22/tcp** | host, sshd | administração — restrinja por IP |
| 8080 | interno do `app` | Tomcat/Actuator; **não** publicar (host já usa 8080 com o Hermes) |
| 5432 | interno do `db` | Postgres; **nunca** publicar |
| 2019 | interno do Caddy | admin API; **nunca** publicar |
| 8081 | host, opcional | *staging* com `127.0.0.1:8081:8080` + `ssh -L` |

## Pré-requisitos

- VPS Linux ARM64 (a atual) com **2 vCPU / 2 GB** no mínimo. **⚠️ confirmar** a distro com
  `cat /etc/os-release`.
- **Docker Engine 27+** com o plugin **Compose v2** (`docker compose version`). A VPS tem
  `docker.io` 29.1.3 disponível no apt, mas o plugin pode faltar.
- Domínio com registro **A** apontando para o IP da VPS (e **AAAA** se houver IPv6).
- Na **Security List da Oracle**: `80/tcp`, `443/tcp` e `443/udp` liberadas; `22` restrita.
- Git instalado na VPS.
- Espaço em disco: **≥ 5 GB** livres para imagem + volumes. Há ~29 GB hoje.
- Registry de imagens (GitHub Container Registry ou equivalente) se for seguir D3(c).

## Instalação do Docker

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # reabra a sessão depois
docker --version
docker compose version
```

Por que esse caminho e não `apt install docker.io`: o script oficial instala `docker-ce` + `buildx` +
`compose` na mesma versão, e os scripts de backup dependem do plugin. Se preferir o pacote da
distro, instale também `docker-compose-v2` e valide com `docker compose version`.

Confirme o estado esperado depois de instalar:

```bash
uname -m                      # aarch64
sysctl net.ipv4.ip_forward    # passa a 1 depois de subir container
docker info --format '{{.ServerVersion}} {{.Driver}}'
```

## Firewall e exposição de portas

Ordem de precedência, do mais externo para o mais interno:

1. **Security List / NSG da Oracle** — é o que realmente bloqueia. Libere 80/443 (tcp) e 443 (udp),
   mantenha 22 restrita. Não libere 8081, 5432 nem 2019.
2. **Cadeia `DOCKER-USER`** (opcional, D6) — defesa em profundidade contra publicação acidental.
3. **Compose** — `app` e `db` sem `ports`. Essa é a camada que mais importa no dia a dia.

Auditoria depois de cada deploy:

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'
ss -lntup | grep -E ':(80|443|8080|5432|2019)\b'
```

O esperado: só `caddy` aparece publicando 80/443. Nenhum listener em 5432 ou 2019.

> **Nunca rode `docker compose up` sem `-f docker-compose.prod.yml` nesta VPS.** O arquivo de
> desenvolvimento está no mesmo diretório e publica `5432`. Enquanto a pendência P1 não for aplicada,
> esse comando exporia o Postgres na internet. Um alias ajuda:
>
> ```bash
> alias dc='docker compose -f docker-compose.prod.yml'
> ```

## Primeiro deploy

> Estes passos assumem que as [pendências no código](#pendências-no-código-antes-de-executar) foram
> aplicadas. Se ainda não foram, veja a nota no passo 4.

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
   # edite POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD, DOMAIN, ACME_EMAIL e as variáveis de e-mail
   ```

   Gere a senha do banco com `openssl rand -hex 32` (evite `$`, espaços, `#` e `=` nos valores).
   `DOMAIN` é só o host, sem esquema e sem barra final (`amigo.exemplo.com`).

   > `.env` e `.env.*` estão no `.gitignore`; `.env.prod.example` é versionado e serve de modelo.

3. **Conferir a configuração resolvida**

   ```bash
   docker compose -f docker-compose.prod.yml config
   ```

   O comando imprime a configuração final (com as variáveis do `.env`). Não copie essa saída para
   lugar público. Confira principalmente: `mem_limit`, `logging`, e se `app`/`db` estão **sem**
   `published`/`ports`.

4. **Subir**

   | Origem da imagem | Comando |
   | --- | --- |
   | Registry (recomendado, D3c) | `docker compose -f docker-compose.prod.yml pull && docker compose -f docker-compose.prod.yml up -d` |
   | Build local na VPS (plano B) | `docker compose -f docker-compose.prod.yml up -d --build` |

   Ordem esperada: `db` sobe e fica `healthy`, `app` sobe (imagem baixada ou buildada), o Flyway
   aplica as migrações e o healthcheck fica `healthy`, depois o Caddy pede o certificado.

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
   curl -sS -o /dev/null -w '%{http_code}\n' https://amigo.exemplo.com/actuator/health
   ```

   Esperado: `200`, HSTS presente, `robots.txt` com `Disallow: /` e **404** em `/actuator/health`.
   O healthcheck real roda dentro do container, em `localhost:8080`.

7. **Confirmar que o Flyway aplicou do zero**

   ```bash
   docker compose -f docker-compose.prod.yml exec db \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank;"'
   ```

   O schema é do Flyway (`ddl-auto=validate`). Migração aplicada não se edita: crie a próxima versão.

8. **Auditar portas e disco**

   ```bash
   docker ps --format 'table {{.Names}}\t{{.Ports}}'
   df -h /
   docker system df
   ```

## E-mail (Brevo) no deploy

O envio é **opcional**. Com `EMAIL_ENABLED=false` (padrão), o app usa um sender de log e nada sai do
servidor; o sorteio continua funcionando e os links seguem sendo copiados/WhatsApp.

Para ativar em produção, no `.env` do servidor:

```bash
EMAIL_ENABLED=true
EMAIL_WORKER_ENABLED=true
BREVO_SANDBOX=false
BREVO_API_KEY=xkeysib-...          # chave de produção, separada da usada em dev
BREVO_FROM_EMAIL=no-reply@mail.barbosalabs.dev
BREVO_FROM_NAME=Amigo Secreto
BREVO_REPLY_TO=                    # opcional
```

Pré-requisitos no Brevo (já feitos neste projeto, revalidar se a conta mudar):

- Domínio de envio `mail.barbosalabs.dev` autenticado (Brevo code, DKIM e DMARC no DNS).
- Sender `no-reply@mail.barbosalabs.dev` criado e `From` alinhado ao domínio.
- API key de produção guardada fora do Git; se usar allowlist, liberar o IP da VPS.

Depois de alterar o `.env`:

```bash
docker compose -f docker-compose.prod.yml up -d app
docker compose -f docker-compose.prod.yml logs --tail=100 app | grep -i -E 'email|brevo|notification'
```

Teste de fumaça: crie um evento, faça um sorteio com um participante que tenha e-mail e confira no
painel o status "Enviado". Se ficar "Falhou", veja `last_error` no banco (sem logar e-mail/token) e o
histórico em **Brevo → Transactional → Logs**. Guia completo: [`EMAIL-BREVO.md`](EMAIL-BREVO.md).

> A cota do plano grátis é 300 e-mails/dia. Um evento de 100 participantes com um refazer gera 200
> envios; dois refazeres no mesmo dia estouram a cota. O worker tenta de novo depois, mas o provedor
> pode recusar com 429.

## Atualização

Antes de enviar a mudança, rode os testes na sua máquina (`.\mvnw.cmd test` no Windows,
`./mvnw test` no Linux). A imagem usa `-DskipTests`, então os testes não rodam no servidor.

```bash
cd /opt/amigo-secreto
git pull

# imagem de registry (recomendado)
export APP_TAG=<sha-ou-tag>
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d

# plano B: build local na VPS
# docker compose -f docker-compose.prod.yml up -d --build

docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=100 app
```

Regras:

- Só faça deploy com os testes verdes.
- Em banco vazio, valide o Flyway aplicando do zero antes de anunciar a versão.
- Depois do `up`, confira `ps` e o health; se a app não ficar `healthy`, leia `logs app`.
- `up -d` recria apenas o que mudou; o volume `pgdata` fica intacto.
- Use **tag imutável** (SHA do commit), não `latest`, para que rollback seja determinístico.

## Rollback

- **Aplicação com registry (recomendado):** volte a tag anterior.

  ```bash
  export APP_TAG=<sha-anterior>
  docker compose -f docker-compose.prod.yml pull
  docker compose -f docker-compose.prod.yml up -d
  ```

- **Aplicação com build local:** volte para o commit anterior e reconstrua.

  ```bash
  git checkout <commit-ou-tag-anterior>
  docker compose -f docker-compose.prod.yml up -d --build
  ```

- **Banco:** o Flyway não reverte migração. Rollback de schema = restaurar o dump anterior com
  `scripts/restore-postgres.sh`. Por isso, faça backup antes de todo deploy que traz migração nova.

## Backup

```bash
cd /opt/amigo-secreto
./scripts/backup-postgres.sh
```

Gera `backups/amigo-secreto-AAAAMMDD-HHMMSS.dump` (`pg_dump -Fc`), com permissão `600`, e apaga
dumps com mais de 14 dias (`RETENTION_DAYS` sobrescreve).

Cron sugerido (backup diário às 03:15), rodando como um usuário do grupo `docker`:

```cron
15 3 * * * cd /opt/amigo-secreto && ./scripts/backup-postgres.sh >> /var/log/amigo-secreto-backup.log 2>&1
```

- **Mantenha cópia fora da VPS** (`rclone`/`rsync`). Backup no mesmo disco não protege contra perda
  da VPS — e o disco é justamente o recurso apertado aqui (29 GB livres).
- Teste o restore periodicamente — backup nunca testado não é backup.
- Com 29 GB livres, avalie reduzir `RETENTION_DAYS` ou mover os dumps para storage externo. O Hermes
  relatou crescimento de 7 GB em poucos dias; investigue o que cresceu antes de somar mais dados.

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
5. em caso de falha, deixa a app parada — corrija o erro e rode
   `docker compose -f docker-compose.prod.yml start app`.

Depois do restore, confira a versão do schema: um dump antigo pode deixar o Flyway atrás do código
em execução. Se a aplicação não subir, o log aponta a migração pendente/divergente.

Para um teste de restore sem risco, suba um banco descartável e restaure nele antes de tocar em
produção:

```bash
docker run --rm -d --name restore-test -e POSTGRES_PASSWORD=teste -e POSTGRES_DB=amigo_secreto -e POSTGRES_USER=amigo postgres:17-alpine
until docker exec restore-test pg_isready -U amigo -d amigo_secreto; do sleep 1; done
docker exec -i restore-test pg_restore --clean --if-exists --no-owner -U amigo -d amigo_secreto < backups/arquivo.dump
docker rm -f restore-test
```

## Notas de segurança

- **HTTPS obrigatório.** Não publique a app em HTTP; o Caddy redireciona e emite o certificado.
- **Tokens nas URLs.** Painel/participante/convite são acessados por link com token: **nunca
  habilite access log** no proxy (o `Caddyfile` não tem `log`) nem log de requisição na aplicação.
  O token está no *path*, não na query string — filtro de query não resolveria.
- **Actuator interno.** Só `health` é exposto (ver `application.yaml`), sem detalhes; o `Caddyfile`
  responde 404 para `/actuator/*` e o container não publica porta no host.
- **Postgres sem porta pública.** Só o Caddy publica portas; `db` e `app` usam `expose`/rede interna.
  O Compose de desenvolvimento publica 5432 e **não** deve rodar nesta VPS (pendência P1).
- **Admin API do Caddy (2019)** não é publicada. Não adicione `ports` para ela.
- **`.env` fora do Git**, com `chmod 600`. Senha do banco com 32+ bytes aleatórios.
- **`SESSION_COOKIE_SECURE=true`** e `APP_BASE_URL=https://<domínio>` (sem barra final) já vêm do
  Compose; se mudar o domínio, atualize o `.env` e recrie a app (`up -d app`).
- **`forward-headers-strategy=framework`** já está no `application.yaml`, para os links gerados
  usarem `https://` atrás do proxy.
- **`robots.txt`** bloqueia tudo (`Disallow: /`); não remova.
- **Sem cache de proxy:** o Caddy não configura `Cache-Control` nem cache de disco; respostas
  dinâmicas seguem o que a aplicação envia. Não adicione `header Cache-Control` no proxy.
- **Firewall:** libere apenas 22 (restrinja por IP se possível), 80 e 443. Lembre que o Docker ignora
  o `INPUT` do host: a auditoria é `docker ps --format '{{.Names}} {{.Ports}}'`.
- **Limite de recursos** ([D4](#d4-limite-explícito-de-memória-para-a-jvm)) e **rotação de log**
  ([D5](#d5-rotação-de-log-do-docker)) são parte da segurança operacional: um OOM ou disco cheio
  derruba o site.
- Atualize a imagem base e as dependências com regularidade; rode `docker compose ... pull` para
  renovar `postgres` e `caddy`.

## Problemas comuns

| Sintoma | Verificação |
| --- | --- |
| `exec format error` | imagem foi buildada para amd64; rebuild com `--platform linux/arm64` (D3) |
| `app` reinicia em loop | `logs app`: conexão com `db`, credenciais do `.env`, Flyway |
| `app` unhealthy | `docker compose -f docker-compose.prod.yml exec app curl -fsS http://localhost:8080/actuator/health` |
| Caddy não emite certificado | DNS aponta para a VPS? 80/443 abertas **na Security List**? `logs caddy` |
| `502 Bad Gateway` | `app` está `healthy`? `docker compose -f docker-compose.prod.yml ps` |
| `up` falha por variável vazia | `.env` ausente ou incompleto; rode `docker compose -f docker-compose.prod.yml config` |
| Senha do banco mudou no `.env` | A senha só é aplicada na criação do volume; em banco existente, altere via `psql` |
| `port is already allocated` | outra coisa usa a porta; 8080 é do Hermes. Use `expose`, não `ports` |
| Postgres acessível de fora | alguém rodou o Compose de dev; confira `docker ps` e derrube o container |
| Disco enchendo | `docker system df`; `docker builder prune -f`; `docker image prune -f`; confira `du -sh backups` |
| Container morre por OOM | falta `mem_limit` (D4); `docker inspect` mostra `OOMKilled` |
| `docker compose: command not found` | plugin v2 ausente; instale via `get.docker.com` (D7) |

## Pendências no código antes de executar

Estas são divergências entre este documento e os arquivos atuais. **Quem for configurar a VPS deve
aplicá-las (ou confirmar que já foram aplicadas) antes de seguir os passos.**

| # | Pendência | Arquivo | Por quê |
| --- | --- | --- | --- |
| **P1** | Trocar `"5432:5432"` por `"127.0.0.1:5432:5432"` | `docker-compose.yml` (dev) | na VPS, o bind em `0.0.0.0` expõe o Postgres e o Docker fura o `INPUT` |
| **P2** | Adicionar `mem_limit: 1g` e `cpus: "1.0"` no `app` | `docker-compose.prod.yml` | sem teto, `MaxRAMPercentage=75` usa os 11 GB do host |
| **P3** | Adicionar bloco `logging` (10m × 3) em `app`, `caddy`, `db` | `docker-compose.prod.yml` | `json-file` sem limite enche o disco |
| **P4** | Usar `--platform=$BUILDPLATFORM` / `$TARGETPLATFORM` | `Dockerfile` | evita emular ARM ao compilar para a VPS |
| **P5** | CI com `buildx` + `--platform linux/arm64` + push para registry | `.github/workflows/ci.yml` | hoje builda amd64 e não publica |
| **P6** | Trocar `build:` por `image:` com tag imutável | `docker-compose.prod.yml` | deploy e rollback determinísticos, sem compilar na VPS |
| **P7** | Atualizar este documento e `implementation.md` após decidir | ambos | manter a documentação fiel ao estado real |

Opcional, fora do escopo do primeiro deploy:

- **P8** — extrair o Caddy para um stack de borda e compartilhar a VPS com outras aplicações. Ver
  [`INFRA-VPS.md`](./INFRA-VPS.md).

## Checklist para quem for configurar a VPS

Ordem sugerida. Cada item tem um comando de verificação; não avance sem ele.

1. **Confirmar fatos do ambiente** (o levantamento do Hermes pode ter mudado)
   ```bash
   cat /etc/os-release
   uname -m                 # aarch64
   nproc                    # 2
   free -h                  # ~11 GB
   df -h /
   ss -lntup
   ```
2. **Confirmar portas livres**
   ```bash
   ss -lntup | grep -E ':(80|443)\b'    # deve sair vazio
   ```
3. **Confirmar que o Docker não está instalado / versão**
   ```bash
   docker --version || echo "docker ausente"
   ```
4. **Aplicar P1–P6** e rodar os testes do projeto.
5. **Instalar Docker + Compose v2** e validar `docker compose version`.
6. **Liberar 80/443 tcp + 443 udp na Security List da Oracle** (fora do host).
7. **Publicar a imagem ARM64** no registry (P5) e confirmar:
   ```bash
   docker buildx imagetools inspect ghcr.io/<owner>/amigo-secreto:<tag>
   ```
8. **Criar `/opt/amigo-secreto`, `.env` e conferir `config`**.
9. **Subir com `pull` + `up -d`** e validar `ps`, `logs` e HTTPS.
10. **Auditar** `docker ps` (só Caddy publica), `df -h`, `docker system df`.
11. **Configurar cron de backup** e cópia fora da VPS.
12. **Registrar no `implementation.md`** o que foi executado e o que mudou.

Coisas que **não** devem ser alteradas sem conversa:

- `Caddyfile` sem `log` (privacidade dos tokens).
- `robots.txt` com `Disallow: /`.
- `app`/`db` sem `ports` no Compose de produção.
- `ddl-auto=validate` — o schema é do Flyway.
- `MaxRAMPercentage` do Dockerfile (ele fica correto **com** `mem_limit`).

## O que ainda não foi validado

O Docker não está instalado na máquina de desenvolvimento, então o build da imagem, a subida do
Compose e o healthcheck foram escritos e revisados, mas **não executados**. Do lado da VPS, o Hermes
levantou o ambiente, mas nada deste projeto foi rodado lá ainda.

No primeiro deploy, valide nesta ordem: `docker compose -f docker-compose.prod.yml config`,
`pull`, `up -d`, `ps`/`logs`, HTTPS e a auditoria de portas. Só depois divulgue o link.
