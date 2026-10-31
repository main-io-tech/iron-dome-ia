<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="110" alt="Mio Review AI">
</p>

<h1 align="center">Mio Review AI</h1>

<p align="center">
  <b>Code review no estilo pull request, direto no IntelliJ.<br>
  Você aponta, a IA corrige e você continua no controle.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/IntelliJ%20IDEA-2025.2%2B-000000?logo=intellijidea&logoColor=white" alt="IntelliJ IDEA 2025.2+">
  <img src="https://img.shields.io/badge/Java%20%C2%B7%20Kotlin%20%C2%B7%20qualquer%20texto-7F52FF?logo=kotlin&logoColor=white" alt="Java, Kotlin e qualquer arquivo de texto">
  <img src="https://img.shields.io/badge/funciona%20com-Cursor%20%C2%B7%20Claude%20Code%20%C2%B7%20qualquer%20IA-8250DF" alt="Funciona com Cursor, Claude Code e qualquer IA">
  <img src="https://img.shields.io/badge/vers%C3%A3o-0.1.0-0969DA" alt="Versão 0.1.0">
</p>

<p align="center">
  <a href="#-como-funciona">Como funciona</a> &nbsp;·&nbsp;
  <a href="#-recursos">Recursos</a> &nbsp;·&nbsp;
  <a href="#-instalação">Instalação</a> &nbsp;·&nbsp;
  <a href="#-configure-sua-ia">Configure sua IA</a> &nbsp;·&nbsp;
  <a href="#-perguntas-frequentes">FAQ</a>
</p>

<p align="center">
  <img src="docs/images/fluxo.svg" width="900" alt="Fluxo do Mio Review AI: comente no editor, salve o roteiro em Markdown e a IA aplica e marca como resolvido">
</p>

---

## ✨ Por que o Mio Review AI?

Pedir correções para a IA pelo chat é frágil: você descreve o lugar do problema, ela procura no
lugar errado, esquece metade dos pedidos e altera o que você só queria discutir.

O **Mio Review AI** traz para dentro da IDE o fluxo que todo time já conhece: o **code review**.
Você comenta o código linha a linha, como num pull request, e o plugin transforma tudo num roteiro
em Markdown com arquivo, linha e trecho citado. A IA segue o roteiro, marca cada item como
resolvido e você vê o resultado em verde no editor.

| 😕 Sem o Mio Review AI | 🚀 Com o Mio Review AI |
| --- | --- |
| "Corrige aquela função do pedido..." e a IA procura no lugar errado | Cada comentário leva o arquivo, a linha e o trecho de código exato |
| Você pede cinco ajustes e só três são feitos | Um checklist em Markdown: a IA marca `[x]` em cada item resolvido |
| A IA altera até o que você só queria discutir | Você escolhe, por comentário: **aplicar direto** ou **discutir antes** |
| Pedidos que se contradizem viram código quebrado | A IA é instruída a parar e perguntar quando comentários se contradizem |
| Você mexe numa classe sem saber o que pode quebrar | O diagrama mostra quem depende dela, o risco e os testes antes da mudança |

## ⚡ Como funciona

<table>
  <tr>
    <td align="center" width="33%">
      <h3>💬 1. Comente</h3>
      Clique no <b>+</b> da margem, dê duplo clique no número da linha ou use
      <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>M</kbd>. Escolha o modo:
      <i>Aplicar direto</i> ou <i>Discutir antes de alterar</i>.
    </td>
    <td align="center" width="33%">
      <h3>💾 2. Salve</h3>
      <b>Salvar Este Arquivo</b> ou <b>Salvar Todos</b> (<kbd>Ctrl</kbd>+<kbd>S</kbd>) gera
      <code>mio-review/mio-review-&lt;id&gt;.md</code> com cada comentário e o trecho de código.
    </td>
    <td align="center" width="33%">
      <h3>🤖 3. Peça à IA</h3>
      Ela aplica as correções, marca <code>[x]</code> em cada item e o plugin mostra o comentário
      em verde, como resolvido.
    </td>
  </tr>
</table>

## 🧩 Recursos

### 💬 Comentários inline, como num pull request

- 📌 **Acompanham o código:** os comentários se movem quando linhas são inseridas ou removidas.
- ✏️ **Ciclo completo:** edite, remova com desfazer e acompanhe o status: não salvo, pendente ou resolvido.
- 💾 **Nada se perde:** rascunhos ficam guardados mesmo se você fechar a IDE antes de salvar.
- 🚦 **Dois modos por comentário:** *Aplicar direto* para o que é óbvio; *Discutir antes de alterar* para a IA apresentar a proposta e esperar o seu OK.
- 🛡️ **Conflitos detectados:** a IA é orientada a perguntar antes de aplicar comentários que se contradizem ou se cruzam.

### 📝 Um roteiro que qualquer IA entende

Ao salvar, cada comentário vira uma instrução clara, com o modo e o código citado. O arquivo traz
no topo as regras para a IA, então funciona com qualquer assistente, sem configuração extra.

<details>
<summary><b>👀 Ver um exemplo do <code>.md</code> gerado</b></summary>

````markdown
# Revisão: mio-review-1

Criada em 31/10/2026 10:05:12. Atualizada em 31/10/2026 10:12:40.

> **Instruções para a IA** (valem para qualquer assistente ou modelo):
> ...

