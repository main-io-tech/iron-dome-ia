# Funcionalidades do Iron Dome IA

Levantamento de tudo o que o plugin faz, por mecanismo de defesa. Cada seção corresponde a um pacote em
`src/main/kotlin/com/mio/irondome/` e é a base das issues do projeto
[Iron Dome AI no Linear](https://linear.app/mainio/project/iron-dome-ai-f447654e94fc/overview).

Compatível com IntelliJ IDEA 2025.2 ou mais novo (build 252). O Raio de Impacto depende do plugin Java, e o
Escudo, do plugin JUnit; os dois já vêm no IntelliJ IDEA.

## 📡 Radar (`radar`)

Comentários de revisão no código, como num pull request, para a IA corrigir.

### No editor

- Marcar alvo numa linha ou seleção pelo **+** da margem, por duplo clique no número da linha ou com
  <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>M</kbd> (**Iron Dome IA: Marcar Alvo**).
- Botão **−** na margem para cancelar o alvo da linha; **Iron Dome IA: Cancelar Alvo** cancela os alvos
  da seleção ou da linha.
- Formulário embutido no editor, abaixo das linhas comentadas, com vários abertos ao mesmo tempo;
  <kbd>Ctrl</kbd>+<kbd>Enter</kbd> envia e <kbd>Esc</kbd> cancela.
- Dois modos por comentário: **Aplicar direto** ou **Discutir antes de alterar**. Um comentário novo
  começa com o modo do último criado.
- Cartão do comentário desenhado abaixo das linhas, com etiqueta e cor por status, e botão **Editar**.
- Comentários resolvidos aparecem recolhidos numa barra de uma linha: o cabeçalho expande ou recolhe, e o
  **OK** dispensa o cartão sem tirar o comentário do `.md`.
- **Iron Dome IA: Limpar Alvos Interceptados** tira do editor os já resolvidos, sem alterar o `.md`.
- Os comentários acompanham o código quando linhas são inseridas ou removidas.
- Cancelar um alvo já disparado deixa o cartão riscado até o próximo disparo; um rascunho sai na hora.
- Rascunhos e a posição dos comentários sobrevivem ao fechamento da IDE, mesmo sem salvar a revisão.

### Alvo de arquivo ou pacote inteiro

- **Iron Dome IA: Marcar como Alvo**, pelo botão direito na árvore do projeto, na aba do editor, no
  editor ou na aba Sentinela, para pedidos que não cabem numa linha.
- Escrito e editado num diálogo; o alvo de arquivo aparece no topo do editor.
- A árvore do projeto mostra quantos alvos pendentes cada arquivo e pacote tem.

### Aba Radar

- Árvore **revisão > arquivo ou pacote > comentário**, com a revisão aberta e as revisões gravadas.
- Clique leva ao código, duplo clique foca o editor, <kbd>Enter</kbd> também navega.
- Menu de contexto: **Editar**, **Cancelar Alvo** e **Desfazer Cancelamento**.
- Cabeçalho de resumo com indicadores por status e barra de proporção.
- Status: **Não disparado**, **Na mira**, **Interceptado**, **Cancelado no próximo disparo** e
  **Discutir antes**.
- Expandir tudo, recolher tudo e atualizar, mantendo o que estava expandido.

### Histórico

- **Consolidar Histórico** junta as revisões encerradas em `iron-dome/historico-interceptacoes.md` e
  apaga os `.md` avulsos; consolidar de novo acrescenta as novas.
- **Limpar Histórico** apaga as revisões encerradas e o consolidado, avisando quantos comentários saem e
  quantos estão pendentes, com a alternativa **Consolidar em Vez Disso**.
- A revisão aberta nunca é consolidada nem apagada.
- Exclusões passam pela IDE e podem ser recuperadas pelo Local History.
- O nó **Histórico consolidado** mostra quantas revisões e comentários guarda; duplo clique abre o arquivo.
- Os números de revisão não se repetem, nem depois de clonar o projeto.

## 🎯 Centro de Comando (`comando`)

Transforma os alvos em ordens de interceptação em Markdown para qualquer IA.

- **Disparar Este Arquivo** (barra do editor) grava no `.md` só os comentários do arquivo aberto.
- **Disparar Todos** (<kbd>Ctrl</kbd>+<kbd>S</kbd>) salva tudo e grava os comentários de todos os arquivos.
- Salvamentos automáticos da IDE não gravam o `.md`, mas guardam a posição dos comentários.
- `iron-dome/ordem-interceptacao-<id>.md` com arquivo, linhas, modo, trecho de código citado e checklist
  `[ ]` por comentário.
- Instruções para a IA no topo do arquivo, válidas para qualquer assistente: analisar os comentários em
  conjunto e perguntar antes de aplicar comentários que se contradizem ou se cruzam.
- Modo discussão: a IA explica por que o código está assim, se o apontamento procede, o impacto de manter,
  alterar ou remover, traz de 2 a 5 opções com prévia e a recomendação, e só altera depois da decisão final.
- Leitura automática: quando a IA marca `[x]`, o comentário aparece resolvido (verde) no editor.
- A revisão se encerra quando todos os comentários ficam resolvidos; o próximo comentário abre uma nova.
- Notificação ao disparar, com atalho para abrir o `.md`.

## 💥 Raio de Impacto (`impacto`)

Mede o estrago que uma mudança pode causar numa classe Java ou Kotlin.

### Como analisar

- **Iron Dome IA: Calcular Raio de Impacto** no editor, na aba, na árvore do projeto, na estrutura e na
  barra de navegação.
- **Mirar Classe Atual**, para a classe aberta no editor.
- Aba **Todas as classes**: lista as classes do projeto sob demanda, com filtro por nome ou pacote, opção
  de mostrar classes de teste e destaque das alteradas; clique analisa e duplo clique abre o arquivo.
- Profundidade de 1 a 3 níveis e direção: ambas, só eferentes ou só aferentes.

### Diagrama de radar (AntV G6)

- Classe analisada no centro e anéis por distância, com a contagem de classes de cada anel.
- Cor por risco ao alterar (baixo, médio, alto), com brilho vermelho no risco alto.
- Ícone por tipo: classe, abstrata, interface, enum e anotação.
- Escudo de testes: bem coberta, parcial, sem testes ou classe de teste.
- Etiqueta vermelha para risco médio ou alto sem nenhum teste; etiqueta azul para alterações não commitadas.
- Setas de quem usa para quem é usado, tracejadas para herança, vermelhas e curvas nos ciclos.
- Hover acende a classe e os vizinhos e mostra um cartão de resumo; clique mostra os detalhes; dois cliques
  abrem o código.
- Bolhas por pacote e minimapa.
- Busca (<kbd>/</kbd> foca, <kbd>Enter</kbd> vai à primeira), filtro por risco na barra de distribuição,
  aproximar, afastar, enquadrar, centralizar e reorganizar os anéis.
- **Destacar alteradas** apaga as outras classes; **Ver alterações** abre o diff.
- Legenda, detalhes e painel inferior minimizáveis; **Expandir** ocupa a IDE e **Abrir em Janela** destaca
  o painel.

### Métricas e recomendações

- Aferentes (quem depende dela) e eferentes (de quem ela depende).
- Risco ao alterar: baixo com menos de 3 dependentes, médio de 3 a 7, alto com 8 ou mais; estar num ciclo
  sobe um nível.
- Testes: classes de teste que usam a classe; bem coberta com um teste dedicado (como `ServiceTest`) ou 3
  ou mais testes.
- Painel de detalhes com medidor de risco, escudo de testes, alterações e a recomendação do que fazer
  antes de mexer.
- Tabela **Classes no raio** e **Manual de campo** explicando cada número, cor e ícone.

## 👁️ Sentinela (`sentinela`)

Vigia os arquivos ainda não commitados.

- **Agrupar Por**: raiz de código e pacote (padrão), só pacote ou tipo de alteração; a escolha fica salva.
- Com mais de 20 arquivos, os grupos de arquivos chegam recolhidos; o que o usuário abre ou fecha é mantido.
- Busca por nome, pasta ou pacote, que abre todos os grupos enquanto busca e restaura ao limpar.
- **Filtrar** por tipo (modificados, adicionados, removidos, não versionados, outros), **Só com Alvos** e
  **Limpar Filtros**.
- Etiquetas por arquivo: tipo de alteração e quantos alvos do Radar tem; grupos com contagem de arquivos e
  alvos.
- Cabeçalho com totais por tipo, pacotes, alvos e quantos o filtro está mostrando.
- **Inspecionar Diff** (também com <kbd>Enter</kbd>), navegando por todos os arquivos exibidos;
  **Abrir Arquivo** e duplo clique abrem no editor.
- **Marcar como Alvo** para arquivo ou pacote direto da lista.
- Atualiza sozinha com commits, edições e reverts; avisa quando o projeto não tem controle de versão.

## 🛡️ Escudo (`escudo`)

Executa os testes JUnit e mostra o resultado agrupado.

- **Executar Todos os Testes** compila o projeto e roda os testes pelo runner JUnit da IDE, com resultado
  ao vivo e a execução também na janela Run; **Parar** interrompe.
- Classes agrupadas pela chave do `@DisplayName("[Chave] Descrição")`, em ordem alfabética, com as
  `@Nested` dentro da classe que as contém.
- Chaves com cor própria e estável; o nome da classe ou do método aparece em cinza ao lado.
- Grupo **Sem chave** para classes sem `@DisplayName` ou sem chave, aviso no topo e sugestão do
  `@DisplayName` nos detalhes.
- Formatação do nome dos testes: etapas **DADO**, **QUANDO** e **ENTÃO**; verbos `deve`/`should` em
  negrito e o negativo em laranja; código e strings com fundo lilás; exceções em vermelho; números e
  valores em azul; seta e resultado esperado em verde; parâmetros de testes parametrizados destacados.
- Detalhes com **Cenário** em etapas e **Parâmetros desta execução**.
- Minibarra por chave e classe com passou, falhou e ignorado, contagem e tempo; cabeçalho **íntegro** ou
  **rompido**.
- Classes com falha abrem sozinhas, a primeira linha do erro aparece abaixo do teste, **Mostrar Só as
  Falhas** esconde o resto e os detalhes trazem o motivo e a pilha.
- **Ir para o Código** e duplo clique abrem o teste no editor.

## 📦 Caixa-Preta (`caixapreta`)

Reporta bugs do próprio plugin.

- **Reportar Bug do Iron Dome IA** no topo do painel e em **Help**.
- Formulário com título, mecanismo onde aconteceu (sugerido pela aba ativa ou pelo erro do log), o que
  aconteceu, passos para reproduzir e o que era esperado.
- Print da janela da IDE tirado antes do formulário abrir, com miniatura e opção de não incluir.
- Gravação em GIF da janela da IDE por até 30 s, com o formulário escondido e **Parar Gravação** na
  notificação.
- Leitura do `idea.log`: erros e avisos que passaram pelo plugin, com a **origem provável** (primeiro frame
  do plugin na pilha) e a pilha resumida.
- `bug.md` com onde o bug está no plugin (mecanismo, pacote e pasta do código), as capturas, os erros e o
  ambiente (versões do plugin, IDE, Java e sistema, plugins opcionais, controle de versão, aba ativa e
  arquivo aberto).
- Destino: **Enviar ao Linear** (issue `[Bug] <mecanismo>: <título>` no projeto Iron Dome AI, com print e GIF
  anexados) ou **Gerar só local** (abre o `bug.md` no editor).
- Pasta local escolhida pelo usuário, com atalhos para `~/iron-dome-ia/bugs/` e `iron-dome/bugs/` do
  projeto; uma pasta `bug-<data>/` por relatório.
- **Settings > Tools > Iron Dome IA**: API key do Linear no cofre de senhas da IDE, URL do projeto, chave do
  time opcional e **Testar Conexão**.
- Se o envio falhar, o relatório fica salvo e a notificação oferece **Tentar de Novo** e **Configurar
  Linear**.

## 🏰 Base (`base`)

O painel lateral e os componentes compartilhados.

- Tool window **Iron Dome IA** na lateral direita, com as abas Radar, Sentinela, Raio de Impacto e Escudo,
  cada uma com ícone colorido.
- Ponto de extensão para abas extras, usado pelo Raio de Impacto (plugin Java) e pelo Escudo (plugin JUnit).
- Cabeçalho de resumo com estado em destaque (*Céu limpo*, *Perímetro limpo*, *Escudo íntegro*),
  indicadores por status, barra de proporção e aviso.
- Etiquetas arredondadas nas árvores, nas mesmas cores dos indicadores.
- Barra flutuante do editor: **Inspecionar Diff** para arquivos alterados, **Disparar Este Arquivo** e
  **Disparar Todos** enquanto há alvos não disparados.
- Ícones claros e escuros para o plugin, as abas e as ações.

## Privacidade

- Comentários, revisões e histórico ficam na IDE e em `iron-dome/` dentro do projeto.
- A única chamada de rede do plugin é o envio de um relatório de bug ao Linear, quando o usuário configura
  a API key e escolhe enviar.
