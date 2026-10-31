<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="110" alt="Iron Dome IA">
</p>

<h1 align="center">Iron Dome IA</h1>

<p align="center">
  <b>Um sistema de defesa para o seu código, direto no IntelliJ.<br>
  Você marca o alvo, a IA intercepta e nenhum bug passa pelo domo.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/IntelliJ%20IDEA-2025.2%2B-000000?logo=intellijidea&logoColor=white" alt="IntelliJ IDEA 2025.2+">
  <img src="https://img.shields.io/badge/Java%20%C2%B7%20Kotlin%20%C2%B7%20qualquer%20texto-7F52FF?logo=kotlin&logoColor=white" alt="Java, Kotlin e qualquer arquivo de texto">
  <img src="https://img.shields.io/badge/funciona%20com-Cursor%20%C2%B7%20Claude%20Code%20%C2%B7%20qualquer%20IA-8250DF" alt="Funciona com Cursor, Claude Code e qualquer IA">
  <img src="https://img.shields.io/badge/vers%C3%A3o-0.1.0-0969DA" alt="Versão 0.1.0">
</p>

<p align="center">
  <a href="#-como-funciona">Como funciona</a> &nbsp;·&nbsp;
  <a href="#-mecanismos-de-defesa">Mecanismos de defesa</a> &nbsp;·&nbsp;
  <a href="#-instalação">Instalação</a> &nbsp;·&nbsp;
  <a href="#-configure-sua-ia">Configure sua IA</a> &nbsp;·&nbsp;
  <a href="#-perguntas-frequentes">FAQ</a>
</p>

<p align="center">
  <img src="docs/images/fluxo.svg" width="900" alt="Fluxo do Iron Dome IA: marque o alvo no editor, envie as ordens em Markdown e a IA intercepta e marca como resolvido">
</p>

---

## ✨ Por que o Iron Dome IA?

Pedir correções para a IA pelo chat é atirar no escuro: você descreve o lugar do problema, ela
procura no lugar errado, esquece metade dos pedidos e altera o que você só queria discutir.

O **Iron Dome IA** traz para dentro da IDE o fluxo que todo time já conhece: o **code review**.
Você marca os alvos linha a linha, como num pull request, e o plugin transforma tudo em ordens de
interceptação em Markdown, com arquivo, linha e trecho citado. A IA segue as ordens, marca cada
alvo como resolvido e você vê o resultado em verde no editor.

| 💣 Sem o domo | 🛡️ Com o Iron Dome IA |
| --- | --- |
| "Corrige aquela função do pedido..." e a IA procura no lugar errado | Cada comentário leva o arquivo, a linha e o trecho de código exato |
| Você pede cinco ajustes e só três são feitos | Um checklist em Markdown: a IA marca `[x]` em cada item resolvido |
| A IA altera até o que você só queria discutir | Você escolhe, por comentário: **aplicar direto** ou **discutir antes** |
| Pedidos que se contradizem viram fogo amigo | A IA é instruída a parar e perguntar quando comentários se contradizem |
| Você mexe numa classe sem saber o que pode quebrar | O raio de impacto mostra quem depende dela, o risco e os testes antes da mudança |

## ⚡ Como funciona

<table>
  <tr>
    <td align="center" width="33%">
      <h3>🎯 1. Marque o alvo</h3>
      Clique no <b>+</b> da margem, dê duplo clique no número da linha ou use
      <kbd>Ctrl</kbd>+<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>M</kbd>. Escolha o modo:
      <i>Aplicar direto</i> ou <i>Discutir antes de alterar</i>.
    </td>
    <td align="center" width="33%">
      <h3>📨 2. Envie as ordens</h3>
      <b>Salvar Este Arquivo</b> ou <b>Salvar Todos</b> (<kbd>Ctrl</kbd>+<kbd>S</kbd>) gera
      <code>iron-dome/ordem-interceptacao-&lt;id&gt;.md</code> com cada comentário e o trecho de código.
    </td>
    <td align="center" width="33%">
      <h3>💥 3. A IA intercepta</h3>
      Ela aplica as correções, marca <code>[x]</code> em cada alvo abatido e o plugin mostra o
      comentário em verde, como resolvido.
    </td>
  </tr>
