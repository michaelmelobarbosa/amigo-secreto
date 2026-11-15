# Ideias e pendências

Coisas conscientemente deixadas para depois do MVP. Nada aqui é compromisso de entrega; é registro para não perder o contexto.

## Segurança e operação

- **Rate limiting (implementado em 2026-11-15):** por IP em `/events`, `/join/{inviteCode}`, sorteio e wishlist, em memória, com `429` + `Retry-After`. Falta validar o IP real atrás do Caddy no deploy. Limite diário, Redis e WAF continuam fora: [`RATE-LIMITING.md`](RATE-LIMITING.md).
- **Hash dos tokens** no banco e links de uso único/expiração. O MVP guarda os tokens em claro porque o painel precisa copiá-los; mitigar exige mudar a UX de compartilhamento.
- **Regenerar o token individual** de um participante (revogar um link que vazou). Hoje só o `inviteCode` é regenerável.
- **Log de auditoria** para apagar evento, refazer/reabrir sorteio e regenerar convite, sem registrar tokens.
- **CSP com reporting** (`report-uri`/`report-to`) e revisão periódica dos headers.
- **Sessão:** rotação de ID e expiração explícita; hoje o cookie é `HttpOnly`, `SameSite=Lax` e `Secure` por padrão.
- **Proxy confiável:** garantir que o app só aceite `X-Forwarded-*` do proxy reverso (Caddy/Nginx), não exposto direto.
- **HTTPS/domínio próprio:** obrigatório para uso real; ver ponto em aberto na spec.

## Consistência e arquitetura

- **Dashboard em uma transação só:** hoje evento, participantes e grupos são lidos em transações separadas; numa corrida exata a tela pode mostrar estado misturado por um instante. Um `DashboardQuery` com `@Transactional(readOnly = true)` resolve.
- **Postgres nos testes:** a suíte roda em H2 `MODE=PostgreSQL`. Falta um job de CI com Postgres/Testcontainers para validar locks, tipos e `ddl-auto=validate` no banco real.
- **Testes de concorrência:** dois `draw` simultâneos, duas inscrições na última vaga, rollback do redraw/reopen, `firstViewedAt` concorrente.
- **Outbox de notificações:** quando o e-mail entrar, usar a tabela `notification` com idempotência por `drawCount`, em vez de enviar dentro da transação.
- **Paginação/histórico:** o painel carrega todos os participantes (máximo 100), o que é suficiente no MVP.

## Produto

- **Fase 2 — e-mail (implementada):** link individual por e-mail, opcional, sem virar login. Brevo no plano grátis, subdomínio `mail.barbosalabs.dev` com DKIM/DMARC, outbox assíncrono, retentativas e status/reenvio no painel. Pendente apenas habilitar no `.env` da VPS. Guia completo em [`docs/EMAIL-BREVO.md`](EMAIL-BREVO.md).
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
- Testes em H2: o comportamento de locks/tipos no Postgres só será provado no deploy/CI.
- CSS próprio em vez de Pico.css: o layout é funcional, mas o guia visual não é o Pico.
