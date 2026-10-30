# Mio Review AI

Plugin para IntelliJ que permite comentar o arquivo aberto como num pull request. Ao salvar, gera
`mio-review/mio-review-<id>.md` com os comentários e o trecho de código de cada um, para uma IA ler e
aplicar as correções.

## Uso

1. Passe o mouse sobre uma linha e clique no **+** que aparece na margem, ou dê duplo clique no número
   da linha. Com um trecho selecionado, o comentário vale para a seleção inteira.
   Também dá para usar o atalho `Ctrl+Alt+Shift+M`.
2. Escreva no formulário que abre abaixo das linhas e escolha o modo:
   - **Aplicar direto:** a IA pode alterar os arquivos sem consultar você.
   - **Discutir antes de alterar:** a IA apresenta a análise e a proposta e só altera depois que
     você concordar. O cartão ganha a etiqueta roxa **Discutir antes**.

   O modo escolhido vira o padrão dos próximos comentários. Clique em **Comentar** (`Ctrl+Enter`).
   `Esc` cancela. Dá para deixar vários formulários abertos ao mesmo tempo, em linhas diferentes.
   Cada comentário adicionado aparece com borda tracejada e "(não salvo)" até o próximo salvamento.
3. O comentário fica no editor e acompanha o código quando linhas são inseridas ou removidas.
   Para mudar o texto ou o modo, clique em **Editar**, à direita do cartão: o formulário abre preenchido no
   lugar dele, e **Atualizar** (`Ctrl+Enter`) aplica a mudança, que vai para o `.md` no próximo
   salvamento. Comentários resolvidos ou marcados para remoção não podem ser editados.
4. Salve pelos botões no canto superior direito do editor:
   - **Salvar Este Arquivo** grava só os comentários do arquivo aberto. Os comentários não salvos de
     outros arquivos continuam pendentes, e o `.md` mantém a versão que já estava salva deles.
   - **Salvar Todos** grava os comentários de todos os arquivos, assim como o `Ctrl+S`. O botão mostra
     quantos arquivos têm comentários não salvos e só aparece quando há pendências fora do arquivo
     aberto.

   O plugin grava a revisão aberta em `mio-review/mio-review-<id>.md`, por exemplo
   `mio-review/mio-review-1.md`. Enquanto houver comentários pendentes, cada salvamento atualiza o
   mesmo arquivo. Os salvamentos automáticos da IDE (ao fechar a aba, trocar de janela etc.) não
   gravam a revisão. Comentários não salvos continuam não salvos, com os botões de salvar, mesmo
   depois de fechar e reabrir o IntelliJ.
5. Peça à IA para aplicar a revisão. Ela troca `[ ]` por `[x]` em cada comentário aplicado, e o
   plugin mostra esses comentários em verde como resolvidos, recolhidos numa barra de uma linha com o
   início do texto. Clique na barra para expandir ou recolher, e no **OK** à direita para tirar o
   comentário do editor. O `.md` não muda: o comentário continua nele como resolvido.
6. Quando todos os comentários estão resolvidos, a revisão é concluída: o `.md` dela não é mais
   alterado e o próximo comentário abre `mio-review-<id + 1>.md`.
7. Para remover comentários, passe o mouse sobre uma das linhas comentadas e clique no **-** que
   aparece ao lado do **+**. Um comentário ainda não salvo sai na hora, pois ainda não está no `.md`.
   Um já salvo fica cinza e riscado, e dá para marcar vários. Eles só são removidos ao salvar o
   arquivo deles (ou todos); até
   lá, o botão **Desfazer** na margem cancela a remoção.
   Comentários resolvidos (verdes) não têm o **-** e não podem ser removidos um a um. Se a IA resolver
   um comentário que estava marcado, a marca é desfeita e ele fica.
   **Remover Comentários Resolvidos**, no Find Action (`Ctrl+Shift+A`), faz o mesmo que o **OK** em
   todos os resolvidos de uma vez.

## Painel Mio Review AI

A tool window **Mio Review AI**, na barra lateral direita, tem duas abas:

- **Comentários:** as revisões em árvore (revisão > arquivo > comentário), com a revisão aberta no
  topo e o histórico lido dos `.md` de `mio-review/`. Cada comentário mostra as linhas, o status e o
  início do texto. Clique para ir ao comentário no código; duplo clique também foca o editor.
- **Alterações:** os arquivos com alterações ainda não commitadas no git, como os que a IA mudou ao
  aplicar a revisão, indicando quantos comentários da revisão aberta cada um tem. Clique para abrir o
  diff com o último commit (as setas do diff passam pelos demais arquivos) e duplo clique para abrir
  o arquivo.

## Diagrama de dependências

A aba **Dependências** da tool window mostra, para uma classe Java ou Kotlin, as classes de que ela
depende (eferentes) e as que dependem dela (aferentes), até 3 níveis de distância. Há três formas de
escolher a classe:

- **Menu de contexto:** clique com o botão direito numa classe ou arquivo e escolha **Mio Review AI:
  Analisar Dependências**. Funciona no editor (vale a classe sob o cursor), na aba do arquivo, no
  Project view, no Structure view e na barra de navegação.
