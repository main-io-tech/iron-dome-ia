package com.mio.irondome.radar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension

/**
 * Editor do código sugerido sobre uma cópia do arquivo comentado: o trecho editável fica no lugar das
 * linhas, com o resto do arquivo em volta, só de leitura e recolhido. Por ser o arquivo inteiro, o
 * autocompletar (Ctrl+Espaço), os imports, a navegação e os erros funcionam como no editor de verdade,
 * sem tocar no arquivo original.
 */
internal class SuggestionEditor private constructor(
    val field: EditorTextField,
    private val region: RangeMarker,
) {

    /** O que está no trecho editável agora. */
    val text: String get() = if (region.isValid) region.document.getText(region.textRange) else ""

    companion object {
        /** Linhas de contexto mostradas antes e depois do trecho editável. */
        private const val CONTEXT_LINES = 2
        private const val MIN_VISIBLE_LINES = 5
        private const val MAX_VISIBLE_LINES = 18
        private val REGION_BACKGROUND = JBColor(Color(0xF3EEFF), Color(0x2E2640))

        /** As linhas [lines] do arquivo, trocadas por [initial] quando já há uma sugestão. */
        fun forLines(project: Project, file: VirtualFile, lines: IntRange, initial: String): SuggestionEditor {
            val document = copyOf(project, file) ?: return plain(project, file.fileType, initial)
            val last = lines.last.coerceAtMost(document.lineCount - 1)
            val first = lines.first.coerceIn(0, last)
            val start = document.getLineStartOffset(first)
            val end = document.getLineEndOffset(last)
            if (initial.isNotEmpty()) edit(project) { document.replaceString(start, end, initial) }
            return build(project, document, file.fileType, start, start + if (initial.isNotEmpty()) initial.length else end - start, last - first + 1)
        }

        /** Um trecho novo no fim do arquivo, para exemplos sobre o arquivo inteiro. */
        fun atEnd(project: Project, file: VirtualFile, initial: String): SuggestionEditor {
            val document = copyOf(project, file) ?: return plain(project, file.fileType, initial)
            val start = document.textLength + 2
            edit(project) { document.insertString(document.textLength, "\n\n$initial") }
            return build(project, document, file.fileType, start, document.textLength, initial.lines().size.coerceAtLeast(1))
        }

        /** Sem arquivo para copiar, como num pacote: só o realce do tipo de arquivo. */
        fun plain(project: Project, fileType: FileType, initial: String): SuggestionEditor {
            val document = EditorFactory.getInstance().createDocument(initial)
            val field = EditorTextField(document, project, fileType, false, false).apply {
                setFontInheritedFromLAF(false)
                addSettingsProvider { configure(it) }
                preferredSize = Dimension(JBUI.scale(560), JBUI.scale(140))
            }
            return SuggestionEditor(field, greedy(document.createRangeMarker(0, document.textLength)))
        }

        /**
         * A cópia mantém o arquivo original como contexto, para a resolução de nomes valer como nele. Precisa
         * do sistema de eventos ligado: sem ele, a IDE não dá um documento editável para a cópia.
         */
        private fun copyOf(project: Project, file: VirtualFile): Document? {
            val documents = PsiDocumentManager.getInstance(project)
            documents.commitAllDocuments()
            val original = PsiManager.getInstance(project).findFile(file) ?: return null
            val copy = PsiFileFactory.getInstance(project).createFileFromText(file.name, original.language, original.text, true, false)
                ?: return null
            (copy as? PsiFileImpl)?.originalFile = original
            (copy.viewProvider.virtualFile as? LightVirtualFile)?.originalFile = file
            return documents.getDocument(copy)?.takeIf { it.isWritable }
        }

        private fun build(project: Project, document: Document, fileType: FileType, start: Int, end: Int, editableLines: Int): SuggestionEditor {
            val region = greedy(document.createRangeMarker(start, end))
            if (start > 0) document.createGuardedBlock(0, start - 1)
            if (end + 1 < document.textLength) document.createGuardedBlock(end + 1, document.textLength)
            val visible = (editableLines + 2 * CONTEXT_LINES + 2).coerceIn(MIN_VISIBLE_LINES, MAX_VISIBLE_LINES)
            val field = EditorTextField(document, project, fileType, false, false).apply {
                setFontInheritedFromLAF(false)
                addSettingsProvider { editor ->
                    configure(editor)
                    editor.settings.isLineNumbersShown = true
                    fold(editor, region)
                    editor.markupModel.addRangeHighlighter(
                        region.startOffset, region.endOffset, HighlighterLayer.SELECTION - 1,
                        TextAttributes().apply { backgroundColor = REGION_BACKGROUND }, HighlighterTargetArea.LINES_IN_RANGE,
                    ).also { (it as? RangeHighlighterEx)?.apply { isGreedyToLeft = true; isGreedyToRight = true } }
                    editor.caretModel.moveToOffset(region.startOffset)
                }
                preferredSize = Dimension(JBUI.scale(560), visible * JBUI.scale(18) + JBUI.scale(12))
            }
            return SuggestionEditor(field, region)
        }

        private fun configure(editor: EditorEx) {
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
            editor.settings.isUseSoftWraps = false
            editor.contentComponent.border = JBUI.Borders.empty(4, 6)
        }

        /** Recolhe o arquivo antes e depois do trecho, deixando [CONTEXT_LINES] de cada lado à vista. */
        private fun fold(editor: EditorEx, region: RangeMarker) {
            val document = editor.document
            val firstContext = (document.getLineNumber(region.startOffset) - CONTEXT_LINES).coerceAtLeast(0)
            val lastContext = (document.getLineNumber(region.endOffset) + CONTEXT_LINES).coerceAtMost(document.lineCount - 1)
            editor.foldingModel.runBatchFoldingOperation {
                if (firstContext > 0) {
                    val end = document.getLineEndOffset(firstContext - 1)
                    editor.foldingModel.addFoldRegion(0, end, "⋯ ${firstContext} linhas acima")?.isExpanded = false
                }
                if (lastContext < document.lineCount - 1) {
                    val start = document.getLineStartOffset(lastContext + 1)
                    val hidden = document.lineCount - 1 - lastContext
                    editor.foldingModel.addFoldRegion(start, document.textLength, "⋯ $hidden linhas abaixo")?.isExpanded = false
                }
            }
        }

        private fun greedy(marker: RangeMarker) = marker.apply {
            isGreedyToLeft = true
            isGreedyToRight = true
        }

        private fun edit(project: Project, change: () -> Unit) {
            CommandProcessor.getInstance().runUndoTransparentAction {
                ApplicationManager.getApplication().runWriteAction(change)
            }
        }
    }
}
