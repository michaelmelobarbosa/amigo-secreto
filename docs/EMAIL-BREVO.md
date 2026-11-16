# Fase 2 — e-mail com Brevo

Guia operacional e de implementação para o envio do link individual por e-mail depois do sorteio. **Status:** backend implementado e **em produção, ligado e validado ponta a ponta em 2026-11-16**: 2 notificações `SENT` com `attempts=1`, painel exibindo **Enviado**, sem erro/retentativa, e evento de teste apagado. A chave de produção é restrita a envio (`GET /v3/account` → 403 é esperado). O SPF foi publicado em 2026-11-16. Os endpoints de e-mail têm rate limit no código (10/h e 20/h), que entra em produção com o próximo deploy; webhooks/double opt-in são opcionais.

## 1. Decisão de arquitetura

- **Canal:** Brevo, plano grátis (300 e-mails/dia).
- **Modo:** API REST (`POST /v3/smtp/email`), não SMTP. A API devolve `messageId`, aceita idempotência, sandbox e erros estruturados; o SMTP não dá nada disso com a mesma clareza.
- **E-mail não é login:** continua sendo só canal de entrega. O token do link é a credencial.
- **Opcional:** participante sem e-mail continua existindo; o organizador copia o link no painel.
- **Fora do escopo desta fase:** verificação por código (double opt-in), e-mail marketing, SMS/WhatsApp e envio de convite por e-mail.

Fluxo:

1. Participante informa e-mail opcional no join ou o organizador adiciona manualmente.
2. Sorteio/refazer publica `DrawCompletedEvent` **após o commit**.
3. Um listener cria uma linha em `notification` por participante com e-mail e `draw_count`.
4. Um worker assíncrono envia pela API do Brevo, com idempotência e retentativa.
5. O painel mostra “enviado”, “falhou” e permite reenviar; a página do participante continua funcionando pelo link.

## 2. Conta e domínio no Brevo (passo a passo)

### 2.1 Conta

1. Crie a conta em `brevo.com` (sem cartão no plano grátis).
2. A Brevo precisa **aprovar a conta para envio**; até lá, o envio pode ficar bloqueado.
3. Ative 2FA na conta e use uma senha única.

### 2.2 Domínio de envio

Não use o domínio da aplicação necessariamente; o importante é o domínio que aparece no `From`. Recomendação: um subdomínio só para e-mail.

Exemplo:

- App: `app.exemplo.com`
- E-mail: `mail.exemplo.com`
- Remetente: `no-reply@mail.exemplo.com`
- Respostas: `contato@exemplo.com` (`Reply-To`)

Passos:

1. No Brevo: **Settings → Senders, Domains & Dedicated IPs → Domains → Add a domain**.
2. Informe o domínio de envio (`mail.exemplo.com`) e escolha autenticação manual se você já tem DMARC no domínio raiz.
3. O Brevo mostra os registros. Copie **exatamente** os valores da sua conta:
   - **Brevo code**: TXT no domínio de envio (prova de posse).
   - **DKIM**: dois CNAMEs (`brevo1._domainkey` e `brevo2._domainkey`) ou um TXT, conforme a conta mostrar. Prefira os dois CNAMEs (chave 2048 bits e rotação).
   - **DMARC**: um único TXT em `_dmarc` com `rua` (o Brevo sugere `v=DMARC1; p=none; rua=mailto:rua@dmarc.brevo.com`; você pode usar seu próprio endereço).
4. No DNS:
   - Cloudflare: deixe os CNAMEs de DKIM **DNS only** (cinza), sem proxy e sem CNAME flattening.
   - Não crie um segundo registro DMARC: dois registros invalidam a avaliação.
   - No **subdomínio dedicado de envio** (`mail.barbosalabs.dev`), publique o SPF indicado pelo Brevo:
     `mail.barbosalabs.dev TXT "v=spf1 include:spf.sendinblue.com -all"` (confirme o valor atual na
     aba de autenticação; pode variar). Não crie dois SPF para o mesmo nome.
   - Em IP compartilhado no domínio raiz, **não adicione o include só por causa do Brevo**: o
     Return-Path é da Brevo e o DMARC passa por DKIM. O SPF do subdomínio dedicado é o que fecha a
     autenticação.
