# Status — Leitor de Mangás e Mangas Brasukas

Atualizado em 2026-11-10 (America/Sao_Paulo).

**Status funcional: PARCIAL.** Correções publicadas e testes de regressão aprovados; leitura real ainda não comprovada nas duas fontes.

## Correção publicada

Commit `be2b54c3daedbb88a084973e34868ecc5d450e92` — `fix(pt): wait for hydrated catalog content`.

- Aguarda os cards antes de concluir a primeira página do catálogo/busca.
- Aceita cards sem capa, que é opcional no modelo.
- Evita concluir a extração de capítulos a partir do placeholder inicial de total zero.

O pin local do Kazuji aponta para esse SHA completo. Alterações anteriores de outras fontes e workflows foram preservadas.

## Validação confirmada

| Verificação | Resultado |
| --- | --- |
| Fixtures de busca, completude e ação manual | 18 testes, zero falhas |
| Snapshot completo: inclui Nexus, BorutoExplorer, HuntersScan e NinjaScan | 41 testes, zero falhas |
| Regressões em Android WebView no Kazuji | 3 testes aprovados |
| Unit tests Kazuji | 45 testes, zero falhas, 1 ignorado |
| Build padrão com artefato JitPack e JDK 17, sem checkout local | testDebugUnitTest + assembleDebug aprovados |

Os testes de renderização atrasada reproduziram busca vazia e capítulos vazios antes das correções e passaram depois.

## Validação funcional pendente

- Mangas Brasukas: Dandadan foi encontrado e 256 capítulos foram extraídos em uma execução. O capítulo 1 não teve imagens confirmadas.
- Os dois testes ao vivo que exigem imagem visível no leitor falharam em verificação Cloudflare. Um retry repetiu o bloqueio.
- No Leitor de Mangás, o catálogo abriu no navegador integrado e o retorno por Feito recarregou os resultados no app.
- A abertura do capítulo 1 no navegador integrado chegou a “Um passo para ler / Bloqueador detectado”. Verificar novamente manteve a barreira, mesmo com anúncio visível e a exceção de AdBlock limitada ao domínio.

Nenhuma fonte foi declarada plenamente funcional e nenhum APK foi entregue como leitura validada.

Próximo requisito: concluir manualmente o acesso no site e repetir busca, detalhes, páginas e exibição de imagem real no leitor para ambas as fontes. Falhas persistentes após acesso comprovado devem gerar reprodução e teste antes de nova alteração.

## Continuação remota

Branch dos parsers: `agent/issue-8-chapter-batches`. Branch do app: `agent/nexusmangas-actions-test` em Kazuji-Media/Kazuji.

[Handoff completo no Kazuji](https://github.com/Kazuji-Media/Kazuji/blob/agent/nexusmangas-actions-test/docs/validation/kazuji-redo-parsers-handoff.md).
