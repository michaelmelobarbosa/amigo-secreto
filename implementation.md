# Plano de Implementação — Amigo Secreto

Documento de trabalho derivado da especificação ([especificação](<./Amigo Secreto — Especificação do Projeto.md>)). Descreve a estrutura planejada, classes, métodos, relações e ordem de implementação. Nada aqui substitui a spec: em caso de divergência, a spec é a referência e deve ser atualizada.

- **Stack:** Java 25, Spring Boot 4.1.2, Spring MVC + Thymeleaf, Spring Data JPA, Bean Validation, Flyway, PostgreSQL (dev/prod) e H2 (testes).
- **Princípios:** sem login, acesso por token, poucas telas, sem SPA, sem Spring Security.
- **E-mail (fase 2 implementada):** canal opcional via Brevo; o caminho manual (copiar/WhatsApp) continua. Ver spec §15.
- **Migrações:** Flyway desde a V1; `spring.jpa.hibernate.ddl-auto=validate`.

---

## 0. Estado atual do código

Já implementado:

- [`CycleDrawSolver`](src/main/java/br/com/michael/amigosecreto/draw/CycleDrawSolver.java) — backtracking com MRV, `RandomGenerator` injetável e orçamento de passos.
  - API: `draw(List<UUID> participants, Map<UUID, Set<UUID>> forbidden) → Map<UUID, UUID>`.
- [`DrawImpossibleException`](src/main/java/br/com/michael/amigosecreto/draw/DrawImpossibleException.java) — com `Reason` (`TOO_FEW_PARTICIPANTS`, `FORBIDDEN_PAIR_WITH_THREE_PARTICIPANTS`, `NO_ALLOWED_TARGET`, `NO_SOLUTION`, `SEARCH_LIMIT_EXCEEDED`).
- [`AmigoSecretoApplication`](src/main/java/br/com/michael/amigosecreto/AmigoSecretoApplication.java) — pacote `br.com.michael.amigosecreto` e `@ConfigurationPropertiesScan`.
- Entidades `Event`, `EventStatus`, `Participant`, `ExclusionGroup` e `Assignment`.
- Repositórios `EventRepository`, `ParticipantRepository`, `ExclusionGroupRepository` e `AssignmentRepository`.
- `TokenGenerator` (prefixos `adm_`, `inv_`, `p_`), `LinkBuilder`, `AppProperties` e `NotFoundException`.
- `EventService` (criar, consultar por token, organizador, apagar, inscrições, regenerar convite, reabrir), `EventController`, `AdminController` e `InviteService`.
- `ParticipantService` (adicionar, entrar por convite, remover, wishlist, primeira visualização, resultado e projeções), `JoinController` e `ParticipantController`.
- `ExclusionGroupService` (criar, remover e projetar grupos), `DrawService` (sortear/refazer), `DrawCompletedEvent` e `DrawConfig`.
- Fundação web: CSRF de sessão, `SecurityHeadersFilter`, `GlobalExceptionHandler`, `GlobalBindingAdvice`, `WebConfig`, layout/fragmentos, CSS e JS.
- Templates `index`, `admin/dashboard`, `join/form`, `join/closed`, `participant/view`, erros 403/404/500.
- Segurança: cookie `Secure` por padrão (perfil `dev` desativa), headers em todas as rotas dinâmicas, CSP sem inline, `robots.txt`, CSP/`data-confirm`, preview bots não marcam `firstViewedAt`.
- Rate limiting em memória por IP e categoria: `POST /events` (5/10 min), `/join` (30/10 min), `draw`/`redraw` (10/h) e wishlist (30/10 min), com `429` + `Retry-After` e mapa LRU limitado. Validado em produção em 2026-11-15, inclusive contra `X-Forwarded-For` forjado.
- E-mail (fase 2, ativo em produção desde 2026-11-16): `Participant.email`, tabela `notification`, `EmailSender`/`BrevoEmailSender`, `EmailContentBuilder`, listener de `DrawCompletedEvent` (nova transação pós-commit), worker com retry/idempotência e status/reenvio no painel.
- Acesso do organizador: cartão de links com cópia, download em TXT e envio por e-mail sob demanda (`OrganizerLinksMailer`), sem persistir o endereço e sem virar login.
- Deploy: `Dockerfile` multi-stage sem QEMU (build nativo + runtime copiado), `docker-compose.prod.yml` (app + Postgres, imagem do GHCR com tag SHA, `mem_limit`/`cpus` e logging), Caddy de borda externo, `scripts/deploy.sh`, scripts de backup/restore, `docs/DEPLOY.md` (operação + decisões), `docs/INFRA-VPS.md` (stack de borda e múltiplas apps) e `docs/DEPLOY_CONCLUIDO.md` (execução real em 2026-11-15).
- Portfólio: `README.md`, `docs/IDEAS.md` e CI em `.github/workflows/ci.yml`.
- Testes do solver, token, link, mapeamento JPA, serviços, fluxos web com MockMvc, adaptador Brevo, outbox, e-mail e rate limiting (164 testes).

**Deploy real concluído em 2026-11-15:** `https://amigo-secreto.barbosalabs.dev` no ar com TLS Let's Encrypt, Flyway V1–V3 em Postgres 17, `amigo-app` + `amigo-secreto-db-1` sem portas publicadas, Caddy de borda `edge-caddy-1` e backup diário por systemd timer. Imagem pública no GHCR (`2b6e2d4…`). Registro completo em [`docs/DEPLOY_CONCLUIDO.md`](docs/DEPLOY_CONCLUIDO.md).

