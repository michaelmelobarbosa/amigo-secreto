# Interface — pesquisa e opções de evolução visual

Status: **proposta de pesquisa**, nada implementado. Serve para escolher o caminho antes de mexer em
template/CSS. O código atual está em `src/main/resources/static/css/app.css` (≈8 KB) e nos templates
Thymeleaf de `src/main/resources/templates/`.

## 1. O que já existe e o que incomoda

O CSS atual é competente para o MVP: tokens de cor na mão, container de 56 rem, cards, foco visível,
alerts, tabela, badges e formulário legível. O problema não é falta de CSS — é falta de **direção de
arte**:

- paleta azul/cinza de painel administrativo, sem relação com a festa;
- tipografia 100% `system-ui`, sem hierarquia visual;
- uma coluna só, muito espaço vazio, formulário "cru" (campos empilhados, `input type=date` nativo);
- nenhuma textura, ilustração, ícone ou movimento;
- dashboard e página do participante usam a mesma linguagem de "formulário de cadastro";
- sem favicon, sem `theme-color`, sem cartão de compartilhamento (OG) — o link do WhatsApp aparece
  sem preview;
- sem tema escuro;
- regras duplicadas no CSS (`.alert-error`, `.subsection`, `.preview` aparecem duas vezes).

## 2. Restrições que mandam no projeto

| Restrição | Consequência |
| --- | --- |
| CSP `default-src 'self'; style-src 'self'; font-src 'self'; script-src 'self'` | Nada de Google Fonts, Tailwind Play CDN, `<style>` inline ou CDN de JS. Todo asset precisa ser servido por `/css`, `/js`, `/images`, `/fonts` ou `/webjars`. |
| `img-src 'self' data:` | SVG/PNG locais e `data:` são ok; imagem remota não. |
| Sem SPA, sem Spring Security, stack enxuta | Solução deve ser progressiva e não pode exigir Node no runtime. Build opcional é aceitável se justificado. |
| `font-src 'self'` | Fonte variável self-hosted é permitida, mas `/fonts/` **não** está em `isStaticAsset()` no `SecurityHeadersFilter`; hoje cairia em `Cache-Control: no-store`. Precisa adicionar `/fonts/` à lista (e opcionalmente cache longo). |
| Portfólio | O "como foi feito" conta. CSS próprio e zero JS é uma narrativa boa; Tailwind também é skill de mercado. |

## 3. Onde o ganho visual realmente está

Ordenado por impacto percebido, não por esforço:

1. **Tipografia.** Uma fonte variável self-hosted muda mais a percepção que qualquer framework. Sugestão
   de par: `Fraunces` (display, eixos `SOFT`/`WONK`, clima de festa/editorial) + `Instrument Sans` ou
   `Inter` (UI). Alternativa: `Bricolage Grotesque` + `Inter`. Todas OFL, dá para baixar woff2 do
   Fontsource/Google Fonts e colocar em `static/fonts/`. Subset latino, `font-display: swap`, `preload`
   do woff2 do heading.
2. **Paleta com identidade.** Sair do azul corporativo. Três direções possíveis:
   - **Papel e vinho:** fundo creme `oklch(98% 0.012 85)`, tinta ameixa, primária vinho
     `oklch(48% 0.16 15)`, dourado de apoio. Elegante, funciona no Natal sem ser clichê.
   - **Noite de festa:** índigo profundo, coral e dourado. Hero escuro, cards claros. Mais dramático.
   - **Presente:** verde-sálvia, terracota e creme. Calmo, bom para o ano todo.
   Em qualquer uma: tokens semânticos (`--bg`, `--surface`, `--text`, `--muted`, `--brand`, `--accent`,
   `--border`, `--danger`), `color-mix()`/`oklch()` para derivar tons e `light-dark()` para tema escuro.
3. **Layout com hierarquia.** Na home: hero curto (título + subtítulo + ilustração) e o formulário como
   "cartão de entrada", não como a página inteira. Em telas ≥ 900 px, formulário em duas colunas
   (nome/data, valor/organizador) e um painel lateral "Como funciona" em 3 passos. No dashboard: cabeçalho
   fixo com nome do evento + status e um **stepper** (Criar → Convidar → Sortear → Resultado).
4. **Forma e textura.** Fundo com gradiente radial suave ou grid de pontos, cards com sombra em camadas,
   raio consistente, borda de 1 px com `color-mix`, detalhe de fita/laço em SVG. Ícones inline (Lucide,
   ISC) via fragmento Thymeleaf — sem icon font.
