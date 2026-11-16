# Amigo Secreto

Aplicação web para organizar amigo secreto **sem login**, com acesso por links que carregam tokens. Serve para uso real (família e trabalho) e como projeto de portfólio, com deploy em VPS própria.

> **Status:** em produção em `https://amigo-secreto.barbosalabs.dev` desde 2026-11-15. MVP funcional com e-mail na fase 2 e rate limiting em memória. Fluxo de evento, convite, participantes, restrições, sorteio, refazer/reabrir, página do participante, segurança básica, envio do link por Brevo e limite de requisições por IP implementados. **159 testes** passando.

## Funcionalidades

- Criar evento e receber três links: painel do organizador, página do organizador como participante e convite, com cópia, download em TXT e envio por e-mail sob demanda.
- Entrada por convite público (`/join/{inviteCode}`) ou adição manual pelo painel.
- Teto de 100 participantes, com lock no evento para não furar o limite em concorrência.
- Wishlist por participante, editável antes e depois do sorteio.
- Grupos de exclusão (casais, famílias, setores) que nunca podem se tirar.
- Sorteio em **ciclo único**, com busca em profundidade e restrições rígidas.
- Refazer sorteio mantendo os links e avisando quem já tinha visto; reabrir o evento para editar.
- Página do participante mostrando **somente** o próprio resultado e a wishlist de quem tirou.
- Painel nunca exibe quem tirou quem.
- Apagar evento com confirmação e cascata.
- E-mail opcional por participante: após o sorteio, o link individual é enviado pela API do Brevo, com outbox, retry e status no painel.

## Segurança e privacidade

- Sem login: **o link é a credencial**. Quem tem o link vê o resultado daquele participante.
- Tokens de 128 bits com `SecureRandom`, prefixos distintos (`adm_`, `inv_`, `p_`) e índices únicos.
- Token inválido retorna **404**, sem confirmar existência.
- CSRF próprio por token de sessão em todos os POSTs (não há Spring Security).
- Rate limiting em memória por IP em `POST /events`, `/join`, `draw`/`redraw` e wishlist, com `429` + `Retry-After` ([plano e limites](docs/RATE-LIMITING.md)). Validado em produção em 2026-11-15, inclusive contra `X-Forwarded-For` forjado.
- Cookie de sessão `HttpOnly` + `SameSite=Lax`; `Secure` por padrão (o perfil `dev` desativa para HTTP local).
- Rotas sensíveis com `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options`, `X-Frame-Options`, CSP e `X-Robots-Tag`.
- `robots.txt` bloqueia indexação.
- Quem tem acesso direto ao banco vê os `Assignment` em claro. É um risco aceito do MVP; detalhes em [docs/IDEAS.md](docs/IDEAS.md).

## Stack

- **Java 25** + **Spring Boot 4.1.2** (webmvc, data-jpa, validation, thymeleaf, actuator)
- **Flyway** desde a V1; `ddl-auto=validate` (nunca `update`)
- **PostgreSQL** em dev/prod; **H2** apenas nos testes
- Thymeleaf + CSS próprio (sem SPA, sem HTMX no MVP)
- Brevo (API transacional) para e-mail opcional na fase 2
- Docker multi-stage + Docker Compose (app + Postgres); Caddy de borda externo com HTTPS automático

## Como rodar localmente

Pré-requisitos: **JDK 25** e **Docker** (para o Postgres). No Windows, use `.\mvnw.cmd`; no Linux/macOS, `./mvnw`.

1. Suba o Postgres de desenvolvimento:

   ```bash
   docker compose up -d
   ```

2. Rode a aplicação com o perfil `dev` (cookie sem `Secure`, pois é HTTP local):

   ```powershell
   $env:SPRING_PROFILES_ACTIVE="dev"
   .\mvnw.cmd spring-boot:run
   ```

   Acesse `http://localhost:8080`.

   > No PowerShell, evite passar `-Dspring-boot.run.profiles=dev` sem aspas: ele pode ser
   > interpretado como duas opções. A variável de ambiente acima é mais simples e não depende disso.

3. Testes (H2 em memória, sem Docker):

   ```powershell
   .\mvnw.cmd test
   ```

4. Build do jar:

   ```powershell
   .\mvnw.cmd -DskipTests package
   ```

Variáveis principais: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `APP_BASE_URL`, `SESSION_COOKIE_SECURE`, as de e-mail (`EMAIL_ENABLED`, `BREVO_API_KEY`, `BREVO_FROM_EMAIL`, `BREVO_SANDBOX`) e `RATE_LIMIT_*`. No deploy, também `APP_IMAGE`, `APP_TAG` e `EDGE_NETWORK`. Veja [`.env.example`](.env.example) e [`.env.prod.example`](.env.prod.example).

### Habilitar e-mail localmente

O Spring Boot **não lê `.env` sozinho**. Para testar o envio real, exporte as variáveis na sessão do PowerShell antes de subir o app:

```powershell
$env:EMAIL_ENABLED="true"
$env:BREVO_API_KEY="xkeysib-..."
$env:BREVO_FROM_EMAIL="no-reply@mail.barbosalabs.dev"
$env:BREVO_FROM_NAME="Amigo Secreto"
$env:BREVO_SANDBOX="true"   # valida sem entregar; use false para envio real
$env:EMAIL_WORKER_ENABLED="true"

$env:SPRING_PROFILES_ACTIVE="dev"
.\mvnw.cmd spring-boot:run
```

