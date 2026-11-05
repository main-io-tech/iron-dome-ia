package com.mio.irondome.radar

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.mio.irondome.base.IronDomeIcons
import com.mio.irondome.comando.ReviewMarkdown
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants

/**
 * Formulário embutido no editor, abaixo das linhas comentadas, como no review do GitHub.
 * Vários podem ficar abertos ao mesmo tempo, um por trecho ou por comentário em edição.
 */
object CommentForm {

    /** [editing] é nulo no formulário de comentário novo. */
    private class OpenForm(
        val inlay: Inlay<*>,
        val range: RangeMarker,
        val textField: EditorTextField,
        val editing: ReviewComment?,
    )

    private val openForms = Key.create<MutableList<OpenForm>>("irondome.commentForms")

    /** Comentários novos começam com o modo do último comentário criado. */
    internal var lastDiscuss = false

    fun open(editor: Editor, lines: IntRange) {
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (focusExisting(editor) { it.editing == null && it.range.isValid && linesOf(it.range) == lines }) return
        val service = project.service<ReviewCommentService>()
        val pullRequest = service.activePullRequest
        val where = ReviewMarkdown.describeLines(lines.first, lines.last)
        show(
            editor,
            lines,
            title = if (pullRequest != 0) "Comentário para o PR #$pullRequest · $where" else "Novo alvo para a IA · $where",
            initialText = "",
            initialDiscuss = lastDiscuss,
            initialSuggestion = "",
            pullRequest = pullRequest,
            file = file,
            submitLabel = if (pullRequest != 0) "Adicionar ao PR" else "Marcar Alvo",
            editing = null,
            onSubmit = { current, text, discuss, suggestion ->
                if (pullRequest == 0) lastDiscuss = discuss
                service.addComment(file, editor.document, current, text, discuss, suggestion)
            },
            onClose = {},
        )
    }

