# Rate limiting — implementação e operação

> **Status:** **implementado em 2026-11-15**. O filtro, a configuração, a resposta 429 e os testes
> estão no código. Falta apenas validar no deploy real que o Caddy entrega o IP do cliente e que
> `X-Forwarded-For` forjado não engana o filtro; só depois disso divulgue o link publicamente.

## 1. Por que existe

O app não tem login: qualquer pessoa que abra `/` ganha uma sessão com CSRF e pode chamar
`POST /events`. CSRF protege contra requisição forjada por outro site, mas **não impede um bot de
criar eventos em série**. Os riscos concretos, em ordem:

1. **Poluição e disco:** eventos vazios acumulam no Postgres e em todo backup.
2. **Convite público:** quem tem `/join/{inviteCode}` pode preencher as 100 vagas; o rate limit
   atrasa, mas não substitui remover participante/regenerar convite.
3. **Cota e reputação do Brevo:** com e-mail ligado, criar evento + cadastrar endereços de terceiros
   + sortear gasta a cota diária e pode queimar o domínio de envio.
4. **Custo operacional:** logs, conexões e backup crescendo sem uso real.

O objetivo não é impedir ataque distribuído (impossível sem WAF/CDN). É **elevar o custo do abuso
casual** sem atrapalhar família, escritório ou NAT compartilhado.

## 2. Escopo

| Rota | Categoria | Limite inicial |
| --- | --- | --- |
| `POST /events` | `event-create` | 5 por 10 minutos |
| `POST /join/{inviteCode}` | `join` | 30 por 10 minutos |
| `POST /admin/{adminToken}/draw` e `/redraw` | `draw` | 10 por hora |
| `POST /p/{accessToken}/wishlist` | `wishlist` | 30 por 10 minutos |

- Só **POST**; GET, assets, `/robots.txt`, `/actuator/**` e `/error` ficam fora.
- Categorias têm contadores separados: criar evento não consome a cota de join.
- Limites generosos de propósito: uma família atrás do mesmo IP precisa criar 1 evento e 10–15
  pessoas entrarem em sequência.
- Sem limite global de eventos: um atacante poderia esgotar a cota e bloquear usuários legítimos.

**Fora do escopo desta primeira versão:** limite diário, Redis/distribuição, captcha, WAF, fingerprint
de bot, limite por conta (não há conta) e bloqueio permanente de IP.

## 3. Decisões

### 3.1 Filtro na aplicação, não no Caddy

O módulo `rate_limit` **não faz parte do build padrão do Caddy**; usá-lo exigiria imagem customizada
e mais uma peça no deploy. Para uma instância única, um `OncePerRequestFilter` resolve, é testável e
não adiciona dependência.

### 3.2 Em memória, sem nova dependência

- Contador de **janela fixa** por IP e categoria. É mais simples que token bucket e suficiente para
  abuso casual; aceita um pico na virada da janela (até 2× o limite), documentado como trade-off.
- Mapa **limitado** (LRU simples, por exemplo 10.000 chaves) para um flood de IPs não virar consumo
  de memória. Entradas expiradas são descartadas na própria passagem.
- Reinício do app zera os contadores: aceitável. Se um dia houver mais de uma instância, trocar por
  Redis ou mover para o proxy.

### 3.3 Chave por IP

- Usar o IP do cliente já extraído pelo `ForwardedHeaderFilter`
  (`server.forward-headers-strategy=framework` já está ligado) ou ler `X-Forwarded-For` **apenas** sob
  a premissa de que o Caddy é o único ingress.
- Normalizar IPv6 para `/64` (primeiros 4 grupos) para reduzir troca de endereço dentro do mesmo
  prefixo. IPv4 usa o endereço completo.
- **Nunca persistir nem logar IP.** Ele existe só em memória, por poucos minutos; isso mantém o
  tratamento compatível com LGPD e com a política de não logar tokens.

### 3.4 Resposta

- `429 Too Many Requests` com `Retry-After` em segundos e uma página HTML mínima, sem token, com
  link para voltar.
- Mensagem em pt-BR: “Muitas tentativas em pouco tempo. Tente novamente em alguns minutos.”
- Não usar `th:utext` nem refletir a URL/IP na resposta.

## 4. Desenho técnico

Arquivos implementados:

- `common/web/RateLimitFilter.java` — `OncePerRequestFilter` com ordem
  `HIGHEST_PRECEDENCE + 10` (depois do `ForwardedHeaderFilter`, que o Spring Boot registra em
  `HIGHEST_PRECEDENCE`), com as categorias e o mapa LRU limitado.
- `common/RateLimitProperties.java` — `@ConfigurationProperties(prefix = "app.rate-limit")`:
  `enabled`, `eventCreatePerWindow`, `joinPerWindow`, `drawPerWindow`, `wishlistPerWindow`,
  `window` (`Duration`), `drawWindow` e `maxKeys`.
