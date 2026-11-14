# Amigo Secreto

Aplicação web para organizar amigo secreto **sem login**, com acesso por links que carregam tokens. Serve para uso real (família e trabalho) e como projeto de portfólio, com deploy em VPS própria.

> **Status:** MVP funcional. Fluxo de evento, convite, participantes, restrições, sorteio, refazer/reabrir, página do participante e segurança básica implementados. **106 testes** passando.

## Funcionalidades

- Criar evento e receber três links: painel do organizador, página do organizador como participante e convite.
- Entrada por convite público (`/join/{inviteCode}`) ou adição manual pelo painel.
- Teto de 100 participantes, com lock no evento para não furar o limite em concorrência.
- Wishlist por participante, editável antes e depois do sorteio.
- Grupos de exclusão (casais, famílias, setores) que nunca podem se tirar.
- Sorteio em **ciclo único**, com busca em profundidade e restrições rígidas.
- Refazer sorteio mantendo os links e avisando quem já tinha visto; reabrir o evento para editar.
- Página do participante mostrando **somente** o próprio resultado e a wishlist de quem tirou.
- Painel nunca exibe quem tirou quem.
- Apagar evento com confirmação e cascata.

## Segurança e privacidade

- Sem login: **o link é a credencial**. Quem tem o link vê o resultado daquele participante.
- Tokens de 128 bits com `SecureRandom`, prefixos distintos (`adm_`, `inv_`, `p_`) e índices únicos.
- Token inválido retorna **404**, sem confirmar existência.
- CSRF próprio por token de sessão em todos os POSTs (não há Spring Security).
- Cookie de sessão `HttpOnly` + `SameSite=Lax`; `Secure` por padrão (o perfil `dev` desativa para HTTP local).
- Rotas sensíveis com `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options`, `X-Frame-Options`, CSP e `X-Robots-Tag`.
- `robots.txt` bloqueia indexação.
- Quem tem acesso direto ao banco vê os `Assignment` em claro. É um risco aceito do MVP; detalhes em [docs/IDEAS.md](docs/IDEAS.md).

## Stack

- **Java 25** + **Spring Boot 4.1.2** (webmvc, data-jpa, validation, thymeleaf, actuator)
- **Flyway** desde a V1; `ddl-auto=validate` (nunca `update`)
- **PostgreSQL** em dev/prod; **H2** apenas nos testes
- Thymeleaf + CSS próprio (sem SPA, sem HTMX no MVP)
- Docker multi-stage + Docker Compose + Caddy (HTTPS automático) no deploy

## Como rodar localmente

Pré-requisitos: **JDK 25** e **Docker** (para o Postgres). No Windows, use `.\mvnw.cmd`; no Linux/macOS, `./mvnw`.

1. Suba o Postgres de desenvolvimento:

   ```bash
   docker compose up -d
   ```

2. Rode a aplicação com o perfil `dev` (cookie sem `Secure`, pois é HTTP local):

   ```powershell
   .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   Acesse `http://localhost:8080`.

3. Testes (H2 em memória, sem Docker):

   ```powershell
   .\mvnw.cmd test
   ```

4. Build do jar:

   ```powershell
   .\mvnw.cmd -DskipTests package
   ```

Variáveis principais: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `APP_BASE_URL` e `SESSION_COOKIE_SECURE`. Veja [`.env.example`](.env.example).

## Deploy

O passo a passo completo está em [docs/DEPLOY.md](docs/DEPLOY.md): Dockerfile multi-stage, `docker-compose.prod.yml` (app + Postgres + Caddy), HTTPS/Let's Encrypt, healthcheck, backup e restore.

```bash
cp .env.prod.example .env
# preencha DOMAIN, ACME_EMAIL, POSTGRES_*
docker compose -f docker-compose.prod.yml up -d --build
```

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
├── admin/          AdminController
└── common/         tokens, links, CSRF, headers, erros, configuração
```

A direção é controller → service → repository. Entidades JPA não viram modelo de tela; as telas usam `record`s.

## Decisões e trade-offs

- **Sem login** mantém o produto simples e o link é a credencial. Em troca, quem tem o link vê o resultado e o histórico do navegador guarda a URL.
- **Spring Boot** foi escolhido por relevância profissional; o projeto evita recursos que não precisa (sem Security, sem SPA).
- **Ciclo único** é fácil de explicar e garante que não há troca direta; o custo é a busca mais restrita que uma permutação genérica.
- **Restrições rígidas** em vez de pesos: casais nunca se tiram.
- **Sem e-mail no MVP:** os links são copiados/compartilhados manualmente. A fase 2 está desenhada na especificação.
- **Flyway desde a V1:** o schema é versionado; não há `ddl-auto=update`.
- **H2 nos testes:** rápido e sem Docker; o deploy valida Postgres.

Ideias e riscos aceitos estão em [docs/IDEAS.md](docs/IDEAS.md).

## Documentação

- [Especificação do projeto](<./Amigo Secreto — Especificação do Projeto.md>) — fonte da verdade de escopo, domínio e segurança.
- [Plano de implementação](implementation.md) — blocos, estrutura e estado atual.
- [Deploy](docs/DEPLOY.md) — VPS, Docker, Caddy, backup.
- [Ideias e pendências](docs/IDEAS.md) — o que ficou para depois e riscos aceitos.
- [E-mail com Brevo](docs/EMAIL-BREVO.md) — guia da fase 2: domínio, API, outbox e testes.

## Testes

A suíte cobre o solver (incluindo o caso de 16 participantes em dois grupos de 8), entidades/repositórios, serviços transacionais, CSRF, headers, 404, IDOR, XSS, fluxo de convite, sorteio/refazer/reabrir e página do participante.

```bash
./mvnw test
```
