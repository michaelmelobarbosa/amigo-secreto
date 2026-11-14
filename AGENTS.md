# Amigo Secreto — contexto do projeto

Aplicação web para organizar amigo secreto sem login: acesso por links com token. Serve para uso real (família e trabalho) e como projeto de portfólio, com deploy em VPS própria.

## Documentos que mandam

- [Especificação](<./Amigo Secreto — Especificação do Projeto.md>) — fonte da verdade de escopo, domínio, arquitetura e segurança. Se o código precisar divergir, atualize a spec na mesma entrega.
- [implementation.md](./implementation.md) — plano por fases, estrutura de pacotes e estado atual. Em caso de divergência com a spec, a spec vence.

## Stack

Java 25 · Spring Boot 4.1.2 (webmvc, data-jpa, validation, thymeleaf) · Flyway · PostgreSQL em dev/prod · H2 só em testes · Pico.css · sem SPA, sem Spring Security, sem Lombok.

## Comandos

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Dtest=CycleDrawSolverTest test
.\mvnw.cmd spring-boot:run
.\mvnw.cmd -DskipTests package
```

(Linux/macOS: `./mvnw`.) Rode os testes que tocam o que você mudou antes de dizer que terminou.

## Estrutura

Pacotes por funcionalidade em `br.com.michael.amigosecreto`: `event`, `participant`, `exclusion`, `draw`, `invite`, `admin`, `common`. Direção única: controller → service → repository. Entidade JPA nunca é modelo de tela.

## Convenções que não se negociam

- **Ids** `UUID`; **tempo** `Instant` em UTC no banco e no domínio; `America/Sao_Paulo` só na borda (DTO/template).
- **Entidades**: `equals`/`hashCode` por `id`, `FetchType.LAZY`, sem `@Data`, sem `toString` com relação bidirecional.
- **DTOs** são `record`; consulta de tela usa projeção/DTO, nunca entidade lazy.
- **Transações** no serviço (`@Transactional`). Toda escrita carrega o `Event` com `PESSIMISTIC_WRITE` e revalida o estado dentro da transação.
- **Schema** é do Flyway. `ddl-auto=validate`. Nunca `update`. Migração aplicada não se edita: crie a próxima versão.
- **Segurança**: token inválido → 404 (nunca 403, nunca confirma existência). Nunca logar token, nunca ecoar token em erro, nunca expor `Assignment` no painel. CSRF próprio em todo POST. `open-in-view=false`.
- **Thymeleaf** escapa por padrão: não use `th:utext` com dado de usuário.
- **Idioma**: identificadores, pacotes e nomes de teste em inglês; comentários, javadoc, mensagens de UI e docs em pt-BR.
- **Dependência nova** só com justificativa na spec/implementation. O projeto é de portfólio e precisa continuar enxuto.

## O que já existe

`CycleDrawSolver` (backtracking com MRV, `RandomGenerator` injetável, orçamento de passos) e `DrawImpossibleException`, com testes. O MVP e a fase 2 de e-mail estão implementados; falta apenas executar o deploy real na VPS.

## Agentes do projeto

- `dominio-jpa` — entidades, repositórios, serviços, Flyway, configuração.
- `web-thymeleaf` — controllers, DTOs de tela, templates, assets.
- `testes` — testes em `src/test/**`.
- `revisor-seguranca` — auditoria de tokens, IDOR, CSRF, headers, privacidade.
- `revisor-spring` — auditoria de JPA, transações, locks, schema.
- `infra-deploy` — POM, Docker, Compose, proxy, deploy.

Ao delegar, passe o recorte exato (fase/arquivos) e diga explicitamente o que **não** deve ser tocado.
