# Segurança e privacidade

O Amigo Secreto não tem login: **o link é a credencial**. Este documento descreve o modelo de ameaças, os controles implementados, os limites conhecidos e os riscos aceitos.

## Modelo de ameaças

| Ativo | Risco | Controle |
| --- | --- | --- |
| Token do painel | vazamento dá controle total do evento | 128 bits, `no-store`, `no-referrer`, sem log de URL |
| Token do participante | vazamento revela o resultado | token individual, 404 para inválido, sem log |
| Convite público | alguém preenche as 100 vagas | rate limit, teto, fechar inscrições, regenerar código |
| Resultado do sorteio | organizador ou terceiro vê pares indevidos | painel nunca renderiza `Assignment`; página só mostra o próprio par |
| E-mail | abuso de cota e reputação do domínio | e-mail opcional, rate limit, chave restrita a envio |
| Infraestrutura | container exposto ou banco acessível | só o Caddy publica portas; banco em rede privada |

A proteção é para uso real em família/trabalho e abuso casual. Não há defesa contra atacante distribuído, WAF ou comprometimento do host.

## Tokens

- Gerados com `SecureRandom`, 128 bits, Base64 URL-safe e prefixos distintos: `adm_`, `inv_`, `p_`.
- Colunas únicas e indexadas; um token de participante nunca abre o painel.
- Token inexistente responde **404**, nunca 403, para não confirmar existência.
- Tokens nunca aparecem em log, `toString`, mensagem de erro ou query string além do próprio link.
- O `inviteCode` é regenerável; os links individuais não mudam.
- Os tokens ficam em texto claro no banco porque o painel precisa copiá-los. É um risco aceito.

## Autorização e IDOR

Toda busca por ID é escopada pelo evento (`id + eventId`). Nenhum endpoint aceita ID solto. As rotas são resolvidas por token:

- `/admin/{adminToken}` → evento do organizador;
- `/join/{inviteCode}` → evento com inscrições abertas;
- `/p/{accessToken}` → participante e evento correspondentes.

O painel nunca recebe `Assignment`. A página do participante recebe apenas o próprio par e a wishlist de quem tirou.

## CSRF e sessão

- Não há Spring Security. O CSRF é próprio, com token de sessão e comparação em tempo constante.
- Todo POST valida o token, inclusive remoções e ações “inofensivas”, e rejeita `_csrf` enviado na query string.
- A sessão só é criada em respostas `GET` de páginas com formulário (`404`, `HEAD`, downloads e estáticos não alocam sessão).
- O gerenciador de sessão do Tomcat limita o total em memória (`maxActiveSessions = 5000`) com timeout de `30m`.
- Cookie `HttpOnly`, `SameSite=Lax` e `Secure` fora do perfil `dev`.
- `server.servlet.session.tracking-modes: cookie` remove o `;jsessionid` da URL.
- O perfil `dev` desativa `Secure` apenas para HTTP local.

## Headers, cache e indexação

Todas as respostas (incluindo arquivos estáticos) recebem `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` e `Permissions-Policy: camera=(), microphone=(), geolocation=()`. Rotas dinâmicas recebem adicionalmente:

- `Cache-Control: no-store` e `Pragma: no-cache`;
- `Content-Security-Policy` restritiva (`script-src 'self'`, `frame-ancestors 'none'`);
- `X-Robots-Tag: noindex, nofollow` em `/admin/`, `/p/` e `/join/`;
- `robots.txt` com `Disallow: /`.

`MatrixParameterRejectionFilter` rejeita com `404` qualquer caminho que contenha `;` (parâmetros de matriz), impedindo discrepâncias entre o proxy de borda e o roteamento do Tomcat (como `/actuator;x`). Em seguida, `SecurityHeadersFilter` e `RateLimitFilter` normalizam o caminho em `RequestPaths` (removendo barras repetidas, segmentos `.`/`..` e percent-encoding redundante) antes de classificar a rota.

Se for necessário telemetria no proxy, redija a URI inteira: o token está no **path**, não na query string. Um access log comum gravaria credenciais em disco.

## Rate limiting