</table>

## 🧩 Mecanismos de defesa

| Mecanismo | Missão |
| --- | --- |
| 📡 **Radar** | Detecta e marca os alvos: comentários inline que acompanham o código. |
| 🎯 **Centro de Comando** | Transforma os alvos em ordens de interceptação em Markdown e evita o fogo amigo. |
| 💥 **Raio de Impacto** | Mede o estrago que uma mudança pode causar antes de você mexer na classe. |
| 🛰️ **Sentinela** | Vigia o perímetro: tudo o que ainda não foi commitado, com o diff a um clique. |
| 🏰 **Base** | O painel lateral onde os mecanismos se reúnem. |

### 📡 Radar: comentários inline, como num pull request

- 📌 **Acompanham o código:** os comentários se movem quando linhas são inseridas ou removidas.
- 🎯 **Arquivo ou pacote inteiro:** para pedidos que não cabem numa linha, como *"separe este pacote em dois"*, clique com o botão direito num pacote ou arquivo (na árvore do projeto, na aba do editor ou na aba Alterações) e use **Marcar como Alvo**. O comentário de arquivo aparece no topo do editor, e a árvore do projeto mostra quantos alvos cada pacote e arquivo tem.
- ✏️ **Ciclo completo:** edite, remova com desfazer e acompanhe o status: não salvo, pendente ou resolvido.
- 💾 **Nada se perde:** rascunhos ficam guardados mesmo se você fechar a IDE antes de salvar.
- 🚦 **Dois modos por comentário:** *Aplicar direto* para o que é óbvio; *Discutir antes de alterar* para a IA apresentar a proposta e esperar a sua autorização de disparo.

### 🎯 Centro de Comando: ordens que qualquer IA entende

Ao salvar, cada comentário vira uma ordem de interceptação clara, com o modo e o código citado. O
arquivo traz no topo as regras de engajamento para a IA, então funciona com qualquer assistente, sem
configuração extra.

- 🤝 **Sem fogo amigo:** a IA é orientada a perguntar antes de aplicar comentários que se contradizem ou se cruzam.

<details>
<summary><b>👀 Ver um exemplo do <code>.md</code> gerado</b></summary>

````markdown
# Revisão: ordem-interceptacao-1

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

### 🏰 Base: o painel Iron Dome IA

| Aba | O que você encontra |
| --- | --- |
| 💬 **Comentários** | A tela do radar: a revisão aberta e o histórico, organizados por arquivo e pacote. Um clique leva ao código, e o botão direito edita ou remove. |
| 🔄 **Alterações** | O posto da sentinela: os arquivos ainda não commitados, agrupados por pacote em grupos que você expande ou recolhe, quantos comentários cada um tem e botões para **Ver Diff** ou **Abrir Arquivo**. |
| 📊 **Dependências** | O raio de impacto da classe escolhida, descrito abaixo. |

### 💥 Raio de Impacto: saiba o estrago antes de mudar

Escolha uma classe Java ou Kotlin pelo menu de contexto (**Iron Dome IA: Analisar Dependências**)
ou na lista **Todas as classes**, sem precisar abrir o arquivo.

![Diagrama de dependências com os detalhes da classe analisada](docs/images/diagrama.png)

| Métrica | O que responde |
| --- | --- |
| ⬇️ **Aferentes** | Quantas classes dependem dela: o que pode quebrar se você mudá-la. |
| ⬆️ **Eferentes** | De quantas classes ela depende: um número alto indica uma classe que faz coisas demais. |
| 🟢 🟡 🔴 **Risco ao alterar** | Baixo, médio ou alto, conforme quantas classes podem ser atingidas. |
| 🧪 **Testes** | O escudo da classe: quais testes a usam e se ela está bem coberta. |
| 🔁 **Ciclos** | Dependências circulares destacadas em vermelho. |
| 🎯 **O que fazer** | Uma recomendação objetiva antes de mexer na classe. |

⚠️ marca as classes com risco médio ou alto **e** sem testes: são as que pedem mais
cuidado. E se você está começando, **Entenda as métricas** explica cada número em linguagem simples.

### 🛰️ Sentinela: alterações não commitadas em destaque

