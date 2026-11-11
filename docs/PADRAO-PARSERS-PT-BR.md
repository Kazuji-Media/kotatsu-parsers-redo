# Padrão de parsers PT-BR — Kazuji #2

Data da auditoria de código: 2026-10-02. Escopo: contrato comum para as fontes brasileiras da issue
guarda-chuva. Este documento é uma auditoria estática; não declara sites funcionais. A verificação no
navegador, os testes de CI e a validação no APK precisam registrar resultados próprios.

## Critério de conclusão por fonte

Uma fonte entra no lote de validação quando catálogo, busca suportada, detalhes, lista completa de
capítulos e páginas de um capítulo público passam. O cartão da fonte também registra cada capacidade
como **implementada e verificada**, **ausente no site**, **pendente** ou **requer ação do usuário**.
Uma fonte com catálogo funcionando e leitor quebrado continua pendente. Fontes indisponíveis,
redirecionadas ou com login recebem evidência e motivo; não são marcadas como implementadas.

O padrão é o mesmo contrato de qualidade em todas as fontes. Cada site só anuncia filtros,
ordenações e metadados que realmente oferece. Não inventar suporte para preencher a matriz.

## Escolha de implementação e ordem de trabalho

1. Atualizar domínio/seletores de filhos curtos de uma família existente, após observar o site.
2. Criar filhos novos de famílias confirmadas pelo HTML/requests observados.
3. Implementar HTML/API específica quando o site tiver contrato diferente.
4. Usar WebView quando o conteúdo depende de renderização e não há endpoint público confirmado.
5. Registrar sites mortos, bloqueados ou dependentes de conta; retomar quando houver acesso verificável.

| Família | Evidência necessária | Contrato atual reutilizável | Limite a verificar |
| --- | --- | --- | --- |
| Madara | `.page-item-detail`, `.wp-manga-chapter`, AJAX WordPress ou `/ajax/chapters/` | Catálogo, busca, gêneros, exclusão, ano, estados, adulto, ordenações, login e leitor | Nem todos os sites permitem todos os parâmetros herdados; detalhes deixam parte dos campos sem atualizar |
| MangaReader | `.bs`, `#chapterlist`, `#readerarea`, `ts_reader` | Catálogo paginado, busca, gêneros/exclusão, estado/tipo e páginas em script | Busca combinada com filtros não anunciada; autor/estado PT precisam seletores observados |
| Zeist/Blogger | Feed Blogger com labels e DOM/script de capítulos compatível | Atualizados, busca, gênero único e estado | API de feed, labels de obra e paginação devem ser comprovadas; não transplantar para WordPress |
| HTML/API específica | Estrutura ou respostas do próprio site | `PagedMangaParser` ou `SinglePageMangaParser` | Não presumir endpoints só pelo nome do site |
| WebView | DOM útil aparece depois de JS e endpoints ainda não demonstrados | `context.evaluateJs(baseUrl, script, timeout)` e ação manual | Tempo limite, paginação virtual, imagens lazy, persistência de sessão e comportamento no Android |

Não mudar a família global para atender um site sem fixtures de outros representantes. Preferir
override pequeno e específico. Promover lógica para a base só depois de demonstrar o mesmo problema
em mais de uma fonte e verificar os caminhos existentes.

## Estado inicial das primeiras candidatas

Os domínios abaixo vêm do código existente, não de teste de disponibilidade. A ordem é de esforço
estimado pelo tamanho e pela reutilização atual; o navegador pode alterar a prioridade.

