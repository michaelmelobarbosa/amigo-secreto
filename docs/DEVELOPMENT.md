# Desenvolvimento

Guia para trabalhar no código do Amigo Secreto: setup, arquitetura, convenções, transações, testes e configuração. O escopo funcional e as regras de negócio estão em [SPEC.md](SPEC.md); o deploy está em [DEPLOYMENT.md](DEPLOYMENT.md).

## Stack

| Item | Versão / escolha |
| --- | --- |
| Linguagem | Java 25 |
| Framework | Spring Boot 4.1.2 (webmvc, data-jpa, validation, thymeleaf, actuator) |
| Banco | PostgreSQL 17 em dev/prod; H2 em memória nos testes |
| Migrações | Flyway desde a V1; `ddl-auto=validate` |
| Frontend | Thymeleaf + CSS próprio; sem SPA, sem Node no runtime |
| Build | Maven Wrapper (`mvnw` / `mvnw.cmd`) |
| E-mail | Brevo via API REST, opcional |

## Setup local

Pré-requisitos: JDK 25 e Docker.

```bash
# banco de desenvolvimento (loopback)
docker compose up -d

# aplicação com perfil dev (cookie sem Secure)
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
# Windows:
$env:SPRING_PROFILES_ACTIVE="dev"; .\mvnw.cmd spring-boot:run
```

