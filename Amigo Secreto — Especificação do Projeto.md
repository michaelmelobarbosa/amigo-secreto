# Amigo Secreto — Especificação do Projeto

Documento de referência para o desenvolvimento: escopo, decisões, modelo de domínio, arquitetura, fluxos e plano de implementação.

## 1. Visão geral

Aplicação web para organizar amigo secreto, sem login, com acesso por links com token. Serve para uso real (família e trabalho) e como projeto de portfólio, com deploy em VPS própria.

**Princípios**

- Simplicidade: poucas telas, nenhuma SPA.
- Privacidade: o resultado do sorteio só aparece para quem tirou. O organizador também participa sem ver o resultado dos outros.
- Segurança por token: links longos e aleatórios, sem IDs sequenciais expostos.

## 2. Decisões fechadas

| Tema | Decisão |
| --- | --- |
| Linguagem | Java (17+) |
| Framework | Spring Boot enxuto: `webmvc`, `data-jpa`, `validation`, `thymeleaf`. Sem Spring Security |
| Frontend | Thymeleaf (renderização no servidor) + CSS próprio minimalista. Pico.css e HTMX ficam opcionais e não entraram no MVP. Sem SPA |
| Convites | Sem login, dois tipos de link: o link público de convite do evento (`/join/{inviteCode}`), forma principal de entrada, e o link individual de cada participante (`/p/{accessToken}`), que dá acesso ao resultado. O organizador também pode adicionar pessoas manualmente. **MVP sem e-mail:** a entrega é manual (copiar/WhatsApp). E-mail é fase 2, opcional e sem virar login (seção 15) |
| Recuperação de link | O painel sempre permite copiar o link de cada participante |
| Preparação para e-mail | `LinkBuilder` com `app.base-url` e `DrawCompletedEvent` após o commit desde o MVP. Sem coluna `email`, tabela de notificação, provider ou template no MVP (seção 15) |
| Telefone/WhatsApp | Fora do MVP. WhatsApp Cloud API e SMS são pagos para envio iniciado pelo negócio; Telegram só como ideia futura. O MVP usa o `wa.me` manual |
| Restrições de sorteio | Grupos de exclusão cadastrados pelo admin (casais, famílias, setores) |
| Tipo de sorteio | Ciclo único: ninguém tira quem lhe tirou (sem troca direta) |
| Refazer sorteio | No MVP: refazer (mantém os links) e reabrir para editar |
| Admin | Também participa do sorteio, com dois links separados (painel e página de participante) |
| Exclusão de evento | O organizador pode apagar o evento com confirmação. A cascata remove participantes, grupos e `Assignment`; ação irreversível |
| Wishlist | Texto livre por participante, no MVP, editável antes e depois do sorteio |
| Banco | PostgreSQL em dev e produção (Docker Compose). H2 apenas nos testes |
| Migrações | Flyway desde a V1; `spring.jpa.hibernate.ddl-auto=validate`. Não usar `update` no schema real (seção 8) |
| Deploy | Dockerfile multi-stage + Docker Compose (app + Postgres) na VPS, com Caddy em stack de borda compartilhado. **Em produção desde 2026-11-15**; registro em [`docs/DEPLOY_CONCLUIDO.md`](docs/DEPLOY_CONCLUIDO.md) |
| Domínio e HTTPS | **Em produção desde 2026-11-15**: app em `amigo-secreto.barbosalabs.dev` e envio em `mail.barbosalabs.dev` (Brevo), com TLS Let's Encrypt |
| Organização do código | Pacotes por funcionalidade |

### Por que Spring Boot, mesmo sendo "overkill"

Tecnicamente um Javalin resolveria. Spring Boot foi escolhido porque o projeto também é de portfólio e o mercado de backend Java pede Spring. A mitigação é mantê-lo enxuto: poucas dependências, sem Security, sem recursos que o projeto não precisa.

**Trade-offs aceitos:** mais coisas implícitas (auto-configuração, proxies) e jar maior, em troca de ecossistema, documentação e relevância profissional.

## 3. Modelo de domínio

```
Event
 ├─ id: UUID
 ├─ name: String
 ├─ eventDate: LocalDate (opcional)
 ├─ suggestedValue: BigDecimal (opcional)
 ├─ adminToken: String (único)
 ├─ inviteCode: String (único, regenerável)
 ├─ registrationOpen: boolean
 ├─ status: OPEN | DRAWN
 ├─ drawnAt: Instant (nulo até o sorteio)
 ├─ drawCount: int (0 até o primeiro sorteio)
 ├─ createdAt: Instant
 ├─ participants: List<Participant>
 └─ exclusionGroups: List<ExclusionGroup>

Participant
 ├─ id: UUID
 ├─ event: Event
 ├─ name: String
 ├─ accessToken: String (único)
 ├─ wishlist: String (texto livre)
 ├─ organizer: boolean
 └─ firstViewedAt: Instant (nulo até abrir o resultado)

ExclusionGroup
 ├─ id: UUID
 ├─ event: Event
 ├─ label: String (opcional)
 └─ members: Set<Participant>

Assignment
 ├─ id: UUID
 ├─ event: Event
 ├─ giver: Participant
 └─ receiver: Participant
```

