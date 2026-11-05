package com.mio.irondome.patrulha

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ListUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.NoticeBox
import com.mio.irondome.base.Tone
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * A linha de avaliação de quem revisa, nas palavras dele: o que cobra, o que deixa passar e o tom. Vai para a IA na
 * Revisão com IA e nas respostas automáticas. Nas preferências da IDE, valendo para todos os projetos.
 */
object ReviewerGuidelines {

    private const val KEY = "IronDome.Patrulha.ReviewerGuidelines"
    const val MAX_LENGTH = 6_000

    var text: String
        get() = PropertiesComponent.getInstance().getValue(KEY).orEmpty()
        set(value) = PropertiesComponent.getInstance().setValue(KEY, value.trim().take(MAX_LENGTH), "")

    /** A seção das instruções nas instruções da IA; vazia sem instruções. */
    fun section(guidelines: String): String {
        val text = guidelines.trim()
        if (text.isEmpty()) return ""
        return buildString {
            appendLine("## Como o revisor avalia PRs")
            appendLine()
            appendLine("Instruções do próprio revisor: o que ele cobra, o que deixa passar e o tom que prefere. Siga-as acima das")
            appendLine("preferências do time e do padrão destas instruções, exceto as regras de não alterar arquivos e do formato da resposta.")
            appendLine()
            appendLine(text)
            appendLine()
        }
    }
}

/** Edita [ReviewerGuidelines] e os arquivos de [ReviewRules] do projeto. */
internal class ReviewerGuidelinesDialog(private val project: Project) : DialogWrapper(project, true) {

    private val editor = JBTextArea(ReviewerGuidelines.text, 12, 80).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ex.: priorize bugs e segurança; não comente nomes e formatação; peça teste para toda regra de negócio nova"
    }
    private val root: Path? = ReviewRules.getInstance(project).root()
    private val rulesModel = CollectionListModel(ReviewRules.getInstance(project).paths)
    private val rulesList = JBList(rulesModel).apply {
        emptyText.text = "Nenhum arquivo de regras vinculado"
        emptyText.appendSecondaryText("Detectar regras do projeto", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { detectRules() }
        cellRenderer = SimpleListCellRenderer.create { label, path: String?, _ ->
            val file = path?.let { p -> root?.let { ReviewRules.resolve(it, p) } }
            label.text = path.orEmpty()
            label.icon = when {
                file == null || !Files.exists(file) -> AllIcons.General.Warning.also { label.text += "  (não encontrado)" }
                Files.isDirectory(file) -> AllIcons.Nodes.Folder
                else -> AllIcons.FileTypes.Text
            }
        }
    }

    val text: String get() = editor.text

    /** Os arquivos e pastas de regras como ficaram na lista. */
    val rules: List<String> get() = rulesModel.items

    init {
        title = "Minhas Instruções e Regras para a IA na Patrulha"
        setOKButtonText("Salvar")
        init()
    }

    private fun addRules() {
        val descriptor = FileChooserDescriptorFactory.createAllButJarContentsDescriptor()
            .withTitle("Arquivos ou Pastas de Regras")
            .withDescription("Arquivos de regras em Markdown, como AGENTS.md, ou pastas como .cursor/rules")
        val start = root?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
        FileChooser.chooseFiles(descriptor, project, start).forEach { addRule(ReviewRules.relativize(root, it.toNioPath())) }
    }

    private fun detectRules() {
        val found = root?.let(ReviewRules::detect).orEmpty().filter { it !in rulesModel.items }
        if (found.isEmpty()) {
            Messages.showInfoMessage(
                rulesList,
                "Nenhuma regra nova em ${ReviewRules.COMMON.joinToString { it }}. Adicione os arquivos pelo +.",
                "Detectar Regras do Projeto",
            )
            return
        }
        found.forEach(::addRule)
    }

    private fun addRule(path: String) {
        if (path !in rulesModel.items) rulesModel.add(path)
    }

    override fun getPreferredFocusedComponent(): JComponent = editor

    override fun doValidate(): ValidationInfo? =
        if (editor.text.trim().length > ReviewerGuidelines.MAX_LENGTH) {
            ValidationInfo("Até ${ReviewerGuidelines.MAX_LENGTH} caracteres: a IA lê tudo a cada revisão e resposta.", editor)
        } else {
            null
        }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(
            JBLabel(
                "<html>Escreva como você avalia um PR, como explicaria para um colega que vai revisar no seu lugar. " +
                    "Vale para a <b>Revisão com IA</b> e para as <b>respostas automáticas</b> nas conversas.</html>",
            ),
        )
        .addComponent(JBScrollPane(editor).apply { preferredSize = JBUI.size(680, 220) })
        .addComponent(
            NoticeBox(AllIcons.General.Information, Tone.BLUE).apply {
                text = "Ideias: o que bloqueia um PR para você (bug, falta de teste, quebra de contrato), o que é só sugestão, " +
                    "o que ignorar (estilo, nomes), padrões do projeto que você cobra, quando aceitar a explicação do dev e " +
                    "quando insistir, e o tom (direto, didático, curto). Fica nas preferências da IDE e vale para todos os projetos."
            },
        )
        .addComponent(TitledSeparator("Regras deste projeto"))
        .addComponent(
            JBLabel(
                "<html>Vincule arquivos <b>.md</b> com as regras do time, como <code>AGENTS.md</code>, ou pastas como " +
                    "<code>.cursor/rules</code>. A IA avalia o PR também por elas e diz qual regra foi descumprida. " +
                    "O conteúdo é lido a cada revisão, então vale o que estiver no arquivo naquela hora.</html>",
            ),
        )
        .addComponent(
            ToolbarDecorator.createDecorator(rulesList)
                .setAddAction { addRules() }
                .setAddActionName("Vincular Arquivos ou Pastas de Regras")
                .setRemoveAction { ListUtil.removeSelectedItems(rulesList) }
                .disableUpDownActions()
                .addExtraAction(DumbAwareAction.create("Detectar Regras do Projeto", AllIcons.Actions.Find) { detectRules() })
                .setPreferredSize(JBUI.size(680, 120))
                .createPanel(),
        )
        .panel
}