Implementado em memória por IP e categoria, em `RateLimitFilter`, com janela fixa, mapa LRU limitado e resposta `429` com `Retry-After`.

| Rota | Categoria | Limite |
| --- | --- | --- |
| `POST /events` | `event-create` | 5 / 10 min |
| `POST /join/{inviteCode}` | `join` | 30 / 10 min |
| `POST /admin/{adminToken}/draw` e `/redraw` | `draw` | 10 / 1 h |
| `POST /admin/{adminToken}/links/email` | `email-links` | 10 / 1 h |
| `POST /admin/{adminToken}/participants/{id}/email/resend` | `email-resend` | 20 / 1 h |
| `POST /admin/{adminToken}/participants` e `/exclusions` | `admin-write` | 40 / 10 min |
| `POST /p/{accessToken}/wishlist` | `wishlist` | 30 / 10 min |

Configuração:

```yaml
app:
  rate-limit:
    enabled: ${RATE_LIMIT_ENABLED:true}
    event-create-per-window: ${RATE_LIMIT_EVENT_CREATE:5}
    join-per-window: ${RATE_LIMIT_JOIN:30}
    draw-per-window: ${RATE_LIMIT_DRAW:10}
    wishlist-per-window: ${RATE_LIMIT_WISHLIST:30}
    email-links-per-window: ${RATE_LIMIT_EMAIL_LINKS:10}
    email-resend-per-window: ${RATE_LIMIT_EMAIL_RESEND:20}
    admin-write-per-window: ${RATE_LIMIT_ADMIN_WRITE:40}
    window: ${RATE_LIMIT_WINDOW:PT10M}
    draw-window: ${RATE_LIMIT_DRAW_WINDOW:PT1H}
    email-window: ${RATE_LIMIT_EMAIL_WINDOW:PT1H}
    max-keys: ${RATE_LIMIT_MAX_KEYS:10000}
```

Decisões:

- **Filtro na aplicação**, não no Caddy: o módulo de rate limit não faz parte do build padrão do proxy.
- **Chave por IP**, com IPv4 normalizado e IPv6 reduzido a `/64`.
- **IP nunca persistido nem logado**; existe apenas em memória.
- **Limites generosos** para não quebrar família/escritório atrás do mesmo NAT.

### IP real atrás do proxy

A aplicação usa `server.forward-headers-strategy: native` com o `RemoteIpValve` do Tomcat lendo apenas `X-Forwarded-For` e `X-Forwarded-Proto` vindos de proxies internos confiáveis (ignorando `Forwarded`, `X-Forwarded-Host` e `X-Forwarded-Prefix`), enquanto o Caddy sobrescreve `X-Forwarded-For`/`X-Forwarded-Proto` e remove `Forwarded` e `X-Forwarded-Prefix` do cliente. Se o app enxergar o IP do proxy, todos caem no mesmo balde; se confiar em header arbitrário, o limite é contornável.

O app não publica porta: só o Caddy da rede `edge` o alcança. Teste com `X-Forwarded-For`, `Forwarded`, `X-Forwarded-Host` e `X-Forwarded-Prefix` forjados e confirme que o balde e o `Location` dos redirects não mudam.

### Limitações

- Janela fixa permite pico na virada (até 2× o limite).
- Uma instância só; reiniciar o container zera contadores.
- NAT compartilhado divide o balde; por isso os limites são altos.
- Não impede alguém com o convite de preencher vagas lentamente; para isso valem remover participante e regenerar o convite.
- O teto diário interno de e-mails (`EMAIL_DAILY_LIMIT=250` global e `EMAIL_EVENT_DAILY_LIMIT=100` por evento em UTC) protege a cota gratuita do Brevo (300/dia), mas o contador de envios avulsos do painel fica em memória.
- Cada evento tem teto de `50` grupos de exclusão e o algoritmo de sorteio possui orçamento de `1.000.000` passos e deadline de `2s` para evitar contenção prolongada de CPU/lock.
- Redis, WAF, captcha e limite por conta ficam fora desta versão.

## E-mail e privacidade

