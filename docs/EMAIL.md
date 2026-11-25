# E-mail transacional

O envio do link individual por e-mail é um canal **opcional** de entrega. Ele nunca vira login, nunca substitui o token e nunca é pré-requisito para participar.

**Status:** ativo em produção desde 2026-11-16, validado ponta a ponta (2 notificações `SENT`, `attempts=1`, painel **Enviado**, evento de teste apagado). A chave de produção é restrita a envio (`GET /v3/account` retorna 403, o que é esperado).

## Arquitetura

- Provedor: **Brevo**, plano grátis (300 e-mails/dia), via API REST `POST /v3/smtp/email`.
- O sorteio publica `DrawCompletedEvent` **depois do commit**.
- Um listener cria uma linha em `notification` por participante com e-mail e `draw_count`.
- Um worker agendado envia com idempotência e retry.
- O painel mostra status e permite reenviar; falha de e-mail não desfaz o sorteio.
- O organizador também pode enviar os três links do painel sob demanda; esse envio é direto, não passa pelo outbox e não persiste o endereço digitado.

## Fluxo

1. Participante informa e-mail opcional no join, ou o organizador adiciona manualmente.
2. `DrawService` publica `DrawCompletedEvent` após o commit.
3. O listener grava `notification(PENDING)` para `(participant, drawCount, EMAIL)`.
4. O worker envia pela API do Brevo e atualiza `SENT`/`FAILED`.
5. A página do participante continua funcionando pelo link, com ou sem e-mail.

## Conta e domínio

1. Crie a conta no Brevo e ative 2FA.
2. Em **Settings → Senders, Domains & Dedicated IPs → Domains**, adicione o domínio de envio (por exemplo, `mail.<seu-dominio>`).
3. Publique os registros que o Brevo mostrar:
   - **Brevo code** (TXT de posse);
   - **DKIM** (preferencialmente dois CNAMEs `brevo1._domainkey` e `brevo2._domainkey`);
   - **DMARC** (um único TXT em `_dmarc`);
   - **SPF** no subdomínio dedicado: `v=spf1 include:spf.sendinblue.com -all`.
4. Não crie dois registros DMARC nem dois SPF para o mesmo nome.
5. Crie o sender com o endereço exato do domínio autenticado. O `From` precisa ser do domínio autenticado.
6. Gere uma **API key** em **Settings → SMTP & API → API keys**. Use chaves separadas para dev e produção e nunca versione a chave.

> No projeto, o domínio de envio é `mail.barbosalabs.dev` e o remetente é `no-reply@mail.barbosalabs.dev`. SPF, DKIM e DMARC estão publicados e verificados.

A chave de produção pode ser restrita ao escopo de envio. Nesse caso, `GET /v3/account` retorna **403 `permission_denied`**; valide a chave por um envio em sandbox ou por um e-mail real.

## Limites do plano grátis

- **300 e-mails/dia**, compartilhados entre campanhas e transacionais.
- 100.000 contatos armazenados; 1 usuário.
- Selo “Sent with Brevo” nos envios gratuitos.
- IP compartilhado; entregabilidade depende do domínio autenticado e da reputação.
- Ao estourar a cota, até 1.000 transacionais podem entrar em fila de retentativa; além disso, não são entregues.
- Rate limit da API de envio: 1.000 req/s; `GET /v3/smtp/emails` é 2 req/s e 7.200/hora (não faça polling).

Um sorteio de 100 participantes usa 100 envios; cada refazer usa mais 100. Dois refazeres no mesmo dia estouram a cota.

## API de envio

```bash
POST https://api.brevo.com/v3/smtp/email
api-key: xkeysib-...
Content-Type: application/json
```

Corpo mínimo:

```json
{
  "sender": { "name": "Amigo Secreto", "email": "no-reply@mail.barbosalabs.dev" },
  "to": [{ "email": "maria@example.com", "name": "Maria", "removeTrackingPixel": true }],
  "replyTo": { "email": "contato@example.com", "name": "Organizador" },
  "subject": "Seu resultado do amigo secreto",
  "htmlContent": "<p>Olá, Maria. <a href=\"https://<dominio>/p/p_...\">Veja seu resultado</a>.</p>",
  "textContent": "Olá, Maria. Veja seu resultado: https://<dominio>/p/p_...",
  "tags": ["amigo-secreto", "draw-1"],
  "headers": {
    "idempotencyKey": "UUID-v4-estável-por-notificação",
    "X-Sib-Sandbox": "drop"
  }
}
```

