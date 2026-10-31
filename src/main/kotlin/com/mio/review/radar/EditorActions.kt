package com.mio.review.radar

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

internal fun AnActionEvent.editorAndFile(): Pair<Editor, VirtualFile>? {
    val editor = getData(CommonDataKeys.EDITOR) ?: return null
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
    return if (file.isInLocalFileSystem) editor to file else null
}

/** Linhas da seleção; sem seleção, a linha do cursor. Uma seleção que termina no início de linha não inclui essa linha. */
internal fun Editor.selectedLines(): IntRange {
    val selection = selectionModel
    if (!selection.hasSelection()) {
        val line = document.getLineNumber(caretModel.offset)
        return line..line
    }
    val start = document.getLineNumber(selection.selectionStart)
    var end = document.getLineNumber(selection.selectionEnd)
    if (end > start && document.getLineStartOffset(end) == selection.selectionEnd) end--
    return start..end
}