**Bloco 0 (base) concluído:** POM final, `docker-compose.yml` (Postgres 17), Flyway V1, `application.yaml`/`application-test.yaml`, `ddl-auto=validate` e `.env.example`. `mvnw test` verde e a V1 aplicada no H2. Na época, o Compose de dev não foi executado porque o Docker não estava instalado na máquina de desenvolvimento; a VPS tem Docker desde o deploy de 2026-11-15.

**Bloco 1 (domínio) concluído:** entidades e repositórios mapeados; `ddl-auto=validate` passou contra a V1; `LinkBuilder` e `TokenGenerator` testados. O `EventRepository.deleteByIdBulk` foi necessário para apagar o evento sem o Hibernate reclamar de referência transitória; a cascata real fica no banco (`ON DELETE CASCADE`).

**Bloco 2 (fundação web + evento) concluído:** CSRF de sessão, headers de privacidade, 404/403, criação de evento, painel com links/participantes, apagar evento e teste HTTP de ponta a ponta.

**Bloco 3 (participantes + convite) concluído:** adicionar/remover participantes, entrada por convite, abrir/fechar inscrições, regenerar código, wishlist e página do participante (espera/resultado quando houver `Assignment`).

**Bloco 4 (restrições + sorteio) concluído:** grupos de exclusão no painel, sorteio com `CycleDrawSolver`, refazer e reabrir, resultado na página do participante, publicação de `DrawCompletedEvent` após o commit.

**Bloco 5 (segurança + consistência) concluído:** auditorias de segurança e de JPA, cookie `Secure` por padrão, headers em redirects, CSP + `data-confirm`, `robots.txt`, página 500, escopo da wishlist, nomes duplicados case-insensitive, preview bots, limpeza de grupos degenerados e testes de segurança (106 testes).

**Bloco 6 (deploy + portfólio) concluído:** Dockerfile multi-stage, Compose de produção, Caddy/HTTPS, backup/restore, `docs/DEPLOY.md`, `docs/INFRA-VPS.md`, README, `docs/IDEAS.md` e CI. O ambiente foi medido em 2026-11-15: Ubuntu 24.04.5, ARM64, 2 vCPU, 11 GiB, 28 GB livres, Docker 29.9.3/Compose v5.7.1, **sem `cron`**, Hermes em loopback (8080/9119/9120). A execução real está no Bloco 11; P1–P6 foram aplicadas no Bloco 10.

**Bloco 7 (e-mail / Brevo) concluído:** conta e domínio `mail.barbosalabs.dev` autenticados, sender e API key validados, envio real testado; migrações V2/V3, outbox `notification`, `BrevoEmailSender`, listener pós-commit, worker com retry/idempotência, templates HTML/texto, e-mail opcional no join/adição manual e status/reenvio no painel. 139 testes verdes na entrega.

**Bloco 8 (acesso do organizador) concluído:** cartão de links sempre visível no painel, download em TXT, envio dos três links por e-mail sob demanda, aviso de risco e confirmação ao sair da tela de criação sem copiar/baixar/enviar. O endereço digitado não é persistido. 147 testes verdes.

**Bloco 9 (rate limiting) concluído:** `RateLimitFilter` em memória por IP e categoria, `RateLimitProperties` (`app.rate-limit`), resposta `429` com `Retry-After` e HTML mínimo, configuração por `RATE_LIMIT_*` no Compose de produção, teste no `application-test.yaml` e `RateLimitFilterTest`. Validação real em 2026-11-15: cinco `POST /events` passaram, o sexto retornou `429` com `Retry-After: 600`, e `X-Forwarded-For` forjado não criou bucket novo.

**Bloco 10 (deploy aplicável) concluído:** P1–P6 aplicadas (Postgres de dev em loopback, `mem_limit`/`cpus`, rotação de log, platform args, CI publica ARM64 no GHCR e Compose usa `APP_IMAGE:APP_TAG`). O Caddy foi desacoplado: o Compose do projeto tem só `app` + `db`, a app entra na rede externa `edge` como `amigo-app`, e o stack de borda vive em [`docs/INFRA-VPS.md`](docs/INFRA-VPS.md). Adicionados `scripts/deploy.sh`, `.env.prod.example` com imagem/tag/rede e a imagem de runtime sem `curl` (BusyBox estático para o HEALTHCHECK). O build ARM64 não usa QEMU.

**Bloco 11 (deploy real) concluído:** `https://amigo-secreto.barbosalabs.dev` no ar em 2026-11-15; DNS A na Name.com, Security List Oracle (TCP 80/443 + UDP 443), stack `edge` com `edge-caddy-1`, imagem pública `ghcr.io/michaelmelobarbosa/amigo-secreto:2b6e2d4…`, Flyway V1–V3 e backup diário por systemd timer. Primeira tentativa falhou por `BREVO_FROM_NAME` sem aspas no `.env` (P10), corrigido no servidor. P9–P11 foram aplicadas depois; P12 foi aplicada e verificada no Caddy em 2026-11-16. Em 2026-11-16 o e-mail foi **ligado e validado ponta a ponta**: 2 notificações `SENT`, `attempts=1`, painel **Enviado**, zero erros, evento de teste apagado; o SPF foi publicado no mesmo dia e o rate limit dos endpoints de e-mail ficou no código. Pendentes: deploy da imagem do commit novo (P15, que leva o rate limit), cópia off-site, restore completo, reboot e fluxo no navegador. Ver [`docs/DEPLOY_CONCLUIDO.md`](docs/DEPLOY_CONCLUIDO.md).

