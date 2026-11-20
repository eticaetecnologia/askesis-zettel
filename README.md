# Askesis-Zettel

Editor Markdown com Zettelkasten, citações Zotero e impressão acadêmica em duas
colunas. Funciona como PWA no navegador e como aplicativo Android nativo.

## Baixar o APK

A cada envio para `main`, o GitHub Actions compila o aplicativo e publica o
arquivo em **[Releases](../../releases)**. Baixe `askesis-zettel.apk` da versão
mais recente e instale no aparelho — na primeira vez o Android pedirá permissão
para instalar de fonte desconhecida.

Se a release não aparecer, o APK também fica na aba **Actions**, como artefato
da execução correspondente.

## O que o aplicativo faz

- Editor Markdown com realce de sintaxe e pré-visualização lado a lado
- Zettelkasten: wikilinks `[[nota]]`, backlinks, índice, etiquetas
- Busca em todo o texto com trecho destacado, e filtro por etiqueta
- Pastas do aparelho: abrir, navegar, editar e salvar `.md` direto no disco
- Citações a partir de uma biblioteca Zotero em CSL-JSON
  (`[@chave]` → `(SOBRENOME, ano)`; `@chave` → `Sobrenome (ano)`), com
  referências ABNT geradas ao final
- Exportação: Markdown, HTML, HTML Latin Modern, PDF, **PDF acadêmico em duas
  colunas**, `.fodt`, `.rtf`, `.txt` e site estático em ZIP
- Imagens `![](arquivo.png)` no texto, embutidas automaticamente na exportação
  em PDF
- Temas, modo foco e interface adaptada para celular

## Metadados do PDF acadêmico

No frontmatter YAML da nota:

```yaml
---
title: A Noção de Áskesis em Foucault
author: Maria Andrade
institution: Universidade Federal do Paraná
abstract: Este artigo examina o conceito de áskesis nos últimos cursos.
translators: [João Silva, Ana Costa]
header_note: Versão preliminar apresentada no colóquio de 2025.
footer_note: Não citar sem autorização.
---
```

Cada campo aceita sinônimos: `título`, `autor`, `instituição`/`affiliation`,
`resumo`, `tradutores`, `note`/`aviso`, `page_note`/`nota_pagina`. O
`header_note` aparece só no topo do artigo; o `footer_note` repete no rodapé de
todas as páginas. Listas precisam do formato em linha — `[a, b]`, não hífens em
linhas separadas.

## Como o APK funciona

O WebView do Android não oferece a File System Access API: o Google exige
mudanças no `onShowFileChooser` para isso e, por ora, mantém a API desativada
por padrão, com previsão de liberação num lançamento futuro do Android. Também
não há impressão direta a partir do WebView.

Em vez de abrir mão dessas funções, o aplicativo as repõe por baixo:

- `android/app/src/main/java/org/askesis/zettel/AskesisFsPlugin.java` — ponte
  nativa. Expõe o Storage Access Framework com a mesma semântica da API do
  navegador, e entrega o HTML de impressão ao `PrintManager`, que oferece
  "Salvar como PDF" em vetor, com texto selecionável.
- `www/native/shim.js` — camada em JavaScript que emula
  `showDirectoryPicker`/`showOpenFilePicker`/`showSaveFilePicker` sobre essa
  ponte, e redireciona downloads e `window.open` + `print()`.

O resultado é que **o código do editor não sabe que está num APK**: o mesmo
`www/index.html` roda sem alteração no navegador e no aplicativo. Fora do
Capacitor, o shim sai na primeira linha e tudo se comporta como o PWA de sempre.

Para a impressão, há um detalhe que vale registrar: o Paged.js sinaliza o fim da
paginação chamando `window.print()`. O shim intercepta essa chamada, o que dá à
ponte o momento exato de acionar o serviço de impressão — em vez de adivinhar
com um tempo fixo.

## Assinatura

O arquivo `askesis.keystore` está versionado neste repositório, com senha
conhecida. Isso mantém a assinatura estável entre builds, de modo que cada APK
novo instala por cima do anterior sem exigir desinstalação.

A contrapartida: qualquer pessoa com acesso ao repositório pode assinar um
pacote com a mesma identidade. Para um aplicativo pessoal distribuído fora da
Play Store é um custo aceitável. Se um dia o aplicativo for para a loja, gere um
keystore novo, guarde-o fora do repositório e passe-o ao workflow por
*secrets* — e tenha em conta que trocar a chave obriga a desinstalar a versão
anterior.

## Desenvolvimento

```bash
npm install
npx cap sync android          # recria assets e plugins (ambos são gerados)
cd android && ./gradlew assembleRelease
```

Requer JDK 17 e o SDK do Android. O editor em si é um único arquivo,
`www/index.html` — sem build step, sem dependências de runtime além do Paged.js
empacotado em `www/vendor/`.