> A fase 2 adiciona `Participant.email` (opcional, normalizado) e a tabela `notification`; o MVP não tem e-mail.
> `Instant` é sempre UTC no banco e no domínio; a conversão para `America/Sao_Paulo` acontece apenas na borda (DTO/template).

**Regras de integridade**

- `(event, giver)` único em `Assignment`: cada pessoa dá a exatamente uma pessoa.
- `(event, receiver)` único em `Assignment`: cada pessoa recebe de exatamente uma pessoa.
- `giver != receiver`.
- Todos dão e recebem exatamente uma vez: cada participante aparece uma vez como `giver` e uma vez como `receiver`.
- Restrições de exclusão são regras rígidas, não preferências: um par proibido nunca pode aparecer no resultado. Não existe sorteio "com peso" no MVP.
- Mínimo para sortear: **3 participantes sem nenhum grupo de exclusão** e **4 participantes quando existe pelo menos um grupo**. Em um ciclo de 3 pessoas todos os três pares são adjacentes; qualquer par proibido torna o sorteio impossível. Com 4 ou mais, o mínimo é condição necessária, não garantia: a viabilidade real é verificada pelo algoritmo.
- Participantes pertencem a um único evento. Um `ExclusionGroup` só contém participantes do mesmo evento. Se a remoção de um participante deixar um grupo com menos de 2 membros, o grupo é apagado.
- Exatamente um participante com `organizer = true` por evento, criado junto com o evento.
- O participante organizador não pode ser removido no MVP; removê-lo quebraria o vínculo entre painel e sorteio.
- Inscrição pelo convite só é aceita com o evento em `OPEN` e `registrationOpen = true`. O sorteio fecha as inscrições automaticamente. Teto de 100 participantes por evento.
- Depois de `DRAWN`, participantes e grupos de exclusão não podem ser alterados. Para mexer neles, o organizador usa "Reabrir e editar" (volta para `OPEN`, apaga os `Assignment` e zera `firstViewedAt`). A wishlist continua editável sempre.

### Tokens

- Gerados com `SecureRandom`, 128 bits ou mais, codificados em Base64 URL-safe.
- `adminToken`, `inviteCode` e `accessToken` são colunas únicas com índice, e distintos entre si: um token de participante ou de convite nunca dá acesso ao painel.
- O `inviteCode` pode ser regenerado pelo organizador. O código antigo deixa de funcionar e os links individuais não mudam.
- Tokens nunca aparecem em logs. Configure o logging para não registrar URLs completas de `/admin/**`, `/join/**` e `/p/**`.

## 4. Serviços e responsabilidades

| Classe | Responsabilidade |
| --- | --- |
| `EventService` | Criar evento (já cria o organizador como participante), consultar por `adminToken`, abrir e fechar inscrições, regenerar `inviteCode`, reabrir o evento (`DRAWN` → `OPEN`, apagando os `Assignment`) e apagar o evento com cascata |
| `ParticipantService` | Adicionar (manualmente ou pelo convite), remover participantes (somente em `OPEN`), respeitar o teto de participantes, atualizar wishlist, registrar primeira visualização |
| `ExclusionGroupService` | Criar e remover grupos de exclusão (somente em `OPEN`), validar que os membros são do evento e que o grupo tem pelo menos 2 pessoas |
| `DrawService` | Validar viabilidade, executar o sorteio com busca em profundidade (backtracking) e fonte de aleatoriedade injetável, persistir `Assignment`, mudar o status para `DRAWN` e fechar as inscrições. Distingue impossibilidade comprovada de limite de busca. Também refaz o sorteio (apaga os `Assignment`, sorteia de novo, incrementa `drawCount`, atualiza `drawnAt` e zera `firstViewedAt`). Tudo em uma transação. Publica `DrawCompletedEvent` **depois do commit**, para a fase 2 notificar sem acoplar o sorteio ao e-mail |
| `InviteService` | Montar o link de convite, os links individuais e o texto para compartilhar no WhatsApp usando o `LinkBuilder`. Na fase 2, envio por e-mail |
| `LinkBuilder` | Montar links absolutos a partir de `app.base-url`: painel, participante, convite e texto de WhatsApp. Não depende do request, para funcionar também no envio assíncrono da fase 2 |
| `TokenGenerator` | Geração dos tokens |

### Controllers

- `EventController` (`/`, `/events`): tela inicial e criação.
- `AdminController` (`/admin/{adminToken}/...`): painel, participantes, grupos, convite, sorteio, refazer e reabrir.
- `JoinController` (`/join/{inviteCode}`): inscrição pública pelo link de convite.
- `ParticipantController` (`/p/{accessToken}`): página do participante e edição da wishlist.

## 5. Algoritmo de sorteio

**Entrada:** lista de participantes e conjunto de pares proibidos.

**Pares proibidos** são derivados dos grupos de exclusão: para cada grupo, todos os pares (A, B) com A ≠ B entre os membros. Guarde em um `Map<UUID, Set<UUID>>` (participante → quem ele não pode tirar). A união de vários grupos funciona naturalmente.

**Procedimento**

