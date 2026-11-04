package com.mio.irondome.radar

import com.intellij.openapi.editor.Document
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.mio.irondome.base.Tone

/**
 * Cartão que outro mecanismo desenha no editor junto com os do Radar, como um pedido já enviado a um PR.
 * Aparece recolhido numa barra de uma linha ([ReviewComment.expanded] alterna), com Ocultar e, se houver
 * [onEdit], Editar. [lines] são as linhas no documento atual, 0-based; nulo põe o cartão no topo do arquivo.
 */
class EditorCard(
    val comment: ReviewComment,
    val lines: IntRange?,
    val badge: String,
    val tone: Tone,
    /** Ao lado do id no cartão expandido, como "Enviado ao PR #12 · Código: Alterado". */
    val subtitle: String,
    val onToggle: () -> Unit,
    val onHide: () -> Unit,
    val onEdit: (() -> Unit)? = null,
)

/** Quem desenha [EditorCard]s; o Radar pede os cartões a cada vez que redesenha um editor. */
interface EditorCardProvider {

    fun cards(project: Project, path: String, document: Document): List<EditorCard>

    companion object {
        val EP_NAME = ExtensionPointName.create<EditorCardProvider>("com.mio.irondome.editorCardProvider")
    }
}