| Fonte exibida | ID que deve ser preservado | Domínio no código | Implementação / marcador | Primeira investigação |
| --- | --- | --- | --- | --- |
| BorutoExplorer | `BORUTOEXPLORER` | `leitor.borutoexplorer.com.br` | Filho Madara, página 10; sem `@Broken` | Domínio, lista, AJAX, data `dd 'de' MMMMM 'de' yyyy` e páginas |
| CafecomYaoi | `CAFECOMYAOI` | `cafecomyaoi.com.br` | Filho Madara adulto; sem `@Broken` | Catálogo adulto, data `dd/MM/yyyy`, login/leitor |
| EuphoriaScan | `EUPHORIASCAN` | `euphoriascan.com` | Filho Madara adulto, página 10; sem `@Broken` | Domínio, data longa PT e capítulo público |
| NoindexScan | `NOINDEXSCAN` | `noindexscan.com` | Filho Madara adulto; sem `@Broken` | Domínio, tags, data `dd/MM/yyyy` e leitor |
| LimboScan | `LIMBOSCAN` | `limboscan.com.br` | Filho Madara, `listUrl=obras/`, `tagPrefix=obras-genre/`; sem `@Broken` | Validar caminhos customizados e fallback do leitor |
| NinjaComics | `NINJASCAN` | `ninjacomics.xyz` | `NinjaScan.kt`, filho Madara; sem `@Broken` | Confirmar domínio atual e data longa PT |
| HuntersScan | `HUNTERSSCAN` | `readhunters.xyz` | Madara + capítulos AJAX paginados HTTP/1.1; `@Broken` | Confirmar domínio/421, paginação completa, cancelamento e número real |
| MangaNight | Novo ID somente após confirmar que não é alias | Não encontrado | Nenhum parser pelo nome na árvore `src/main` | Confirmar URL e família no navegador |
| LuratoonScan | `RANDOMSCANS` | `luratoons.net` | `SinglePageMangaParser`; `@Broken("Not dead, changed template")` | Confirmar template/domínio novo e mecanismo de imagens |
| Nexus Mangás | `NEXUSMANGAS_PTBR` | `www.nexusmangas.com` | Parser WebView customizado; sem `@Broken`; possui fixtures | Cobertura de filtros/search, capítulos virtuais, imagens lazy e ação manual |

Não renomear `RANDOMSCANS` para `LURATOON` nem `NINJASCAN` para `NINJACOMICS`: a identidade da fonte
participa de `generateUid` e a troca rompe a continuidade de favoritos/histórico/capítulos.

## Contrato do parser e relação com as funções do Kazuji

| Função do app | Contrato do parser | Evidência mínima |
| --- | --- | --- |
| Explorar catálogo e busca global | `getList`, `availableSortOrders`, `filterCapabilities` | Páginas 1/2 diferentes; busca acentuada; consulta sem resultado |
| Filtrar resultados | `getFilterOptions`, `MangaListFilter` | Cada filtro anunciado muda o request corretamente e o site o reconhece |
| Tela de detalhes, capa, gêneros e alternativas | `getDetails`, `Manga` | Título, aliases, autores, sinopse, estado, rating, classificação e capas quando presentes |
| Leitor, modo webtoon, pré-carregamento e salvar página | `getPages`, `getPageUrl`, headers/interceptor | Ordem completa de imagens; imagem inicial, central e final carregam como imagem |
| Downloads e leitura offline | Os mesmos contratos de páginas/imagens | Download de um capítulo público e abertura offline no APK |
| Favoritos, histórico, bookmarks e retomada | IDs/URLs estáveis de manga/capítulo/página | Atualizar detalhes duas vezes conserva identidade; mudança de domínio não altera IDs |
| Notificação de capítulos novos | Lista completa de capítulos em ordem cronológica, IDs, `uploadDate`, `branch` | Atualização repetida não cria capítulos duplicados nem perde antigos |
| Scrobbling/progresso externo | Número real do capítulo, volume e branch | Capítulos fracionários/gaps não viram índices da lista |
| Related/sugestões | `getRelatedManga`, tags e busca | Related nativo quando existe ou fallback de busca coerente |
| Abrir no navegador, domínio alternativo e favicon | `publicUrl`, `configKeyDomain`, `getFavicons` | URL utilizável; preset/override de domínio testado; favicon válido |
| Login e proteção que exige intervenção | `authorizationProvider`, `requestBrowserAction`, erros de autenticação | Mensagem/ação manual apropriada e nova tentativa após ação no APK |