1. Pré-validações (cada uma falha com `DrawImpossibleException` e uma razão específica):
   - mínimo de 3 participantes sem restrições; mínimo de 4 se existir pelo menos um grupo de exclusão;
   - cada participante precisa ter pelo menos um alvo permitido;
   - nenhum participante pode estar proibido de tirar todos os outros.
2. Busca em profundidade com retrocesso (backtracking):
   1. escolha como início o participante com menos alvos permitidos (desempate pela ordem recebida);
   2. monte o ciclo posição a posição: o próximo precisa ser um alvo permitido ainda não visitado; o ciclo só pode fechar quando todos tiverem sido visitados;
   3. ordene os candidatos do próximo passo por menos opções restantes (heurística MRV), com desempate aleatório vindo de um `RandomGenerator` injetado;
   4. se um candidato levar a um beco sem saída, desfaça a escolha e tente o próximo;
   5. ao visitar todos, aceite somente se o último puder apontar para o início.
3. Se a busca terminar sem solução, lance `DrawImpossibleException` com a razão `NO_SOLUTION` e uma mensagem que ajude o organizador (por exemplo, quais participantes têm menos opções).
4. Se o orçamento de passos da busca estourar, lance a mesma exceção com a razão `SEARCH_LIMIT`: a mensagem é "não foi possível encontrar um sorteio; tente novamente ou revise as restrições", diferente de "não existe sorteio válido". O orçamento evita travar o servidor em grafos patológicos; para os grupos sociais típicos a busca termina em milissegundos.
5. Persista os `Assignment` e marque o evento como `DRAWN` na mesma transação. O `DrawCompletedEvent` é publicado **depois do commit**, nunca dentro dela.

**Observações**

- **Ciclo único (decidido):** o resultado é uma fila circular: cada um tira o próximo e o último tira o primeiro. Exemplo: Carla → Ana → Diego → Bruno → (volta para Carla). Todos dão e recebem uma vez, ninguém tira a si mesmo e nunca há troca direta (A tira B e B tira A).
- Alternativa futura: permutação sem pontos fixos, que admite trocas diretas e vários círculos pequenos, como opção do evento. A validação contra os pares proibidos permanece igual.
- A busca com retrocesso substitui a tentativa e erro, que tem falha probabilística: com 16 pessoas em dois grupos de 8 mutuamente excludentes, só 1 em cada 6.435 ciclos é válido, e 1.000 tentativas falham em cerca de 86% dos casos. A busca encontra esse caso sem depender de sorte.
- A ordem dos candidatos é aleatorizada com `SecureRandom` em produção para o resultado não ser previsível; a semente é injetável para testes determinísticos.
- O caso geral de ciclo hamiltoniano é NP-completo; por isso a busca tem orçamento de passos. Para os grupos sociais típicos (casais, famílias, setores) ela termina em milissegundos.

### Refazer o sorteio e reabrir o evento

- **Refazer sorteio:** botão no painel, com confirmação ("Todos os resultados atuais serão descartados"). Apaga os `Assignment`, sorteia de novo e mantém `DRAWN`, em uma transação. Os links individuais não mudam: quem abrir depois vê o resultado novo.
- Como alguém pode já ter visto o resultado antigo, a página do participante mostra "Sorteio refeito em dd/mm" quando `drawCount > 1`. O `firstViewedAt` de todos é zerado.
- **Reabrir e editar:** volta o evento para `OPEN`, apaga os `Assignment`, zera `firstViewedAt`, mantém `drawCount` e reabre as inscrições. É o caminho quando alguém desiste ou entra uma pessoa nova. Depois de editar, o organizador sorteia novamente.

## 6. Fluxos e telas

### 6.1 Criar evento

`GET /` mostra o formulário: nome do evento, data (opcional), valor sugerido (opcional) e nome do organizador. `POST /events` cria o evento e o participante organizador, e redireciona para `/admin/{adminToken}`.

> Aviso na tela de sucesso: "Guarde este link. Ele é a única forma de acessar o painel." Mostre também o link do organizador como participante.

A tela de sucesso mostra três links: o painel (`/admin/{adminToken}`), a página do organizador como participante e o link de convite (`/join/{inviteCode}`), com botões de copiar e de compartilhar no WhatsApp.

O organizador pode **baixar os três links em um TXT** ou **enviá-los para um e-mail informado na hora**. O endereço usado nesse envio não é persistido, não vira login e não funciona como recuperação automática. Enquanto não copiar, baixar ou enviar, a tela avisa que sair sem guardar o link do painel significa perder o acesso ao evento.

### 6.2 Painel do organizador (`/admin/{adminToken}`)

- Dados do evento.
- Link de convite em destaque, com copiar, compartilhar no WhatsApp, abrir ou fechar inscrições e regenerar o código.
- Lista de participantes, com para cada um: nome, link individual (botão copiar e botão WhatsApp), indicador "já visualizou" e a ação de remover (enquanto `OPEN`, exceto o organizador).
- Formulário para adicionar participante manualmente.
- Seção "Restrições": lista de grupos de exclusão, com remover, e botão para criar um novo (checkboxes com os participantes).
- Botão "Sortear", que só habilita com 3 participantes sem restrições ou 4 com pelo menos um grupo de exclusão. Mostra erro claro se o sorteio for inviável.
- Depois do sorteio: o painel mostra apenas o status de visualização e os botões "Refazer sorteio" e "Reabrir e editar". **Nunca exibe quem tirou quem.**
- Link de acesso do próprio organizador como participante em destaque.
- Cartão "Seus links" com cópia, download em TXT e envio por e-mail sob demanda; o e-mail não é armazenado e não substitui o token do painel.
- Nomes duplicados são permitidos; o painel sinaliza quando há repetição.
- A wishlist não é exibida no painel do organizador.
- Zona de perigo: "Apagar evento" com confirmação explícita. Remove participantes, grupos e `Assignment` em cascata; ação irreversível.
- No MVP, nenhum e-mail é enviado: os links são copiados ou compartilhados manualmente pelo WhatsApp.