5. Clique em **Authenticate this email domain** e aguarde a propagação (pode levar até 48h).
6. Crie o remetente em **Senders** com o endereço exato do domínio autenticado. O `From` tem que ser do domínio autenticado; se autenticou `mail.exemplo.com`, não envie de `@exemplo.com`.

> **Estado em 2026-11-16:** Brevo code, DKIM (`brevo1`/`brevo2`), DMARC e **SPF** estão publicados
> em `mail.barbosalabs.dev`. O SPF (`v=spf1 include:spf.sendinblue.com -all`) foi verificado no DNS
> público no mesmo dia; o `brevo-code` continua no mesmo nome, como segundo TXT.

### 2.3 Credenciais

- **API key:** Settings → SMTP & API → API keys → Generate. Copie na hora; ela só aparece uma vez e começa com `xkeysib-`.
- Crie chaves separadas para dev/prod; nunca commite.
- A chave de produção pode ser **restrita a envio** (escopo de e-mail transacional). Nesse caso,
  `GET /v3/account` retorna **403 `permission_denied`**; isso é esperado e é menor privilégio. Valide a
  chave por um envio em sandbox (`X-Sib-Sandbox: drop`) ou um e-mail real.
- Se ativar **Block unknown IP addresses**, cadastre o IP público da VPS (o IP de saída dos containers
  medido em 2026-11-15 é `<VPS_PUBLIC_IP>`, o mesmo IP público do host) antes de testar; senão o envio
  falha com 401.
- **SMTP key** é outra credencial (`smtp-relay.brevo.com`, portas 587/2525 STARTTLS ou 465 SSL). Não confunda com API key. Para este projeto, use API key.

### 2.4 Onde conseguir domínio grátis sendo estudante

O GitHub Student Developer Pack tem três ofertas de domínio (cada uma pode ser resgatada uma vez; você pode pegar as três, mas não precisa):

| Oferta | Extensões | Prazo grátis | Cartão? | Observação |
| --- | --- | --- | --- | --- |
| **Name.com** | 25+ extensões, incluindo **.dev**, **.app**, .live, .studio, .software | Primeiro ano (confirme no checkout) | Sim, mas não cobra o ano grátis | **Melhor opção para este projeto**: `.dev` é profissional e combina com HTTPS |
| **Namecheap** | **.me** + 1 SSL | 1 ano | Não informado | Boa para portfólio pessoal; confira a renovação no checkout |
| **.TECH** | **.tech** (nomes standard) | 1 ano | Não informado | Renovação costuma ser bem mais cara; evite se for manter anos |

Recomendação para o Amigo Secreto:

- **Name.com `.dev`**: use `seudominio.dev` para o app e `mail.seudominio.dev` para o Brevo. `.dev` está na lista HSTS, então **só HTTPS**; o Caddy/Let's Encrypt resolve isso. É a opção mais “portfólio”.
- Se não quiser cartão ou preferir algo pessoal, **Namecheap `.me`**: `seudominio.me` + `mail.seudominio.me`.
- `.tech` só se você gostar do nome e aceitar a renovação cara.

Cuidados:

- É grátis **só no primeiro ano**. Anote a data, desligue o auto-renew se não quiser pagar e confira o preço de renovação (as fontes divergem; veja o checkout).
- Name.com pode exigir cartão na conta, mas o ano grátis deve aparecer como `$0` no carrinho. Se não aparecer, volte pelo link do Pack.
- O domínio precisa estar no **seu** nome/conta e você precisa controlar o DNS (Name.com, Namecheap ou Cloudflare).
- **Subdomínios grátis não servem para o Brevo**: `github.io`, `pages.dev`, `vercel.app`, `netlify.app`, `is-a.dev`, `thedev.id`, `eu.org` etc. não são domínios registrados por você; o Brevo exige um domínio próprio para autenticar DKIM/DMARC. Use-os só para o site, não para e-mail.
- **Freenom** (`.tk`, `.ml`, `.ga`, `.cf`, `.gq`) encerrou o modelo gratuito/saiu do negócio; não use. Serviços como `us.kg` já tiveram suspensão por abuso; evite para e-mail.
- Se as ofertas estudantis não derem certo, um `.com` no Cloudflare Registrar custa cerca de US$ 8–9/ano — barato e confiável para um projeto real.