Classes com alterações ainda não commitadas ganham um contorno azul. **Destacar alteradas** mostra
só elas e **Ver alterações** abre o diff: o jeito mais rápido de revisar o que vai para o próximo commit.

![Classes alteradas e não commitadas em destaque](docs/images/alteradas.png)

Legenda, detalhes e painel inferior podem ser minimizados para o diagrama ganhar espaço.

## 📦 Instalação

1. Baixe o `.zip` do plugin (ou gere com `.\gradlew.bat buildPlugin`).
2. No IntelliJ: **Settings > Plugins > ⚙️ > Install Plugin from Disk...** e escolha o arquivo.
3. Pronto: o painel **Iron Dome IA** aparece na lateral da IDE.

> [!NOTE]
> Compatível com IntelliJ IDEA 2025.2 em diante. O diagrama de dependências usa o plugin Java, que já
> vem no IntelliJ IDEA.

## 🤖 Configure sua IA

O `.md` gerado já traz as instruções para a IA. Para que ela trate as revisões sem você precisar
pedir, adicione ao `AGENTS.md` (ou a uma rule do Cursor) do projeto revisado:

```markdown
## Revisões em `iron-dome/`

Quando houver arquivos em `iron-dome/ordem-interceptacao-*.md` com comentários pendentes (`[ ]`), trate cada
um no arquivo indicado na seção dele (código, spec, documentação ou configuração), localizando o
ponto pelo trecho citado, e marque-o como `[x]` ao terminar. Não edite o restante do `.md`.
Comentários de arquivo inteiro valem para o arquivo todo, e os de pacote inteiro, para todos os
arquivos da pasta indicada na seção.
Antes de alterar qualquer arquivo, analise os comentários pendentes em conjunto. Se algum contradizer
outro, ou se aplicar um quebrar outro, pergunte ao usuário como resolver e espere a resposta. Sem
conflitos, trate os comentários em sequência. Os de `**Modo:** aplicar direto` podem ser aplicados
sem consulta. Nos de `**Modo:** discutir antes de alterar`, apresente a análise e a proposta e só
altere depois que o usuário concordar.
```

> [!TIP]
> Depois disso, basta dizer à IA algo como *"aplique a revisão"*.

> [!NOTE]
> Vindo da versão com o nome Mio Review AI? Ao abrir o projeto, o plugin move `mio-review/` para
> `iron-dome/` sozinho. Só falta trocar o caminho na regra do `AGENTS.md` ou do Cursor.

## ❓ Perguntas frequentes

<details>
<summary><b>Com qual IA ele funciona?</b></summary>

Com qualquer assistente que leia arquivos do projeto: Cursor, Claude Code e outros. O roteiro é um
Markdown comum, sem depender de ferramentas de um produto específico.
</details>

<details>
<summary><b>Meu código é enviado para algum servidor?</b></summary>

Não pelo plugin: nada sai do domo. Ele não faz nenhuma chamada de rede, e os comentários ficam na
IDE e no `.md` dentro do seu projeto. O que vai para a IA é decidido por você e pelo assistente que você usa.
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
.\gradlew.bat buildPlugin  # build/distributions/iron-dome-ia-<versão>.zip
```

> [!IMPORTANT]
> O `buildPlugin` roda os testes antes e não gera o `.zip` se algum falhar.

O código fica em `src/main/kotlin/com/mio/review`, com um pacote por mecanismo de defesa:

| Pacote | Mecanismo | O que tem |
| --- | --- | --- |
| `radar` | 📡 Radar | Comentários, formulário, margem do editor, cartões e a aba Comentários |
| `comando` | 🎯 Centro de Comando | Geração e leitura do `.md` e as ações de salvar |
| `impacto` | 💥 Raio de Impacto | Análise de dependências, diagrama e métricas (só com o plugin Java) |
| `sentinela` | 🛰️ Sentinela | Aba Alterações e o diff dos arquivos não commitados |
| `base` | 🏰 Base | Tool window, abas extras e a barra flutuante do editor |

O diagrama usa o [Cytoscape.js](https://js.cytoscape.org/) (licença MIT).

---

<p align="center">
  Feito para quem quer a velocidade da IA com o código protegido pelo domo. 🛡️
</p>