### 6.3 Página do participante (`/p/{accessToken}`)

- Antes do sorteio: mensagem de espera, dados do evento e campo de wishlist editável.
- Depois do sorteio: "Você tirou: **Fulano**", wishlist dele(a), e a própria wishlist editável. Se o sorteio foi refeito, mostra "Sorteio refeito em dd/mm".
- Na primeira abertura após o sorteio, registra `firstViewedAt`. Robôs de prévia de link (WhatsApp, Telegram, Slack etc.) não contam como visualização.

### 6.4 Entrada pelo link de convite (`/join/{inviteCode}`)

1. O organizador cria o evento e recebe o link de convite, com botões de copiar e compartilhar no WhatsApp.
2. A pessoa abre o link e preenche nome e wishlist (opcional).
3. O sistema a cadastra e redireciona para `/p/{accessToken}`, com o aviso: "Guarde este link, é por ele que você vê o resultado."

- Só funciona com o evento em `OPEN` e `registrationOpen = true`. O sorteio fecha as inscrições, e o organizador pode fechá-las antes. Evento sorteado ou fechado mostra "inscrições encerradas".
- Quem abre o convite vê o nome e os dados do evento, nunca a lista de participantes.
- Se o link vazar, o organizador regenera o `inviteCode`. O código antigo para de funcionar e os links individuais continuam válidos.
- Limites: tamanho máximo para nome e wishlist e teto de 100 participantes por evento.
- Os grupos de exclusão continuam sendo cadastrados só pelo organizador, depois que a lista está montada.

### 6.5 Rotas

| Método | Rota | Descrição |
| --- | --- | --- |
| GET | `/` | Formulário de criação |
| POST | `/events` | Cria o evento |
| GET | `/admin/{adminToken}` | Painel |
| POST | `/admin/{adminToken}/participants` | Adiciona participante |
| POST | `/admin/{adminToken}/participants/{id}/delete` | Remove participante |
| POST | `/admin/{adminToken}/exclusions` | Cria grupo de exclusão |
| POST | `/admin/{adminToken}/exclusions/{id}/delete` | Remove grupo |
| POST | `/admin/{adminToken}/draw` | Executa o sorteio |
| POST | `/admin/{adminToken}/redraw` | Refaz o sorteio |
| POST | `/admin/{adminToken}/reopen` | Reabre o evento para edição |
| POST | `/admin/{adminToken}/registration` | Abre ou fecha as inscrições |
| POST | `/admin/{adminToken}/invite/regenerate` | Regenera o `inviteCode` |
| POST | `/admin/{adminToken}/delete` | Apaga o evento e todos os dados (confirmação) |
| GET | `/join/{inviteCode}` | Formulário de inscrição |
| POST | `/join/{inviteCode}` | Cadastra o participante |
| GET | `/p/{accessToken}` | Página do participante |
| POST | `/p/{accessToken}/wishlist` | Atualiza a wishlist |

Use POST para remoções (formulários HTML não têm DELETE) e proteja contra CSRF com um token de sessão próprio (não há Spring Security).

## 7. Segurança e privacidade

- Todo acesso é validado por token. Token inexistente retorna 404 (não 403), para não confirmar a existência.
- Os tokens do admin, do convite e dos participantes são distintos. Os dois últimos não dão acesso ao painel.
- Rotas `/p/**`, `/join/**` e `/admin/**` com `Cache-Control: no-store` e `Referrer-Policy: no-referrer`, para o token não vazar para terceiros. Os headers de segurança também cobrem `/` e `/events` (o redirect de criação carrega o token do painel).
- `robots.txt` bloqueia indexação; CSP restritiva (`script-src 'self'`, `frame-ancestors 'none'`); `X-Content-Type-Options`, `X-Frame-Options` e `X-Robots-Tag` nas rotas sensíveis.
- Cookie de sessão `HttpOnly`, `SameSite=Lax` e `Secure` por padrão. O perfil `dev` desativa `Secure` para rodar em HTTP local; produção exige HTTPS.
- O convite é público: limite de tamanho nos campos, teto de participantes por evento e rate limiting por IP em `POST /events` (5/10 min), `POST /join/{inviteCode}` (30/10 min), `draw`/`redraw` (10/h), wishlist (30/10 min) e endpoints de e-mail (`links/email` 10/h, `email/resend` 20/h), em memória, com `429` + `Retry-After`. **Validado em produção em 2026-11-15** (e-mail em 2026-11-16), inclusive contra `X-Forwarded-For` forjado. O rate limit dos endpoints de e-mail está no código e entra em produção com o próximo deploy. Ver [`docs/RATE-LIMITING.md`](docs/RATE-LIMITING.md).
- Validação de entrada com Bean Validation e escape automático do Thymeleaf contra XSS. Proteção CSRF com token de sessão próprio em todos os POSTs.
- Apagar evento exige CSRF e confirmação explícita; a operação é irreversível.
- Segredos (senha do banco) em variáveis de ambiente, fora do repositório.
- Como os tokens viajam na URL, não exponha o app em HTTP puro na internet com dados reais. Em produção há HTTPS desde 2026-11-15; em rede local, use o perfil `dev` ou um túnel.
- Disciplina do organizador: o banco guarda os `Assignment` em claro. Quem tem acesso direto ao banco pode ver o resultado. Para uso em família e trabalho isso é aceitável, e vale registrar no README.
- O link do painel é a credencial do organizador. O download e o e-mail dos links carregam aviso explícito para não compartilhar; o endereço digitado para o envio avulso não é persistido.
- O e-mail é apenas canal de entrega: não vira login, não substitui o token e não é exibido em página pública. Endereços são dados pessoais e são apagados junto com o evento. Em produção ele está **ligado e validado desde 2026-11-16**; a chave de produção é restrita a envio.