- `sender` e `to` são objetos `{email, name}`, não `"Nome <email>"`.
- `idempotencyKey` e `X-Sib-Sandbox` vão no objeto `headers` do **corpo**, não em headers HTTP reais.
- A chave de idempotência vale por 30 minutos; repetir retorna `400 duplicate_parameter` sem reenviar.
- Sucesso: **201** com `messageId`. `202` é agendamento e não é usado.
- `removeTrackingPixel: true` evita pixel de abertura.
- Em produção, remova `X-Sib-Sandbox`.

### Erros e retentativa

| Status | Código | Ação |
| --- | --- | --- |
| 400 | `duplicate_parameter` | já aceito; tratar como enviado |
| 400 | `missing_parameter`/`invalid_parameter` | bug de payload; não retentar |
| 400 | `not_enough_credits` | cota esgotada; falhar e avisar |
| 401 | `unauthorized` | chave/IP bloqueado; corrigir configuração |
| 402/403 | crédito/permissão | falha de conta; avisar |
| 429 | rate limit | esperar `x-sib-ratelimit-reset` e retentar |
| 5xx/rede | — | retentar com backoff e a mesma `idempotencyKey` |

Não há `Retry-After`; leia `x-sib-ratelimit-limit`, `x-sib-ratelimit-remaining` e `x-sib-ratelimit-reset`.

### Sandbox

`X-Sib-Sandbox: drop` retorna 201 e `messageId` sem entregar e sem registrar log. Serve para validar payload, chave e domínio; **não** testa entrega, DKIM nem renderização.

## Integração no backend

### Migrações

- `V2__add_participant_email.sql`: `participant.email` nullable, normalizado (trim + lowercase).
- `V3__create_notification.sql`: outbox com `participant_id`, `draw_count`, `channel`, `status`, `attempts`, `provider_message_id`, `last_error`, `created_at`, `sent_at`; único por `(participant_id, draw_count, channel)`.

### Configuração

```yaml
app:
  email:
    enabled: ${EMAIL_ENABLED:false}
    daily-limit: ${EMAIL_DAILY_LIMIT:250}
    event-daily-limit: ${EMAIL_EVENT_DAILY_LIMIT:100}
    brevo:
      api-key: ${BREVO_API_KEY:}
      from-email: ${BREVO_FROM_EMAIL:no-reply@mail.barbosalabs.dev}
      from-name: ${BREVO_FROM_NAME:Amigo Secreto}
      reply-to: ${BREVO_REPLY_TO:}
      sandbox: ${BREVO_SANDBOX:true}
```

- `enabled=false` usa um sender de log; nada sai do servidor.
- Com `enabled=true`, chave ou remetente ausente derruba o startup (fail-fast).
- Nunca logue a API key, o e-mail ou o token do link.

### Porta e adaptador

```java
public interface EmailSender {
    EmailSendResult send(EmailMessage message);
}

public record EmailMessage(
    String toEmail, String toName, String subject,
    String html, String text, UUID idempotencyKey, List<String> tags) {}

public record EmailSendResult(
    boolean accepted, String providerMessageId,
    String errorCode, String errorMessage, boolean retryable) {}
```

- `BrevoEmailSender` usa `RestClient`, lê até 16 KB do corpo de resposta e devolve resultado tipado. `LoggingEmailSender` cobre dev/test. Não use SMTP: sem `messageId`, sem idempotência e sem sandbox.

### Listener e worker

- O listener roda após o commit e grava o outbox em transação nova.
- O worker (`@Scheduled(fixedDelay = 30_000)`) busca `PENDING`/`FAILED` com `attempts < 3`, prepara e registra a tentativa em transação curta, chama a API fora de transação (sem prender conexão do banco durante I/O) e grava `SENT` ou `FAILED` em outra transação curta, verificando antes a cota diária (`EmailQuotaGuard`: `250/dia` global e `100/dia` por evento em UTC).
- Se o teto diário interno for atingido, `EmailQuotaGuard` bloqueia o disparo (outbox ou links do organizador) antes de chamar o provedor, sem retentativas em loop.
- Retry: 429 espera o reset; 5xx/rede usa backoff; 400 de payload e 401 não retentam.
- `duplicate_parameter` com idempotência é tratado como enviado.
- Refazer o sorteio cria novo `drawCount` e novas notificações.
- Reabrir marca pendentes antigas como `SKIPPED` se o evento não estiver mais `DRAWN`.

