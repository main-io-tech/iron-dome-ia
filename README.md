<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="88" alt="Mio Review AI">
</p>

<h1 align="center">Mio Review AI</h1>

<p align="center">
  <b>Revise o código como num pull request e deixe a IA aplicar as correções.</b><br>
  Plugin para IntelliJ IDEA · Java, Kotlin e qualquer arquivo de texto · Funciona com Cursor, Claude Code e outros assistentes
</p>

---

Você comenta o código direto no editor. O Mio Review AI transforma os comentários num roteiro em
Markdown que qualquer IA entende, aplica e devolve marcado como resolvido. E antes de mexer numa
classe, você vê quem depende dela, o risco da mudança e se há testes para protegê-la.

## Por que usar

- **Revisão sem atrito:** comente linhas ou trechos como num PR, sem sair da IDE.
- **A IA faz o trabalho pesado:** cada comentário vira uma instrução clara, com o código citado.
- **Você mantém o controle:** escolha entre aplicar direto ou discutir antes de alterar.
- **Mudanças com segurança:** veja o impacto de alterar uma classe antes de tocar nela.

## Como funciona

1. **Comente.** Clique no **+** da margem (ou `Ctrl+Alt+Shift+M`), escreva e escolha o modo:
   *Aplicar direto* ou *Discutir antes de alterar*.
2. **Salve.** **Salvar Este Arquivo** ou **Salvar Todos** (`Ctrl+S`) gera
   `mio-review/mio-review-<id>.md` com cada comentário e o trecho de código.
3. **Peça à IA.** Ela aplica as correções, marca `[x]` em cada item e o plugin mostra o comentário
   em verde como resolvido.

## Recursos

### Comentários inline

- Comentários acompanham o código quando linhas são inseridas ou removidas.
- Edite, remova com desfazer e acompanhe o status: não salvo, pendente, resolvido.
- Rascunhos ficam guardados mesmo se você fechar a IDE antes de salvar.
- A IA é orientada a perguntar antes de aplicar comentários que se contradizem ou se cruzam.

### Painel Mio Review AI

- **Comentários:** a revisão aberta e o histórico, por arquivo. Um clique leva ao código.
- **Alterações:** os arquivos ainda não commitados, com o diff de cada um e quantos comentários têm.

### Diagrama de dependências

Escolha uma classe Java ou Kotlin pelo menu de contexto (**Mio Review AI: Analisar Dependências**)
ou na lista **Todas as classes**, sem precisar abrir o arquivo.

![Diagrama de dependências com os detalhes da classe analisada](docs/images/diagrama.png)

- **Aferentes e eferentes:** quantas classes dependem dela e de quantas ela depende.
- **Risco ao alterar:** baixo, médio ou alto, conforme quantas classes podem quebrar.
- **Testes:** quais testes usam a classe e se ela está bem coberta.
- **O que fazer:** uma recomendação objetiva antes de mudar a classe.
- **Ciclos** em vermelho e **⚠** nas classes com risco e sem testes.
- **Entenda as métricas:** explicações em linguagem simples, para quem está começando.

Classes com alterações ainda não commitadas ganham um contorno azul. **Destacar alteradas** mostra
só elas, e **Ver alterações** abre o diff.

![Classes alteradas e não commitadas em destaque](docs/images/alteradas.png)

Legenda, detalhes e painel inferior podem ser minimizados para o diagrama ganhar espaço.

## Instalação

1. Baixe o `.zip` do plugin (ou gere com `.\gradlew.bat buildPlugin`).
2. No IntelliJ: **Settings > Plugins > ⚙ > Install Plugin from Disk...** e escolha o arquivo.

Compatível com IntelliJ IDEA 2025.2 em diante. O diagrama exige o plugin Java, que já vem no
IntelliJ IDEA.

## Configure sua IA

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

Requer JDK 21+.

```powershell
.\gradlew.bat runIde       # IDE de sandbox com o plugin
.\gradlew.bat test         # testes
.\gradlew.bat buildPlugin  # build/distributions/mio-review-ia-<versão>.zip
```

O diagrama usa o [Cytoscape.js](https://js.cytoscape.org/) (licença MIT).