- `application.yaml` — defaults e placeholders `RATE_LIMIT_*`; `application-test.yaml` desliga o
  filtro (os testes o montam diretamente com um `Clock` controlável).
- Resposta `429` direta no filtro, com `Retry-After` em segundos e HTML mínimo sem token/IP.
- `src/test/java/.../common/web/RateLimitFilterTest.java`.

Fluxo:

1. Se `enabled=false`, segue direto.
2. Se não for POST ou não casar com as rotas, segue.
3. Resolve IP e categoria.
4. Em uma seção sincronizada curta: pega a janela, zera se expirou, incrementa e decide.
5. Se passou do limite, escreve `429` + `Retry-After` e retorna; senão, `chain.doFilter`.

Configuração sugerida:

```yaml
app:
  rate-limit:
    enabled: ${RATE_LIMIT_ENABLED:true}
    event-create-per-window: ${RATE_LIMIT_EVENT_CREATE:5}
    join-per-window: ${RATE_LIMIT_JOIN:30}
    draw-per-window: ${RATE_LIMIT_DRAW:10}
    wishlist-per-window: ${RATE_LIMIT_WISHLIST:30}
    window: ${RATE_LIMIT_WINDOW:PT10M}
    draw-window: ${RATE_LIMIT_DRAW_WINDOW:PT1H}
    max-keys: ${RATE_LIMIT_MAX_KEYS:10000}
```

> O limite de `draw` é por hora; por isso ele usa `draw-window`, separada da janela curta das demais
> rotas.

## 5. Proxy e IP real — ponto mais delicado

O rate limit só funciona se o IP for confiável.

- A porta do app **não é publicada** no host; só o Caddy alcança `app:8080`. Isso já é a regra do
  deploy e deve continuar.
- O Caddy precisa **sobrescrever** `X-Forwarded-For` com o IP real do cliente, não confiar no header
  que o cliente mandou. Em Caddy moderno, sem `trusted_proxies` ele não confia em headers de
  upstream; ainda assim, validar com um teste.
- Se houver Caddy de borda + Caddy do app, configurar a cadeia de proxies confiáveis e testar o
  resultado. Não aceitar `X-Forwarded-For` arbitrário de cliente.
- O filtro deve rodar **depois** do `ForwardedHeaderFilter`; se rodar antes, vê o IP do proxy e todo
  mundo cai no mesmo balde. Testar esse detalhe explicitamente.

Teste manual no deploy:

```bash
# deve contar no mesmo IP real, não no IP forjado
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  -H 'X-Forwarded-For: 1.2.3.4' https://SEU-DOMINIO/events
```

O corpo/CSRF do POST pode retornar 403; o que importa é confirmar no comportamento (ou em um log de
debug temporário, sem IP) que o balde é o do cliente real.

## 6. Testes

- 5 POSTs de `/events` do mesmo IP passam; o 6º retorna 429 com `Retry-After`.
- IP diferente continua passando.
- GET `/` e assets não consomem cota.
- `join` tem balde separado de `event-create`.
- `draw`/`redraw` usam a janela de 1 hora.
- Configuração `enabled=false` desliga tudo.
- IPv4, IPv6 e IPv6 mapeado (`::ffff:1.2.3.4`) são normalizados corretamente.
- Mapa não cresce sem limite (teste unitário com `maxKeys` pequeno).
- 429 não contém token, IP nem detalhe interno.
- Suíte existente continua verde (159 testes).

## 7. Checklist de execução

- [ ] Confirmar que o deploy real está estável e que o app não publica porta no host.
- [ ] Confirmar que o Caddy entrega o IP real e que `X-Forwarded-For` forjado não engana o filtro.
- [x] Implementar propriedades, filtro, resposta 429 e testes.
- [x] Rodar a suíte completa.
- [ ] Subir com limites padrão e testar de duas redes diferentes.
- [ ] Verificar que criar 1 evento e entrar com 10 pessoas no mesmo Wi-Fi continua funcionando.
- [ ] Só então divulgar o link publicamente.
- [ ] Se aparecer abuso real, considerar limite diário, Redis ou WAF — não antecipar.

## 8. Limitações conhecidas

- Janela fixa permite rajada na virada; token bucket resolveria, mas não vale a complexidade agora.
- Uma instância só; memória zera no restart.
- NAT compartilhado pode fazer várias pessoas dividirem o mesmo balde; por isso os limites são altos.
- IPv6 `/64` é uma aproximação; provedores podem dar prefixos diferentes para o mesmo usuário.
- Rate limit não impede alguém com o convite de preencher vagas lentamente; para isso valem remoção
  de participante e regeneração do convite.
- Não há limite de e-mails por dia nesta versão; se o Brevo virar alvo, limitar `draw` e/ou criar
  cota diária de notificações.
- O disco continua crescendo com eventos legítimos antigos; a limpeza/expiração de eventos é uma
  pendência separada em [`IDEAS.md`](IDEAS.md).