- E-mail é opcional e não é credencial.
- Endereços são normalizados, únicos por evento, não aparecem em página pública e são apagados em cascata com o evento/participante.
- A API key nunca é logada, ecoada em erro ou versionada.
- A chave de produção é restrita a envio.
- Não há pixel de rastreamento; `removeTrackingPixel` é enviado ao provedor.
- Webhooks e double opt-in ainda não existem.

Detalhes operacionais em [EMAIL.md](EMAIL.md).

## Dados e LGPD

- Coletar apenas o necessário: nome, e-mail opcional, wishlist e resultado.
- O resultado é dado sensível no contexto da brincadeira: só o participante e quem tem o link veem.
- Apagar o evento remove participantes, grupos, assignments e notificações em cascata.
- Tokens e `Assignment` ficam em claro no banco. Quem tem acesso direto ao banco vê tudo.
- O IP do cliente não é persistido; existe só em memória no rate limit.
- Backups contêm os mesmos dados e precisam da mesma proteção; mantenha cópia off-site criptografada.

## Segurança da infraestrutura

- Nenhum container do projeto publica portas; só o Caddy de borda publica 80/443.
- O Actuator (`/actuator/health`) roda em porta de management separada e vinculada apenas a `127.0.0.1:8081` (não responde na porta `8080` alcançada pelo Caddy).
- O banco fica em rede privada; o app participa da rede privada e da `edge`.
- Firewall de nuvem libera apenas 80, 443 e a porta de administração restrita.
- Docker ignora o `INPUT` do host para portas publicadas; a disciplina é não publicar.
- HSTS com `includeSubDomains` está ativo para o host da aplicação.
- O Caddy é ponto único de falha para todos os sites da borda.
- Usuário no grupo `docker` equivale a root; aceito em operação single-tenant.
- Segredos apenas no `.env` do servidor, com `600`, e fora do Git.
- Backups no mesmo disco não protegem contra perda da VPS.

## Riscos aceitos

- Token em texto claro no banco e na URL (histórico do navegador).
- `Assignment` em claro no banco.
- Sem login e sem recuperação automática de link.
- Sem hash/expiração de token e sem revogação do token individual.
- Sem log de auditoria para ações do organizador.
- Rate limit em memória, sem distribuição.
- Sessão sem rotação explícita de ID.
- Sem testes de concorrência no Postgres real (a suíte roda em H2).
- Contador diário de envios avulsos de links (fora do outbox) fica em memória e reinicia com o container.

## Checklist de verificação

```bash
# token inválido
curl -s -o /dev/null -w '%{http_code}\n' "https://<dominio>/admin/adm_naoexiste"   # 404

# headers
curl -sSI "https://<dominio>/p/p_naoexiste" | grep -Ei 'cache-control|referrer|nosniff|x-frame|content-security|x-robots'

# actuator e parâmetros de matriz bloqueados (app + borda)
for p in /actuator /actuator/health '/actuator;x' '/actuator;x/health' '/admin;x/adm_inexistente'; do
  printf '%-28s -> ' "$p"
  curl -sS --path-as-is -o /dev/null -w '%{http_code}\n' "https://<dominio>$p"    # 404
done

# rate limit (CSRF pode responder 403 antes do limite)
for i in $(seq 1 6); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST "https://<dominio>/events"
done
```

Testes automatizados relevantes:

- `SecurityHeadersFilter`/integração: headers e 404;
- `CsrfTokenManager`/integração: POST sem token;
- `RateLimitFilterTest`: janela, categoria, IP, IPv6, LRU e 429;
- testes de web: painel sem `Assignment`, participante só com o próprio par, XSS escapado.

## Código relacionado

- `common/TokenGenerator`, `common/LinkBuilder`
- `common/web/CsrfTokenManager`, `common/web/CsrfTokenInterceptor`
- `common/web/MatrixParameterRejectionFilter`, `common/web/SecurityHeadersFilter`, `common/web/RequestPaths`
- `common/web/RateLimitFilter`, `common/RateLimitProperties`
- `common/web/GlobalExceptionHandler`
- `notification/`, `notification/EmailQuotaGuard` e `email/` para entrega, cota diária e outbox
