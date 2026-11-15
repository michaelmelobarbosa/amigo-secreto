# Deploy — Amigo Secreto

Guia de operação em VPS Linux: Docker Compose (`app` + PostgreSQL) publicado por um **Caddy de
borda externo** com TLS automático. O Postgres e a aplicação **não** publicam portas no host; só o
Caddy, em um stack separado, fica exposto.

> **Origem das informações sobre a VPS.** O levantamento de hardware, sistema, portas e firewall foi
> feito pelo **Hermes**, agente instalado na própria VPS, em 2026-11-14, e cruzado com o repositório.
> Onde o dado não pôde ser confirmado por comando, está marcado com **⚠️ confirmar**.

> **Documento complementar.** O Caddy foi desacoplado deste projeto: ele vive em um stack `edge`
> compartilhado, que roteia esta e outras aplicações. A topologia, os arquivos do stack de borda e a
> política de logs estão em [`INFRA-VPS.md`](./INFRA-VPS.md). Este arquivo cobre apenas o deploy
> desta aplicação; o stack de borda é configurado **fora** do repositório.

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
- [Pendências de deploy aplicadas](#pendências-de-deploy-aplicadas-2026-11-15)
- [Checklist para quem for configurar a VPS](#checklist-para-quem-for-configurar-a-vps)
- [O que configurar fora do projeto](#o-que-configurar-fora-do-projeto)
- [O que ainda não foi validado](#o-que-ainda-não-foi-validado)

## Resumo executivo

- **Dois containers neste projeto**: `db` (Postgres 17) e `app` (Spring Boot). O Caddy é um
  **stack de borda separado**, compartilhado com outras aplicações da VPS; o porquê está em
  [Decisões](#decisões-de-arquitetura-com-justificativa) e em [`INFRA-VPS.md`](./INFRA-VPS.md).
- **Nenhum container deste projeto publica portas.** `app` usa `expose: 8080` e `db` não tem
  `ports`. Só o Caddy do stack `edge` publica `80/tcp`, `443/tcp` e `443/udp`.
- **A VPS é ARM64.** Todas as imagens usadas têm `linux/arm64/v8` (verificado no registry), e o CI
  publica a imagem ARM64 no GHCR; a VPS só faz `pull`.
- **A imagem vem de registry com tag imutável.** O Compose usa `APP_IMAGE:APP_TAG` (o `APP_TAG` é o
  SHA do commit), então deploy e rollback são determinísticos e não há build na VPS.
- **O firewall do host não protege container com porta publicada.** Quem manda é a *Security List* da
  Oracle Cloud. Detalhes em [Firewall](#firewall-e-exposição-de-portas).
- **Sempre use `-f docker-compose.prod.yml`.** O `docker-compose.yml` de desenvolvimento está no
  mesmo diretório, roda com outro nome de projeto (`amigo-secreto-dev`) e publica o Postgres em
  loopback; rodá-lo na VPS não é o caminho de produção.

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
   vive no namespace do container. O Caddy de borda alcança `amigo-app:8080` pela rede externa
   `edge`. Conflito só existiria com `-p 8080:8080` ou `network_mode: host` — nenhum dos dois é usado.
3. **80/443 estão livres**, então o Caddy do stack de borda pode ser o dono delas.
4. **Disco é o recurso crítico**, não RAM. O uso subiu de 12 GB para 19 GB em poucos dias segundo o
   Hermes: vale investigar o que cresceu antes de somar mais coisa.

## Decisões de arquitetura (com justificativa)

Cada decisão abaixo lista as opções consideradas e por que a escolhida venceu. Quem for executar a
configuração deve seguir a escolha; se quiser mudar, registre o motivo aqui.

### D1. Dois containers neste projeto (`db` + `app`) e o Caddy em stack de borda

| Opção | Prós | Contras |
| --- | --- | --- |
| **1 container** (app + banco + TLS) | menos peças | anti-padrão: Postgres no mesmo container da JVM, PID 1 duplo, upgrade acoplado, backup frágil |
| **2 containers + Caddy de borda** (escolhido) | isolamento do banco, deploy independente, TLS centralizado para N apps | um stack de borda a mais para operar, fora deste repositório |
| **3 containers** (Caddy neste Compose) | simples para uma app só | acopla o ciclo de deploy do ingress ao da aplicação; não escala para a segunda app |

Por que não 1: o schema é **PostgreSQL** e o Flyway aplica migrações específicas do dialeto
(`ON DELETE CASCADE`, locks `PESSIMISTIC_WRITE`, `ddl-auto=validate`). Trocar por H2 embarcado exigiria
outro conjunto de migrações e mudaria o comportamento que a spec trata como crítico. Além disso,
[`scripts/backup-postgres.sh`](.bash scripts/backup-postgres.sh) e
[`scripts/restore-postgres.sh`](.bash scripts/restore-postgres.sh) dependem de `docker compose exec db`.

Por que o Caddy saiu daqui: ele passa a ser um **stack de borda** compartilhado, com um bloco por
hostname. O `docker-compose.prod.yml` deste projeto só conhece `app` e `db`; a app entra na rede
externa `edge` com o nome `amigo-app`. A configuração do Caddy e os certificados ficam em
[`INFRA-VPS.md`](./INFRA-VPS.md).

### D2. Nenhum container deste projeto publica portas

| Opção | Resultado |
| --- | --- |
| Publicar a app (`-p 8080:8080`) | app exposta, sem TLS/HSTS, firewall do host não protege |
| Bind em loopback + túnel SSH | seguro, mas exige túnel para cada acesso; bom para *staging* |
| **Caddy de borda publicando 80/443** (escolhido) | um ponto de entrada, TLS automático, HSTS, `/actuator` bloqueado |

O bloco do site no stack de borda faz `reverse_proxy amigo-app:8080`, `encode zstd gzip`, remoção do
header `Server`, HSTS e `respond 404` para `/actuator*` (ver [`INFRA-VPS.md`](./INFRA-VPS.md)). O
`app` não tem `ports:` no Compose de produção, só `expose: 8080`.

**Auditoria rápida** — `amigo-app` e o banco não devem aparecer com portas; o Caddy do stack `edge`
é o único com 80/443:

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'
```

### D3. Não compilar na VPS; imagem vem de registry

| Opção | Custo | Risco |
| --- | --- | --- |
| (a) `up -d --build` na VPS (documentação antiga) | 5–10+ min de Maven em 2 vCPU ARM, download de dependências, cache de 5–10 GB | disco enche; deploy lento; CI e produção divergem |
| (b) build na máquina local + `docker save`/registry | rápido | depende de a máquina local ter Docker e a arquitetura certa |
| **(c) CI com `buildx --platform linux/arm64` + push (escolhido)** | minutos no CI | exige registry e credencial |

O CI atual ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) publica a imagem ARM64 no
GHCR:

- o job `test` roda a suíte e o `package`;
- o job `image` usa `docker/setup-buildx-action` + `docker/build-push-action@v6` com
  `platforms: linux/arm64`, `push: true` e a tag imutável `ghcr.io/<owner>/<repo>:<sha>`;
- PRs rodam só os testes e não publicam imagem.

O Dockerfile usa `$BUILDPLATFORM` no build e `$TARGETPLATFORM` no runtime. O usuário é criado em uma
stage nativa, e a stage de runtime só copia arquivos; o HEALTHCHECK usa um BusyBox estático copiado
de uma stage ARM64 **sem `RUN`**. Assim, `docker buildx build --platform linux/arm64` compila o jar
nativamente e **não usa QEMU** em nenhuma etapa.

```dockerfile
FROM --platform=$TARGETPLATFORM busybox:1.37.0 AS busybox
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre AS user
FROM --platform=$TARGETPLATFORM eclipse-temurin:25-jre
```

O Compose de produção usa `image:` com tag imutável (SHA) em vez de `build:`:

```yaml
  app:
    image: ${APP_IMAGE}:${APP_TAG}
    pull_policy: always
```

O `.env` traz `APP_IMAGE=ghcr.io/<owner>/amigo-secreto` e `APP_TAG=<sha-do-commit>`. Se o pacote do
GHCR for privado, a VPS precisa de `docker login ghcr.io` com um token de leitura (`read:packages`).
Tornar o pacote público elimina esse passo.

**Plano B (somente se não houver registry):** compilar na própria VPS ARM64 ainda funciona, mas não é
o fluxo documentado. Se usar, controle o disco:

```bash
docker builder prune -f        # limpa cache de build
docker image prune -f          # remove camadas órfãs
docker system df               # confira o que está ocupando
```

### D4. Limite explícito de memória para a JVM

O `ENTRYPOINT` usa `-XX:MaxRAMPercentage=75`. **Sem `mem_limit`, o container enxerga os 11 GB do
host** e pode tentar um heap de ~8,2 GB — com ~9,7 GB livres e o Hermes também usando a máquina. Um
pico derruba a VPS inteira por OOM. O `docker-compose.prod.yml` já aplica:

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

Aplique em `app` e `db` (já está no `docker-compose.prod.yml`) e no Caddy do stack de borda (ver
[`INFRA-VPS.md`](./INFRA-VPS.md)). Isso é independente do access log do Caddy, que continua desligado
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
| **80/tcp** | host, Caddy do stack `edge` | redirecionamento HTTP→HTTPS e desafio ACME |
| **443/tcp** | host, Caddy do stack `edge` | HTTPS (produção) |
| **443/udp** | host, Caddy do stack `edge` | HTTP/3 (opcional; sem ela cai para h2) |
| **22/tcp** | host, sshd | administração — restrinja por IP |
| 8080 | interno de `amigo-app` | Tomcat/Actuator; **não** publicar (host já usa 8080 com o Hermes) |
| 5432 | interno do `db` | Postgres; **nunca** publicar |
| 2019 | interno do Caddy do stack `edge` | admin API; **nunca** publicar |
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
- **Stack de borda (Caddy) já criado e de pé**, com a rede externa `edge` e o site desta aplicação
  (`reverse_proxy amigo-app:8080`). Configuração em [`INFRA-VPS.md`](./INFRA-VPS.md).
- Acesso ao **GitHub Container Registry** (GHCR) para puxar a imagem; se o pacote for privado,
  `docker login ghcr.io` com um token de leitura (`read:packages`).

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

O esperado: só o `caddy` do stack `edge` aparece publicando 80/443. `amigo-app` e o banco não
devem aparecer com portas. Nenhum listener em 5432 ou 2019.

> **Sempre prefira `-f docker-compose.prod.yml` nesta VPS.** O arquivo de desenvolvimento está no
> mesmo diretório, roda com o nome de projeto `amigo-secreto-dev` e publica o Postgres apenas em
> loopback, mas não é o caminho de produção. Um alias ajuda:
>
> ```bash
> alias dc='docker compose -f docker-compose.prod.yml'
> ```

## Primeiro deploy

> Estes passos assumem as pendências de deploy já aplicadas (elas estão no repositório). O que
> depende de fora está em [O que configurar fora do projeto](#o-que-configurar-fora-do-projeto).

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
   # edite POSTGRES_*, DOMAIN, APP_IMAGE, APP_TAG, EDGE_NETWORK,
   # as variáveis RATE_LIMIT_* e as de e-mail
   ```

   Gere a senha do banco com `openssl rand -hex 32` (evite `$`, espaços, `#` e `=` nos valores).
   `DOMAIN` é só o host, sem esquema e sem barra final (`amigo-secreto.barbosalabs.dev`).
   `APP_IMAGE` é o repositório do GHCR e `APP_TAG` é o **SHA do commit** publicado pelo CI.
   `ACME_EMAIL` **não** fica aqui: pertence ao `.env` do stack de borda.

   > `.env` e `.env.*` estão no `.gitignore`; `.env.prod.example` é versionado e serve de modelo.

3. **Conferir a rede de borda e o Caddy**

   ```bash
   set -a; source .env; set +a
   docker network ls | grep -w "${EDGE_NETWORK:-edge}" \
     || docker network create "${EDGE_NETWORK:-edge}"
   docker ps --format 'table {{.Names}}\t{{.Ports}}'
   ```

   O Caddy do stack `edge` precisa estar de pé e publicando 80/443. Se esta for a primeira
   migração, suba o stack de borda **antes** deste deploy e pare o Caddy antigo para não disputar as
   portas (ver [`INFRA-VPS.md`](./INFRA-VPS.md)).

4. **Conferir a configuração resolvida**

   ```bash
   docker compose -f docker-compose.prod.yml config
   ```

   O comando imprime a configuração final (com as variáveis do `.env`). Não copie essa saída para
   lugar público. Confira principalmente: `image`/`APP_TAG`, `mem_limit`, `cpus`, `logging`, a rede
   `edge` como externa e se `app`/`db` estão **sem** `published`/`ports`.

5. **Subir**

   ```bash
   APP_TAG=<sha-do-commit> bash scripts/deploy.sh
   ```

   O script valida a rede `edge`, roda `pull` da imagem, `up -d --remove-orphans` e espera o
   healthcheck de `amigo-app`. O equivalente manual:

   ```bash
   docker compose -f docker-compose.prod.yml pull
   docker compose -f docker-compose.prod.yml up -d --remove-orphans
   ```

   Ordem esperada: `db` sobe e fica `healthy`, `app` sobe com a imagem do registry, o Flyway aplica
   as migrações e o healthcheck fica `healthy`. O certificado é emitido pelo Caddy de borda.

6. **Conferir saúde e logs**

   ```bash
   docker compose -f docker-compose.prod.yml ps
   docker compose -f docker-compose.prod.yml logs --tail=200 app
   # logs do Caddy ficam no stack de borda:
   docker compose -f /opt/edge/compose.yml logs --tail=100 caddy
   ```

   `ps` deve mostrar `db (healthy)` e `amigo-app (healthy)`. O Caddy aparece no stack `edge`.

7. **Testar HTTPS**

   ```bash
   curl -sS -o /dev/null -w '%{http_code}\n' https://amigo-secreto.barbosalabs.dev/
   curl -sSI https://amigo-secreto.barbosalabs.dev/ | grep -i strict-transport-security
   curl -sS https://amigo-secreto.barbosalabs.dev/robots.txt
   curl -sS -o /dev/null -w '%{http_code}\n' https://amigo-secreto.barbosalabs.dev/actuator/health
   ```

   Esperado: `200`, HSTS presente, `robots.txt` com `Disallow: /` e **404** em `/actuator/health`.
   O healthcheck real roda dentro do container, em `localhost:8080`.

8. **Confirmar que o Flyway aplicou do zero**

   ```bash
   docker compose -f docker-compose.prod.yml exec db \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank;"'
   ```

   O schema é do Flyway (`ddl-auto=validate`). Migração aplicada não se edita: crie a próxima versão.

9. **Auditar portas e disco**

   ```bash
   docker ps --format 'table {{.Names}}\t{{.Ports}}'
   df -h /
   docker system df
   ```

   Só o Caddy do stack `edge` deve publicar 80/443. `amigo-app` e o banco não publicam portas.

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

# tag imutável publicada pelo CI para o commit atual
APP_TAG=<sha-do-commit> bash scripts/deploy.sh

# equivalente manual
export APP_TAG=<sha-do-commit>
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --remove-orphans
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=100 app
```

Regras:

- Só faça deploy com os testes verdes.
- O CI precisa ter publicado a imagem `ghcr.io/<owner>/amigo-secreto:<sha>` antes do `pull`.
- Em banco vazio, valide o Flyway aplicando do zero antes de anunciar a versão.
- Depois do `up`, confira `ps` e o health; se a app não ficar `healthy`, leia `logs app`.
- `up -d` recria apenas o que mudou; o volume `pgdata` fica intacto.
- Use **tag imutável** (SHA do commit), não `latest`, para que rollback seja determinístico.
- O Caddy de borda não é reiniciado por este deploy; ele só precisa do site apontando para
  `amigo-app:8080`.

## Rollback

- **Aplicação:** volte ao `APP_TAG` anterior e rode o mesmo script. O `up -d` recria o container com a
  imagem antiga.

  ```bash
  APP_TAG=<sha-anterior> bash scripts/deploy.sh
  ```

- **Banco:** o Flyway não reverte migração. Rollback de schema = restaurar o dump anterior com
  `scripts/restore-postgres.sh`. Por isso, faça backup antes de todo deploy que traz migração nova.

## Backup

```bash
cd /opt/amigo-secreto
bash scripts/backup-postgres.sh
```

Gera `backups/amigo-secreto-AAAAMMDD-HHMMSS.dump` (`pg_dump -Fc`), com permissão `600`, e apaga
dumps com mais de 14 dias (`RETENTION_DAYS` sobrescreve).

Cron sugerido (backup diário às 03:15), rodando como um usuário do grupo `docker`:

```cron
15 3 * * * cd /opt/amigo-secreto && bash scripts/backup-postgres.sh >> /var/log/amigo-secreto-backup.log 2>&1
```

- **Mantenha cópia fora da VPS** (`rclone`/`rsync`). Backup no mesmo disco não protege contra perda
  da VPS — e o disco é justamente o recurso apertado aqui (29 GB livres).
- Teste o restore periodicamente — backup nunca testado não é backup.
- Com 29 GB livres, avalie reduzir `RETENTION_DAYS` ou mover os dumps para storage externo. O Hermes
  relatou crescimento de 7 GB em poucos dias; investigue o que cresceu antes de somar mais dados.

## Restore

```bash
cd /opt/amigo-secreto
bash scripts/restore-postgres.sh backups/amigo-secreto-20260101-030000.dump
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

- **HTTPS obrigatório.** Não publique a app em HTTP; o Caddy do stack de borda redireciona e emite o
  certificado.
- **Tokens nas URLs.** Painel/participante/convite são acessados por link com token: **nunca
  habilite access log** no proxy (o site do stack `edge` não tem `log`) nem log de requisição na
  aplicação. O token está no *path*, não na query string — filtro de query não resolveria.
- **Rate limiting.** Implementado em memória por IP (`POST /events`, `/join`, `draw`/`redraw` e
  wishlist), com `429` + `Retry-After`. Os limites vêm de `RATE_LIMIT_*` no `.env`; sem elas valem
  os padrões de [`RATE-LIMITING.md`](RATE-LIMITING.md). Depois do deploy, confirme que o Caddy de
  borda entrega o IP real e que um `X-Forwarded-For` forjado não engana o filtro **antes de divulgar
  o link**.
- **Actuator interno.** Só `health` é exposto (ver `application.yaml`), sem detalhes; o site do stack
  `edge` responde 404 para `/actuator/*` e o container não publica porta no host.
- **Postgres sem porta pública.** Nenhum container deste projeto publica portas; `db` e `app` usam
  `expose`/rede interna. O Compose de desenvolvimento publica 5432 apenas em loopback e **não** deve
  rodar nesta VPS.
- **Admin API do Caddy (2019)** não é publicada no stack de borda. Não adicione `ports` para ela.
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
  renovar `postgres` e o stack de borda separadamente.

## Problemas comuns

| Sintoma | Verificação |
| --- | --- |
| `exec format error` | imagem foi buildada para amd64; use a imagem `linux/arm64` publicada pelo CI (D3) |
| `app` reinicia em loop | `logs app`: conexão com `db`, credenciais do `.env`, Flyway |
| `app` unhealthy | `docker compose -f docker-compose.prod.yml exec app /bin/busybox wget -q -O - http://localhost:8080/actuator/health` |
| Caddy não emite certificado | no stack `edge`: DNS aponta para a VPS? 80/443 abertas **na Security List**? `docker compose -f /opt/edge/compose.yml logs caddy` |
| `502 Bad Gateway` | `amigo-app` está `healthy`? A app está na rede `edge`? `docker compose -f docker-compose.prod.yml ps` |
| `network edge not found` | a rede externa não existe; `docker network create edge` e suba o stack de borda |
| `up` falha por variável vazia | `.env` ausente ou incompleto; rode `docker compose -f docker-compose.prod.yml config` |
| Senha do banco mudou no `.env` | A senha só é aplicada na criação do volume; em banco existente, altere via `psql` |
| `port is already allocated` | 80/443 pertencem ao Caddy do stack `edge`; a app não publica portas. 8080 do host é do Hermes |
| Postgres acessível de fora | confira `docker ps`: só o Caddy de borda pode publicar portas; o Compose de dev usa loopback |
| Disco enchendo | `docker system df`; `docker builder prune -f`; `docker image prune -f`; confira `du -sh backups` |
| Container morre por OOM | confira `mem_limit` (D4); `docker inspect` mostra `OOMKilled` |
| `docker compose: command not found` | plugin v2 ausente; instale via `get.docker.com` (D7) |

## Pendências de deploy aplicadas (2026-11-15)

As divergências que este documento listava foram corrigidas no código e na configuração:

| # | Pendência | Estado |
| --- | --- | --- |
| **P1** | Postgres de dev em loopback | aplicado: `127.0.0.1:5432:5432` e projeto `amigo-secreto-dev` |
| **P2** | `mem_limit: 1g` e `cpus: "1.0"` no `app` | aplicado no Compose de produção |
| **P3** | Rotação de log (10m × 3) | aplicado em `app` e `db`; o Caddy tem o mesmo bloco no stack `edge` |
| **P4** | `--platform=$BUILDPLATFORM` / `$TARGETPLATFORM` | aplicado; o runtime não executa `RUN`, então o build ARM64 não usa QEMU |
| **P5** | CI com `buildx` ARM64 + push no GHCR | aplicado: job `image` publica `ghcr.io/<owner>/<repo>:<sha>` |
| **P6** | `image:` com tag imutável no lugar de `build:` | aplicado: `APP_IMAGE:APP_TAG` com `pull_policy: always` |
| **P7** | Documentação sincronizada | aplicado neste documento, no `implementation.md` e no `INFRA-VPS.md` |
| **P8** | Caddy de borda | arquitetura adotada; configuração externa (ver [`INFRA-VPS.md`](./INFRA-VPS.md)) |

O que continua **fora do repositório** está na seção
[O que configurar fora do projeto](#o-que-configurar-fora-do-projeto).

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
4. **Confirmar que P1–P6 estão aplicadas** (já estão no código) e rodar os testes do projeto.
5. **Instalar Docker + Compose v2** e validar `docker compose version`.
6. **Liberar 80/443 tcp + 443 udp na Security List da Oracle** (fora do host).
7. **Criar a rede `edge` e subir o stack de borda** (Caddy) — ver
   [`INFRA-VPS.md`](./INFRA-VPS.md). Confirmar que só o Caddy publica 80/443.
8. **Publicar/confirmar a imagem ARM64** no GHCR e, se privado, fazer `docker login` na VPS:
   ```bash
   docker buildx imagetools inspect ghcr.io/<owner>/amigo-secreto:<sha>
   ```
9. **Criar `/opt/amigo-secreto`, o `.env` e conferir `docker compose ... config`**.
10. **Subir com `APP_TAG=<sha> bash scripts/deploy.sh`** e validar `ps`, `logs` e HTTPS.
11. **Auditar** `docker ps` (só o Caddy de borda publica), `df -h`, `docker system df`.
12. **Configurar cron de backup** e cópia fora da VPS.
13. **Registrar no `implementation.md`** o que foi executado e o que mudou.

Coisas que **não** devem ser alteradas sem conversa:

- O site do stack de borda **sem `log`** (privacidade dos tokens no path).
- `robots.txt` com `Disallow: /`.
- `app`/`db` sem `ports` no Compose de produção.
- `ddl-auto=validate` — o schema é do Flyway.
- `MaxRAMPercentage` do Dockerfile (ele fica correto **com** `mem_limit`).

## O que configurar fora do projeto

Tudo abaixo vive na VPS ou em serviços externos; segredos não entram no repositório.

### 1. DNS

- Registro **A** de `amigo-secreto.barbosalabs.dev` apontando para o IP da VPS (e **AAAA** se houver
  IPv6).
- `mail.barbosalabs.dev` já autenticado no Brevo (Brevo code + DKIM + DMARC). **Não** crie um segundo
  DMARC.
- O Caddy de borda emite/renova o certificado; não é preciso certificado manual.

### 2. Oracle Cloud (Security List / NSG)

- Liberar `80/tcp`, `443/tcp` e `443/udp`.
- Manter `22/tcp` restrita por IP, se possível.
- **Não** liberar `5432`, `8080` nem `2019`.

### 3. Stack de borda (`/opt/edge`)

- Criar a rede: `docker network create edge` (ou o nome configurado em `EDGE_NETWORK`).
- Criar `/opt/edge/compose.yml`, `Caddyfile`, `sites/amigo-secreto.caddy` e `.env` com `DOMAIN` e
  `ACME_EMAIL`. Os arquivos e a topologia estão em [`INFRA-VPS.md`](./INFRA-VPS.md).
- O site desta app deve fazer `reverse_proxy amigo-app:8080`, responder 404 para `/actuator*`, enviar
  HSTS e **não** ter `log` (o token está no path).
- `docker compose -f /opt/edge/compose.yml up -d`; validar HTTPS, HSTS e `/actuator/health` 404.
- O Caddy é o único a publicar 80/443 e é ponto único de falha para todas as apps da VPS.

### 4. GitHub Container Registry (GHCR)

- O CI publica `ghcr.io/michaelmelobarbosa/amigo-secreto:<sha>` a cada push.
- O workflow declara `permissions: packages: write`. Se o push falhar com 403, confira em
  **Settings → Actions → General → Workflow permissions** se o repositório permite leitura e escrita
  para o `GITHUB_TOKEN`.
- Se o pacote for **privado**, a VPS precisa de `docker login ghcr.io` com um token de leitura
  (`read:packages`). Tornar o pacote público elimina esse passo.
- Confirme a imagem do commit antes do deploy:
  `docker buildx imagetools inspect ghcr.io/michaelmelobarbosa/amigo-secreto:<sha>`.
- O GITHUB_TOKEN do Actions já publica; não é preciso criar secret para o push.

### 5. `.env` da aplicação (`/opt/amigo-secreto/.env`)

- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` (32+ bytes aleatórios).
- `DOMAIN` (host sem esquema), `APP_IMAGE`, `APP_TAG` (SHA), `EDGE_NETWORK` (padrão `edge`).
- `RATE_LIMIT_*` (os padrões já são os recomendados) e as variáveis de e-mail.
- `chmod 600`. Nunca commitar. `ACME_EMAIL` fica no `.env` do stack de borda, não aqui.

### 6. Brevo (fase 2)

- Domínio de envio `mail.barbosalabs.dev` autenticado; sender `no-reply@mail.barbosalabs.dev`.
- API key de **produção** separada da de dev; se usar allowlist de IP, cadastrar o IP de saída da
  VPS.
- Para envio real: `EMAIL_ENABLED=true`, `EMAIL_WORKER_ENABLED=true`, `BREVO_SANDBOX=false`.

### 7. Backup e restore

- Cron diário rodando `bash scripts/backup-postgres.sh` (exemplo em [Backup](#backup)).
- Copiar os dumps para fora da VPS (`rclone`/`rsync`): backup no mesmo disco não protege contra a
  perda da VPS.
- Testar o restore periodicamente com `scripts/restore-postgres.sh`.

## O que ainda não foi validado

O Docker não está instalado na máquina de desenvolvimento, então o build da imagem, a subida do
Compose e o healthcheck foram escritos e revisados, mas **não executados localmente**. O CI ainda não
rodou o novo job de publicação; a primeira execução valida o build ARM64. Do lado da VPS, o Hermes
levantou o ambiente, mas o stack de borda e esta aplicação ainda não foram rodados lá.

No primeiro deploy, valide nesta ordem: rede `edge` e stack de borda de pé, `docker compose -f
docker-compose.prod.yml config`, `pull`, `up -d`, `ps`/`logs`, HTTPS e a auditoria de portas. Depois
confirme que o rate limiting conta o IP real do cliente (e não o do Caddy) antes de divulgar o link.
Só então divulgue.