5. **Movimento discreto.** `@view-transition { navigation: auto; }` dá transição entre páginas inteiras
   com 2 linhas de CSS (Chrome/Edge 126+, Safari 18.2+; Firefox degrada para navegação instantânea).
   `@starting-style` para o card entrar; `:has()` para estado de erro do campo; hover com
   `translateY(-2px)`. Tudo com `prefers-reduced-motion`.
6. **Momento de festa.** Na página do participante, revelar o resultado com flip/scale e um confete
   **só CSS** (12–20 peças com `nth-child` e custom properties). Sem canvas, sem lib. É o efeito com
   maior "uau" por linha de código.
7. **Detalhes que parecem acabamento.** Favicon SVG, `theme-color`, imagem OG 1200×630 (preview no
   WhatsApp), páginas 403/404/500 com a mesma linguagem, estados vazios, tabela virando cards no mobile
   (via `data-label` + `::before`), botão de copiar com feedback visual.

## 4. Trilhas possíveis

### Trilha A — CSS próprio, sem lib (recomendada)

Continuar com `app.css`, mas reescrevendo com `@layer` (reset → base → componentes → utilidades),
tokens e tipografia. Sem dependência nova, sem build, CSP intacto.

- **Prós:** controle total, CSS final pequeno (15–25 KB), narrativa de portfólio ("server-rendered,
  zero framework"), zero risco de conflito com o CSS atual, deploy inalterado.
- **Contras:** mais trabalho manual; cada componente novo é seu; sem tema pronto.
- **Esforço:** 2–3 dias para o pacote completo; 4–6 h para o primeiro salto visível (tokens + fonte +
  paleta + hero + formulário).

### Trilha B — framework CSS classless/leve self-hosted

- **Pico.css:** era a opção "oficial" do projeto, mas **v2.1.1 (mar/2025) é a última release e o
  repositório foi arquivado em 2026-10-04**. O site segue no ar e a licença é MIT, então funciona, mas
  não recebe correção. O fork compatível **Blades CSS** se apresenta como sucessor; maturidade ainda
  precisa ser verificada.
- **Simple.css / MVP.css / Water.css:** 5–9 KB, classless, ótimos para conteúdo, fracos para dashboard
  (tabela, badge, grid de ações).
- **Bulma 1.0:** class-based, sem JS, tema claro/escuro, componentes prontos. Mais completo, porém
  pesado e exigiria renomear classes nos templates.
- **Prós:** resultado rápido, bom padrão de formulário e acessibilidade.
- **Contras:** Pico congelado; qualquer framework vai brigar com os 470 linhas de CSS atual; para
  dashboard provavelmente ainda falta CSS próprio.
- **Esforço:** 4–8 h, com retrabalho de classes.

### Trilha C — Tailwind CSS 4 + daisyUI 5, compilado no Maven

O `maven-tailwind-plugin` baixa o binário standalone do Tailwind e compila no ciclo do Maven, **sem
Node/npm**. O CSS gerado vai para `static/css/` e continua CSP-safe. daisyUI 5 traz 35 temas (incluindo
`valentine` e `christmas`), tokens e componentes sem JS.

- **Prós:** teto de design mais alto, iteração rápida, tema pronto, skill relevante de portfólio,
  output pequeno (só classes usadas).
- **Contras:** plugin de terceiro no build; reescrever todos os templates com classes utilitárias;
  muda o Docker build; foge do "stack enxuto" e da estética "CSS próprio" registrada na spec.
- **Esforço:** 1 dia de setup + 1–2 dias de migração de templates.

### Trilha D — interatividade (só se o visual pedir)

- **htmx 4.0** (ou 2.0.x estável, ~16 KB gz): `hx-boost` para navegação sem reload, validação inline,
  atualização parcial do painel, tudo com Thymeleaf e degradação sem JS. CSP-safe se self-hosted e sem
  `hx-on`/expressões `js:`.
- **Alpine.js (~15 KB):** estado local (menus, toggles). O `app.js` atual já cobre copiar link e
  confirmação; provavelmente não vale.
- **Web Awesome** (sucessor do Shoelace, core MIT, self-host): dialog, toast, tooltip acessíveis. Custo
  alto (web components + CSS interno) e precisa testar contra `style-src 'self'`; provavelmente
  overkill para 5 telas.
- **Motion/anime.js:** desnecessário; View Transitions + CSS cobrem o caso.

## 5. Comparativo

| Critério | A. CSS próprio | B. Classless | C. Tailwind + daisyUI | D. htmx/Web Awesome |
| --- | --- | --- | --- | --- |
| Dependência nova | nenhuma | 1 arquivo CSS | plugin de build | 1 JS |
| Build | igual | igual | muda | igual |
| Controle visual | total | médio | alto | n/a |
| Esforço inicial | médio | baixo | alto | baixo |
| Risco de conflito | baixo | alto | alto (rewrite) | médio |
| CSP | ok | ok | ok | testar |
| Portfólio | "zero framework" | "semântico" | "Tailwind" | "hipermídia" |

## 6. Recomendação

**Trilha A primeiro, em três entregas.** Ela ataca exatamente o que está feio na screenshot (tipografia,
cor, hierarquia, textura) sem adicionar dependência nem tocar em Java. Se depois de pronta a sensação for
"ainda falta sistema de componentes", aí sim avalie C; se faltar fluidez, D.

### Entrega 1 — fundação (4–6 h)

- `docs/UI-VISUAL.md` aprovado e spec/AGENTS atualizados (Pico não é mais "opcional recomendado").
- Tokens em `:root` + `@layer` + reset enxuto; remover duplicatas.
- Fonte variável self-hosted (1 display + 1 texto), `@font-face`, `preload`; adicionar `/fonts/` em
  `isStaticAsset()`.
- Paleta escolhida, `color-scheme: light dark`, `light-dark()`.
- Escala tipográfica fluida com `clamp()`, espaçamento, raios e sombras em camadas.

### Entrega 2 — telas (8–12 h)

- Home: hero + ilustração SVG + formulário em grid + "Como funciona".
- Dashboard: header com evento/status, stepper, seções, tabela responsiva.
- Participante: card de resultado com revelação; confete CSS no primeiro view; wishlist como "bilhete".
- 403/404/500, estados vazios e rodapé.

### Entrega 3 — acabamento (4–6 h)

- View Transitions, `@starting-style`, hover/press, `:has()` para erro.
- Favicon SVG, `theme-color`, OG image + metas (só em páginas públicas; não colocar nome do evento em
  URL com token).
- Auditoria de contraste AA, foco, `prefers-reduced-motion` e teste em mobile real.

## 7. Riscos e cuidados

- **CSP:** qualquer fonte/ícone/imagem precisa ser local. Tailwind CDN e Google Fonts estão fora.
- **Cache de fonte:** sem `/fonts/` em `isStaticAsset()`, cada página baixa a fonte de novo (`no-store`).
- **`input[type=date]`:** aparência varia por navegador; estilizar com cuidado e testar no Safari iOS.
- **Dark mode:** `light-dark()` exige `color-scheme`; revisar contraste de alerts e badges.
- **OG em URL com token:** não gerar preview com nome do evento/dados pessoais; usar card genérico.
- **Movimento:** respeitar `prefers-reduced-motion`; confete sem `animation` nesse caso.
- **Acessibilidade:** manter foco de 3 px, labels, contraste 4.5:1 e alvos de toque ≥ 44 px.
- **Teste de regressão:** `Block5SecurityIntegrationTest` valida o header CSP; nenhuma mudança de CSS
  deve alterá-lo. Se entrar fonte, o teste de cache/asset pode precisar de caso novo.

## 8. Referências consultadas

- Pico CSS — manutenção encerrada, v2.1.1 final: <https://picocss.com/docs/maintenance>
- Discussão oficial do arquivamento: <https://github.com/picocss/pico/discussions/754>
- Cross-document View Transitions (MPA), 2 linhas de CSS: <https://webkit.org/blog/16967/two-lines-of-cross-document-view-transitions-code-you-can-use-on-every-website-today/>
- htmx (4.0 lançado; 2.x estável): <https://htmx.org/docs/>
- Tailwind CSS 4 + Maven sem Node: <https://github.com/4ndreiDev/maven-tailwind-plugin>
- daisyUI 5 e temas: <https://daisyui.com/docs/themes/>
- Web Awesome (sucessor do Shoelace): <https://webawesome.com/docs>
- Estado do CSS em 2026 (Baseline, `:has`, container queries, `@starting-style`): <https://masturbyte.com/modern-css-2026.html>
- Confete só com CSS: <https://purecss.com/examples/confetti>
