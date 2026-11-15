# Infra da VPS — Caddy de borda, múltiplas aplicações e política de logs

Documento de **decisões de arquitetura** para a VPS que hospeda o Amigo Secreto. O passo a passo do
deploy desta aplicação está em [`DEPLOY.md`](./DEPLOY.md); aqui fica o que é transversal a todas as
aplicações da máquina.

> **Contexto.** A VPS é ARM64, 2 vCPU, 11 GB de RAM e 48 GB de disco (29 GB livres), roda o agente
> **Hermes** como processo systemd (portas 8080, 9119 e 9120) e não tinha Docker. Levantamento feito
> pelo Hermes em 2026-11-14. Fatos operacionais detalhados e implicações estão em
> [`DEPLOY.md#ambiente-da-vps`](./DEPLOY.md#ambiente-da-vps).

## Estado atual

O Caddy **já foi desacoplado** deste projeto: o Compose do Amigo Secreto tem apenas `app` e `db`;
o Caddy vive em um stack `edge`, compartilhado com as demais aplicações da VPS. O ingress roteia
por hostname na 443 e alcança `amigo-app:8080` pela rede externa `edge`.

```
Internet ──▶ caddy :80/:443            stack "edge" (rede edge)
              │
              ├──▶ amigo-app:8080  ──▶ amigo-db:5432   (rede privada do projeto)
              └──▶ outra-app:3000  ──▶ outra-db:5432   (rede privada do projeto)
```

O que fica neste repositório: `docker-compose.prod.yml` com `app` + `db`, a imagem da app e os
scripts de operação. O que fica no stack de borda: `Caddyfile`, `sites/*.caddy`, certificados e a
rede `edge`. O `Caddyfile` que antes estava na raiz deste projeto foi removido para não sugerir
duas fontes de verdade.

## Opções consideradas

| # | Opção | Prós | Contras |
| --- | --- | --- | --- |
| **A** | **Caddy em stack de borda + rede `edge` compartilhada** | cada app é dona do seu ciclo de deploy; TLS/certificados centralizados; escala para N apps | mais uma rede e um stack para operar; a config de ingress vive no stack de borda |
| **B** | Segunda app entra no **mesmo Compose** do Amigo Secreto | simples, um `up` sobe tudo | acopla deploys, versões e ciclos de vida das duas aplicações |
| **C** | Uma **porta dedicada** por app no Caddy (`:8443` etc.) | isolamento por porta | exige abrir porta extra no firewall, URL com porta, perde a elegância do SNI; se o app publicar a porta direto, o proxy vira decoração |

**Escolha: A**, com B aceitável como atalho temporário se a segunda aplicação for descartável.

## Por que porta dedicada (C) não é o caminho

O papel do proxy reverso **não é expor uma porta por aplicação**. É expor *uma* porta (443) e rotear
pelo **nome do host** (SNI/`Host`):

- um domínio por app, todos na 443;
- certificado emitido e renovado automaticamente por site;
- firewall com 80/443 apenas, independentemente de quantas apps existirem;
- nenhum app publica porta no host.

Criar `:8443` é possível, mas significa: abrir mais uma porta na Security List, obrigar o usuário a
digitar porta na URL, e — se a app publicar a porta direto no host em vez de usar o proxy — anular o
proxy por completo.

## Topologia recomendada (opção A)

### Rede

```bash
docker network create edge
```

- `edge`: compartilhada, **user-defined** (necessária para DNS por nome de container). Só contém o
  Caddy e os *entrypoints* das apps.
- Rede privada de cada projeto (`amigo-secreto_internal`, `outra_internal`): contém a app e o banco.
  O banco **nunca** entra na `edge`.
- Cada app participa de **duas** redes: a privada (para o banco) e a `edge` (para o Caddy).

### Stack de borda

```yaml
# edge/compose.yml
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
      DOMAIN: ${DOMAIN}
      ACME_EMAIL: ${ACME_EMAIL}
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

O `.env` do stack fica em `/opt/edge/.env` e é a única fonte desses valores:

```bash
DOMAIN=amigo-secreto.barbosalabs.dev
ACME_EMAIL=voce@exemplo.com
```

- `caddy_data` guarda certificados e chaves: **precisa persistir**. Recriar o container sem o volume
  faz o Caddy reemitir tudo e pode bater no rate limit da Let's Encrypt.
- `caddy_config` guarda config autosave; não é crítico, mas evita ruído.
- O healthcheck do Caddy é implícito: se ele estiver de pé, responde. Não há `depends_on` entre
  stacks — se a app estiver parada, o Caddy devolve **502**.

### Caddyfile da borda

```caddyfile
{
    email {$ACME_EMAIL}
    # Nenhum "log" aqui: log global configura loggers, NÃO liga access log.
}

import sites/*.caddy
```

### Site do Amigo Secreto

```caddyfile
# edge/sites/amigo-secreto.caddy
{$DOMAIN} {
    encode zstd gzip

    @actuator path /actuator /actuator/*
    respond @actuator 404

    reverse_proxy amigo-app:8080

    header {
        -Server
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
    }

    # SEM "log": as URLs carregam tokens de acesso.
}
```

`DOMAIN` e `ACME_EMAIL` vêm do `.env` do stack de borda. O valor de `DOMAIN` é o mesmo
`amigo-secreto.barbosalabs.dev` usado no `.env` da aplicação (que monta o `APP_BASE_URL`).

### App do Amigo Secreto

```yaml
# docker-compose.prod.yml (trecho)
services:
  app:
    image: ${APP_IMAGE}:${APP_TAG}
    container_name: amigo-app
    expose:
      - "8080"
    mem_limit: 1g
    cpus: "1.0"
    networks: [internal, edge]   # internal = app + db

  db:
    networks: [internal]          # banco nunca na edge

networks:
  internal: {}
  edge:
    external: true
    name: ${EDGE_NETWORK:-edge}
```

O nome usado no `reverse_proxy` (`amigo-app`) precisa ser o nome resolvível do container na rede
`edge` — normalmente `container_name` ou o nome do serviço **com** prefixo do projeto. Se o serviço
se chama `app` no projeto `amigo-secreto`, o DNS na `edge` pode ser `amigo-secreto-app-1`. A forma
menos surpreendente é definir `container_name: amigo-app` (ou um alias de rede) e usar esse nome.

## Nuance importante: "só o Caddy alcança" é verdade na borda, não dentro da rede

Qualquer container na `edge` consegue falar com `amigo-app:8080` diretamente. O Docker **não tem
firewall por container**. A garantia real é:

- **nada além do Caddy está publicado no host**;
- o banco está em rede privada, sem acesso da `edge`.

Se for necessário isolamento mais forte entre apps, isso é firewall/rede externa ao Compose (ou
*network policies* de orquestrador), não `docker-compose.yml`.

## Configuração por projeto: quem manda no quê

O `import sites/*.caddy` permite que cada projeto entregue seu bloco de ingress, mas com uma regra de
segurança: **`sites/` pertence ao pipeline de deploy, não à aplicação**. Se um container
comprometido puder escrever em `sites/`, ele controla o ingress de todos os sites da VPS.

- dono: usuário de deploy (root ou dedicado);
- montado `:ro` no Caddy;
- escrita só por deploy automatizado ou por operador humano.

Alternativa mais dinâmica é a **admin API** do Caddy (`:2019`). Ela **nunca** deve ser publicada;
no máximo via socket Unix ou túnel SSH. Em troca, a configuração deixa de ser declarativa e passa a
depender de persistência — para poucas apps, arquivo + `import` + reload é mais auditável.

### Reload sem downtime

```bash
docker compose -f edge/compose.yml exec caddy caddy reload --config /etc/caddy/Caddyfile
```

Como `Caddyfile` e `sites/` são bind mounts, mudanças valem no reload, sem rebuild. Exceção: mudanças
de `output file` de log exigem **restart** do processo (a doc do Caddy é explícita), a menos que se
troque o nome do arquivo de log.

### Certificados

- um certificado por hostname, emitido automaticamente;
- HSTS com `includeSubDomains` já está ativo para o site do Amigo Secreto: **subdomínios do próprio
  host** (ex.: `foo.amigo.exemplo.com`) passam a ser forçados a HTTPS no navegador. Hosts irmãos
  (`outra.exemplo.com`) **não** são afetados, porque o HSTS não atravessa o domínio pai. Se um
  subdomínio do host for apontado para outro servidor HTTP, o navegador vai quebrar;
- a renovação é automática enquanto `caddy_data` existir.

## Política de logs por aplicação

Resposta curta: **sim, é possível ter logs diferentes por projeto**. O access log do Caddy é
*opt-in por site*; não existe chave global que ligue tudo.

### As três camadas (não confundir)

| Camada | Quem decide | Onde vai |
| --- | --- | --- |
| **Access log do Caddy** | diretiva `log` dentro do bloco do site | arquivo, stdout ou `stderr` |
| **Log de runtime do Caddy** | opção global `log` (bloco de opções) | `stderr` por padrão |
| **Log da aplicação** | app (Spring/logback/etc.) | `stdout` → driver do Docker |

Desligar o access log do Amigo Secreto **não** afeta os logs da aplicação nem os logs de outra app.

### Amigo Secreto: sem access log (regra de privacidade)

As URLs carregam credenciais (`/admin/{token}`, `/p/{token}`, `/join/{code}`). O token está no
**path**, não na query string. Um access log gravaria tokens em disco. Por isso o bloco do site
**não** tem `log`.

Se algum dia for indispensável ter telemetria, as opções seguras são:

```caddyfile
log {
    format filter {
        # apaga a URI inteira (mantém método, status, duração)
        request>uri delete
    }
}
```

ou redigir só o segmento:

```caddyfile
log {
    format filter {
        request>uri regexp /(admin|p|join)/[^/?]+ /$1/REDACTED
    }
}
```

> O encoder `filter` tem uma ação específica para **query string** (`query { ... }`), mas ela não
> resolve este caso: o token está no path. E o Caddy redige `Cookie`/`Set-Cookie`/`Authorization`
> por padrão, **mas não redige a URL** — por isso a regra de não logar.

### Outra aplicação: access log habilitado

```caddyfile
outra.exemplo.com {
    log {
        output file /var/log/caddy/outra.log {
            roll_size 50MiB
            roll_keep 5
            roll_keep_for 720h
        }
        format json
    }

    reverse_proxy outra-app:3000
}
```

Ou, para container, `log` sem `output` (vai para `stderr` → `docker logs`).

### Rotação e disco

O driver `json-file` do Docker **não tem limite por padrão**. Configure por serviço:

```yaml
logging:
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"
```

Se o Caddy escrever em arquivo, monte `/var/log/caddy` em volume e use `roll_size`/`roll_keep`/
`roll_keep_for`. Sem isso, o disco de 29 GB vira o gargalo.

### Redução de volume

- `log_skip` para healthchecks e estáticos;
- `sampling` para tráfego alto;
- `log_level` por requisição, se necessário.

### Retenção

Access log é dado pessoal. Defina prazo (30–90 dias é razoável) e faça o `roll_keep_for` cumprir
sozinho. Documente a decisão no projeto dono do site.

## Migração para o stack de borda (aplicada)

O que já está no repositório:

- [x] `docker-compose.prod.yml` sem `caddy`, com `container_name: amigo-app` e redes `internal` + `edge`.
- [x] `Caddyfile` da raiz removido (a fonte de verdade passou a ser o stack de borda).
- [x] `APP_IMAGE`/`APP_TAG`, `mem_limit`, `cpus`, logging e imagem ARM64 publicada pelo CI.

O que fica **fora** do repositório:

1. Criar a rede: `docker network create edge`.
2. Criar `/opt/edge` com `compose.yml`, `Caddyfile`, `sites/amigo-secreto.caddy` e `.env`
   (`DOMAIN`, `ACME_EMAIL`).
3. `docker compose -f /opt/edge/compose.yml up -d`.
4. No projeto: `APP_TAG=<sha> bash scripts/deploy.sh`.
5. Validar: HTTPS, `/actuator` 404, HSTS e `docker ps` mostrando só o Caddy publicando portas.

**Cuidado na primeira migração:** não rode os dois Caddys ao mesmo tempo — 80/443 só aceitam um
processo. Pare o antigo antes de subir o novo, ou aceite alguns segundos de 502.

## Riscos e pontos abertos

- **Recursos:** 2 vCPU e 11 GB para N apps. Defina `mem_limit` e `cpus` por serviço; sem isso, um
  OOM em uma app pode derrubar as outras.
- **Disco:** 29 GB livres. Imagens, camadas, logs e backups competem. Não compile na VPS; controle
  rotação e retenção.
- **Blast radius da borda:** o Caddy é ponto único de falha para todos os sites. Se ele cair, todos
  caem. `restart: unless-stopped` mitiga, não elimina.
- **Acoplamento de config:** o stack de borda passa a ser o lugar onde todo site é registrado. Trate
  `edge/` como infraestrutura versionada, com revisão.
- **Portas:** 80/443 continuam sendo as únicas públicas. Qualquer necessidade de porta extra deve
  ser justificada e refletida na Security List da Oracle.
- **Docker + firewall do host:** o Docker fura o `INPUT`; a proteção real é a Security List e a
  disciplina de não publicar. Ver [`DEPLOY.md#firewall-e-exposição-de-portas`](./DEPLOY.md#firewall-e-exposição-de-portas).

## Decisões registradas

| Data | Decisão | Motivo |
| --- | --- | --- |
| 2026-11-14 | Manter 3 containers no deploy do Amigo Secreto (não 1) | Postgres não vive no container da JVM; backup e schema dependem dele |
| 2026-11-14 | Só o Caddy publica portas | TLS/HSTS centralizados, superfície mínima, firewall do host não protege container |
| 2026-11-14 | Build fora da VPS, imagem ARM64 de registry | 2 vCPU/29 GB; CI atual gera amd64 e quebraria com `exec format error` |
| 2026-11-14 | Limite de memória explícito na JVM | `MaxRAMPercentage=75` sem limite usa os 11 GB do host |
| 2026-11-14 | Access log desligado no site do Amigo Secreto | tokens no path das URLs |
| 2026-11-14 | Caddy desacoplável para stack de borda (opção A) quando houver segunda app | evita acoplar ciclos de deploy; roteia por SNI na 443 |
| 2026-11-15 | Caddy desacoplado de fato: `edge` fora deste repositório | a app publica só `expose: 8080`; o ingress é compartilhado e roteia várias apps |