---

## 1. Estrutura de pacotes

```
br.com.michael.amigosecreto
├── AmigoSecretoApplication
├── common/
│   ├── TokenGenerator
│   ├── LinkBuilder
│   ├── RateLimitProperties              (@ConfigurationProperties: app.rate-limit)
│   ├── config/
│   │   ├── WebConfig                    (interceptors, resolvers, filtros)
│   │   └── AppProperties                (@ConfigurationProperties: app.base-url)
│   └── web/
│       ├── CsrfTokenManager
│       ├── CsrfTokenInterceptor
│       ├── RateLimitFilter              (OncePerRequestFilter: 429 por IP/categoria)
│       ├── SecurityHeadersFilter        (OncePerRequestFilter)
│       └── GlobalExceptionHandler       (@ControllerAdvice)
├── event/
│   ├── Event
│   ├── EventStatus                     (enum: OPEN, DRAWN)
│   ├── EventRepository
│   ├── EventService
│   ├── EventController
│   └── dto/ (CreateEventForm, EventSummary, ...)
├── participant/
│   ├── Participant
│   ├── ParticipantRepository
│   ├── ParticipantService
│   ├── ParticipantController
│   └── dto/ (ParticipantRow, JoinForm, WishlistForm, ParticipantPageView)
├── exclusion/
│   ├── ExclusionGroup
│   ├── ExclusionGroupRepository
│   ├── ExclusionGroupService
│   └── dto/ (ExclusionGroupForm, ExclusionGroupView)
├── draw/
│   ├── Assignment
│   ├── AssignmentRepository
│   ├── DrawService
│   ├── DrawCompletedEvent             (record de domínio)
│   ├── CycleDrawSolver                (já existe)
│   └── DrawImpossibleException        (já existe)
├── invite/
│   ├── InviteService
│   └── JoinController
└── admin/
    ├── AdminController
    └── dto/ (AdminDashboardView, ...)
```

`notification/` e `email/` implementam a fase 2 (outbox e Brevo). A direção é sempre controller → service → repository; entidades não vão para a view.

---

## 2. Domínio (entidades e relações)

### `Event` (`event`)

- `UUID id`
- `String name` — `@NotBlank`, máximo 80
- `LocalDate eventDate` — opcional
- `BigDecimal suggestedValue` — opcional (`precision=10, scale=2`)
- `String adminToken` — único, indexado
- `String inviteCode` — único, indexado, regenerável
- `boolean registrationOpen`
- `EventStatus status` — `OPEN | DRAWN`, persistido como string
- `Instant drawnAt` — nulo até o primeiro sorteio
- `int drawCount` — 0 até o primeiro sorteio
- `Instant createdAt`
- Relações:
  - `List<Participant> participants` — `@OneToMany(mappedBy = "event", cascade = ALL, orphanRemoval = true)`
  - `List<ExclusionGroup> exclusionGroups` — `@OneToMany(mappedBy = "event", cascade = ALL, orphanRemoval = true)`

Métodos de domínio pequenos (sem regra de transação):
`isOpen()`, `canJoin()`, `markDrawn(Instant)`, `reopen()`, `regenerateInvite(String)`.

### `Participant` (`participant`)

- `UUID id`
- `Event event` — `@ManyToOne`, obrigatório
- `String name` — `@NotBlank`, máximo 80
- `String accessToken` — único, indexado
- `String wishlist` — texto livre, máximo 2000
- `boolean organizer`
- `Instant firstViewedAt` — nulo até abrir o resultado
- Relação com grupos: `@ManyToMany` via `exclusion_group_member`.
- **Não** guarda `Assignment` (evita carregar listas desnecessárias); consultas vão pelo `AssignmentRepository`.

### `ExclusionGroup` (`exclusion_group` + `exclusion_group_member`)

- `UUID id`
- `Event event` — `@ManyToOne`, obrigatório
- `String label` — opcional
- `Set<Participant> members` — `@ManyToMany`, tabela de junção `exclusion_group_member`
- Invariantes no serviço: pelo menos 2 membros, todos do mesmo evento.

### `Assignment` (`assignment`)

- `UUID id`
- `Event event` — `@ManyToOne`
- `Participant giver` — `@ManyToOne`
- `Participant receiver` — `@ManyToOne`
- Constraints:
  - `UNIQUE(event_id, giver_id)`
  - `UNIQUE(event_id, receiver_id)`
  - `CHECK(giver_id <> receiver_id)`

As duas primeiras garantem “todos dão e recebem exatamente uma vez”; a terceira impede auto-sorteio.

### Convenções de entidade

- `equals`/`hashCode` por `id`; não usar `@Data` em entidade (relações bidirecionais e lazy loading).
- Timestamps em `Instant` (UTC); formatação em `America/Sao_Paulo` na borda (DTO).
- `FetchType.LAZY` por padrão; consultas de tela usam projeções/DTOs.
- `spring.jpa.open-in-view=false` — a view nunca dispara lazy loading.