## 8. Arquitetura e estrutura do projeto

```
src/main/java/.../amigosecreto/
 ├─ event/          Event, EventRepository, EventService, EventController
 ├─ participant/    Participant, ParticipantRepository, ParticipantService, ParticipantController
 ├─ exclusion/      ExclusionGroup, repository, service
 ├─ draw/           Assignment, AssignmentRepository, DrawService, CycleDrawSolver, DrawImpossibleException, DrawCompletedEvent
 ├─ invite/         InviteService
 ├─ admin/          AdminController
 └─ common/         TokenGenerator, LinkBuilder, configuração, tratamento de erros

src/main/resources/
 ├─ db/migration/   V1__init.sql ...
 ├─ templates/      Thymeleaf
 ├─ static/         Pico.css, htmx
 └─ application.yaml, application-test.yaml (perfil de teste)

docs/IDEAS.md       Ideias futuras (e-mail, join público etc.)
Dockerfile
docker-compose.yml
```

A fase 2 adiciona o pacote `notification/` (`EmailSender`, `NotificationService` e templates de e-mail) sem alterar `draw/`. O `app.base-url` fica em configuração e é obrigatório para gerar links absolutos no painel, no WhatsApp e no futuro e-mail.

**Camadas:** controller (HTTP e view) → service (regras e transação) → repository (Spring Data JPA). Entidades JPA não vão direto para a view quando houver risco de expor dado indevido. Use DTOs ou `record`s para as telas sensíveis, principalmente a do participante.

### Esquema do banco (resumo da V1)

- `event(id, name, event_date, suggested_value, admin_token UNIQUE, invite_code UNIQUE, registration_open, status, drawn_at, draw_count, created_at)`
- `participant(id, event_id FK, name, access_token UNIQUE, wishlist, organizer, first_viewed_at)`
- `exclusion_group(id, event_id FK, label)`
- `exclusion_group_member(group_id FK, participant_id FK, PK(group_id, participant_id))`
- `assignment(id, event_id FK, giver_id FK, receiver_id FK, UNIQUE(event_id, giver_id), UNIQUE(event_id, receiver_id), CHECK(giver_id <> receiver_id))`

Use `ON DELETE CASCADE` a partir de `event`. Remover um participante deve remover também a participação dele nos grupos.

- Fase 2 de e-mail: `participant.email` (nullable, normalizado) e `notification(...)`; não fazem parte da V1.

### Migrações e `ddl-auto`

O schema é versionado com Flyway desde a V1. `spring.jpa.hibernate.ddl-auto=validate`; `update` não é usado. Motivos:

- `update` não é ferramenta de migração: ele adiciona coisas de forma conservadora, mas não renomeia, não remove, não migra dados e não garante que constraints e índices sejam iguais em dev e produção.
- `validate` falha cedo, no boot ou no teste, se a entidade divergir do schema. Isso evita a surpresa de "funciona na minha máquina".
- Cada mudança vira uma migration pequena (`V2__add_participant_email.sql`, `V3__create_notification.sql` etc.). Migration aplicada não é editada; adiciona-se outra.
- Se precisar recriar o banco em desenvolvimento, apague o volume e rode o Flyway de novo. Não troque para `update` "só no dev".
- Os testes rodam as mesmas migrations com `validate` (H2 com `MODE=PostgreSQL` no MVP; Testcontainers se o dialeto começar a divergir).

Isso também deixa a fase 2 barata: `email` e `notification` são migrations novas, não um schema paralelo.

## 9. Testes

**Unitários (prioridade máxima: `DrawService`)**