A aplicação sobe em `http://localhost:8080`. O Spring Boot não lê `.env`; exporte as variáveis na sessão. Para e-mail local, veja [EMAIL.md](EMAIL.md#desenvolvimento-local).

## Comandos

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Dtest=CycleDrawSolverTest test
.\mvnw.cmd spring-boot:run
.\mvnw.cmd -DskipTests package
```

No Linux/macOS, troque `.\mvnw.cmd` por `./mvnw`.

## Estrutura de pacotes

```
br.com.michael.amigosecreto
├── AmigoSecretoApplication
├── event/          evento, painel, inscrições, convite regenerável
├── participant/    participantes, wishlist, primeira visualização
├── exclusion/      grupos de exclusão
├── draw/           Assignment, DrawService, CycleDrawSolver, evento pós-commit
├── invite/         convite público
├── admin/          painel do organizador
├── notification/   outbox, listener e worker de e-mail
├── email/          porta EmailSender, adaptador Brevo, conteúdo
└── common/         tokens, links, CSRF, headers, rate limit, erros, config
```

A direção é sempre **controller → service → repository**. Entidade JPA nunca é modelo de tela; as views usam `record`s/projeções.

## Domínio

O modelo completo está em [SPEC.md §3](SPEC.md#3-modelo-de-domínio). Resumo:

- `Event`: nome, data e valor opcionais, `adminToken`, `inviteCode`, `registrationOpen`, estado `OPEN | DRAWN`, `drawnAt`, `drawCount`.
- `Participant`: nome, `accessToken`, wishlist, flag `organizer`, `firstViewedAt` e e-mail opcional.
- `ExclusionGroup`: grupo de participantes que nunca podem se tirar.
- `Assignment`: par `giver → receiver`, único por evento nas duas pontas e com `CHECK(giver <> receiver)`.

Invariantes que o banco e o serviço defendem:

- todos dão e recebem exatamente uma vez;
- ninguém tira a si mesmo;
- nenhum par proibido aparece no resultado;
- exatamente um organizador por evento;
- teto de 100 participantes;
- depois de `DRAWN`, participantes e grupos só mudam via “Reabrir e editar”.

## Camadas e fluxo

1. O controller resolve o token (`/admin/{adminToken}`, `/join/{inviteCode}`, `/p/{accessToken}`) e monta DTOs de tela.
2. O service valida estado, regras e limites dentro de uma transação.
3. O repository acessa o banco com Spring Data JPA; consultas de tela usam projeções.
4. O template Thymeleaf renderiza apenas DTOs.

Token inexistente vira `NotFoundException` e responde **404**, nunca 403.

## Persistência

- IDs `UUID`; `equals`/`hashCode` por `id`.
- Tempo em `Instant` (UTC) no banco e no domínio; `America/Sao_Paulo` só na borda.
- `FetchType.LAZY` por padrão; `open-in-view=false`.
- Sem Lombok, sem `@Data`, sem `toString` com relação bidirecional.
- `Assignment` não é carregado em `Participant`; consultas vão pelo `AssignmentRepository`.
- O schema é do Flyway. `ddl-auto=validate` precisa passar em H2 e Postgres.

## Transações e concorrência

Toda escrita roda em `@Transactional` no service e carrega o `Event` com `PESSIMISTIC_WRITE` antes de revalidar estado e limites. Isso cobre duplo clique, duas abas e duas inscrições na última vaga.

- `draw` e `redraw`: uma transação; em falha do solver, o resultado anterior permanece.
- `redraw`: apaga assignments em bulk antes de reinserir; cuidado com `flush`/`clearAutomatically`.
- `reopen`: `DRAWN → OPEN`, apaga assignments, zera `firstViewedAt` e reabre inscrições.
- `firstViewedAt`: update atômico com `WHERE first_viewed_at IS NULL`.
- O `DrawCompletedEvent` é publicado **depois do commit**, nunca dentro da transação.

## Sorteio

`CycleDrawSolver` é puro (sem Spring), recebe a lista de participantes e o mapa de pares proibidos, e devolve `giver → receiver` ou lança `DrawImpossibleException` com uma razão:

| Razão | Significado |
| --- | --- |
| `TOO_FEW_PARTICIPANTS` | menos de 3, ou menos de 4 quando há grupos |
| `FORBIDDEN_PAIR_WITH_THREE_PARTICIPANTS` | ciclo de 3 com qualquer par proibido |
| `NO_ALLOWED_TARGET` | alguém não pode tirar ninguém |
| `NO_SOLUTION` | não existe ciclo válido |
| `SEARCH_LIMIT_EXCEEDED` | orçamento de passos esgotado |

O algoritmo usa busca em profundidade com retrocesso, heurística MRV e `RandomGenerator` injetável. Testes usam semente fixa; produção usa `SecureRandom`.

## Camada web

Rotas principais (contrato completo em [SPEC.md §6.5](SPEC.md#65-rotas)):

| Método | Rota | Ação |
| --- | --- | --- |
| GET | `/` | formulário de criação |
| POST | `/events` | cria evento e organizador |
| GET | `/admin/{adminToken}` | painel |
| POST | `/admin/{adminToken}/draw` | sorteia |
| POST | `/admin/{adminToken}/redraw` | refaz |
| POST | `/admin/{adminToken}/reopen` | reabre |
| GET/POST | `/join/{inviteCode}` | inscrição pública |
| GET/POST | `/p/{accessToken}` e `/wishlist` | resultado e wishlist |

Convenções:

- POST-redirect-GET com `RedirectAttributes` para mensagens.
- Todo `<form method="post">` inclui o fragmento CSRF; não há exceção.
- DTOs são `record`s; nenhuma entidade sensível vai para a view.
- O painel nunca recebe `Assignment`; a página do participante só recebe o próprio par.
- Thymeleaf escapa por padrão. Não use `th:utext` com dado de usuário.
- Textos de UI em pt-BR; datas formatadas só na borda.

## Segurança no código

A visão completa está em [SECURITY.md](SECURITY.md). No dia a dia:

- CSRF próprio por token de sessão (`CsrfTokenManager`/`CsrfTokenInterceptor`), comparação em tempo constante.
- `SecurityHeadersFilter` aplica `no-store`, `no-referrer`, `nosniff`, `DENY`, CSP e `X-Robots-Tag` nas rotas dinâmicas.
- `RateLimitFilter` limita POST por IP e categoria, com `429` + `Retry-After`.
- Tokens nunca aparecem em log, erro, `toString` ou query string.
- `TrackingMode` é `cookie`; o `;jsessionid` não aparece na URL.
- Cookies são `HttpOnly`, `SameSite=Lax` e `Secure` fora do perfil `dev`.

## E-mail

O envio é opcional e assíncrono:

1. `DrawService` publica `DrawCompletedEvent` após o commit.
2. O listener grava uma linha `PENDING` em `notification` por participante com e-mail e `draw_count`.
3. Um worker agendado envia pela API do Brevo com idempotência e retry.
4. O painel mostra status e permite reenviar; falha de e-mail nunca desfaz o sorteio.

Configuração, conteúdo, sandbox e operação estão em [EMAIL.md](EMAIL.md).

## Configuração

Chaves principais em `application.yaml`:

```yaml
app:
  base-url: ${APP_BASE_URL:http://localhost:8080}
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
server:
  forward-headers-strategy: native
  tomcat:
    remoteip:
      remote-ip-header: x-forwarded-for
      protocol-header: x-forwarded-proto
      host-header: ""
  error:
    include-message: never
    include-stacktrace: never
```

- Perfil `dev`: cookie sem `Secure`, adequado a HTTP local.
- Perfil `test`: H2 com `MODE=PostgreSQL`, Flyway ativo e rate limit desligado por padrão.
- Produção: variáveis do `.env` (ver [DEPLOYMENT.md](DEPLOYMENT.md)).

## Migrações

- Nomeie `V{n}__descricao_em_snake_case.sql`.
- Migração aplicada **não se edita**; crie a próxima.
- Toda migração precisa passar em H2 e Postgres com `ddl-auto=validate`.
- FKs a partir de `event` usam `ON DELETE CASCADE`.
- A remoção de participante também remove a participação nos grupos.

## Testes

```bash
./mvnw test
```

Camadas cobertas:

- **Unitário:** `CycleDrawSolver` (invariantes, bowtie impossível, 16 em dois grupos de 8, determinismo), `TokenGenerator`, `LinkBuilder`, `RateLimitFilter`.
- **Serviço/repositório:** estados `OPEN`/`DRAWN`, teto, cascata, regeneração de convite, `redraw`/`reopen`, `firstViewedAt`.
- **Integração MockMvc:** 404 para token inválido, painel sem `Assignment`, participante só com o próprio par, CSRF, headers, XSS, convite fechado e rate limit.

Regras:

- Teste independente e determinístico; sem rede e sem `Thread.sleep`.
- Use `Clock`/`RandomGenerator` injetáveis.
- Não enfraqueça asserção nem use `@Disabled` para ficar verde.
- H2 cobre o fluxo; locks e tipos sob concorrência no Postgres real ainda não têm teste dedicado.

## Estilo

- Constructor injection; sem `@Autowired` em campo.
- Javadoc curto em pt-BR para regra de negócio, não para getter.
- Identificadores, pacotes e nomes de teste em inglês; comentários, mensagens e docs em pt-BR.
- Dependência nova só com justificativa na spec; o projeto precisa continuar enxuto.

## Adicionando uma funcionalidade

1. Atualize [SPEC.md](SPEC.md) se a regra ou o escopo mudarem.
2. Crie a migração Flyway, se houver schema novo.
3. Implemente no service com transação e lock; mantenha o controller fino.
4. Exponha apenas DTOs na view e inclua CSRF em todo POST.
5. Adicione teste unitário/integração do comportamento novo.
6. Rode `./mvnw test` e confira `ddl-auto=validate`.
7. Atualize a documentação pública afetada (SPEC, DEVELOPMENT, DEPLOYMENT, EMAIL ou SECURITY).