Modos de leitura, zoom, temas, backups, categorias de favoritos e serviços externos são recursos do app.
O parser fornece dados para eles; não deve implementar UI, sincronização ou preferências do usuário.

Os pontos de integração auditados no app são `core/parser/ParserMangaRepository.kt`,
`filter/ui/FilterCoordinator.kt`, `search/domain/SearchV2Helper.kt`, `download/ui/worker/DownloadWorker.kt`,
`reader/domain/PageLoader.kt`, `tracker/domain/CheckNewChaptersUseCase.kt` e
`scrobbling/common/domain/Scrobbler.kt`. O coordenador de filtros monta a UI diretamente das capacidades
e opções do parser: anunciar suporte falso cria um controle que aparenta funcionar mas ignora a escolha.

### Identidade, domínio e metadados

- Preservar nome da fonte, `Manga.id`, `Manga.url` e `Manga.source` ao enriquecer detalhes.
- Gerar IDs pela URL relativa canônica ou ID interno estável; não usar domínio atual, posição ou timestamp.
- Usar `configKeyDomain`/`domain` em requests; `publicUrl` e capas devem ser absolutas utilizáveis.
- Remover tracking/fragmentos apenas quando observado que não participam da identidade do recurso.
- Manter query/assinatura necessárias para carregar mídia; uma URL assinada não é um identificador estável.
- Usar `manga.copy(...)`; campos ausentes preservam informação anterior ou ficam desconhecidos.
- `rating` fica em 0..1, ou `RATING_UNKNOWN`; estado desconhecido fica `null`, data desconhecida fica 0.
- Mapear autores e artistas publicados, aliases distintos, sinopse, capa grande, gêneros e adulto quando presentes.
- Não inferir `SAFE` só porque uma obra não tem um badge adulto.
- Parsing de números/datas usa locale/formato adequados; datas PT longas, curtas e relativas precisam exemplos.

### Capítulos e páginas

- Retornar todos os capítulos e manter ordem do primeiro ao último por branch. Paginação AJAX/virtual deve
  detectar fim e comparar quantidade quando o site anuncia um total.
- Extrair número/volume real do texto ou atributos; não usar índice da lista em uma fonte nova.
- `MangaChapter.title` contém o título específico; não repetir número ou nome da obra no campo.
- Preservar capítulos 0, extras, fracionários e múltiplas versões sem colisões de ID. `branch` separa grupos
  sobrepostos e `scanlator` registra o publicador quando o site oferece o dado.
- Não engolir falha de uma página de capítulos e devolver lista parcial como se estivesse completa.
- `getPages` devolve imagens na ordem do leitor, sem capas, ícones, anúncios e thumbnails alheios.
- Usar utilitários `src`/`requireSrc`, URLs relativas/absolutas e variantes lazy do projeto antes de novo código.
- Falhar com `ParseException` contendo URL quando um capítulo que deveria ter imagens não devolve páginas.
- Headers de Referer/Origin/User-Agent e interceptores devem valer também para downloads e capas.
- Interceptor de ZIP/imagem fecha streams e preserva status/headers relevantes; só desembrulha formato comprovado.

### Capabilities, erros e cancelamento

- `availableSortOrders` não pode ficar vazio; somente incluir ordens realmente suportadas pelo request.
- `filterCapabilities` declara busca, busca com filtros, tags múltiplas/exclusão, ano/range,
  autor e idioma original. `getFilterOptions` só oferece valores suportados para estado, tipo,
  classificação, demografia, gêneros e idiomas.
- Não tratar timeout/challenge/login/HTML inesperado como busca vazia ou capítulo sem páginas.
- Usar `runCatchingCancellable` e repropagar `CancellationException` em captura ampla.
- Concorrência pertence à coroutine pai e tem limite; não criar jobs soltos nem requisições sem controle.
- Login usa o contrato existente. Gate que exige ação humana usa `context.requestBrowserAction`.
- WebView retorna resultado síncrono ou `null` para polling do host: Promise não aguardada pode virar `{}`
  na ponte Android. O teste atual do Nexus já inclui esse caso; fixture JVM não executa um WebView real.