- ninguém tira a si mesmo;
- nenhuma exclusão é violada;
- todos dão e recebem exatamente uma vez e o resultado forma um único ciclo;
- caso impossível lança `DrawImpossibleException` com a razão correta;
- menos de 3 participantes é rejeitado, e 3 com qualquer grupo de exclusão também;
- 16 participantes em dois grupos de 8 (dois setores/famílias) são sorteados com sucesso — o caso que a tentativa e erro falhava;
- grafo em "bowtie" (5 pessoas, grupos `{A,C}`, `{A,D}`, `{B,C}`, `{B,D}`) é detectado como impossível;
- sorteio determinístico com `Random`/`RandomGenerator` com semente fixa;
- (opcional) teste de propriedade: para grafos aleatórios pequenos, o resultado, quando existe, respeita as invariantes acima.

**Serviço e repositório**

- regras de `OPEN` e `DRAWN` (não editar participantes nem grupos depois do sorteio);
- remoção de participante remove ele dos grupos;
- unicidade dos tokens;
- refazer sorteio: apaga os `Assignment` antigos, mantém os tokens, zera `firstViewedAt` e incrementa `drawCount`;
- reabrir evento: volta para `OPEN`, apaga os `Assignment`, zera `firstViewedAt` e reabre as inscrições;
- apagar evento: remove participantes, grupos e `Assignment` em cascata;
- teto de participantes e regeneração do `inviteCode`.

**Integração (MockMvc)**

- token inválido retorna 404;
- painel do admin não contém os dados de `Assignment`;
- página do participante só mostra o próprio resultado;
- `/join/{inviteCode}` aceita inscrição só com o evento `OPEN` e inscrições abertas, e não exibe a lista de participantes;
- `inviteCode` antigo deixa de funcionar depois de regenerado;
- `LinkBuilder` gera URLs absolutas a partir de `app.base-url`, independente do request;
- `DrawService` publica `DrawCompletedEvent` somente depois do commit e não publica em rollback.

**Banco de testes:** H2 em memória em `application-test.yaml`, com `MODE=PostgreSQL`, rodando as migrations do Flyway e `ddl-auto=validate`. Se as migrações usarem recursos específicos do Postgres, migre os testes de integração para Testcontainers (bom diferencial de portfólio).

## 10. Deploy na VPS

> **Executado em 2026-11-15:** `https://amigo-secreto.barbosalabs.dev` no ar, TLS Let's Encrypt,
> Flyway V1–V3 e backup diário por systemd timer. Registro completo em
> [`docs/DEPLOY_CONCLUIDO.md`](docs/DEPLOY_CONCLUIDO.md).