---

## 3. Persistência (repositories)

### `EventRepository`

- `Optional<Event> findByAdminToken(String)`
- `Optional<Event> findByInviteCode(String)`
- Versões com lock pessimista para operações de escrita:
  - `findByAdminTokenForUpdate(String)`
  - `findByInviteCodeForUpdate(String)`
  - `findByIdForUpdate(UUID)`
- `int deleteByIdBulk(UUID)` — bulk delete com `clearAutomatically`; a cascata real é do banco (`ON DELETE CASCADE`).

### `ParticipantRepository`

- `Optional<Participant> findByAccessToken(String)`
- `Optional<Participant> findByIdAndEvent_Id(UUID id, UUID eventId)` — escopo anti-IDOR
- `Optional<Participant> findByEvent_IdAndOrganizerTrue(UUID eventId)`
- `long countByEvent_Id(UUID eventId)` — teto de 100
- `List<Participant> findByEvent_Id(UUID eventId)` — insumo do sorteio
- `int markFirstViewedIfAbsent(UUID id, UUID eventId, Instant at)` — update atômico
- `int resetFirstViewedAtByEventId(UUID eventId)` — usado no refazer/reabrir
- Projeção para o painel: `List<ParticipantRow> findRowsByEventId(UUID eventId)` com `id`, `name`, `accessToken`, `organizer`, `firstViewedAt`.

### `ExclusionGroupRepository`

- `List<ExclusionGroup> findByEvent_Id(UUID eventId)`
- `Optional<ExclusionGroup> findByIdAndEvent_Id(UUID id, UUID eventId)`
- `List<ExclusionGroup> findByEventIdWithMembers(UUID eventId)` — `join fetch` dos membros

### `AssignmentRepository`

- `Optional<Assignment> findByEvent_IdAndGiver_Id(UUID eventId, UUID giverId)`
- `void deleteByEventId(UUID eventId)` — `@Modifying` em bulk; usar `flush`/`clearAutomatically` antes de reinserir no redraw
- `long countByEventId(UUID eventId)`

---

## 4. Serviços

### `TokenGenerator`

- `String adminToken()`
- `String inviteCode()`
- `String accessToken()`

Gera 16 bytes com `SecureRandom`, Base64 URL-safe sem padding, com prefixos `adm_`, `inv_`, `p_`. Os prefixos garantem que os três tipos sejam distintos e aceleram a rejeição de token inválido.

### `LinkBuilder`

Configurado por `app.base-url` (sem barra final).

- `String admin(Event event)`
- `String participant(Participant participant)`
- `String join(Event event)`
- `String whatsapp(String message)` — `https://wa.me/?text=` + URL encode

Não depende de `HttpServletRequest`, para funcionar também em envio assíncrono futuro.

### `InviteService`

Depende de `LinkBuilder`.

- `InviteLinks linksFor(Event event, Participant organizer)` → record com as três URLs e os textos de compartilhamento.
- Usado pela tela de criação, pelo painel e pelo e-mail.

### `EventService`

- `Event create(CreateEventCommand command)` — `@Transactional`; cria `Event` + participante organizador (`organizer=true`), gerando `adminToken`, `inviteCode` e `accessToken`.
- `Event byAdminToken(String token)` / `Event byInviteCode(String code)` — lança `NotFoundException` (→ 404).
- `void setRegistrationByAdminToken(String adminToken, boolean open)` — estado desejado explícito (não toggle); `@Transactional` + lock.
- `Event regenerateInviteCodeByAdminToken(String adminToken)` — `@Transactional` + lock; o código antigo deixa de funcionar.
- `Event reopenByAdminToken(String adminToken)` — `DRAWN → OPEN`; apaga os `Assignment`, reabre inscrições, zera `firstViewedAt`, mantém `drawCount`.
- `Participant organizerOf(Event event)` — busca o participante com `organizer=true`.
- `void deleteByAdminToken(String adminToken)` — lock pelo token, `deleteByIdBulk` e cascata do banco. `@Transactional`.

### `ParticipantService`

- `Participant byAccessToken(String accessToken)` — busca por token e inicializa o evento (a página lê fora da transação).
- `List<ParticipantRow> rowsFor(Event event)` — projeção do painel, mantendo o controller fora do repositório.
- `Participant addByOrganizer(Event event, String name, String wishlist)` — só `OPEN`; lock no evento; respeita o teto de 100.
- `Participant joinByInvite(Event event, String name, String wishlist)` — só `OPEN && registrationOpen`; lock; teto.
- `void remove(Event event, UUID participantId)` — só `OPEN`; busca por `id + event`; não permite remover o organizador; remove a participação nos grupos.
- `void updateWishlist(Participant participant, String wishlist)` — sempre permitido (antes e depois do sorteio).
- `void markFirstViewed(Participant participant)` — update atômico (`WHERE first_viewed_at IS NULL`), escopado por evento; a borda só chama quando `DRAWN`.
- `Optional<ParticipantResult> resultFor(Participant participant)` — quem a pessoa tirou e a wishlist, ou vazio sem `Assignment`.

### `ExclusionGroupService`