Pendentes: 2 de 2.

## `src/main/kotlin/loja/Pedidos.kt`

### [ ] C1 — linha 42

**Modo:** aplicar direto

```kotlin
pagamento.cobrar(total)
```

**Comentário:**

Valide o estoque antes de cobrar o pedido.

### [ ] C2 — linhas 58–63

**Modo:** discutir antes de alterar

> **IA: discuta antes de alterar.** Não altere nenhum arquivo por causa deste comentário. ...

```kotlin
fun calcularFrete(pedido: Pedido): BigDecimal {
    ...
}
```

**Comentário:**

Esse cálculo deveria considerar o peso dos itens?
````

</details>

### 🗂️ Painel Mio Review AI

| Aba | O que você encontra |
| --- | --- |
| 💬 **Comentários** | A revisão aberta e o histórico, organizados por arquivo. Um clique leva ao código. |
| 🔄 **Alterações** | Os arquivos ainda não commitados, quantos comentários cada um tem e botões para **Ver Diff** ou **Abrir Arquivo**. |
| 📊 **Dependências** | O diagrama de dependências da classe escolhida, descrito abaixo. |

### 📊 Diagrama de dependências: saiba o impacto antes de mudar

Escolha uma classe Java ou Kotlin pelo menu de contexto (**Mio Review AI: Analisar Dependências**)
ou na lista **Todas as classes**, sem precisar abrir o arquivo.

![Diagrama de dependências com os detalhes da classe analisada](docs/images/diagrama.png)

| Métrica | O que responde |
| --- | --- |
| ⬇️ **Aferentes** | Quantas classes dependem dela: o que pode quebrar se você mudá-la. |
| ⬆️ **Eferentes** | De quantas classes ela depende: um número alto indica uma classe que faz coisas demais. |
| 🟢 🟡 🔴 **Risco ao alterar** | Baixo, médio ou alto, conforme quantas classes podem ser atingidas. |
| 🧪 **Testes** | Quais testes usam a classe e se ela está bem coberta. |
| 🔁 **Ciclos** | Dependências circulares destacadas em vermelho. |
| 🎯 **O que fazer** | Uma recomendação objetiva antes de mexer na classe. |

⚠️ marca as classes com risco médio ou alto **e** sem testes: são as que pedem mais
cuidado. E se você está começando, **Entenda as métricas** explica cada número em linguagem simples.

### 🔵 Alterações não commitadas em destaque

Classes com alterações ainda não commitadas ganham um contorno azul. **Destacar alteradas** mostra
só elas e **Ver alterações** abre o diff: o jeito mais rápido de revisar o que vai para o próximo commit.

![Classes alteradas e não commitadas em destaque](docs/images/alteradas.png)

Legenda, detalhes e painel inferior podem ser minimizados para o diagrama ganhar espaço.

## 📦 Instalação

1. Baixe o `.zip` do plugin (ou gere com `.\gradlew.bat buildPlugin`).
2. No IntelliJ: **Settings > Plugins > ⚙️ > Install Plugin from Disk...** e escolha o arquivo.
3. Pronto: o painel **Mio Review AI** aparece na lateral da IDE.

> [!NOTE]
> Compatível com IntelliJ IDEA 2025.2 em diante. O diagrama de dependências usa o plugin Java, que já
> vem no IntelliJ IDEA.

## 🤖 Configure sua IA

O `.md` gerado já traz as instruções para a IA. Para que ela trate as revisões sem você precisar
pedir, adicione ao `AGENTS.md` (ou a uma rule do Cursor) do projeto revisado:

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

> [!TIP]
> Depois disso, basta dizer à IA algo como *"aplique a revisão"*.

## ❓ Perguntas frequentes

<details>
<summary><b>Com qual IA ele funciona?</b></summary>

Com qualquer assistente que leia arquivos do projeto: Cursor, Claude Code e outros. O roteiro é um
Markdown comum, sem depender de ferramentas de um produto específico.
</details>

<details>
<summary><b>Meu código é enviado para algum servidor?</b></summary>

Não pelo plugin: ele não faz nenhuma chamada de rede. Os comentários ficam na IDE e no `.md` dentro
do seu projeto. O que vai para a IA é decidido por você e pelo assistente que você usa.
</details>

<details>
<summary><b>Quais linguagens são suportadas?</b></summary>

Os comentários funcionam em qualquer arquivo de texto: código, specs, documentação, configuração.
O diagrama de dependências é para classes Java e Kotlin.
</details>

<details>
<summary><b>A coluna de testes mede a cobertura de linhas?</b></summary>

Não. Ela mostra quais classes de teste usam a classe diretamente, sem executar os testes. Para medir
a cobertura de linhas, use o **Run with Coverage** do IntelliJ.
</details>

## 🛠️ Desenvolvimento

Requer JDK 21+.

```powershell
.\gradlew.bat runIde       # IDE de sandbox com o plugin
.\gradlew.bat test         # testes
.\gradlew.bat buildPlugin  # build/distributions/mio-review-ia-<versão>.zip
```

> [!IMPORTANT]
> O `buildPlugin` roda os testes antes e não gera o `.zip` se algum falhar.

O diagrama usa o [Cytoscape.js](https://js.cytoscape.org/) (licença MIT).

---

<p align="center">
  Feito para quem quer a velocidade da IA sem abrir mão do code review. 💜
</p>