## Seletores existentes e riscos concretos

### Madara

- Catálogo: `div.row.c-tabs-item__content`, fallback `div.page-item-detail`; título em `.tab-summary`/
  `.item-summary` `h3,h4`, fallback `.manga-name,.post-title`; rating `span.total_votes`.
- Tags de filtro: `header ul.second-menu` e `div.genres_wrap ul.list-unstyled`; extrai chave por `tagPrefix`.
- Detalhes: `h1`, `div.genres-content a`, seletores `description-summary`/`summary_content`/
  `manga-summary`/`.desc` e campo `Status`/`Estado`; aliases `.summary-content` sob `Alt`/`Nomes alternativos`.
- Capítulos: `li.wp-manga-chapter, div.wp-manga-chapter`; data `span.chapter-release-date i` ou
  título de `a.c-new-tag`; `?style=list` nas URLs. Conteúdo estático por `div.listing-chapters_wrap`;
  fallback POST `/<obra>/ajax/chapters/` ou WordPress `manga_get_chapters`.
- Leitor: `div.main-col-inner div.reading-content` → `div.page-break img`; login
  `.content-blocked,.login-required`; caminho alternativo `#chapter-protector-data`.
- Related: `.related-manga div.related-reading-wrap`, título `.widget-title`.

Riscos auditados: `getDetails` muda URL por `og:url`, contrariando o contrato documentado na interface;
não atualiza `authors`, `rating` nem `largeCoverUrl` dos detalhes; campos ausentes podem apagar tags/estado;
aliases usam `tableValue` depois de selecionar `.summary-content`, o que exige fixture específica;
número do capítulo é ordinal; um root de leitor presente sem `.page-break` pode devolver páginas vazias.
As ordens/filtros amplos herdados não foram confirmados individualmente nos sites da issue.

### HuntersScan

Capítulos em `/<obra>/ajax/chapters/?t=N`, total obtido de `.pagination a[data-page]`, batches de até
10 requests e cliente HTTP/1.1 para o erro 421 anterior. `catch (Exception)` devolve lista vazia e também
captura cancelamento; isso precisa ser corrigido antes de liberar. Após `mapChapters(reversed=true)`,
atribui `size-index` como número, sem extrair número real. Preservar o caminho HTTP/1.1 somente se ainda
necessário pelo tráfego observado.

### Luratoon

Catálogo `/todas-as-obras`, `.todas__as__obras .comics__all__box`; detalhes `.sumario__container`,
`h1.desc__titulo__comic`, `.sumario__sinopse__texto`, `ul.capitulos__lista .numero__capitulo`; páginas
por `const urls = [...]`; imagem `application/octet-stream` tratada como ZIP e primeira entrada.
Busca declarada ausente; tags/categoria têm caminho no código mas opções vazias. Data usa `YYYY` (ano
da semana), que deve virar `yyyy` se esse parser for mantido. A classificação `SAFE` no catálogo não
tem evidência. O marcador diz que o template mudou: os seletores não devem ser reaproveitados por suposição.

### Nexus

Catálogo/ranking e busca capturam `a[href*='/obra/']`; detalhes extraem `h1`, links genre/theme e linhas
`Cap. N`; capítulos sintéticos `/capitulo/<slug>/<numero>`; páginas `img[data-kotatsu-page-url]`.
Anuncia `POPULARITY`/`RELEVANCE`, busca e nenhuma opção de filtro. Ranking escolhe página via script;
busca faz recorte local de resultados capturados. Confirmar que os resultados completos são capturados
antes de anunciar paginação de busca. Details registra total/capturados de capítulos e lança erro
se faltar parte; a origem dos números e virtualização deve ser confirmada no navegador e Android.

## Fixtures e execução por GitHub Actions