- `ExclusionGroup create(Event event, String label, Set<UUID> memberIds)` — só `OPEN`; lock; membros do mesmo evento; mínimo 2.
- `void remove(Event event, UUID groupId)` — só `OPEN`; lock; escopo por evento.
- `List<ExclusionGroupView> viewsFor(Event event)` — projeção da seção de restrições do painel.

### `DrawService`

- `void drawByAdminToken(String adminToken)` — `@Transactional` + lock pelo token; exige `OPEN`; monta os pares proibidos dos grupos; chama `CycleDrawSolver`; persiste `Assignment`; `markDrawn` fecha inscrições, grava `drawnAt` e incrementa `drawCount`; publica `DrawCompletedEvent` **após o commit**.
- `void redrawByAdminToken(String adminToken)` — `@Transactional` + lock; exige `DRAWN`; apaga assignments antigos, zera `firstViewedAt`, sorteia de novo e incrementa `drawCount`; rollback mantém o resultado antigo se o solver falhar.
- `DrawCompletedEvent` é publicado via `TransactionSynchronization`; o listener de e-mail (`EmailNotificationListener`) abre uma transação nova e grava o outbox.
- `DrawConfig` cria o `CycleDrawSolver` com `SecureRandom`.

### `DrawCompletedEvent`

`record DrawCompletedEvent(UUID eventId, int drawCount, Instant at)`.

O listener de e-mail escuta o evento sem alterar o `DrawService`; o envio real fica no worker.

---

## 5. Subsistema de sorteio

1. `DrawService.draw` carrega os participantes do evento.
2. Constrói `Map<UUID, Set<UUID>> forbidden`:
   - para cada `ExclusionGroup`, todos os pares ordenados `(A, B)` e `(B, A)` entre membros.
3. Chama `CycleDrawSolver.draw(ids, forbidden)` (solver puro, sem Spring).
4. Converte `Map<UUID, UUID>` em `Assignment` e persiste.
5. Em caso de impossibilidade, `DrawImpossibleException` sobe ao controller, que devolve mensagem clara e redireciona (nunca 500).

O solver continua puro e determinístico com semente fixa nos testes.

---

## 6. Camada web

### `EventController`

| Método | Rota | Ação |
| --- | --- | --- |
| GET | `/` | Exibe `CreateEventForm` |
| POST | `/events` | Valida, cria evento, redireciona para `/admin/{adminToken}` com flash “Guarde este link” e banner com as três URLs |

### `AdminController`

Resolve o `Event` por `adminToken` (404 se inexistente).

| Método | Rota | Ação |
| --- | --- | --- |
| GET | `/admin/{adminToken}` | `AdminDashboardView` |
| POST | `/admin/{adminToken}/participants` | Adiciona participante |
| POST | `/admin/{adminToken}/participants/{id}/delete` | Remove participante |
| POST | `/admin/{adminToken}/exclusions` | Cria grupo |
| POST | `/admin/{adminToken}/exclusions/{id}/delete` | Remove grupo |
| POST | `/admin/{adminToken}/draw` | Sorteia |
| POST | `/admin/{adminToken}/redraw` | Refaz |
| POST | `/admin/{adminToken}/reopen` | Reabre |
| POST | `/admin/{adminToken}/registration` | Abre/fecha inscrições (`open=true|false`) |
| POST | `/admin/{adminToken}/invite/regenerate` | Regenera `inviteCode` |
| POST | `/admin/{adminToken}/delete` | Apaga o evento e todos os dados (confirmação) |

Todos os POSTs usam POST-redirect-GET e `RedirectAttributes` para flash. O painel **nunca** renderiza `Assignment`.

### `JoinController`

| Método | Rota | Ação |
| --- | --- | --- |
| GET | `/join/{inviteCode}` | Formulário de inscrição; 404 se o código não existir; “inscrições encerradas” se fechado/sorteado; nunca lista participantes |
| POST | `/join/{inviteCode}` | Valida, cadastra e redireciona para `/p/{accessToken}` com aviso para guardar o link |

### `ParticipantController`

| Método | Rota | Ação |
| --- | --- | --- |
| GET | `/p/{accessToken}` | Se `DRAWN`: “Você tirou: X” + wishlist de X + aviso de sorteio refeito; se `OPEN`: espera + wishlist. Registra `firstViewedAt` na primeira abertura pós-sorteio |
| POST | `/p/{accessToken}/wishlist` | Atualiza a própria wishlist |

### DTOs (records)

`CreateEventForm`, `JoinForm`, `WishlistForm`, `ExclusionGroupForm`, `ParticipantRow`, `AdminDashboardView`, `ParticipantPageView`, `InviteLinks`.

Nenhuma entidade JPA é usada diretamente como modelo de tela sensível.

---

## 7. Cross-cutting (segurança sem Spring Security)

### Resolução de token

Um mecanismo único carrega:

- `Event` por `adminToken` (painel);
- `Event` por `inviteCode` (join);
- `Participant` por `accessToken` (página do participante).

Token inexistente → **404** (não 403), sem confirmar existência. Sugestão: `HandlerMethodArgumentResolver` com anotações `@AdminEvent`, `@InviteEvent`, `@ParticipantToken`, ou métodos `@ModelAttribute` em cada controller. O importante é não repetir a busca e nunca aceitar `id` sem escopo de evento.

### CSRF próprio