1. `Dockerfile` multi-stage: build com Maven/JDK, runtime com JRE enxuto, usuário não root.
2. `docker-compose.yml` de produção: serviços `app` e `db` (Postgres com volume persistente). O **Caddy** vive em um stack de borda separado, compartilhado com outras aplicações, e publica 80/443 com HTTPS automático (Let's Encrypt); a app não publica portas e entra na rede externa `edge` como `amigo-app`.
3. Configuração por variáveis de ambiente (`SPRING_DATASOURCE_URL`, usuário, senha, `APP_BASE_URL`) em arquivo `.env` fora do Git.
4. Backup periódico do volume do Postgres (`pg_dump` agendado). Na VPS atual, que **não tem `cron`**, o agendamento é um **systemd timer** (`amigo-secreto-backup.timer`, 03:15, retenção 14 dias).
5. Endpoint de saúde (`/actuator/health`), se adicionar o Actuator, usado pelo healthcheck do Compose.
6. Domínio e HTTPS **em produção desde 2026-11-15**: app em `amigo-secreto.barbosalabs.dev` e envio em `mail.barbosalabs.dev`, com DKIM/DMARC. A VPS não tem IPv6 global, então só há registro A.
7. Atrás do proxy, configurar `server.forward-headers-strategy=framework` para que os links absolutos saiam com o host e o esquema (`https`) corretos.

## 11. Fora do MVP (registrar em `docs/IDEAS.md`)

- E-mail transacional (fase 2): link individual após o sorteio/refazer, e-mail opcional, Brevo, domínio/SPF/DKIM/DMARC, retentativas e status de entrega. **Implementado, ligado e validado ponta a ponta em 2026-11-16**; SPF publicado e rate limit dos endpoints de e-mail no código (entra com o próximo deploy). Detalhes na seção 15.
- Telefone/WhatsApp Cloud API/SMS/Telegram como canal automatizado. O MVP mantém o `wa.me` manual; WhatsApp API é paga para mensagens utility iniciadas pelo negócio e SMS é pago.
- Recuperação de link por e-mail e double opt-in (avaliar depois da fase 2).
- Rate limiting por IP em `/events`, `/join` e sorteio, **implementado e validado em produção em 2026-11-15**. Limite diário, Redis e WAF continuam fora. Plano em [`docs/RATE-LIMITING.md`](docs/RATE-LIMITING.md).
- Permutação com reciprocidade permitida, como opção do evento.
- Expiração automática de eventos antigos (limpeza de dados).
- Domínio próprio e HTTPS: **implementado em 2026-11-15** (`amigo-secreto.barbosalabs.dev`).
- Internacionalização.

## 12. Plano de implementação

1. **Base do projeto:** Spring Initializr, Docker Compose com Postgres, Flyway desde a V1, `ddl-auto=validate`, perfil de teste com H2.
2. **Domínio e persistência:** entidades, repositórios, `TokenGenerator`, `LinkBuilder` com `app.base-url`.
3. **Eventos e participantes:** criação de evento, painel básico, adicionar e remover participantes, links individuais e apagar evento com confirmação.
4. **Link de convite:** `/join/{inviteCode}`, abrir e fechar inscrições, regenerar o código, teto de participantes.
5. **Grupos de exclusão:** modelo, serviço e tela com checkboxes.
6. **Sorteio:** `DrawService` com testes unitários primeiro (incluindo 16 participantes em dois grupos de 8), depois a integração com o painel, incluindo refazer e reabrir. Publica `DrawCompletedEvent` após o commit; o MVP não tem listener.
7. **Página do participante:** resultado, wishlist, registro de visualização, aviso de sorteio refeito.
8. **Acabamento:** estilos com Pico.css, mensagens de erro, HTMX onde ajudar.
9. **Segurança:** cabeçalhos, 404 para tokens inválidos, testes de privacidade.
10. **Deploy:** Dockerfile, Compose de produção, proxy, backup.
11. **Portfólio:** README (o que é, como rodar, decisões de arquitetura, prints) e `docs/IDEAS.md`.
12. **Fase 2 — e-mail:** coluna `email`, tabela `notification`, listener de `DrawCompletedEvent`, Brevo, conteúdo HTML/texto via `EmailContentBuilder` e testes de entrega (seção 15). **Implementado; produção desligada até a API key.**

## 13. Pontos em aberto

Nenhum ponto de decisão em aberto. O domínio/HTTPS foi aplicado e o deploy está em produção desde
2026-11-15. P9–P11 foram aplicadas, P12 (Caddy) foi aplicada e verificada em 2026-11-16, o e-mail foi
ligado/validado e o SPF foi publicado; o rate limit dos endpoints de e-mail está no código. As
pendências restantes são operacionais ou de código: P15 (deploy da imagem do commit novo), cópia
off-site, restore completo, reboot e fluxo no navegador. Ver
[`docs/DEPLOY_CONCLUIDO.md`](docs/DEPLOY_CONCLUIDO.md) §8/§9/§14 e [`docs/IDEAS.md`](docs/IDEAS.md).

## 14. Histórico de decisões

- 2026-11-13: sorteio definido como ciclo único com busca em profundidade (backtracking), restrições rígidas e mínimo de 4 participantes quando há grupos de exclusão. Substitui a tentativa e erro com `MAX_ATTEMPTS`; o caso de 16 participantes em dois grupos de 8 passa a ter cobertura de teste.
- 2026-11-13: MVP sem e-mail; entrega manual por copiar/WhatsApp. E-mail fica para a fase 2, opcional e sem virar login; telefone/WhatsApp API/SMS fora do MVP.
- 2026-11-13: preparação para e-mail no MVP: `LinkBuilder` com `app.base-url` e `DrawCompletedEvent` após o commit, sem coluna/provider/tabela de e-mail agora.
- 2026-11-13: Flyway desde a V1 com `ddl-auto=validate`; `update` não é usado.
- 2026-11-13: decisões de MVP confirmadas: organizador participa; apagar evento com confirmação; sem edição de nome de participante; CSRF por token de sessão; H2 nos testes; nomes duplicados permitidos com aviso; wishlist fora do painel; fuso convertido na borda.
- 2026-11-14: fase 2 implementada com Brevo, outbox `notification`, worker com retry/idempotência, e-mail opcional no join e na adição manual, status/reenvio no painel e envio real validado. Dedupe por e-mail continua fora desta versão.
- 2026-11-14: rate limiting por IP decidido para **depois do deploy**, com filtro em memória, janela fixa, sem dependência nova e limites generosos; plano em [`docs/RATE-LIMITING.md`](docs/RATE-LIMITING.md).
- 2026-11-14: mitigação da perda do link do painel: cartão de links com cópia, download em TXT e envio por e-mail sob demanda, mais aviso ao sair sem guardar. Não é login nem recuperação automática; a conta de organizador continua como fase futura.
- 2026-11-15: rate limiting em memória implementado: `OncePerRequestFilter` por IP/categoria, janela fixa, mapa LRU, `429` + `Retry-After` e janela própria de 1 hora para sorteio. Validado em produção no mesmo dia, inclusive contra `X-Forwarded-For` forjado.
- 2026-11-15: deploy endurecido: Postgres de dev em loopback, `mem_limit`/`cpus`, rotação de log, build ARM64 sem QEMU, imagem imutável no GHCR e Caddy desacoplado para um stack de borda externo (rede `edge`).
- 2026-11-15: **deploy real concluído** em `https://amigo-secreto.barbosalabs.dev`: DNS A, Security List Oracle, stack `edge` com `edge-caddy-1`, imagem pública `2b6e2d4…`, Flyway V1–V3 e backup diário por **systemd timer** (a VPS não tem `cron`). Divergências e pendências (P9–P12) em [`docs/DEPLOY_CONCLUIDO.md`](docs/DEPLOY_CONCLUIDO.md).
- 2026-11-15: P9–P11 aplicadas: `db` com `mem_limit: 512m`/`cpus: "0.5"`, exemplos de `.env` com `BREVO_FROM_NAME="Amigo Secreto"` e `deploy.sh` validando o arquivo, e sessão só por cookie (`tracking-modes: cookie`) com teste. P12 (`mem_limit: 256m` do Caddy) foi aplicada e verificada no runtime em 2026-11-16.
- 2026-11-16: e-mail real ligado e validado ponta a ponta em produção: chave restrita a envio, `.env` com `EMAIL_ENABLED=true`/`BREVO_SANDBOX=false`, 2 notificações `SENT` (`attempts=1`), painel **Enviado**, zero erros e evento de teste apagado. SPF publicado e rate limit dos endpoints de e-mail implementado no código no mesmo dia; pendente: deploy da imagem do commit novo (P15), que leva o rate limit.

## 15. Fase 2 — e-mail (implementado)

Guia operacional detalhado: [`docs/EMAIL-BREVO.md`](docs/EMAIL-BREVO.md).

A fase 2 está implementada: o e-mail é um canal **opcional** de entrega do link individual, nunca credencial e nunca obrigatório. O caminho manual (`wa.me` e copiar link) continua funcionando. O envio real depende de `EMAIL_ENABLED=true`, `BREVO_SANDBOX=false` e da API key de produção no ambiente. **Em 2026-11-16 o e-mail foi ligado e validado ponta a ponta**: 2 notificações `SENT`, `attempts=1`, painel **Enviado**, zero erros e evento de teste apagado. A chave de produção é restrita a envio (`GET /v3/account` → 403 é esperado). O IP de saída da VPS é `147.15.121.26`; o SPF foi publicado e verificado, e o rate limit dos endpoints de e-mail (10/h e 20/h) está no código, entrando em produção com o próximo deploy.

### O que ficou preparado antes da fase 2

- `LinkBuilder` central com `app.base-url`, gerando links absolutos para painel, participante e convite. Usado pelos botões de copiar/WhatsApp e pelo e-mail.
- `DrawService` publica `DrawCompletedEvent` **após o commit** (não dentro da transação). Na época, o MVP não tinha listener; a fase 2 adicionou o listener de e-mail.
- DTOs nas telas; adicionar `email` e status de entrega depois é aditivo.
- Nenhuma coluna `email`, tabela de notificação, provider ou template no MVP.

### Escopo da fase 2

- `Participant.email` opcional (nullable), normalizado (trim + lowercase); **não é credencial**.
- Formulário de join e adição manual com e-mail opcional.
- Envio do link individual após o sorteio e após refazer; o link não muda, o `drawCount` identifica a nova rodada.
- Tabela `notification` (ou `email_delivery`): participante, `draw_count`, canal, status, tentativas, id do provedor, último erro, enviado em; único por `(participant, draw_count, channel)` para idempotência.
- Envio assíncrono: o `DrawService` publica `DrawCompletedEvent` só depois do commit; o listener grava o outbox numa transação nova (`TransactionTemplate` com `REQUIRES_NEW`) e um worker agendado processa os pendentes. Retentativas não desfazem o sorteio.
- Conteúdo HTML + texto montado por `EmailContentBuilder`, com link absoluto via `LinkBuilder` e HTML escapado; sem pixel de rastreamento.
- Painel mostra status de entrega e botão "reenviar"; o reenvio gera nova chave de idempotência.
- O painel também envia, sob demanda, os três links do organizador para um e-mail digitado na hora. É envio direto (não passa pelo outbox de sorteio), não persiste o endereço e não é recuperação de conta.
- Dedupe por e-mail (melhor esforço, sem bloquear) **não foi implementado nesta versão**: dois participantes com o mesmo e-mail recebem dois avisos. Caixa compartilhada e dois e-mails para a mesma pessoa são limitações conhecidas.
- Sem verificação (double opt-in) na primeira versão. Se a recuperação de link por e-mail virar requisito, adicionar confirmação depois.
- Privacidade/LGPD: coletar apenas o necessário, informar a finalidade, não expor e-mail em página pública, apagar junto com o evento (cascade) e com a remoção do participante, não logar e-mail nem token.

### Provedor e domínio

- Recomendação: **Brevo** no plano grátis (300 e-mails/dia, sem prazo; inclui "Sent with Brevo"). **Resend** tem 3.000/mês mas 100/dia, apertado para 100 participantes + refazer. **SMTP2GO** tem 1.000/mês e 200/dia, mas 25/hora até verificar o domínio. **SES** é centavos, mas exige domínio e liberação de produção. Limites conferidos em nov/2026; revalidar antes de implementar.
- O "domínio" é o **domínio de envio** (From), não necessariamente o da aplicação. Um único domínio resolve os dois: app em `app.exemplo.com` e e-mail em `mail.exemplo.com`, com SPF/DKIM/DMARC no DNS. Não precisa hospedar e-mail.
- O link no e-mail precisa ser público e HTTPS; em produção usa `https://amigo-secreto.barbosalabs.dev`.

### O que não entra

- Telefone como identidade ou canal no MVP. WhatsApp Cloud API é pago para mensagens utility iniciadas pelo negócio (o free tier cobre apenas service messages), exige conta business, número, opt-in, template aprovado e meio de pagamento. Telegram é grátis, mas exige que cada pessoa inicie o bot. SMS é pago. O caminho do MVP continua sendo o `wa.me` manual.
