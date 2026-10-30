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
4. Clique em **Salvar Revisão**, no canto superior direito do editor, ou salve com `Ctrl+S`.
   O plugin grava a revisão aberta em `mio-review/mio-review-<id>.md`, por exemplo
   `mio-review/mio-review-1.md`, com os comentários de todos os arquivos do projeto.
   Enquanto houver comentários pendentes, cada salvamento atualiza o mesmo arquivo.
5. Peça à IA para aplicar a revisão. Ela troca `[ ]` por `[x]` em cada comentário aplicado, e o
   plugin mostra esses comentários em verde como resolvidos, recolhidos numa barra de uma linha com o
   início do texto. Clique na barra para expandir ou recolher, e no **OK** à direita para tirar o
   comentário do editor. O `.md` não muda: o comentário continua nele como resolvido.
6. Quando todos os comentários estão resolvidos, a revisão é concluída: o `.md` dela não é mais
   alterado e o próximo comentário abre `mio-review-<id + 1>.md`.
7. Para remover comentários, passe o mouse sobre uma das linhas comentadas e clique no **-** que
   aparece ao lado do **+**. O comentário fica cinza e riscado, e dá para marcar vários. Eles só são
   removidos ao salvar; até lá, o botão **Desfazer** na margem cancela a remoção.
   Comentários resolvidos (verdes) não têm o **-** e não podem ser removidos um a um. Se a IA resolver
   um comentário que estava marcado, a marca é desfeita e ele fica.
   **Remover Comentários Resolvidos**, no Find Action (`Ctrl+Shift+A`), faz o mesmo que o **OK** em
   todos os resolvidos de uma vez.

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