Sem Spring Security, `th:action` **não** injeta `_csrf`. Implementar:

- `CsrfTokenManager`: token por sessão, comparação em tempo constante.
- `CsrfTokenInterceptor`: gera e expõe `_csrf` em GET; valida em todo POST.
- Fragmento Thymeleaf `csrf.html` com `<input type="hidden" name="_csrf" ...>`.
- Cookie de sessão: `HttpOnly`, `SameSite=Lax`, `Secure` quando HTTPS.

### Headers de segurança

`SecurityHeadersFilter` (OncePerRequestFilter), aplicado a `/p/**`, `/join/**` e `/admin/**`:

- `Cache-Control: no-store`
- `Pragma: no-cache`
- `Referrer-Policy: no-referrer`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Content-Security-Policy: frame-ancestors 'none'`
- `X-Robots-Tag: noindex, nofollow`
- `robots.txt` bloqueando tudo

### Rate limiting

`RateLimitFilter` (`OncePerRequestFilter`, ordem `HIGHEST_PRECEDENCE + 10`, depois do
`ForwardedHeaderFilter`) limita apenas `POST`, por IP e categoria:

| Rota | Categoria | Limite |
| --- | --- | --- |
| `POST /events` | `event-create` | 5 por 10 min |
| `POST /join/{inviteCode}` | `join` | 30 por 10 min |
| `POST /admin/{adminToken}/draw` e `/redraw` | `draw` | 10 por hora |
| `POST /p/{accessToken}/wishlist` | `wishlist` | 30 por 10 min |

Janela fixa com contador por chave, mapa LRU limitado por `max-keys` (10.000) e IP apenas em
memória (nunca persistido ou logado). Resposta `429` com `Retry-After` e HTML mínimo, sem token ou
IP. IPv4 mapeado é normalizado e IPv6 usa o prefixo /64. Configuração em `app.rate-limit`
(`RATE_LIMIT_*`). Plano e justificativas: [`docs/RATE-LIMITING.md`](docs/RATE-LIMITING.md).

### Tratamento de erros

`GlobalExceptionHandler` (`@ControllerAdvice`):

- `NotFoundException` → página 404 genérica, sem ecoar token.
- `CannotAcquireLockException` / falha de lock otimista → mensagem “tente novamente”.
- `DataIntegrityViolationException` → página de erro sem detalhes internos.
- `DrawImpossibleException` é tratada no controller de sorteio para virar flash e redirect.

### Logs

Nunca logar tokens. Access log do Tomcat desligado. `server.error.include-message` e `include-stacktrace` em `never`.

---

## 8. Configuração e migrações

### `application.yaml` (chaves principais)

```yaml
app:
  base-url: http://localhost:8080        # sobrescrito por APP_BASE_URL em produção
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/amigo_secreto}
    username: ${SPRING_DATASOURCE_USERNAME:amigo}
    password: ${SPRING_DATASOURCE_PASSWORD:amigo}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
  web:
    locale: pt_BR
server:
  forward-headers-strategy: framework
  error:
    include-message: never
    include-stacktrace: never
```

### `application-test.yaml`

H2 em memória com `MODE=PostgreSQL`, `DB_CLOSE_DELAY=-1`, Flyway ativo e `ddl-auto=validate`.

### Flyway `V1__init.sql`

Tabelas:

- `event(id, name, event_date, suggested_value, admin_token UNIQUE, invite_code UNIQUE, registration_open, status, drawn_at, draw_count, created_at)`
- `participant(id, event_id FK, name, access_token UNIQUE, wishlist, organizer, first_viewed_at)`
- `exclusion_group(id, event_id FK, label)`
- `exclusion_group_member(group_id FK, participant_id FK, PK(group_id, participant_id))`
- `assignment(id, event_id FK, giver_id FK, receiver_id FK, UNIQUE(event_id, giver_id), UNIQUE(event_id, receiver_id), CHECK(giver_id <> receiver_id))`

Regras:

- FKs com `ON DELETE CASCADE` a partir de `event`.
- Remover participante também remove a participação dele nos grupos.
- Índices únicos em `admin_token`, `invite_code` e `access_token`.
- Índices de apoio em `participant.event_id`, `assignment.event_id`, `exclusion_group.event_id`.

### `pom.xml`

Adicionar:

- `spring-boot-starter-thymeleaf`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`
- `spring-boot-starter-flyway` (Boot 4 modularizou o auto-config do Flyway; `flyway-core` sozinho não ativa)
- `flyway-database-postgresql`
- `spring-boot-starter-actuator` (health)

Remover/ajustar:

- remover `spring-boot-h2console`;
- H2 apenas em `scope=test`.

---

## 9. Testes

### Unitários

- `CycleDrawSolver` (já feito):
  - ninguém tira a si mesmo;
  - nenhuma exclusão violada;
  - todos dão e recebem uma vez;
  - ciclo único;
  - 16 participantes em dois grupos de 8;
  - bowtie impossível;
  - 3 participantes com restrição rejeitados;
  - determinismo com semente fixa;
  - comparação com força bruta em grafos pequenos.
