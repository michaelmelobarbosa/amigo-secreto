# Deploy — Amigo Secreto

Guia de operação em VPS Linux: Docker Compose (`app` + PostgreSQL) publicado por um **Caddy de
borda externo** com TLS automático. O Postgres e a aplicação **não** publicam portas no host; só o
Caddy, em um stack separado, fica exposto.

> **Execução real.** O primeiro deploy foi concluído em **2026-11-15** e está registrado em
> [`DEPLOY_CONCLUIDO.md`](./DEPLOY_CONCLUIDO.md), com evidências, arquivos externos e pendências.
> Este documento continua sendo o guia reproduzível; onde divergir do registro de execução, vale o
> registro.
>
> **Origem das informações sobre a VPS.** O levantamento inicial foi feito pelo **Hermes** em
> 2026-11-14 e confirmado na execução de 2026-11-15. Os valores de ambiente abaixo são medidos.

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
- [Checklist de aceite](#checklist-de-aceite-executado-em-2026-11-15)
- [O que configurar fora do projeto](#o-que-configurar-fora-do-projeto)
- [O que ainda não foi validado](#o-que-ainda-não-foi-validado)

## Resumo executivo

- **Deploy executado em 2026-11-15:** `https://amigo-secreto.barbosalabs.dev` no ar com TLS
  Let's Encrypt, Flyway V1–V3 aplicado em Postgres 17 e backup diário por systemd timer. Registro
  completo em [`DEPLOY_CONCLUIDO.md`](./DEPLOY_CONCLUIDO.md).
- **E-mail real ativo e validado em 2026-11-16:** 2 notificações `SENT`, `attempts=1`, painel
  **Enviado**, zero erro/retentativa; chave de produção restrita a envio. SPF publicado; rate limit
  dos endpoints de e-mail no código (`links/email` 10/h, `email/resend` 20/h), entra com P15.
- **Dois containers neste projeto**: `db` (Postgres 17) e `app` (Spring Boot). O Caddy é um
  **stack de borda separado**, compartilhado com outras aplicações da VPS; o porquê está em
  [Decisões](#decisões-de-arquitetura-com-justificativa) e em [`INFRA-VPS.md`](./INFRA-VPS.md).
- **Nenhum container deste projeto publica portas.** `app` usa `expose: 8080` e `db` não tem
  `ports`. Só o Caddy do stack `edge` publica `80/tcp`, `443/tcp` e `443/udp`.
- **A VPS é ARM64.** Todas as imagens usadas têm `linux/arm64/v8`, e o CI publica a imagem ARM64 no
  GHCR; a VPS só faz `pull`.
- **A imagem vem de registry com tag imutável.** O Compose usa `APP_IMAGE:APP_TAG` (o `APP_TAG` é o
  SHA do commit), então deploy e rollback são determinísticos e não há build na VPS. O pacote no
  GHCR é **público**; o primeiro deploy usou `ghcr.io/michaelmelobarbosa/amigo-secreto:2b6e2d4…`.
- **O firewall do host não protege container com porta publicada.** Quem manda é a *Security List* da
  Oracle Cloud. Detalhes em [Firewall](#firewall-e-exposição-de-portas).
- **Sempre use `-f docker-compose.prod.yml`.** O `docker-compose.yml` de desenvolvimento está no
  mesmo diretório, roda com outro nome de projeto (`amigo-secreto-dev`) e publica o Postgres em
  loopback; rodá-lo na VPS não é o caminho de produção.

## Ambiente da VPS

Levantamento confirmado na execução de 2026-11-15 (ver [`DEPLOY_CONCLUIDO.md`](./DEPLOY_CONCLUIDO.md) §3).

| Item | Valor medido | Impacto no deploy |
| --- | --- | --- |
| Instância | `hermes-vps` · `VM.Standard.A1.Flex` · `sa-saopaulo-1` · AD-1 | Oracle Cloud |
| IP | `<VPS_PUBLIC_IP>` público / `<VPS_PRIVATE_IP>` privado · **sem IPv6 global** | DNS só com registro A; sem AAAA |
| Arquitetura | **aarch64 (ARM64)** | imagens precisam de `linux/arm64`; build fora da VPS |
| vCPU / RAM | **2 vCPU** · **11 GiB** (+2 GiB swap) | suficiente; `mem_limit` por serviço |
| Disco | 48 GB, **28 GB livres (43% usado)** | logs, imagens e backups competem |
| SO / kernel | **Ubuntu 24.04.5 LTS** · `6.17.0-1020-oracle` | atende Docker 29 |
| Docker / Compose | **29.9.3** / **v5.7.1**, storage `overlayfs` | instalado na execução |
| Usuário de deploy | `ubuntu` (uid 1001), no grupo `docker`, `sudo` sem senha | `sg docker -c` na sessão em que o grupo ainda não vale |
| `cron` | **não existe** (`crontab: command not found`) | backup por systemd timer |
| Firewall do host | `iptables-nft`: `INPUT` aceita 22 + ICMP + lo, resto `REJECT`; `FORWARD` com `REJECT` persistido | **não** bloqueia porta publicada por container |
| Cloud | **Oracle Cloud** (Security List) | 80/443 liberados em 2026-11-15 |
| Hermes | `127.0.0.1:9119` e `:9120`; um `python3` em `127.0.0.1:8080` | loopback; sem conflito com a app |

### O que já roda na VPS

- **Hermes**: processos systemd em **loopback** (`127.0.0.1:9119`, `:9120` e um `python3` em `:8080`).
  Nada em `0.0.0.0`, portanto não conflita com a app, que também não publica porta.
- Portas em uso no host: **22, 53, 111, 631, 8080, 9119, 9120, 49105**.
- **80/443 agora são do Caddy de borda** (`edge-caddy-1`), que publica `80/tcp`, `443/tcp` e
  `443/udp`; `2019/tcp` fica só na rede interna.
- Serviços da Oracle (`unified-monitoring-agent`, `snap.oracle-cloud-agent`) e
  `unattended-upgrades` não foram tocados.

### O que isso significa para nós

1. **Não há conflito de software.** Docker usa cgroups/namespaces e cadeias de iptables próprias; o
   Hermes é um processo systemd em loopback. Convivem.
2. **A porta 8080 do host é do Hermes, mas isso não conflita com a nossa app.** O `8080` da aplicação
   vive no namespace do container. O Caddy de borda alcança `amigo-app:8080` pela rede externa
   `edge`. Conflito só existiria com `-p 8080:8080` ou `network_mode: host` — nenhum dos dois é usado.
3. **80/443 pertencem ao Caddy do stack de borda**, que emitiu o certificado Let's Encrypt em
   ~6 s após o DNS e a Security List.
4. **Disco é o recurso crítico**, não RAM. No deploy, o consumo medido foi app **288 MiB** (limite
   1 GiB), db **45 MiB** e Caddy **13 MiB**; restam ~28 GB. Monitore `backups/`, imagens e logs.

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
[`scripts/backup-postgres.sh`](../scripts/backup-postgres.sh) e
[`scripts/restore-postgres.sh`](../scripts/restore-postgres.sh) dependem de `docker compose exec db`.

Quando a VPS passar a hospedar mais de uma aplicação, a mesma lógica vale **entre projetos**: um
Postgres por app, não um banco compartilhado. Custo e justificativa estão em
[`INFRA-VPS.md#banco-de-dados-um-postgres-por-projeto`](./INFRA-VPS.md#banco-de-dados-um-postgres-por-projeto).

Por que o Caddy saiu daqui: ele passa a ser um **stack de borda** compartilhado, com um bloco por
hostname. O `docker-compose.prod.yml` deste projeto só conhece `app` e `db`; a app entra na rede
externa `edge` com o nome `amigo-app`. A configuração do Caddy e os certificados ficam em
[`INFRA-VPS.md`](./INFRA-VPS.md).

**Executado em 2026-11-15:** `amigo-app` + `amigo-secreto-db-1` no projeto `amigo-secreto`, volume
`amigo-secreto_pgdata`, rede privada `amigo-secreto_internal`; `edge-caddy-1` no stack `edge`
(`172.18.0.0/16`).

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

**Resultado em 2026-11-15:** só `edge-caddy-1` publica 80/443; `amigo-app` e
`amigo-secreto-db-1` aparecem com `8080/tcp` e `5432/tcp` internos, **sem** `0.0.0.0`.

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

O `.env` traz `APP_IMAGE=ghcr.io/michaelmelobarbosa/amigo-secreto` e `APP_TAG=<sha-do-commit>`. O
pacote no GHCR é **público** (verificado no deploy) e a VPS **não** precisou de `docker login`. A
imagem do primeiro deploy foi `ghcr.io/michaelmelobarbosa/amigo-secreto:2b6e2d4…`. Se um dia o
pacote virar privado, a VPS precisará de `docker login ghcr.io` com um token de leitura
(`read:packages`).

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
No deploy de 2026-11-15, o app ficou em **288 MiB de 1 GiB**, o db em **45 MiB** e o Caddy em
**13 MiB**.

### D5. Rotação de log do Docker

O driver `json-file` **não tem limite por padrão**. Caddy escreve log de runtime em `stderr` e o
Spring escreve tudo em `stdout`; ambos crescem sem teto. Com ~28 GB livres, isso é risco
operacional real.

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

**Estado em 2026-11-15:** o `FORWARD -j REJECT` estava persistido em `/etc/iptables/rules.v4`. O
restore do `netfilter-persistent` **não** apaga as cadeias `DOCKER-USER`/`DOCKER-FORWARD` (apenas
duplicou uma regra `REJECT`, removida). Foram criados drop-ins systemd para ordenar
`netfilter-persistent` antes do `docker.service`:

```ini
# /etc/systemd/system/netfilter-persistent.service.d/10-before-docker.conf
[Unit]
Before=docker.service

# /etc/systemd/system/docker.service.d/10-after-netfilter.conf
[Unit]
After=netfilter-persistent.service
```

A regra restritiva em `DOCKER-USER` **não** foi aplicada; continua opcional.

### D7. Compose v2 tem que existir

`docker.io` do apt e o plugin `docker compose` são pacotes separados. Os scripts de backup e todo
este guia usam `docker compose` (v2), não `docker-compose` (v1). Confirme antes de tudo:

```bash
docker compose version
```

Se faltar, prefira o instalador oficial (`get.docker.com`), que traz o plugin, em vez de misturar
`docker.io` com pacotes do repositório da Docker Inc. No deploy de 2026-11-15: **Docker 29.9.3** e
**Compose v5.7.1**, com storage `overlayfs`.

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

No deploy de 2026-11-15: `edge-caddy-1` publica 80/443; `amigo-app` e `amigo-secreto-db-1` não
publicam porta alguma.

## Pré-requisitos

- VPS Linux ARM64 (a atual): Ubuntu 24.04.5, 2 vCPU, 11 GiB, 28 GB livres. **Sem IPv6 global.**
- **Docker Engine 29.9.3** com **Compose v5.7.1** (instalados em 2026-11-15).
- Domínio com registro **A** apontando para o IP da VPS. **Não há AAAA** porque a VPS não tem IPv6
  global.
- Na **Security List da Oracle**: `80/tcp`, `443/tcp` e `443/udp` liberadas; `22` restrita.
- Git instalado na VPS (o primeiro deploy usou `git clone --depth 1`; ver
  [O que ainda não foi validado](#o-que-ainda-não-foi-validado)).
- Espaço em disco: **≥ 5 GB** livres. Há ~28 GB.
- **Stack de borda (Caddy) de pé**, com a rede externa `edge` e o site desta aplicação
  (`reverse_proxy amigo-app:8080`). Configuração em [`INFRA-VPS.md`](./INFRA-VPS.md).
- Acesso ao **GitHub Container Registry**: o pacote é **público**, então não é necessário login.
- **Não há `cron` na VPS**: o backup usa systemd timer (ver [Backup](#backup)).

## Instalação do Docker

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # reabra a sessão depois
docker --version
docker compose version
```

No deploy de 2026-11-15: **Docker 29.9.3** e **Compose v5.7.1**. O `usermod` só passa a valer em uma
sessão nova; enquanto isso, rode os comandos como `sg docker -c '<comando>'`.

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

**Estado em 2026-11-15:** a Security List tem 3 ingress rules stateful (`TCP 80`, `TCP 443`,
`UDP 443`, source `0.0.0.0/0`); `5432`, `8080` e `2019` não foram abertos. A auditoria confirmou
`edge-caddy-1` como único container com portas publicadas.

> **Sempre prefira `-f docker-compose.prod.yml` nesta VPS.** O arquivo de desenvolvimento está no
> mesmo diretório, roda com o nome de projeto `amigo-secreto-dev` e publica o Postgres apenas em
> loopback, mas não é o caminho de produção. Um alias ajuda:
>
> ```bash
> alias dc='docker compose -f docker-compose.prod.yml'
> ```

## Primeiro deploy

> **Executado em 2026-11-15.** O registro com evidências, arquivos externos e pendências está em
> [`DEPLOY_CONCLUIDO.md`](./DEPLOY_CONCLUIDO.md). Os passos abaixo são reproduzíveis para uma nova
> VPS ou para reconstruir o ambiente.

1. **Clonar o projeto**

   ```bash
   sudo mkdir -p /opt/amigo-secreto
   sudo chown "$USER":"$USER" /opt/amigo-secreto
   git clone --depth 1 https://github.com/michaelmelobarbosa/amigo-secreto.git /opt/amigo-secreto
   cd /opt/amigo-secreto
   # o primeiro deploy usou --depth 1; para histórico/rollback:
   #   git fetch --unshallow
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
   >
   > **Atenção (P10):** valores com espaço exigem aspas. Use
   > `BREVO_FROM_NAME="Amigo Secreto"`; sem aspas, `source .env` quebra com
   > `Secreto: command not found`. Os dois exemplos já usam aspas e o `deploy.sh` valida o arquivo,
   > normaliza CRLF/BOM e mostra uma mensagem clara. Se editar o `.env` no servidor, mantenha as aspas.

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
   # se o grupo docker ainda não vale na sessão:
   # sg docker -c 'APP_TAG=<sha-do-commit> bash scripts/deploy.sh'
   ```

   O script valida a rede `edge`, roda `pull` da imagem, `up -d --remove-orphans` e espera o
   healthcheck de `amigo-app`. O equivalente manual:

   ```bash
   docker compose -f docker-compose.prod.yml pull
   docker compose -f docker-compose.prod.yml up -d --remove-orphans
   ```

   Ordem esperada: `db` sobe e fica `healthy`, `app` sobe com a imagem do registry, o Flyway aplica
   as migrações e o healthcheck fica `healthy`. O certificado é emitido pelo Caddy de borda.

   **Resultado em 2026-11-15:** `amigo-secreto-db-1 Healthy` → `amigo-app Started` →
   `App saudável.`; Flyway `V1`, `V2` e `V3` com `success = t`; logs de arranque sem `ERROR`/`WARN`.

6. **Conferir saúde e logs**

   ```bash
   docker compose -f docker-compose.prod.yml ps
   docker compose -f docker-compose.prod.yml logs --tail=200 app
   # logs do Caddy ficam no stack de borda:
   docker compose -f /opt/edge/compose.yml logs --tail=100 caddy
   ```

   `ps` deve mostrar `amigo-secreto-db-1 (healthy)` e `amigo-app (healthy)`. O Caddy aparece no
   stack `edge` como `edge-caddy-1`.

7. **Testar HTTPS**

   ```bash
   curl -sS -o /dev/null -w '%{http_code}\n' https://amigo-secreto.barbosalabs.dev/
   curl -sSI https://amigo-secreto.barbosalabs.dev/ | grep -i strict-transport-security
   curl -sS https://amigo-secreto.barbosalabs.dev/robots.txt
   curl -sS -o /dev/null -w '%{http_code}\n' https://amigo-secreto.barbosalabs.dev/actuator/health
   ```

   Esperado: `200`, HSTS presente, `robots.txt` com `Disallow: /` e **404** em `/actuator/health`.
   O healthcheck real roda dentro do container, em `localhost:8080`.

   **Resultado em 2026-11-15:** `200`; redirect HTTP `308` → HTTPS; HSTS
   `max-age=31536000; includeSubDomains`; CSP/XFO/nosniff/referrer presentes; `/actuator` e
   `/actuator/health` `404`; certificado Let's Encrypt válido até 2027-02-13.

8. **Confirmar que o Flyway aplicou do zero**

   ```bash
   docker compose -f docker-compose.prod.yml exec db \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank;"'
   ```

   O schema é do Flyway (`ddl-auto=validate`). Migração aplicada não se edita: crie a próxima versão.

   **Resultado em 2026-11-15:** `V1 init`, `V2 add participant email` e `V3 create notification`,
   todas com `success = t`.

9. **Auditar portas e disco**

   ```bash
   docker ps --format 'table {{.Names}}\t{{.Ports}}'
   df -h /
   docker system df
   ```

   Só o Caddy do stack `edge` deve publicar 80/443. `amigo-app` e o banco não publicam portas.

   **Resultado em 2026-11-15:** app `288 MiB / 1 GiB`, db `45 MiB`, Caddy `13 MiB`; ~28 GB livres;
   só `edge-caddy-1` publica.

## E-mail (Brevo) no deploy

O envio é **opcional**. Com `EMAIL_ENABLED=false` (padrão), o app usa um sender de log e nada sai do
servidor; o sorteio continua funcionando e os links seguem sendo copiados/WhatsApp.

**Estado em 2026-11-16:** e-mail **ligado e validado ponta a ponta** em produção. O `.env` está com
`EMAIL_ENABLED=true`, `EMAIL_WORKER_ENABLED=true`, `BREVO_SANDBOX=false` e a chave de produção
(restrita a envio; `GET /v3/account` → 403 é esperado). O teste real com 2 participantes gerou duas
notificações `SENT` (`attempts=1`, com `provider_message_id`), o painel exibiu **Enviado** e não houve
erro/retentativa; o evento de teste foi apagado e o banco voltou a 0/0/0/0. O IP de saída da VPS é
`<VPS_PUBLIC_IP>` (relevante se o Brevo usar allowlist).

Para ativar em produção, no `.env` do servidor:

```bash
EMAIL_ENABLED=true
EMAIL_WORKER_ENABLED=true
BREVO_SANDBOX=false
BREVO_API_KEY=xkeysib-...          # chave de produção, separada da usada em dev
BREVO_FROM_EMAIL=no-reply@mail.barbosalabs.dev
BREVO_FROM_NAME="Amigo Secreto"    # aspas obrigatórias (P10)
BREVO_REPLY_TO=                    # opcional
```

Pré-requisitos no Brevo (já feitos neste projeto, revalidar se a conta mudar):

- Domínio de envio `mail.barbosalabs.dev` autenticado (Brevo code, DKIM e DMARC no DNS).
- Sender `no-reply@mail.barbosalabs.dev` criado e `From` alinhado ao domínio.
- API key de produção guardada fora do Git; se usar allowlist, liberar o IP de saída da VPS
  (`<VPS_PUBLIC_IP>`).

Depois de alterar o `.env`:

```bash
docker compose -f docker-compose.prod.yml up -d app
docker compose -f docker-compose.prod.yml logs --tail=100 app | grep -i -E 'email|brevo|notification'
```

Teste de fumaça: crie um evento, faça um sorteio com um participante que tenha e-mail e confira no
painel o status "Enviado". Se ficar "Falhou", veja `last_error` no banco (sem logar e-mail/token) e o
histórico em **Brevo → Transactional → Logs**. Guia completo: [`EMAIL-BREVO.md`](EMAIL-BREVO.md).

Na VPS, os dois testes rápidos são:

```bash
# 1) valida a API key, o domínio e o IP de saída (<VPS_PUBLIC_IP>)
TEST_EMAIL=seu@email.com bash scripts/check-brevo.sh

# 2) valida o caminho real da aplicação (cria evento, envia os links e apaga o evento)
TEST_EMAIL=seu@email.com bash scripts/smoke-email.sh
```

O primeiro não passa pela aplicação; o segundo usa o mesmo `EmailSender` do painel. O fluxo completo
com outbox/worker é o sorteio acima.

> Os scripts `check-brevo.sh` e `smoke-email.sh` foram adicionados ao repositório. No teste de
> 2026-11-16 eles ainda não estavam no remoto, então o Hermes fez o equivalente via API/UI.

> A cota do plano grátis é 300 e-mails/dia. Um evento de 100 participantes com um refazer gera 200
> envios; dois refazeres no mesmo dia estouram a cota. O worker tenta de novo depois, mas o provedor
> pode recusar com 429.

## Atualização

Antes de enviar a mudança, rode os testes na sua máquina (`.\mvnw.cmd test` no Windows,
`./mvnw test` no Linux). A imagem usa `-DskipTests`, então os testes não rodam no servidor.

```bash
cd /opt/amigo-secreto
git pull
# se o clone foi --depth 1: git fetch --unshallow

# tag imutável publicada pelo CI para o commit atual
APP_TAG=<sha-do-commit> bash scripts/deploy.sh
# se o grupo docker ainda não vale na sessão:
# sg docker -c 'APP_TAG=<sha-do-commit> bash scripts/deploy.sh'

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
  # ou: sg docker -c 'APP_TAG=<sha-anterior> bash scripts/deploy.sh'
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

**Na VPS atual não existe `cron`.** O backup é um **systemd timer** (`amigo-secreto-backup.service`
+ `amigo-secreto-backup.timer`), com `OnCalendar=*-*-* 03:15:00`, `Persistent=true` e
`RandomizedDelaySec=5m`, rodando como `ubuntu`. O conteúdo integral está em
[`DEPLOY_CONCLUIDO.md`](./DEPLOY_CONCLUIDO.md) §5.6/5.7.

**Estado em 2026-11-15:** timer ativo; próxima execução `2026-11-16 03:18 -03`; execução manual gerou
`amigo-secreto-20261115-195346.dump` (20K), `pg_restore --list` com 48 entradas e `status=0/SUCCESS`.
O diretório `backups/` está em `700` e o dump em `600`.

Se a VPS tiver `cron`, o equivalente é:

```cron
15 3 * * * cd /opt/amigo-secreto && bash scripts/backup-postgres.sh >> /var/log/amigo-secreto-backup.log 2>&1
```

- **Mantenha cópia fora da VPS** (`rclone`/`rsync`). Backup no mesmo disco não protege contra perda
  da VPS — e o disco é justamente o recurso apertado aqui (~28 GB livres). **Ainda pendente.**
- Teste o restore periodicamente — backup nunca testado não é backup. A integridade do dump foi
  validada; o restore completo **ainda não** foi executado.
- Com ~28 GB livres, avalie reduzir `RETENTION_DAYS` ou mover os dumps para storage externo.

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

**Estado em 2026-11-15:** a integridade do dump foi validada com `pg_restore --list` (48 TOC
entries, gzip, `dbname: amigo_secreto`); o **restore completo ainda não foi executado**. Faça o teste
com banco descartável abaixo antes de tocar em produção.

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
  os padrões de [`RATE-LIMITING.md`](RATE-LIMITING.md). **Validado em produção em 2026-11-15:** os
  cinco primeiros `POST /events` passaram, o sexto retornou `429` com `Retry-After: 600`, e
  `X-Forwarded-For` forjado (`9.9.9.9`, `8.8.4.4`) não criou bucket novo.
- **E-mail real ativo:** `POST /admin/{token}/links/email` e
  `POST /admin/{token}/participants/{id}/email/resend` têm rate limit no código (10/h e 20/h, janela
  de 1h), que entra em produção com P15. A cota do Brevo é 300/dia.
- **`ubuntu` no grupo `docker` = root equivalente.** O acesso ao socket do Docker permite controlar
  todos os containers. Aceito para operação single-tenant.
- **HSTS com `includeSubDomains`** está ativo: subdomínios de `amigo-secreto.barbosalabs.dev` ficam
  presos em HTTPS. Hosts irmãos (`outro.barbosalabs.dev`) não são afetados.
- **`db` limitado a 512m (P9).** O Postgres não vê mais os ~11 GiB do host; o limite é
  `mem_limit: 512m` com `cpus: "0.5"`.
- **Sessão apenas por cookie (P11).** `server.servlet.session.tracking-modes: cookie` remove o
  `;jsessionid` das URLs; o `SessionTrackingIntegrationTest` cobre o comportamento.
- **Caddy é ponto único de falha** para todos os sites da borda; `restart: unless-stopped` mitiga.
- **Backup no mesmo disco** não protege contra perda da VPS; a cópia off-site está pendente.
- **Qualquer container na rede `edge` alcança `amigo-app:8080`.** O Docker não tem firewall por
  container; o banco está na rede privada.
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
| `line NN: Secreto: command not found` no deploy | **corrigido (P10)**: os exemplos usam `BREVO_FROM_NAME="Amigo Secreto"`; se reaparecer, use aspas no `.env` |
| `crontab: command not found` | a VPS não tem cron; use o systemd timer de backup (ver [Backup](#backup)) |
| `;jsessionid=` aparece na URL | **corrigido (P11)**: `tracking-modes: cookie`; se reaparecer, confira a configuração |
| `db` usando a RAM do host | **corrigido (P9)**: `mem_limit: 512m` no `db`; confira o bloco no Compose |
| `permission denied /var/run/docker.sock` | o grupo `docker` não vale na sessão; use `sg docker -c '...'` ou reabra a sessão |
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

### Pendências abertas após o deploy

Estas foram encontradas na execução real. **P9–P11 foram aplicadas em 2026-11-15**; P12 foi
incluída no template do stack de borda e depende de aplicação no `/opt/edge/compose.yml` da VPS.
P13 foi implementada no código em 2026-11-16 (rate limit dos endpoints de e-mail); P14 (SPF) foi
aplicada no mesmo dia; P15 é operacional.

| # | Pendência | Onde | Estado / ação |
| --- | --- | --- | --- |
| **P9** | `db` sem limite de memória | `docker-compose.prod.yml` | **aplicado**: `mem_limit: 512m` + `cpus: "0.5"` |
| **P10** | `.env.example`/`.env.prod.example` quebram `source .env` | ambos | **aplicado**: `BREVO_FROM_NAME="Amigo Secreto"` nos dois; `deploy.sh` valida o `.env` |
| **P11** | `;jsessionid` na URL quando não há cookie | `application.yaml` | **aplicado**: `tracking-modes: cookie` + `SessionTrackingIntegrationTest` |
| **P12** | Caddy de borda sem `mem_limit` | `/opt/edge/compose.yml` (externo) | **template atualizado** com `256m`; falta aplicar na VPS |
| **P13** | Endpoints de e-mail sem rate limit | `common/web/RateLimitFilter` | **implementado no código em 2026-11-16**: `links/email` 10/h e `email/resend` 20/h, janela própria e testes; entra em produção com P15 |
| **P14** | SPF ausente no subdomínio de envio | DNS de `mail.barbosalabs.dev` | **aplicado em 2026-11-16**: TXT `v=spf1 include:spf.sendinblue.com -all`, verificado no DNS público |
| **P15** | VPS no commit `2b6e2d4`; remoto em `26bd7f4` | deploy | quando o CI publicar a imagem do novo SHA, `APP_TAG=<sha> bash scripts/deploy.sh` |

Detalhes e reprodução em [`DEPLOY_CONCLUIDO.md`](./DEPLOY_CONCLUIDO.md) §8.

## Checklist de aceite (executado em 2026-11-15)

Todos os itens foram executados na VPS `hermes-vps`; o checklist fica como reprodução para uma nova
máquina. Evidências em [`DEPLOY_CONCLUIDO.md`](./DEPLOY_CONCLUIDO.md).

- [x] Ambiente medido: Ubuntu 24.04.5, aarch64, 2 vCPU, 11 GiB, 28 GB livres.
- [x] Portas 80/443 livres antes do Caddy.
- [x] Docker 29.9.3 + Compose v5.7.1 instalados; `ubuntu` no grupo `docker`.
- [x] P1–P6 aplicadas e suíte verde (159 na execução; 162 após os testes de sessão e fail-fast).
- [x] TCP 80, TCP 443 e UDP 443 liberados na Security List da Oracle.
- [x] Rede `edge` + `/opt/edge` + `edge-caddy-1` de pé; certificado Let's Encrypt emitido.
- [x] Imagem ARM64 pública no GHCR; nenhum `docker login` necessário.
- [x] `/opt/amigo-secreto` + `.env` (600) + `docker compose config`.
- [x] `APP_TAG=2b6e2d4… bash scripts/deploy.sh` → `App saudável.`
- [x] Auditoria: só `edge-caddy-1` publica; app 288 MiB, db 45 MiB, caddy 13 MiB.
- [x] Backup diário por systemd timer; execução manual validada; off-site pendente.
- [x] Registro em `DEPLOY_CONCLUIDO.md` e documentação atualizada.
- [ ] Restore completo, reboot, fluxo no navegador e e-mail real (ver
      [O que ainda não foi validado](#o-que-ainda-não-foi-validado)).

Coisas que **não** devem ser alteradas sem conversa:

- O site do stack de borda **sem `log`** (privacidade dos tokens no path).
- `robots.txt` com `Disallow: /`.
- `app`/`db` sem `ports` no Compose de produção.
- `ddl-auto=validate` — o schema é do Flyway.
- `MaxRAMPercentage` do Dockerfile (ele fica correto **com** `mem_limit`).

## O que configurar fora do projeto

Tudo abaixo vive na VPS ou em serviços externos; segredos não entram no repositório. **Estado em
2026-11-15** (detalhes e evidências em [`DEPLOY_CONCLUIDO.md`](./DEPLOY_CONCLUIDO.md)):

| Item | Status | Observação |
| --- | --- | --- |
| DNS A | ✅ | Name.com, TTL 300, `<VPS_PUBLIC_IP>`; **sem AAAA** |
| Security List | ✅ | TCP 80, TCP 443, UDP 443 |
| Stack `/opt/edge` | ✅ | `edge-caddy-1`, Let's Encrypt, sem access log |
| GHCR | ✅ | pacote público; sem `docker login` |
| `.env` da app | ✅ | `600`, senha de 64 hex, `APP_TAG=2b6e2d4…` |
| Backup | ✅ | systemd timer, 03:15, retenção 14 dias |
| E-mail Brevo | ✅ | ligado e validado ponta a ponta; chave restrita a envio |
| SPF do subdomínio de envio | ✅ | TXT `v=spf1 include:spf.sendinblue.com -all` em `mail.barbosalabs.dev`, verificado em 2026-11-16 |
| Rate limit dos endpoints de e-mail | ✅ (código) | `links/email` 10/h e `email/resend` 20/h; entra em produção com P15 |
| Cópia off-site do backup | ❌ | backup só na VPS |
| Restore completo | ❌ | só `pg_restore --list` foi validado |
| Reboot da VPS | ❌ | não testado |
| Fluxo no navegador | ❌ | smoke HTTP + 164 testes; falta criar/sortear/apagar |
| Monitoração externa | ❌ | opcional |
| `DOCKER-USER` restritivo | ❌ | opcional |
| Clone raso | ❌ | `git fetch --unshallow` |
| Imagens de teste | ❌ | `hello-world`, `alpine:3.20` |
| Deploy automatizado (CD) | ❌ | opcional |

### 1. DNS (feito)

- Registro **A** de `amigo-secreto.barbosalabs.dev` → `<VPS_PUBLIC_IP>`, TTL 300, na Name.com.
- **Nenhum AAAA**: a VPS não tem IPv6 global.
- `mail.barbosalabs.dev` continua autenticado no Brevo (Brevo code + DKIM + DMARC); MX/TXT não foram
  tocados. **Não** crie um segundo DMARC.
- O certificado é emitido/renovado pelo Caddy de borda; não há certificado manual.

### 2. Oracle Cloud (Security List / NSG) (feito)

- 3 ingress rules stateful, source `0.0.0.0/0`: **TCP 80**, **TCP 443** e **UDP 443**.
- `22/tcp` restrita por IP, se possível.
- **Não** foram abertos `5432`, `8080` nem `2019`.
- Egress rules não foram alteradas.

### 3. Stack de borda (`/opt/edge`) (feito)

- Rede `edge` criada (`172.18.0.0/16`); stack `edge` com `edge-caddy-1` publicando 80/443.
- `/opt/edge/compose.yml`, `Caddyfile`, `sites/amigo-secreto.caddy` e `.env` (`ACME_EMAIL`) criados;
  `sites/` é `root:root`, montado `:ro`.
- O site desta app faz `reverse_proxy amigo-app:8080`, responde 404 para `/actuator*`, envia HSTS e
  **não** tem `log`.
- **Desvio consciente:** o site usa **hostname literal** em vez de `{$DOMAIN}`; um `.env` por stack
  não escala para N apps. Nova app = novo arquivo em `sites/` + `caddy reload`.
- O Caddy é o único a publicar 80/443 e é ponto único de falha para todas as apps da VPS.
- O template do stack já traz `mem_limit: 256m`; confirme o mesmo no `/opt/edge/compose.yml` da VPS
  (P12) e recrie o Caddy:
  ```bash
  cd /opt/edge && sudo docker compose -f compose.yml up -d caddy
  ```

### 4. GitHub Container Registry (GHCR) (feito)

- O CI publica `ghcr.io/michaelmelobarbosa/amigo-secreto:<sha>` a cada push.
- O pacote é **público**; a VPS não precisou de `docker login`. O workflow declara
  `permissions: packages: write`.
- Se o push falhar com 403, confira **Settings → Actions → General → Workflow permissions**.
- Se o pacote virar privado, a VPS precisará de `docker login ghcr.io` com `read:packages`.

### 5. `.env` da aplicação (feito)

- `/opt/amigo-secreto/.env` em `600`, dono `ubuntu`, fora do Git.
- `POSTGRES_PASSWORD` gerada com `openssl rand -hex 32` (64 hex), só no servidor.
- `DOMAIN=amigo-secreto.barbosalabs.dev`, `APP_IMAGE=ghcr.io/michaelmelobarbosa/amigo-secreto`,
  `APP_TAG=2b6e2d4…`, `EDGE_NETWORK=edge`.
- `RATE_LIMIT_*` com os padrões; `EMAIL_ENABLED=true` desde 2026-11-16.
- **P10:** os exemplos já usam `BREVO_FROM_NAME="Amigo Secreto"`; confirme o mesmo no `.env` do
  servidor. O `deploy.sh` valida o arquivo antes de carregar.

### 6. Brevo (fase 2) (feito)

- Domínio de envio `mail.barbosalabs.dev` autenticado; sender `no-reply@mail.barbosalabs.dev`.
- API key de produção **restrita a envio**; `GET /v3/account` retorna 403 e isso é esperado.
- E-mail **ligado e validado ponta a ponta** em 2026-11-16: 2 notificações `SENT`, `attempts=1`,
  painel **Enviado**, zero erro/retentativa. IP de saída da VPS: `<VPS_PUBLIC_IP>`.
- **SPF:** publicado em 2026-11-16 (`v=spf1 include:spf.sendinblue.com -all`) e verificado no DNS
  público; DKIM e DMARC já estavam corretos.
- **Rate limit:** `POST /admin/{token}/links/email` (10/h) e
  `POST /admin/{token}/participants/{id}/email/resend` (20/h), com janela de 1h, para proteger a cota
  de 300/dia e a reputação do domínio; está no código e entra em produção com P15.

### 7. Backup e restore (parcial)

- Backup diário via systemd timer (`amigo-secreto-backup.timer`), 03:15, retenção 14 dias; execução
  manual validada.
- **Cópia off-site ainda não existe**: backup no mesmo disco não protege contra perda da VPS.
- **Restore completo ainda não foi executado**; só a integridade do dump foi validada com
  `pg_restore --list`.
- Teste o restore periodicamente com `scripts/restore-postgres.sh` (procedimento em
  [Restore](#restore)).

### 8. Próximos passos operacionais (pendentes)

- Reiniciar a VPS em janela controlada e conferir: 3 containers `Up`, `iptables -S FORWARD` com
  `DOCKER-USER` no topo, HTTPS 200 e `systemctl list-timers`.
- Criar um evento real de teste pelo navegador e apagar depois (o fluxo HTTP já foi exercitado).
- Configurar monitoração externa (ex.: Uptime Kuma/healthchecks.io) apontando para `/`.
- Aplicar `mem_limit: 256m` no Caddy do `/opt/edge/compose.yml` (P12) e recriar o serviço.
- Quando o CI publicar a imagem do commit novo (`26bd7f4` ou posterior), rodar
  `APP_TAG=<sha> bash scripts/deploy.sh` (P15).
- `git fetch --unshallow` se precisar de histórico/rollback por commit.
- `docker rmi hello-world alpine:3.20` para limpar as imagens de teste.
- Opcional: CD com `appleboy/ssh-action` rodando `scripts/deploy.sh` com `APP_TAG=$GITHUB_SHA`.

## O que ainda não foi validado

| Item | Status | Como fechar |
| --- | --- | --- |
| Deploy da imagem do commit novo | ❌ | aguardar o CI publicar o SHA e rodar `deploy.sh` |
| Cópia off-site do backup | ❌ | `rclone`/`rsync` para storage externo |
| Restore completo | ❌ | banco descartável + `restore-postgres.sh` |
| Reboot da VPS | ❌ | reiniciar e conferir containers, iptables, HTTPS e timer |
| Fluxo funcional no navegador | ❌ | criar evento, sortear, abrir link e apagar |
| Monitoração/uptime externo | ❌ | monitor HTTP com alerta |
| `DOCKER-USER` restritivo | ❌ | regras de drop liberando só 80/443 |
| Clone raso | ❌ | `git fetch --unshallow` |
| Imagens de teste no host | ❌ | `docker rmi hello-world alpine:3.20` |
| Deploy automatizado (CD) | ❌ | opcional |
| Atualização de imagens base | ❌ | `docker compose pull` periódico; avaliar fixar digest |

**Pendências de código/configuração:** P9–P11 aplicadas; P12 (Caddy `mem_limit`) está no template
externo; P13 (rate limit dos endpoints de e-mail) foi implementado no código e P14 (SPF) foi aplicada
em 2026-11-16; P15 é o deploy da imagem do commit novo. A suíte de **164 testes** e os testes de
produção de 2026-11-15/16 cobrem o fluxo funcional em nível de aplicação, mas não substituem o teste
no navegador.