- **Todas as classes:** na aba de mesmo nome, embaixo do diagrama, **Carregar todas as classes** lista
  as classes Java e Kotlin do projeto, com filtro por nome ou pacote e a opção de mostrar as de teste.
  Clique (ou Enter) analisa a classe sem abrir o arquivo; duplo clique também a abre no editor. Classes
  com alterações não commitadas aparecem na cor do git.
- **Analisar Classe Atual:** na barra da aba, analisa a classe aberta no editor.

- **Diagrama:** a classe analisada fica destacada e a cor de cada classe mostra o risco de alterá-la
  (verde baixo, amarelo médio, vermelho alto). O sinal ⚠ marca classes com risco médio ou alto e sem
  testes. Interfaces aparecem como elipses, setas cheias indicam uso, setas tracejadas com ponta vazada
  indicam herança ou implementação, e ligações vermelhas indicam ciclos de dependência. Passe o mouse
  para destacar os vizinhos de uma classe, clique para ver os detalhes e dê duplo clique para abrir o
  código. Em **Analisar a partir daqui**, o diagrama é refeito em torno de outra classe.
- **Aferentes e eferentes:** quantas classes do projeto dependem da classe (aferentes) e de quantas
  ela depende (eferentes). Contam o código de produção inteiro, não só o que aparece no diagrama.
- **Risco ao alterar:** baixo com menos de 3 classes dependendo dela, médio de 3 a 7 e alto com 8 ou
  mais. Fazer parte de um ciclo sobe o risco um nível.
- **Testes:** as classes de teste que usam a classe diretamente. **Bem coberta** quando há um teste
  com o nome dela (como `ServiceTest`) ou 3 ou mais classes de teste a usam, **Parcial** com uma ou
  duas, **Sem testes** com nenhuma. Os testes não são executados; para medir as linhas cobertas, use
  **Run with Coverage**. Cada classe traz uma recomendação do que fazer antes de alterá-la.
- **Alterações não commitadas:** as classes cujo arquivo foi mexido e ainda não entrou num commit,
  pela mesma regra da aba **Alterações**, ganham um contorno azul no diagrama e aparecem na coluna
  **Alteração** da tabela. **Destacar alteradas** apaga as demais classes, e **Ver alterações**, nos
  detalhes da classe, abre o diff com o último commit, passando pelas outras classes alteradas do
  diagrama. As marcas se atualizam sozinhas ao editar, commitar ou reverter.
- **Explicações:** **Entenda as métricas**, na barra da aba e no diagrama, explica cada número e cor.
  Os botões **?** nos detalhes da classe e os cabeçalhos da tabela também explicam cada métrica.
- **Controles:** profundidade (1 a 3 níveis), direção (ambas, só eferentes ou só aferentes) e busca.
  A classe analisada fica no centro e cada anel em volta está um passo de dependência mais longe
  dela. **Expandir** faz a aba ocupar a janela inteira e **Abrir em Janela** a destaca numa janela
  separada.
- **Mais espaço para o diagrama:** **Mostrar Painel Inferior**, na barra da aba, esconde ou mostra as
  tabelas, a lista de classes e as explicações. No diagrama, o **−** minimiza a legenda e os detalhes
  da classe, deixando só o título, e o **✕** fecha os detalhes. A IDE lembra dessas escolhas.

Contam como dependência: tipos citados, herança, construtores e membros estáticos, de `object` ou de
nível de arquivo. Classes aninhadas contam como a classe de topo que as contém, e bibliotecas ficam de
fora. O diagrama usa o [Cytoscape.js](https://js.cytoscape.org/) (licença MIT) e exige o plugin Java
do IntelliJ; sem ele, a aba não aparece.

## Instruções para a IA

Adicione ao `AGENTS.md` (ou a uma rule do Cursor) do projeto revisado:

```markdown
## Revisões em `mio-review/`

Quando houver arquivos em `mio-review/mio-review-*.md` com comentários pendentes (`[ ]`), trate cada
um no arquivo indicado na seção dele (código, spec, documentação ou configuração), localizando o
ponto pelo trecho citado, e marque-o como `[x]` ao terminar. Não edite o restante do `.md`.
Antes de alterar qualquer arquivo, analise os comentários pendentes em conjunto. Se algum contradizer
outro, ou se aplicar um quebrar outro, pergunte ao usuário como resolver e espere a resposta. Sem
conflitos, trate os comentários em sequência. Os de `**Modo:** aplicar direto` podem ser aplicados
sem consulta. Nos de `**Modo:** discutir antes de alterar`, apresente a análise e a proposta e só
altere depois que o usuário concordar.
```

## Desenvolvimento

Requer JDK 21+ no host (o Gradle baixa o JDK 21 do toolchain se faltar).

```powershell
.\gradlew.bat runIde       # abre uma IDE de sandbox com o plugin
.\gradlew.bat buildPlugin  # gera build/distributions/mio-review-ia-<versão>.zip
```

Para instalar na sua IDE: **Settings > Plugins > ⚙ > Install Plugin from Disk...** e escolha o `.zip`.

Compatível com IntelliJ 2025.2 (build 252) em diante.
