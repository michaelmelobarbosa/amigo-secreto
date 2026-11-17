# Amigo Secreto

Aplicação web para organizar amigo secreto **sem login**, com acesso por links que carregam tokens. Feita para uso real (família e trabalho) e como projeto de portfólio, com deploy em VPS própria.

**Status:** em produção em `https://amigo-secreto.barbosalabs.dev` desde 2026-11-15. MVP completo, e-mail transacional ativo desde 2026-11-16 e interface renovada em 2026-11-17. **164 testes** passando.

## Funcionalidades

- Criação de evento com três links: painel do organizador, página do organizador como participante e convite.
- Entrada por convite público (`/join/{inviteCode}`) ou adição manual pelo painel.
- Teto de 100 participantes, com lock no evento para não furar o limite em concorrência.
- Wishlist por participante, editável antes e depois do sorteio.
- Grupos de exclusão (casais, famílias, setores) que nunca podem se tirar.
- Sorteio em **ciclo único**, com busca em profundidade e restrições rígidas.
- Refazer sorteio mantendo os links e avisando quem já tinha visto; reabrir o evento para editar.
- Página do participante mostrando **somente** o próprio resultado e a wishlist de quem tirou.
- Painel que nunca exibe quem tirou quem.
- Apagar evento com confirmação e cascata.
- E-mail opcional por participante: após o sorteio, o link individual é enviado pela API do Brevo, com outbox, retry e status no painel.

## Segurança e privacidade

- Sem login: **o link é a credencial**. Quem tem o link vê o resultado daquele participante.
- Tokens de 128 bits com `SecureRandom`, prefixos distintos (`adm_`, `inv_`, `p_`) e índices únicos.
- Token inválido retorna **404**, sem confirmar existência.
- CSRF próprio por token de sessão em todos os POSTs (não há Spring Security).
- Rate limiting em memória por IP em criação, convite, sorteio, wishlist e endpoints de e-mail, com `429` + `Retry-After`.
- Cookie de sessão `HttpOnly` + `SameSite=Lax`; `Secure` por padrão. A sessão é **só por cookie**, sem `;jsessionid` na URL.
- Rotas sensíveis com `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, CSP, `X-Frame-Options`, `X-Content-Type-Options` e `X-Robots-Tag`.
- `robots.txt` bloqueia indexação.
- Quem tem acesso direto ao banco vê os `Assignment` em claro. É um risco aceito do MVP.

O modelo de ameaças, os limites e os riscos aceitos estão em [docs/SECURITY.md](docs/SECURITY.md).

## Stack

- **Java 25** + **Spring Boot 4.1.2** (webmvc, data-jpa, validation, thymeleaf, actuator)
- **Flyway** desde a V1; `ddl-auto=validate` (nunca `update`)
- **PostgreSQL** em dev/prod; **H2** apenas nos testes
- Thymeleaf + CSS próprio, sem SPA
- Brevo (API transacional) para e-mail opcional
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

   > No PowerShell, prefira a variável de ambiente a `-Dspring-boot.run.profiles=dev`, que pode ser
   > interpretado como duas opções.

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

O Spring Boot **não lê `.env` sozinho**. Exporte as variáveis na sessão antes de subir o app:

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

Em produção, o `docker-compose.prod.yml` passa essas variáveis a partir do `.env` do servidor. Guia completo: [docs/EMAIL.md](docs/EMAIL.md).

## Como o sorteio funciona

O evento tem dois estados: `OPEN` e `DRAWN`.

- **OPEN:** inscrições e edição de participantes/grupos liberadas.
- **DRAWN:** resultados gravados; participantes e grupos ficam bloqueados até “Reabrir e editar”.
- Mínimo para sortear: **3 participantes sem restrições** ou **4 com pelo menos um grupo**.
- O ciclo é único: A tira B, B tira C, ..., o último tira A. Ninguém tira a si mesmo e não existe troca direta.
- O algoritmo é backtracking com heurística MRV, não tentativa e erro. Restrições são regras rígidas.
- Refazer apaga os `Assignment` antigos, incrementa `drawCount`, zera `firstViewedAt` e mantém os links.
- Reabrir volta para `OPEN`, apaga os `Assignment` e limpa `firstViewedAt`.

## Arquitetura

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
└── common/         tokens, links, CSRF, headers, rate limit, erros, configuração
```

A direção é controller → service → repository. Entidades JPA não viram modelo de tela; as telas usam `record`s. Detalhes de código, convenções e testes em [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Deploy

O passo a passo está em [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md): imagem ARM64 publicada no GHCR pelo CI, Compose com app + Postgres, healthcheck, Caddy de borda, backup e restore.

```bash
cp .env.prod.example .env
# preencha POSTGRES_*, DOMAIN, APP_IMAGE e APP_TAG (SHA da imagem publicada pelo CI)
APP_TAG=<sha-do-commit> bash scripts/deploy.sh
```

A app não publica portas: entra na rede externa `edge` e o Caddy a alcança em `amigo-app:8080`.

## Decisões e trade-offs

- **Sem login** mantém o produto simples e o link é a credencial. Em troca, quem tem o link vê o resultado e o histórico do navegador guarda a URL. O painel oferece cópia, download em TXT e envio dos links por e-mail, mas não há recuperação automática se tudo for perdido.
- **Spring Boot** foi escolhido por relevância profissional; o projeto evita recursos que não precisa (sem Security, sem SPA).
- **Ciclo único** é fácil de explicar e garante que não há troca direta; o custo é uma busca mais restrita que uma permutação genérica.
- **Restrições rígidas** em vez de pesos: casais nunca se tiram.
- **E-mail na fase 2:** opcional por participante, enviado pela API do Brevo, com outbox, idempotência e retry. O link continua sendo a credencial; e-mail não é login.
- **Flyway desde a V1:** o schema é versionado; não há `ddl-auto=update`.
- **H2 nos testes:** rápido e sem Docker; o deploy valida Postgres.

## Documentação

- [Especificação](docs/SPEC.md) — fonte da verdade de escopo, domínio, fluxos e segurança.
- [Desenvolvimento](docs/DEVELOPMENT.md) — arquitetura, convenções, configuração e testes.
- [Deploy](docs/DEPLOYMENT.md) — VPS, Docker, registry, borda, backup e operação.
- [E-mail](docs/EMAIL.md) — Brevo, domínio, outbox, limites e testes.
- [Segurança](docs/SECURITY.md) — modelo de ameaças, CSRF, headers, rate limiting e riscos aceitos.

O backlog de produto e as pendências operacionais ficam fora do repositório, porque incluem dados reais de infraestrutura.

## Testes

A suíte cobre o solver (incluindo o caso de 16 participantes em dois grupos de 8), entidades/repositórios, serviços transacionais, CSRF, headers, 404, IDOR, XSS, fluxo de convite, sorteio/refazer/reabrir, página do participante, adaptador Brevo, outbox, retry/reenvio, e-mail no fluxo web, download/envio dos links do organizador e rate limiting.

```bash
./mvnw test
```