Links oficiais: [GitHub Student Developer Pack](https://education.github.com/pack), [Namecheap .me](https://nc.me/landing/github), [Name.com](https://www.name.com/), [.TECH](https://get.tech/).

### 2.5 Escolha do nome e subdomínios

Para este projeto, com um domínio `seudominio.dev`:

- App: `app.seudominio.dev` (ou o raiz `seudominio.dev`)
- E-mail: `mail.seudominio.dev`
- `From`: `no-reply@mail.seudominio.dev`
- `Reply-To`: `contato@seudominio.dev` (pode ser um Gmail seu; não precisa autenticar)
- Brevo: autentique **`mail.seudominio.dev`** (não o raiz, a não ser que vá enviar do raiz)
- Caddy: `app.seudominio.dev` com A record para a VPS e HTTPS automático

Não use o mesmo host para app e e-mail se quiser separar reputação; subdomínio é mais limpo.

### 2.6 Teste rápido da API

```bash
curl -s https://api.brevo.com/v3/account \
  -H "api-key: $BREVO_API_KEY" \
  -H "accept: application/json"
```

Se retornar os dados da conta, a chave está válida.

## 3. Limites e custos do plano grátis

- **300 e-mails/dia**, sem prazo; o limite é compartilhado entre campanhas e transacionais.
- 100.000 contatos armazenados; 1 usuário.
- E-mails do plano grátis levam o selo **“Sent with Brevo”**; remover exige plano pago/add-on.
- IP compartilhado (sem IP dedicado), então a entregabilidade depende de domínio autenticado e reputação.
- Ao estourar o limite diário, até 1.000 transacionais podem ficar numa fila de retentativa; além disso, não são entregues. Não conte com a fila como garantia.
- Não misture campanha de marketing com o mesmo evento; 100 resultados + refazer já usam 200 dos 300 do dia.
- Rate limit da API de envio: **1.000 req/s** no plano geral, mas o gargalo real é a cota diária. `GET /v3/smtp/emails` (logs) é 2 req/s e 7.200/hora; não faça polling.

## 4. API de envio

### 4.1 Requisição

`POST https://api.brevo.com/v3/smtp/email`

Headers HTTP:

- `api-key: xkeysib-...`
- `Content-Type: application/json`
- `Accept: application/json`

Corpo mínimo:

```json
{
  "sender": { "name": "Amigo Secreto", "email": "no-reply@mail.exemplo.com" },
  "to": [{ "email": "maria@exemplo.com", "name": "Maria", "removeTrackingPixel": true }],
  "replyTo": { "email": "contato@exemplo.com", "name": "Organizador" },
  "subject": "Seu resultado do amigo secreto",
  "htmlContent": "<p>Olá, Maria. <a href=\"https://app.exemplo.com/p/p_...\">Veja seu resultado</a>.</p>",
  "textContent": "Olá, Maria. Veja seu resultado: https://app.exemplo.com/p/p_...",
  "tags": ["amigo-secreto", "draw-1"],
  "headers": {
    "idempotencyKey": "UUID-v4-estável-por-notificação",
    "X-Sib-Sandbox": "drop"
  }
}
```

Detalhes importantes:

- `sender` e `to` são objetos `{email, name}`, nunca `"Nome <email>"`.
- `htmlContent` **ou** `textContent` ou `templateId`; mande HTML e texto.
- `replyTo` é um único endereço.
- `removeTrackingPixel: true` no destinatário evita pixel de abertura.
- `tags` ajudam a filtrar logs/webhooks; use `draw-{drawCount}`.
- **`idempotencyKey` e `X-Sib-Sandbox` vão dentro do objeto `headers` do corpo**, não como headers HTTP reais. A chave de idempotência é UUID e vale por 30 minutos; repetir a mesma chave no período retorna `400 duplicate_parameter` sem reenviar.
- Em produção, remova `X-Sib-Sandbox`; ele só valida o payload e não entrega nada (e não cria log).
- Resposta de sucesso: **201** com `messageId` (guarde para casar com webhooks/logs). `202` é agendamento (não usamos).

### 4.2 Erros e retentativa

| Status | Código | Ação |
| --- | --- | --- |
| 400 | `duplicate_parameter` | A requisição já foi aceita; trate como enviada |
| 400 | `missing_parameter`/`invalid_parameter` | Bug de payload; não retentar, corrigir |
| 400 | `not_enough_credits` | Cota esgotada; falhar e avisar |
| 401 | `unauthorized` | Chave/IP bloqueado; config, não retentar |
| 402/403 | crédito/permissão | Falha de conta; avisar |
| 429 | rate limit | Esperar `x-sib-ratelimit-reset` segundos e retentar |
| 5xx/rede | — | Retentar com backoff e a **mesma** idempotencyKey |

Não há `Retry-After`; leia `x-sib-ratelimit-limit`, `x-sib-ratelimit-remaining` e `x-sib-ratelimit-reset`.

### 4.3 Sandbox

Para desenvolvimento/staging, use `X-Sib-Sandbox: drop` no corpo. Retorna 201 e `messageId`, não envia e não registra log. **Não testa entrega, DKIM nem renderização**; faça um envio real para uma caixa sua antes do lançamento.

## 5. Integração no backend

### 5.1 Migrações Flyway

`V2__add_participant_email.sql`:

```sql
ALTER TABLE participant ADD COLUMN email VARCHAR(254);
```

`V3__create_notification.sql` (esboço):

```sql
CREATE TABLE notification (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL,
    draw_count INTEGER NOT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(255),
    last_error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_notification_participant FOREIGN KEY (participant_id)
        REFERENCES participant (id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_participant_draw_channel
        UNIQUE (participant_id, draw_count, channel)
);

CREATE INDEX idx_notification_status ON notification (status);
```

- `email` é nullable; normalizado (trim + lowercase) na entrada.
- Não torne e-mail obrigatório no join; quem não informar continua recebendo o link na tela.
- Unicidade por evento/email é “melhor esforço”; sem verificação, duas pessoas podem usar o mesmo e-mail. Evite bloquear de forma agressiva; mostre mensagem neutra ou deixe o organizador resolver.
- `notification` é o outbox: uma linha por participante por `draw_count`; idempotente.
- `channel` já nasce com `EMAIL` para permitir outros canais no futuro.

### 5.2 Configuração

```yaml
app:
  email:
    enabled: ${EMAIL_ENABLED:false}
    brevo:
      api-key: ${BREVO_API_KEY:}
      from-email: ${BREVO_FROM_EMAIL:no-reply@mail.exemplo.com}
      from-name: ${BREVO_FROM_NAME:Amigo Secreto}
      reply-to: ${BREVO_REPLY_TO:contato@exemplo.com}
      sandbox: ${BREVO_SANDBOX:false}
```

- `enabled=false` em dev/test: use `LoggingEmailSender`/fake.
- Falhe o startup em produção se `enabled=true` e faltar chave/remetente.
- Nunca logue a API key, o e-mail ou o token do link.

### 5.3 Porta e adaptador

```java
public interface EmailSender {
    EmailSendResult send(EmailMessage message);
}

public record EmailMessage(
    String toEmail,
    String toName,
    String subject,
    String html,
    String text,
    UUID idempotencyKey,
    List<String> tags) {}

public record EmailSendResult(
    boolean accepted,
    String providerMessageId,
    String errorCode,
    String errorMessage,
    boolean retryable) {}
```

- `BrevoEmailSender` usa `RestClient` (Spring 6.1+/Boot 4), `POST /v3/smtp/email`, header `api-key`, e devolve o resultado tipado.
- `LoggingEmailSender` em dev/test registra “enviaria para…”, sem e-mail real.
- O SDK oficial Java existe (`com.brevo:brevo:1.1.11`), mas para um único endpoint o `RestClient` evita dependência extra e dá controle de timeout/códigos. Se usar SDK, pine a versão e cuidado com o cliente OkHttp.
- Não use SMTP neste projeto: sem `messageId`, sem idempotência e sem sandbox.

### 5.4 Listener do sorteio

O `DrawService` já publica `DrawCompletedEvent` após o commit. Então:

```java
@EventListener
@Transactional
public void onDrawCompleted(DrawCompletedEvent event) {
    notificationService.enqueueForDraw(event);
}
```

- `enqueueForDraw` busca participantes com e-mail do evento e cria `notification(PENDING)` para `(participant, drawCount, EMAIL)`, ignorando duplicatas.
- **Não envie dentro da transação do sorteio.** O evento já é pós-commit; o envio é do worker.
- Refazer sorteio gera novo `drawCount`; novas notificações e o e-mail diz “sorteio refeito”.
- Reabrir não envia resultado; se houver notificações pendentes do draw antigo, o worker deve verificar o estado atual e marcá-las como `SKIPPED` se o evento não estiver mais `DRAWN`/`drawCount` mudou.

### 5.5 Worker, idempotência e status

- `@Scheduled(fixedDelay = 30_000)` busca `PENDING`/`FAILED` com `attempts < 3`, em lote pequeno (ex.: 20).
- Marca `SENDING` antes de chamar a API; ao voltar 201, `SENT` + `provider_message_id`; erro, `FAILED` + `last_error`.
- Retry: 429 → espera reset; 5xx/rede → backoff; 400 de payload/401 → não retentar.
- Use a `idempotencyKey` persistida na notificação; repita a mesma em retries dentro de 30 min.
- `duplicate_parameter` com idempotência = já aceito; trate como `SENT` (sem `messageId`).
- Não faça polling em `GET /v3/smtp/emails`; use webhooks se quiser status de entrega.
- Status no painel: “sem e-mail”, “pendente”, “enviado”, “falhou”, com botão “reenviar” que zera `attempts` e volta para `PENDING`.
- Reenvio manual deve usar a mesma `idempotencyKey`? Se já passou de 30 min, pode gerar nova chave; se o usuário pedir reenvio, uma nova mensagem é aceitável. Registre a tentativa.

### 5.6 Conteúdo do e-mail

O conteúdo é montado em Java por [`EmailContentBuilder`](../src/main/java/br/com/michael/amigosecreto/email/EmailContentBuilder.java)
(não há templates Thymeleaf de e-mail no repositório):

- Assunto: `Seu resultado do amigo secreto — {evento}` (ou “Sorteio refeito”).
- Corpo: “Olá, {nome}. Seu resultado está pronto.” + link absoluto + “Guarde este link; ele é a única forma de ver seu resultado. Não compartilhe.”
- Não inclua a wishlist no e-mail; ela já aparece na página e pode mudar. Inclua o link.
- HTML com CSS inline simples; texto puro como fallback. Não use imagens/pixel.
- Escaping: o builder escapa nomes e wishlist no HTML; não há `th:utext` em jogo.
- O link vem do `LinkBuilder` (`app.base-url`), nunca do request assíncrono.
- Pode incluir `{{params}}`? Não; renderize no app e envie `htmlContent`/`textContent`. Isso mantém o conteúdo versionado no Git.

### 5.7 Join e painel

- `Participant.email` opcional no join e na adição manual; validação `@Email` + tamanho 254.
- No painel, mostrar a presença do e-mail e o status da notificação; nunca a wishlist.
- Se o e-mail estiver errado, o organizador ainda pode copiar o link; uma edição de e-mail poderia entrar depois (hoje não há rota de editar participante).
- Dedupe: normalizar e comparar; mensagem neutra em caso de duplicado. Não bloqueie sem verificação.
- Apagar evento remove `notification` em cascata.

### 5.8 Links do organizador (envio avulso)

- O painel tem um cartão "Seus links" com o link do painel, o link do organizador como participante e o convite.
- Ações: copiar, baixar um `.txt` e enviar os três por e-mail para um endereço digitado na hora.
- O envio é **direto** pelo `OrganizerLinksMailer`, não passa pelo outbox de sorteio: é uma ação explícita do organizador, com resposta imediata.
- O endereço digitado **não é persistido** e não vira login nem recuperação automática.
- Com `app.email.enabled=false`, o cartão informa que o envio está desativado e aponta o TXT.
- Em sandbox, o Brevo aceita mas não entrega; a tela avisa em vez de fingir sucesso.
- O TXT e o e-mail carregam aviso para não compartilhar o link do painel.
- Testes: `OrganizerLinksMailerTest`, `OrganizerLinksWebIntegrationTest` e `OrganizerLinksEmailWebIntegrationTest`.

## 6. Webhooks (opcional, fase 2.1)

Brevo **não assina** webhooks. Para usar:

- Crie o webhook via dashboard/API com **Bearer token** ou custom header e/ou allowlist de IPs da Brevo.
- Endpoint `POST /webhooks/brevo` fora das rotas de token, com HTTPS.
- Valide `Authorization: Bearer` em tempo constante; responda 200 rápido; processe assíncrono.
- Eventos transacionais: `sent`/`request`, `delivered`, `hardBounce`, `softBounce`, `blocked`, `spam`, `invalid`, `deferred`, `click`, `opened`, `uniqueOpened`, `unsubscribed`.
- Deduplicate por `(message-id, event)`; o payload inclui `event`, `email`, `message-id`, `tag`/`tags`, timestamps.
- Atualize `notification.status` para `DELIVERED`/`BOUNCED`/`SPAM`; não guarde o HTML nem o token.
- Para o MVP de e-mail, dá para começar só com o retorno 201 e o painel; webhooks entram quando você quiser saber entrega/bounce.

## 7. Testes

1. **Unitário do adaptador:** payload, header `api-key`, `idempotencyKey` no body, `X-Sib-Sandbox`, `removeTrackingPixel`, parsing de 201/400/429/5xx. Use `MockRestServiceServer`/WireMock.
2. **Sandbox real:** chamar a API com `X-Sib-Sandbox: drop` e conferir 201; não prova entrega.
3. **Envio real controlado:** para seu Gmail/Outlook; abra “Show original” e confirme DKIM `pass` e DMARC `pass`; confira o log transacional do Brevo.
4. **Serviço:** draw publica evento → notificações criadas; redraw cria novas por `drawCount`; falha de e-mail não desfaz o sorteio; reabrir marca pendentes como skipped.
5. **Integração:** `EmailSender` fake captura o link; o link enviado é o mesmo `/p/{token}` da página; sem e-mail, não cria notificação.
6. **Segurança:** API key não aparece em log/erro; token não aparece em log; webhook rejeita bearer inválido.
7. **Cota:** simular 300 envios e garantir que o app para/marca falha em vez de entrar em loop.

## 8. Operação e monitoramento

- **Estado em 2026-11-15:** deploy concluído com `EMAIL_ENABLED=false` e `BREVO_API_KEY` vazio;
  nenhum e-mail foi enviado. O IP de saída dos containers é `<VPS_PUBLIC_IP>`.
- **Atualização em 2026-11-16:** e-mail **ligado e validado ponta a ponta** em produção.
  `.env` com `EMAIL_ENABLED=true`, `EMAIL_WORKER_ENABLED=true`, `BREVO_SANDBOX=false` e a chave em
  `600`; container `amigo-app` healthy. Teste real com 2 participantes: as duas notificações ficaram
  `SENT`, `attempts=1`, com `provider_message_id`, e o painel exibiu **Enviado**; nenhum erro/aviso
  do Brevo no log. O evento de teste foi apagado e o banco voltou a 0 eventos/0 participantes/0
  notificações/0 assignments. A chave é restrita a envio (`GET /v3/account` → 403 esperado).
- Guarde `BREVO_API_KEY` no `.env`/secret manager; nunca no Git, imagem Docker ou log.
- Se ativar bloqueio de IP, cadastre o IP da VPS; confirme o IP de saída do container.
- Monitore **Transactional → Logs** e a cota diária; 100 resultados + refazer = 200.
- Não habilite access log do proxy para `/p`, `/join`, `/admin`; o Brevo vê os links (é o canal), mas seus logs não precisam.
- Rotacione a API key periodicamente; revogue a antiga.
- Faça um envio real de teste após qualquer mudança de DNS/domínio.
- Se o plano grátis ficar apertado, o próximo passo é Starter; não tente burlar a cota com múltiplas contas.

### 8.1 Ativação em produção

> Executado e validado em 2026-11-16. O roteiro abaixo serve para uma nova VPS, uma rotação de chave
> ou uma reconstrução do ambiente.

1. Crie a **API key de produção** no Brevo (Settings → SMTP & API → API keys), separada da usada em
   dev. Ela começa com `xkeysib-` e pode ser restrita ao escopo de envio.
2. Se a conta usar **allowlist de IP**, cadastre `<VPS_PUBLIC_IP>` (IP público/saída da VPS).
3. No `/opt/amigo-secreto/.env`:
   ```bash
   EMAIL_ENABLED=true
   EMAIL_WORKER_ENABLED=true
   BREVO_SANDBOX=false
   BREVO_API_KEY=xkeysib-...
   BREVO_FROM_EMAIL=no-reply@mail.barbosalabs.dev
   BREVO_FROM_NAME="Amigo Secreto"
   ```
4. Recrie a app:
   ```bash
   cd /opt/amigo-secreto
   sg docker -c 'docker compose -f docker-compose.prod.yml up -d app'
   ```
5. Confira o arranque sem a chave no log:
   ```bash
   sg docker -c 'docker compose -f docker-compose.prod.yml logs --tail=100 app | grep -i -E "email|brevo|notification"'
   ```

Se `EMAIL_ENABLED=true` e `BREVO_API_KEY`/`BREVO_FROM_EMAIL` estiverem vazios, o startup **falha**
(fail-fast do `BrevoEmailSender`). O `.env.prod.example` continua com `EMAIL_ENABLED=false` por padrão
para não quebrar um deploy sem chave.

### 8.2 Teste de fumaça

**Direto no Brevo** (na VPS, na raiz do projeto, com o `.env` de produção):
```bash
TEST_EMAIL=seu@email.com bash scripts/check-brevo.sh
```
O script tenta `GET /v3/account`; uma chave restrita a envio retorna **403 `permission_denied`** e o
script segue para o teste de envio. Com `BREVO_SANDBOX=true`, ele envia com `X-Sib-Sandbox: drop` e
não entrega; com `false`, entrega de verdade. `201/202` = aceito; `401` = chave; `429` = cota. Ele
não imprime a API key.

> A validação de produção de 2026-11-16 foi feita pelo Hermes com a chave restrita a envio: sandbox
> `201`, depois dois envios reais `SENT`. Se você rodar o script na VPS, confirme o IP
> `<VPS_PUBLIC_IP>` e a allowlist, se estiver ativa.

**Pela aplicação** (mesmo caminho do botão do painel):
```bash
TEST_EMAIL=seu@email.com bash scripts/smoke-email.sh
```
O script:
- cria um evento temporário;
- envia os três links pelo `OrganizerLinksMailer` (Brevo real);
- espera o flash “Links enviados para o e-mail informado.”;
- apaga o evento no final;
- não imprime o token do painel.

Se aparecer “O envio de e-mail está desativado”, o `.env` ainda está com `EMAIL_ENABLED=false`. Se
aparecer “Links aceitos em modo sandbox”, `BREVO_SANDBOX=true` e nada foi entregue.

> O teste de produção de 2026-11-16 foi feito via API/UI porque os scripts ainda **não estavam no
> remoto** naquele momento. Eles já foram adicionados ao repositório e podem ser usados na VPS após o
> deploy do commit que os contém.

**Fluxo completo do sorteio:** crie um evento, adicione um participante com e-mail, sorteie e confira
o status **Enviado** no painel e o **Transactional Logs** do Brevo. O worker roda a cada 30s; o painel
mostra `Pendente`, `Enviado`, `Falhou` ou `Ignorado`, e `notification.last_error` guarda o erro do
provedor. Esse é o teste que cobre outbox + worker, além do adaptador.

> **Cota e abuso:** o plano grátis tem 300 e-mails/dia. Um sorteio de 100 participantes usa 100;
> cada refazer usa mais 100 — dois refazeres no mesmo dia estouram a cota. Os endpoints
> `POST /admin/{token}/links/email` (10/h) e `POST /admin/{token}/participants/{id}/email/resend`
> (20/h) têm rate limit no código, que entra em produção com P15; a cota diária em si ainda não tem
> um teto separado.

## 9. Checklist de implementação

- [x] Conta Brevo aprovada e envio real testado.
- [x] Domínio de envio autenticado (Brevo code + DKIM + DMARC; sem SPF include em IP compartilhado).
- [x] Sender `no-reply@mail.barbosalabs.dev` criado e `From` alinhado ao domínio.
- [x] API key de **dev** criada e guardada fora do Git.
- [x] Teste de `/v3/account` e envio sandbox.
- [x] Migrações V2/V3 com `Participant.email` e `notification`.
- [x] `EmailProperties`, `EmailSender`, `BrevoEmailSender`, `LoggingEmailSender`.
- [x] Listener de `DrawCompletedEvent` + outbox + worker + retry/idempotência.
- [x] Templates HTML/texto com link absoluto e aviso de privacidade (via `EmailContentBuilder`).
- [x] Painel com status e reenvio; join com e-mail opcional.
- [x] Testes unitários/integração + sandbox + um envio real.
- [x] Runbook no README/DEPLOY (env, IP allowlist, cota, rotação).
- [x] Deploy em produção em 2026-11-15 com o e-mail desligado (`EMAIL_ENABLED=false`, sem API key).
- [x] API key de produção validada em 2026-11-16 por sandbox `201` e por envios reais (`GET /account` 403 é esperado em chave restrita).
- [x] E-mail habilitado em produção (`EMAIL_ENABLED=true`, `BREVO_SANDBOX=false`, worker ativo).
- [x] Teste ponta a ponta com 2 participantes: `SENT`, `attempts=1`, painel **Enviado**, zero erros; evento de teste apagado.
- [ ] Confirmar se a chave usada é separada da de dev (recomendado).
- [x] SPF de `mail.barbosalabs.dev` publicado em 2026-11-16 (`v=spf1 include:spf.sendinblue.com -all`) e verificado no DNS público.
- [x] Rate limit em `POST /admin/{token}/links/email` (10/h) e `.../participants/{id}/email/resend` (20/h) implementado no código em 2026-11-16; entra em produção com P15.
- [ ] Opcional: webhooks de delivered/bounce e double opt-in.

## 10. Decisões que ainda podem ser tomadas

1. **E-mail único por evento?** Melhor esforço, sem bloquear sem verificação. Se quiser garantia, entra double opt-in.
2. **Double opt-in?** Aumenta confiança e reduz typo, mas adiciona um e-mail e um clique; fica para 2.1.
3. **Corrigir e-mail depois?** Hoje não há rota de editar participante; o organizador pode remover/readicionar enquanto `OPEN`.
4. **Webhooks agora?** Começar com 201 + painel; adicionar delivered/bounce quando houver volume.
5. **Template no Brevo ou no app?** Recomendo no app (Thymeleaf), versionado no Git; Brevo só como relay.
6. **Quem é o `Reply-To`?** Um endereço real do organizador/projeto; não use `no-reply` como resposta.

## 11. Referências

- Envio transacional: <https://developers.brevo.com/reference/send-transac-email>
- Sandbox: <https://developers.brevo.com/docs/using-sandbox-mode>
- Idempotência: <https://developers.brevo.com/docs/heterogenous-versions-batch-emails>
- Limites: <https://developers.brevo.com/docs/api-limits> e <https://developers.brevo.com/docs/limit-headers>
- Domínio/DKIM/DMARC: <https://help.brevo.com/hc/en-us/articles/12163873383186-Authenticate-your-domain-with-Brevo-Brevo-code-DKIM-DMARC>
- SMTP (alternativa): <https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP>
- Webhooks: <https://developers.brevo.com/docs/transactional-webhooks> e <https://developers.brevo.com/docs/secured-webhooks>
- Plano grátis: <https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan>
- SDK Java (alternativa): <https://github.com/getbrevo/brevo-java>