- `TokenGenerator`: formato, prefixos, unicidade.
- `LinkBuilder`: URL absoluta, base sem barra final, encoding do WhatsApp.
- `DrawService` com mocks: estados, teto, evento publicado, tradução das exceções.
- `RateLimitFilter`: limite por janela, baldes por IP e categoria, janela de 1 hora do sorteio,
  reset após expirar, IPv4 mapeado/IPv6 `/64`, mapa limitado, `enabled=false` e resposta 429 sem
  vazar IP/token.

### Serviço e repositório (Flyway + H2/Testcontainers)

- criação do evento já cria o organizador;
- remover participante limpa a participação nos grupos;
- `markFirstViewed` atômico;
- grupo com menos de 2 membros é rejeitado;
- `reopen` zera `firstViewedAt`;
- `redraw` apaga assignments antigos, mantém tokens e incrementa `drawCount`;
- teto de 100 participantes;
- regenerar `inviteCode` invalida o código antigo;
- apagar evento remove participantes, grupos e `Assignment` em cascata.

### Integração (MockMvc)

- token inválido → 404;
- painel do admin não contém dados de `Assignment`;
- página do participante só mostra o próprio resultado;
- `/join/{inviteCode}` só aceita com `OPEN` e inscrições abertas, e não mostra a lista;
- `inviteCode` antigo deixa de funcionar após regenerar;
- headers `no-store` e `no-referrer` presentes;
- POST sem CSRF é rejeitado;
- nome com `<script>` é escapado pelo Thymeleaf.

### Concorrência (opcional)

- dois `draw` simultâneos: só um vence, o outro recebe mensagem amigável.

---

## 10. Máquina de estados e concorrência

| Ação | De → Para | Guardas |
| --- | --- | --- |
| `draw` | OPEN → DRAWN | ≥3 participantes sem restrição, ≥4 com restrição, e viável |
| `redraw` | DRAWN → DRAWN | `drawCount + 1`, zera `firstViewedAt` |
| `reopen` | DRAWN → OPEN | apaga `Assignment`, reabre inscrições |
| adicionar/remover participante | só OPEN | escopo por evento, teto 100 |
| criar/remover grupo | só OPEN | membros do evento, ≥2 |
| wishlist | qualquer estado | — |
| `join` | só OPEN + `registrationOpen` | teto 100 |
| `registration` | só OPEN | estado desejado explícito |
| `invite/regenerate` | qualquer estado | invalida o código antigo |
| `delete` | qualquer estado | confirmação explícita; cascata total |

Operações de escrita carregam o `Event` com `PESSIMISTIC_WRITE`. O estado é revalidado dentro da transação; duplo clique e duas abas não geram sorteio duplicado nem furam o teto.

---

## 11. Ordem de implementação

1. [x] **Base:** POM final, Docker Compose com Postgres, Flyway V1, perfis, `ddl-auto=validate`.
2. [x] **Domínio e persistência:** entidades, repositórios, `TokenGenerator`, `LinkBuilder`.
3. [x] **Evento, painel e links:** criação de evento, painel básico, links individuais e apagar evento com confirmação.
4. [x] **Participantes e convite:** adicionar/remover, `/join/{inviteCode}`, abrir/fechar inscrições, regenerar o código e teto de participantes.
5. [x] **Grupos de exclusão:** modelo, serviço e tela com checkboxes.
6. [x] **Sorteio:** `DrawService` + `DrawCompletedEvent`, integração no painel, refazer e reabrir.
7. [x] **Página do participante:** resultado, wishlist, primeira visualização, aviso de sorteio refeito.
8. [x] **Acabamento:** CSS próprio, mensagens de erro, estados vazios e página 500.
9. [x] **Segurança:** headers em todas as rotas dinâmicas, 404 para tokens inválidos, CSRF, CSP, robots e testes de privacidade.
10. [x] **Deploy:** Dockerfile multi-stage sem QEMU, Compose app + db com imagem ARM64 do GHCR e tag SHA, Caddy de borda externo, `scripts/deploy.sh` e backup.
11. [x] **Portfólio:** README, `docs/IDEAS.md` e CI.
12. [x] **Fase 2 — e-mail:** coluna `email`, tabela `notification`, listener de `DrawCompletedEvent`, Brevo, conteúdo HTML/texto e testes de entrega. Guia: [`docs/EMAIL-BREVO.md`](docs/EMAIL-BREVO.md).
13. [x] **Rate limiting:** filtro em memória por IP/categoria, `app.rate-limit`, resposta 429 e testes; endpoints de e-mail incluídos em 2026-11-16. Plano: [`docs/RATE-LIMITING.md`](docs/RATE-LIMITING.md).
14. [x] **Deploy aplicável:** P1–P6, Caddy de borda fora do repositório, CI publicando ARM64 no GHCR, `scripts/deploy.sh` e `.env.prod.example` atualizado.
15. [x] **Deploy real:** VPS em produção em 2026-11-15, DNS/Security List, stack `edge`, Flyway no Postgres, backup por systemd timer. Registro: [`docs/DEPLOY_CONCLUIDO.md`](docs/DEPLOY_CONCLUIDO.md).

---

## 12. Decisões fechadas