Em produção, o `docker-compose.prod.yml` passa essas variáveis a partir do `.env` do servidor. Guia completo: [docs/EMAIL-BREVO.md](docs/EMAIL-BREVO.md).

## Deploy

O passo a passo completo está em [docs/DEPLOY.md](docs/DEPLOY.md): imagem ARM64 publicada no GHCR pelo CI, `docker-compose.prod.yml` (app + Postgres), healthcheck, backup e restore. O **Caddy é um stack de borda separado**, compartilhado com outras aplicações da VPS; a configuração dele está em [docs/INFRA-VPS.md](docs/INFRA-VPS.md).

**Estado:** deploy executado em **2026-11-15**; registro em
[docs/DEPLOY_CONCLUIDO.md](docs/DEPLOY_CONCLUIDO.md). TLS Let's Encrypt, Flyway V1–V3, backup diário
por systemd timer. O e-mail está desligado em produção até a API key do Brevo.

```bash
cp .env.prod.example .env
# preencha POSTGRES_*, DOMAIN, APP_IMAGE e APP_TAG (SHA da imagem publicada pelo CI)
APP_TAG=<sha-do-commit> bash scripts/deploy.sh
```

A app não publica portas: ela entra na rede externa `edge` e o Caddy a alcança em `amigo-app:8080`.

## Como o sorteio funciona

O evento tem dois estados: `OPEN` e `DRAWN`.

- **OPEN:** inscrições e edição de participantes/grupos liberadas.
- **DRAWN:** resultados gravados; participantes e grupos ficam bloqueados até “Reabrir e editar”.
- Mínimo para sortear: **3 participantes sem restrições** ou **4 com pelo menos um grupo**.
- O ciclo é único: A tira B, B tira C, ..., o último tira A. Ninguém tira a si mesmo e não existe troca direta.
- O algoritmo é backtracking com heurística MRV, não tentativa e erro. Restrições são regras rígidas.
- Refazer apaga os `Assignment` antigos, incrementa `drawCount`, zera `firstViewedAt` e mantém os links.
- Reabrir volta para `OPEN`, apaga os `Assignment` e limpa `firstViewedAt`.

## Estrutura

```
src/main/java/br/com/michael/amigosecreto/
├── event/          Event, EventRepository, EventService, EventController
├── participant/    Participant, ParticipantRepository, ParticipantService, ParticipantController
├── exclusion/      ExclusionGroup, ExclusionGroupService, ExclusionGroupRepository
├── draw/           Assignment, AssignmentRepository, DrawService, CycleDrawSolver
├── invite/         InviteService, JoinController
├── notification/   Notification, NotificationService, listener, worker
├── email/          EmailSender, BrevoEmailSender, EmailContentBuilder
├── admin/          AdminController
└── common/         tokens, links, CSRF, headers, erros, configuração
```

A direção é controller → service → repository. Entidades JPA não viram modelo de tela; as telas usam `record`s.

## Decisões e trade-offs

- **Sem login** mantém o produto simples e o link é a credencial. Em troca, quem tem o link vê o resultado e o histórico do navegador guarda a URL. O painel oferece cópia, download em TXT e envio dos links por e-mail, mas não há recuperação automática se tudo for perdido.
- **Spring Boot** foi escolhido por relevância profissional; o projeto evita recursos que não precisa (sem Security, sem SPA).
- **Ciclo único** é fácil de explicar e garante que não há troca direta; o custo é a busca mais restrita que uma permutação genérica.
- **Restrições rígidas** em vez de pesos: casais nunca se tiram.
- **E-mail na fase 2:** opcional por participante, enviado pela API do Brevo, com outbox, idempotência e retry. O link continua sendo a credencial; e-mail não é login.
- **Flyway desde a V1:** o schema é versionado; não há `ddl-auto=update`.
- **H2 nos testes:** rápido e sem Docker; o deploy valida Postgres.

Ideias e riscos aceitos estão em [docs/IDEAS.md](docs/IDEAS.md).

## Documentação

- [Especificação do projeto](<./Amigo Secreto — Especificação do Projeto.md>) — fonte da verdade de escopo, domínio e segurança.
- [Plano de implementação](implementation.md) — blocos, estrutura e estado atual.
- [Deploy](docs/DEPLOY.md) — VPS, Docker, registry, backup.
- [Deploy concluído](docs/DEPLOY_CONCLUIDO.md) — execução real de 2026-11-15, evidências e pendências.
- [Infra da VPS](docs/INFRA-VPS.md) — Caddy de borda, rede `edge` e múltiplas aplicações.
- [Ideias e pendências](docs/IDEAS.md) — o que ficou para depois e riscos aceitos.
- [E-mail com Brevo](docs/EMAIL-BREVO.md) — guia da fase 2: domínio, API, outbox e testes.
- [Rate limiting](docs/RATE-LIMITING.md) — implementação, limites e validação no deploy.

## Testes

A suíte cobre o solver (incluindo o caso de 16 participantes em dois grupos de 8), entidades/repositórios, serviços transacionais, CSRF, headers, 404, IDOR, XSS, fluxo de convite, sorteio/refazer/reabrir, página do participante, adaptador Brevo, outbox, retry/reenvio, e-mail no fluxo web, download/envio dos links do organizador e rate limiting (janela, IP/categoria, IPv6 e resposta 429).

```bash
./mvnw test
```