    /** Troca o cartão do comentário por um formulário preenchido com o texto atual. */
    fun edit(editor: Editor, comment: ReviewComment) {
        val project = editor.project ?: return
        if (focusExisting(editor) { it.editing === comment }) return
        val service = project.service<ReviewCommentService>()
        val lines = service.currentLines(comment, editor.document)
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!service.startEditing(comment)) return
        val where = ReviewMarkdown.describeLines(lines.first, lines.last)
        show(
            editor,
            lines,
            title = if (comment.pullRequest != 0) "Editar ${comment.id} do PR #${comment.pullRequest} · $where" else "Editar alvo ${comment.id} · $where",
            initialText = comment.text,
            initialDiscuss = comment.discuss,
            initialSuggestion = comment.suggestion,
            pullRequest = comment.pullRequest,
            file = file,
            submitLabel = "Atualizar",
            editing = comment,
            onSubmit = { _, text, discuss, suggestion -> service.update(comment, text, discuss, suggestion) },
            onClose = { service.stopEditing(comment) },
        )
    }

    private fun focusExisting(editor: Editor, matches: (OpenForm) -> Boolean): Boolean {
        val existing = editor.getUserData(openForms)?.firstOrNull(matches) ?: return false
        editor.project?.let { IdeFocusManager.getInstance(it).requestFocus(existing.textField, true) }
        return true
    }

    private fun show(
        editor: Editor,
        lines: IntRange,
        title: String,
        initialText: String,
        initialDiscuss: Boolean,
        initialSuggestion: String,
        pullRequest: Int,
        file: VirtualFile,
        submitLabel: String,
        editing: ReviewComment?,
        onSubmit: (lines: IntRange, text: String, discuss: Boolean, suggestion: String) -> Unit,
        onClose: () -> Unit,
    ) {
        val forPullRequest = pullRequest != 0
        val project = editor.project ?: return onClose()
        val editorEx = editor as? EditorEx ?: return onClose()
        val document = editor.document

        ReviewGutter.hide(editor)
        val forms = editor.getUserData(openForms) ?: mutableListOf<OpenForm>().also { editor.putUserData(openForms, it) }

        val range = document.createRangeMarker(
            document.getLineStartOffset(lines.first),
            document.getLineEndOffset(lines.last),
        )
        val textField = EditorTextField(
            EditorFactory.getInstance().createDocument(initialText),
            project,
            FileTypes.PLAIN_TEXT,
            false,
            false,
        ).apply {
            setPlaceholder(
                if (forPullRequest) "O que o autor do PR deve mudar e por quê"
                else "Descreva a correção que a IA deve fazer",
            )
            addSettingsProvider {
                it.settings.isUseSoftWraps = true
                it.setVerticalScrollbarVisible(true)
                it.contentComponent.border = JBUI.Borders.empty(6, 8)
            }
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(96))
        }
        var suggestionEditor: SuggestionEditor? = null
        val suggestOption = JBCheckBox("Sugerir código", initialSuggestion.isNotEmpty()).apply {
            isOpaque = false
            toolTipText = "Escreva como o trecho deveria ficar. No GitHub vira uma sugestão que o autor aplica com um clique."
        }
        val codeSection = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            isVisible = false
            add(
                JBLabel(
                    "Como deveria ficar: edite o trecho destacado como no editor (Ctrl+Espaço completa); " +
                        "o arquivo não muda. Fora do diff do PR, vai como exemplo",
                ).apply {
                    font = JBFont.small()
                    foreground = ReviewColors.mutedForeground
                },
                BorderLayout.NORTH,
            )
        }
        /** Criado na primeira vez que a sugestão aparece: copiar o arquivo só vale a pena quando ela é usada. */
        fun suggestionEditor(): SuggestionEditor = suggestionEditor ?: SuggestionEditor.forLines(
            project, file, if (range.isValid) linesOf(range) else lines, initialSuggestion,
        ).also {
            suggestionEditor = it
            codeSection.add(it.field, BorderLayout.CENTER)
        }
        if (forPullRequest && initialSuggestion.isNotEmpty()) {
            suggestionEditor()
            codeSection.isVisible = true
        }
        val submitButton = JButton(submitLabel).apply {
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        }
        val cancelButton = JButton("Cancelar")
        val applyOption = JBRadioButton("Aplicar direto", !initialDiscuss).apply {
            isOpaque = false
            toolTipText = "A IA pode alterar os arquivos sem consultar você"
        }
        val discussOption = JBRadioButton("Discutir antes de alterar", initialDiscuss).apply {
            isOpaque = false
            toolTipText = "A IA apresenta a análise e a proposta e só altera depois que você concordar"
        }
        ButtonGroup().apply {
            add(applyOption)
            add(discussOption)
        }

        val header = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(
                JBLabel(
                    title,
                    if (forPullRequest) IronDomeIcons.patrulha else ReviewIcons.comment,
                    SwingConstants.LEFT,
                ).apply {
                    font = JBFont.label().asBold()
                    iconTextGap = JBUI.scale(6)
                },
                BorderLayout.WEST,
            )
            add(
                JBLabel("Ctrl+Enter: $submitLabel · Esc: Cancelar").apply {
                    font = JBFont.small()
                    foreground = ReviewColors.mutedForeground
                },
                BorderLayout.EAST,
            )
        }
        val card = CardPanel(
            if (forPullRequest) ReviewColors.pullRequestBackground else CommentStatus.UNSAVED.background,
            if (forPullRequest) ReviewColors.pullRequestAccent else CommentStatus.UNSAVED.accent,
        ).apply {
            add(header, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                    isOpaque = false
                    add(textField, BorderLayout.CENTER)
                    add(codeSection, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER,
            )
            add(
                JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
                    isOpaque = false
                    add(
                        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
                            isOpaque = false
                            if (forPullRequest) {
                                add(suggestOption)
                                add(
                                    JBLabel("Fica no PR #$pullRequest como não enviado até Enviar ao GitHub, na aba Patrulha").apply {
                                        font = JBFont.small()
                                        foreground = ReviewColors.mutedForeground
                                    },
                                )
                            } else {
                                add(applyOption)
                                add(discussOption)
                            }
                        },
                        BorderLayout.WEST,
                    )
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                            isOpaque = false
                            add(cancelButton)
                            add(submitButton)
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.SOUTH,
            )
        }
        val indent = editor.offsetToXY(firstNonBlank(editor, lines.first)).x
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, indent, 4, 0)
            add(card, BorderLayout.WEST)
        }

        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editorEx,
            wrapper,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,
                true,
                false,
                0,
                document.getLineEndOffset(lines.last),
            ),
        ) ?: run {
            range.dispose()
            onClose()
            return
        }
        val form = OpenForm(inlay, range, textField, editing)
        forms += form
        var submitted = false
        Disposer.register(inlay, Disposable {
            range.dispose()
            forms.remove(form)
            if (!submitted) onClose()
        })

        fun backToEditor() {
            Disposer.dispose(inlay)
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
        }

        fun submit() {
            val text = textField.text.trim()
            if (text.isEmpty()) return
            val current = if (range.isValid) linesOf(range) else lines
            val suggestion = if (forPullRequest && suggestOption.isSelected) suggestionEditor?.text.orEmpty() else ""
            submitted = true
            backToEditor()
            onSubmit(current, text, !forPullRequest && discussOption.isSelected, suggestion)
        }

        suggestOption.addActionListener {
            val show = suggestOption.isSelected
            val code = if (show) suggestionEditor() else null
            codeSection.isVisible = show
            wrapper.revalidate()
            wrapper.repaint()
            if (code != null) IdeFocusManager.getInstance(project).requestFocus(code.field, true)
        }
        submitButton.addActionListener { submit() }
        cancelButton.addActionListener { backToEditor() }
        DumbAwareAction.create { submit() }.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)),
            wrapper,
            inlay,
        )
        DumbAwareAction.create { backToEditor() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, wrapper, inlay)

        ApplicationManager.getApplication().invokeLater {
            if (!inlay.isValid) return@invokeLater
            IdeFocusManager.getInstance(project).requestFocus(textField, true)
            textField.setCaretPosition(textField.document.textLength)
        }
    }

    /** Painel com cantos arredondados e faixa colorida à esquerda, igual ao cartão do comentário. */
    private class CardPanel(private val background: Color, private val accent: Color) : JPanel(BorderLayout(0, JBUI.scale(8))) {

        init {
            isOpaque = false
            border = JBUI.Borders.empty(10, 14, 10, 10)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(10).toFloat()
                val shape = RoundRectangle2D.Float(0f, 0f, width - 1f, height - 1f, arc, arc)
                val oldClip = g2.clip
                g2.clip(shape)
                g2.color = background
                g2.fill(shape)
                g2.color = accent
                g2.fillRect(0, 0, JBUI.scale(4), height)
                g2.clip = oldClip
                g2.color = ReviewColors.cardBorder
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun linesOf(range: RangeMarker): IntRange =
        range.document.getLineNumber(range.startOffset)..range.document.getLineNumber(range.endOffset)

    private fun firstNonBlank(editor: Editor, line: Int): Int {
        val document = editor.document
        val text = document.charsSequence
        var offset = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        while (offset < end && text[offset].isWhitespace()) offset++
        return offset
    }
}