Guardar HTML/JSON mínimo obtido de observação real em `src/test/resources/ptbr/<source-id>/`.
Adicionar um manifesto com URL base, data, operação, valores de filtros, status/content-type esperado
e origem da amostra. Retirar tokens, cookies, dados de conta e mídia completa; usar URLs representativas
sem segredos. Uma fixture reproduz o formato observado, não substitui validação live.

| Amostra | Regressão que deve verificar |
| --- | --- |
| `list-page-1` e `list-page-2` | IDs distintos por obra, sem repetição entre páginas e fim válido |
| `search`, `search-empty`, consulta com acento | Encoding correto e distinção entre vazio legítimo e HTML inválido |
| `filter-options` + responses filtradas | Somente capacidades suportadas; múltiplos/exclusão quando anunciados |
| `details-rich` e `details-minimal` | Preservação id/url/source; campos ricos e ausentes; estado/rating/aliases |
| `chapters-static` ou AJAX 1/2/final | Ordem cronológica, número fracionário, gaps, branches e completude |
| `chapter-pages` com lazy/ad/placeholder | Ordem exata de imagens; exclusão de ads e erro se vazio |
| `auth-gate`, `changed-template`, erro de uma página AJAX | Erro correto com URL; nenhuma lista parcial silenciosa |
| Interceptor/coroutine mock | Referer/domínio novo, cancelamento propagado e limite de concorrência |
| Snapshot WebView de virtualização quando necessário | Marca total/capturados, lista completa, bridge síncrona, gate manual |

Usar JUnit Jupiter e `runTest`, já presentes, com `MangaLoaderContext` e interceptor OkHttp de fixture
que intercepta os requests e devolve respostas sem rede. Testar request/resultado pelo contrato público;
evitar assertions da mesma implementação do seletor. Não adicionar dependência só para fixtures.
Uma fixture deve falhar se surgir request não cadastrado: isso detecta endpoints/queries incorretos.

No runner Linux com JDK 17 e Gradle wrapper, executar por exemplo:

```bash
./gradlew test --tests 'org.koitharu.kotatsu.parsers.site.pt.*FixtureTest' \
  --tests 'org.koitharu.kotatsu.parsers.site.pt.NexusMangasWebViewTest' \
  --tests 'org.koitharu.kotatsu.parsers.site.pt.LeitorDeMangaBrowserActionTest'
```

O sufixo `FixtureTest` é uma convenção proposta para os novos testes; criar as classes antes de aplicar
o filtro no workflow. Depois ampliar `check` conforme mudança real, sem rodar milhares de fontes live
para cada ajuste. Upload de relatórios `build/test-results/test` e `build/reports/tests/test` no CI.

Smoke live deve ser separado, com conjunto explícito de fontes do lote e timeout, pois depende de
rede, geolocalização e proteção do site. `MangaParserTest` já verifica catálogo, paginação, busca,
tags, detalhes, páginas e favicon, mas usa seleção de enum em `MangaSources.kt`; revisar a seleção e
capacidades antes de adotá-lo como gate. Seu mock JS usa Quack, não renderiza DOM Android e não comprova
WebView. Resultados sem teste de leitor no APK não recebem o estado final de validação.

## Ficha exigida em cada lote/APK

Para cada fonte, preencher: nome para pesquisar no app, ID preservado, domínio observado, família,
commit do parser, capacidades testadas/ausentes, título público para busca, capítulos antigo/recente,
resultado de imagens e download, gate/login e instrução de validação. O APK precisa indicar exatamente
o commit/pin de parsers incluído; uma fonte pronta no repositório irmão pode ainda não estar nesse APK.

Validação humana mínima: habilitar idioma Português (e conteúdo adulto apenas para fontes classificadas
assim), procurar o nome exibido, abrir catálogo, pesquisar o título registrado, abrir detalhes, ler um
capítulo antigo e um recente, testar filtro anunciado, favoritar/retomar, baixar e abrir offline.
Informar ao usuário fontes bloqueadas e ação necessária com evidência atual, sem anunciar que funcionam.