### Conteúdo

`EmailContentBuilder` monta HTML e texto em Java (não há template Thymeleaf de e-mail):

- assunto `Seu resultado do amigo secreto — {evento}` ou “Sorteio refeito”;
- link absoluto via `LinkBuilder`;
- aviso para guardar e não compartilhar o link;
- sem wishlist, sem imagens e sem pixel;
- nomes escapados no HTML.

### Painel

- E-mail opcional no join e na adição manual (`@Email`, máximo 254).
- Status por participante: sem e-mail, pendente, enviado, falhou; botão de reenvio.
- A wishlist continua fora do painel.
- Apagar evento remove `notification` em cascata.

## Operação

### Variáveis de produção

```bash
EMAIL_ENABLED=true
EMAIL_WORKER_ENABLED=true
BREVO_SANDBOX=false
BREVO_API_KEY=xkeysib-...
BREVO_FROM_EMAIL=no-reply@mail.barbosalabs.dev
BREVO_FROM_NAME="Amigo Secreto"
BREVO_REPLY_TO=
```

Depois de alterar, recrie a app:

```bash
docker compose -f docker-compose.prod.yml up -d app
docker compose -f docker-compose.prod.yml logs --tail=100 app \
  | grep -i -E 'email|brevo|notification'
```

Se o Brevo usar allowlist de IP, cadastre o IP de saída dos containers (`curl -s https://ifconfig.me` dentro de um container). Nunca publique esse IP em documentação.

### Testes de fumaça

```bash
# valida a API key e o domínio; não passa pela aplicação
TEST_EMAIL=seu@email.com bash scripts/check-brevo.sh

# valida o caminho real da aplicação e apaga o evento no final
TEST_EMAIL=seu@email.com bash scripts/smoke-email.sh
```

`check-brevo.sh` aceita 201/202, trata 403 de chave restrita, 401 de chave inválida e 429 de cota, e não imprime a API key.

### Rotina

- Monitore **Transactional → Logs** e a cota diária.
- Rotacione a API key periodicamente e revogue a antiga.
- Faça um envio real após qualquer mudança de DNS ou domínio.
- Não habilite access log do proxy para `/p`, `/join` ou `/admin`.

## Desenvolvimento local

```powershell
$env:EMAIL_ENABLED="true"
$env:BREVO_API_KEY="xkeysib-..."
$env:BREVO_FROM_EMAIL="no-reply@mail.barbosalabs.dev"
$env:BREVO_FROM_NAME="Amigo Secreto"
$env:BREVO_SANDBOX="true"
$env:EMAIL_WORKER_ENABLED="true"
$env:SPRING_PROFILES_ACTIVE="dev"
.\mvnw.cmd spring-boot:run
```

Com sandbox ligado, a tela avisa que nada foi entregue; não trate como sucesso de entrega.

## Testes

- **Adaptador:** payload, `api-key`, `idempotencyKey` no corpo, sandbox, `removeTrackingPixel`, parsing de 201/400/429/5xx.
- **Serviço:** draw publica evento; notificações criadas; redraw cria novas por `drawCount`; falha de e-mail não desfaz o sorteio; reabrir marca pendentes como skipped.
- **Integração:** sender fake captura o link; o link enviado é o mesmo `/p/{token}`; sem e-mail não cria notificação.
- **Segurança:** chave e token nunca aparecem em log ou erro.
- **Cota:** o app falha e para em vez de entrar em loop quando o provedor recusa.

## Fora do escopo

- Double opt-in e verificação de endereço.
- Dedupe global entre eventos diferentes (dentro do mesmo evento, e-mail repetido já é rejeitado).
- Webhooks de delivered/bounce.
- Envio de convite por e-mail e e-mail marketing.
- WhatsApp/SMS como canal automatizado.

## Referências

- [Envio transacional](https://developers.brevo.com/reference/send-transac-email)
- [Sandbox](https://developers.brevo.com/docs/using-sandbox-mode)
- [Idempotência](https://developers.brevo.com/docs/heterogenous-versions-batch-emails)
- [Limites da API](https://developers.brevo.com/docs/api-limits)
- [Domínio, DKIM e DMARC](https://help.brevo.com/hc/en-us/articles/12163873383186-Authenticate-your-domain-with-Brevo-Brevo-code-DKIM-DMARC)
- [Webhooks](https://developers.brevo.com/docs/transactional-webhooks)