1. **Organizador participa do sorteio no MVP.** O opt-out fica para uma fase futura.
2. **Apagar evento:** ação no painel, com confirmação explícita e cascata total.
3. **Editar nome de participante:** fora do MVP; não haverá rota de edição.
4. **CSRF:** token de sessão próprio (sem Spring Security), validado em todo POST.
5. **Testes:** H2 + Flyway agora; Testcontainers quando o dialeto começar a divergir.
6. **Nomes duplicados:** permitidos, com sinalização no painel.
7. **Wishlist:** não aparece no painel do organizador; só para quem tirou a pessoa.
8. **Fuso:** `Instant` UTC no banco/domínio; `America/Sao_Paulo` na borda (DTO/template).

---

## 13. Critérios de conclusão do MVP

- [x] Criar evento e receber os três links (painel, participante organizador, convite).
- [x] Apagar evento com confirmação, removendo todos os dados em cascata.
- [x] Inscrever participantes pelo convite e manualmente, respeitando o teto de 100.
- [x] Abrir/fechar inscrições e regenerar o código de convite.
- [x] Criar e remover grupos de exclusão com validação.
- [x] Sortear com ciclo único, restrições rígidas e mensagens claras de impossibilidade.
- [x] Refazer sorteio mantendo links, incrementando `drawCount` e zerando `firstViewedAt`.
- [x] Reabrir evento apagando assignments e reabrindo inscrições.
- [x] Página do participante mostrando apenas o próprio resultado e permitindo editar wishlist.
- [x] Painel nunca expõe quem tirou quem.
- [x] Tokens inválidos retornam 404; headers de privacidade presentes; CSRF em todos os POSTs.
- [x] Rate limiting em memória por IP nas rotas de criação, convite, sorteio e wishlist; validado em produção em 2026-11-15.
- [x] Flyway aplicando a V1–V3 do zero em Postgres real, com `ddl-auto=validate` (validado no deploy de 2026-11-15).
- [x] Testes unitários, de serviço e de integração passando (164 testes).
- [x] Deploy Docker/Compose funcional na VPS: `https://amigo-secreto.barbosalabs.dev` no ar, Caddy de borda, DNS, Security List, GHCR e backup por systemd timer.

---

## 14. Pendências e riscos aceitos

O que ficou conscientemente fora do MVP está detalhado em [`docs/IDEAS.md`](docs/IDEAS.md). Resumo:

- **Rate limiting** implementado em memória por IP (5/10 min em `/events`, 30/10 min em `/join`, 10/h no sorteio e 30/10 min na wishlist) e **validado em produção** em 2026-11-15, inclusive contra `X-Forwarded-For` forjado. Limite diário/Redis/WAF ficam fora do escopo. Ver [`docs/RATE-LIMITING.md`](docs/RATE-LIMITING.md).
- **Dashboard em uma transação só**: leituras separadas podem mostrar estado misto por um instante.
- **Postgres no CI/Testcontainers**: a suíte roda em H2; Flyway e `ddl-auto=validate` foram provados no Postgres real no deploy, mas locks/tipos sob concorrência ainda não têm teste no banco real.
- **Testes de concorrência** (draw × draw, última vaga, rollback de redraw/reopen).
- **P9–P11 aplicadas em 2026-11-15:** `db` com `mem_limit: 512m`/`cpus: "0.5"`, exemplos de `.env`
  com `BREVO_FROM_NAME="Amigo Secreto"` e `tracking-modes: cookie` (teste de URL sem `;jsessionid`).
- **P12 — Caddy de borda (aplicada e verificada em 2026-11-16):** `mem_limit: 256m` no
  `/opt/edge/compose.yml`, `HostConfig.Memory=268435456`, config-hash idêntico e dry-run sem mudanças.
- **P13 — rate limit dos endpoints de e-mail (implementado no código em 2026-11-16):**
  `POST /admin/{token}/links/email` (10/h) e `.../participants/{id}/email/resend` (20/h), com janela
  de 1h e baldes separados; entra em produção com P15.
- **P14 — SPF (aplicada em 2026-11-16):** TXT `v=spf1 include:spf.sendinblue.com -all` em
  `mail.barbosalabs.dev`, verificado no DNS público; DKIM/DMARC já estavam corretos.
- **P15 — deploy da imagem nova:** a VPS roda `2b6e2d4`; o remoto já tem as correções P9–P12 e o
  P13 está no working tree. Quando o CI publicar a imagem do commit novo,
  `APP_TAG=<sha> bash scripts/deploy.sh`.
- **Tokens em texto claro** e `Assignment` em claro no banco: risco aceito do modelo “link = credencial”.
- **Tokens na URL**: histórico do navegador; `no-store`/`no-referrer` mitigam, não apagam.
- **E-mail:** o envio está **ligado e validado ponta a ponta em 2026-11-16** (2 `SENT`, `attempts=1`, painel **Enviado**, zero erros, evento de teste apagado); o SPF foi publicado e o rate limit dos endpoints de e-mail ficou no código (entra com P15). Dedupe por endereço (melhor esforço sem verificação) ainda não foi implementado; telefone/WhatsApp API/SMS ficam fora.
- **CSS próprio** em vez de Pico.css; Pico/HTMX continuam opcionais.
- **Pendências operacionais do deploy**: cópia off-site do backup, restore completo, reboot, fluxo no navegador, monitoração externa, `DOCKER-USER` restritivo, `git fetch --unshallow`, limpeza de imagens de teste, P15 (deploy do commit novo) e CD opcional. Ver [`docs/DEPLOY_CONCLUIDO.md`](docs/DEPLOY_CONCLUIDO.md) §9/§14.
