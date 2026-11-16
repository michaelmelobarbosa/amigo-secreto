# Ideias e pendências

Coisas conscientemente deixadas para depois do MVP. Nada aqui é compromisso de entrega; é registro para não perder o contexto.

## Segurança e operação

- **Rate limiting (implementado e validado em produção em 2026-11-15; e-mail em 2026-11-16):** por IP
  em `/events`, `/join/{inviteCode}`, sorteio, wishlist e nos endpoints de e-mail (`links/email` 10/h,
  `email/resend` 20/h), em memória, com `429` + `Retry-After`. A validação real confirmou o IP do
  cliente atrás do Caddy e que `X-Forwarded-For` forjado não cria bucket novo. O rate limit de e-mail
  está no código e entra em produção com P15. Limite diário, Redis e WAF continuam fora:
  [`RATE-LIMITING.md`](RATE-LIMITING.md).
- **Hash dos tokens** no banco e links de uso único/expiração. O MVP guarda os tokens em claro porque o painel precisa copiá-los; mitigar exige mudar a UX de compartilhamento.
- **Regenerar o token individual** de um participante (revogar um link que vazou). Hoje só o `inviteCode` é regenerável.
- **Log de auditoria** para apagar evento, refazer/reabrir sorteio e regenerar convite, sem registrar tokens.
- **CSP com reporting** (`report-uri`/`report-to`) e revisão periódica dos headers.
- **Sessão:** rotação de ID e expiração explícita; hoje o cookie é `HttpOnly`, `SameSite=Lax` e `Secure` por padrão, e a sessão é só por cookie (sem `;jsessionid` na URL).
- **Proxy confiável:** o app não é exposto direto e só entra na rede `edge`, mas qualquer container
  dessa rede ainda alcança `amigo-app:8080` e pode forjar `X-Forwarded-*`. Isolamento mais forte
  exigiria firewall/rede fora do Compose.
- **HTTPS/domínio próprio:** **em produção desde 2026-11-15** (`amigo-secreto.barbosalabs.dev`),
  com certificado Let's Encrypt renovado pelo Caddy de borda.

## Consistência e arquitetura

- **Dashboard em uma transação só:** hoje evento, participantes e grupos são lidos em transações separadas; numa corrida exata a tela pode mostrar estado misturado por um instante. Um `DashboardQuery` com `@Transactional(readOnly = true)` resolve.
- **Postgres nos testes:** a suíte roda em H2 `MODE=PostgreSQL`. Falta um job de CI com Postgres/Testcontainers para validar locks, tipos e `ddl-auto=validate` no banco real.
- **Testes de concorrência:** dois `draw` simultâneos, duas inscrições na última vaga, rollback do redraw/reopen, `firstViewedAt` concorrente.
- **P9/P10/P11 aplicadas em 2026-11-15:** `db` com `mem_limit: 512m`/`cpus: "0.5"`, exemplos de
  `.env` com `BREVO_FROM_NAME="Amigo Secreto"` e sessão só por cookie (sem `;jsessionid`).
- **P12 — Caddy de borda:** o template tem `mem_limit: 256m`; falta aplicar no
  `/opt/edge/compose.yml` da VPS.
- **Paginação/histórico:** o painel carrega todos os participantes (máximo 100), o que é suficiente no MVP.

## Produto

- **Fase 2 — e-mail (implementada e ativa):** link individual por e-mail, opcional, sem virar login.
  Brevo no plano grátis, subdomínio `mail.barbosalabs.dev` com SPF/DKIM/DMARC, outbox assíncrono,
  retentativas e status/reenvio no painel. **Ligado e validado ponta a ponta em 2026-11-16** (2
  `SENT`, `attempts=1`, painel **Enviado**, evento de teste apagado). SPF aplicado e rate limit dos
  endpoints de e-mail no código; entra em produção com P15. Guia completo em [`docs/EMAIL-BREVO.md`](EMAIL-BREVO.md).
- **Telefone/WhatsApp:** WhatsApp Cloud API é paga para mensagens utility iniciadas pelo negócio; SMS é pago; Telegram é grátis mas exige que cada pessoa inicie o bot. O MVP mantém o `wa.me` manual.
- **Recuperação automática de link** (conta/login do organizador) e double opt-in, se o e-mail virar canal principal. Hoje existe apenas o envio avulso dos links para um endereço digitado no painel.
- **Organizador pode optar por não participar do sorteio.** Hoje ele sempre participa.
- **Expiração automática de eventos antigos** e limpeza de dados pessoais.
- **Permutação com reciprocidade permitida** (múltiplos ciclos) como opção do evento, além do ciclo único.
- **Internacionalização** (mensagens, datas e moeda).
- **Pico.css/HTMX:** o MVP usa CSS próprio; Pico.css e HTMX continuam opcionais.

## Riscos aceitos no MVP

- Token do admin/convite/participante em texto claro no banco: quem tem acesso ao banco pode ver tudo.
- Token na URL: fica no histórico do navegador; `no-store`/`no-referrer` reduzem o vazamento, mas não apagam o histórico.
- `Assignment` em claro: quem tem acesso direto ao banco pode ver quem tirou quem.
- Sem login: o link é a credencial; quem tem o link vê o resultado.
- Rate limiting em memória é uma barreira contra abuso casual, não contra ataque distribuído; o teto de 100 e o lock continuam sendo a defesa de integridade.
- `ubuntu` no grupo `docker` é equivalente a root na VPS (acesso ao socket). Aceito para operação single-tenant.
- HSTS com `includeSubDomains`: subdomínios de `amigo-secreto.barbosalabs.dev` ficam presos em HTTPS no navegador do usuário.
- Caddy de borda é ponto único de falha para todos os sites; `restart: unless-stopped` mitiga, não elimina.
- Backup só na VPS: cópia off-site ainda não existe; a perda da VPS leva o banco junto.
- Qualquer container na rede `edge` alcança `amigo-app:8080` e pode forjar `X-Forwarded-*`; o banco está na rede privada.
- `db` limitado a `mem_limit: 512m`: um pico do Postgres não compete com a app e com o Hermes.
- Cota do Brevo no plano grátis é 300/dia; um sorteio de 100 + dois refazeres estoura. Os endpoints de e-mail têm rate limit no código (10/h e 20/h), mas a cota diária ainda não tem teto separado.
- Testes em H2: Flyway e `ddl-auto=validate` foram provados no Postgres real no deploy; o comportamento de locks sob concorrência ainda só tem cobertura H2.
- CSS próprio em vez de Pico.css: o layout é funcional, mas o guia visual não é o Pico.
